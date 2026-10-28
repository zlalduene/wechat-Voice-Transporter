package com.example.wechat_talking.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

class AudioDecodeException(message: String) : Exception(message)

/**
 * 把任意音频文件（mp3 / m4a / wav / flac / ogg / amr / aac …）解码成单声道 Float PCM。
 * 全程走系统的 MediaExtractor + MediaCodec，不依赖第三方解码库。
 */
object AudioDecoder {

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    private const val MAX_SECONDS = 600

    fun decode(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw AudioDecodeException("打不开这个文件：${e.message}")
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw AudioDecodeException("这个文件里没有音频轨道（可能是纯视频或图片）")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sink = FloatList()

        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            var channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val limit = MAX_SECONDS.toLong() * 44100L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }

                    outIdx >= 0 -> {
                        val f = codec.outputFormat
                        sampleRate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                        channels = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            runCatching { f.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrDefault(encoding)
                        } else {
                            encoding
                        }

                        val buffer = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendPcm(buffer, encoding, channels, sink)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        if (sink.size > limit) sawOutputEos = true
                    }

                    else -> Unit
                }
            }

            if (sink.size == 0) throw AudioDecodeException("解码后没有得到任何音频数据")
            return Pcm(sink.toArray(), sampleRate)
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw AudioDecodeException("解码失败：${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendPcm(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        channels: Int,
        sink: FloatList,
    ) {
        buffer.order(ByteOrder.nativeOrder())
        val ch = if (channels < 1) 1 else channels
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buffer.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get(i * ch + c)
                    sink.add(sum / ch)
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = buffer.remaining()
                val frames = n / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) {
                        sum += ((buffer.get(i * ch + c).toInt() and 0xFF) - 128) / 128f
                    }
                    sink.add(sum / ch)
                }
            }

            else -> {
                val sb = buffer.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get(i * ch + c) / 32768f
                    sink.add(sum / ch)
                }
            }
        }
    }

    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
