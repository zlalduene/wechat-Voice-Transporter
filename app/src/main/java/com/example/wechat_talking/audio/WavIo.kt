package com.example.wechat_talking.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 极简 16bit PCM WAV 读写（只支持单声道，够用就好）。 */
object WavIo {

    fun writeMono16(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataLen = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write("RIFF".toByteArray())
            raf.write(intLe(36 + dataLen))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intLe(16))
            raf.write(shortLe(1))            // PCM
            raf.write(shortLe(1))            // mono
            raf.write(intLe(sampleRate))
            raf.write(intLe(sampleRate * 2)) // byteRate
            raf.write(shortLe(2))            // blockAlign
            raf.write(shortLe(16))           // bits
            raf.write("data".toByteArray())
            raf.write(intLe(dataLen))

            val buf = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                val n = minOf(8192, samples.size - i)
                for (k in 0 until n) {
                    val v = (samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    buf.putShort(v)
                }
                raf.write(buf.array(), 0, n * 2)
                i += n
            }
        }
    }

    /** 读回自己写的 WAV（只用于回采试听）。 */
    fun readMono16(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        if (bytes.size < 44) return FloatArray(0) to 16000
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate = 16000
        var dataOffset = 44
        var dataLen = bytes.size - 44
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                sampleRate = bb.getInt(pos + 12)
            } else if (id == "data") {
                dataOffset = pos + 8
                dataLen = minOf(size, bytes.size - dataOffset)
                break
            }
            pos += 8 + size + (size and 1)
        }
        val n = dataLen / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = bb.getShort(dataOffset + i * 2) / 32768f
        }
        return out to sampleRate
    }

    private fun intLe(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLe(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
