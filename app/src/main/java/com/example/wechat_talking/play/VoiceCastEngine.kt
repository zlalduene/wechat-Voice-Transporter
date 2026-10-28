package com.example.wechat_talking.play

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.example.wechat_talking.audio.Dsp
import com.example.wechat_talking.audio.WavIo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 播放引擎。
 *
 * 三个刻意的设计：
 * 1. 用 AudioTrack 直接喂 PCM，而不是 MediaPlayer —— 且不申请音频焦点。
 *    微信按下录音时不会因为焦点被抢而把我们的播放掐掉。
 * 2. 播放前把媒体音量拉到最大，播完恢复原值。
 * 3. 就是进程里的一个单例，由 Activity 或无障碍服务直接调，不用前台服务
 *    （Android 12+ 从后台启动前台服务会被系统直接拒绝，而这正是我们的主场景）。
 */
object VoiceCastEngine {

    private var worker: Thread? = null
    private var captureThread: Thread? = null
    private val stopFlag = AtomicBoolean(false)

    @Volatile
    private var captureRms: Float = -99f

    @Volatile
    var isRunning: Boolean = false
        private set

    fun play(
        context: Context,
        samples: FloatArray,
        sampleRate: Int,
        delayMs: Long,
        route: OutputRoute,
        capturePreview: Boolean,
        captureFile: File,
    ) {
        if (isRunning) return
        stopFlag.set(false)
        isRunning = true
        captureRms = -99f
        CastBus.captureLevel.value = 0f

        val appContext = context.applicationContext
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                runPlayback(appContext, samples, sampleRate, delayMs, route, capturePreview, captureFile)
            } catch (t: Throwable) {
                CastBus.state.value = CastState.Failed(t.message ?: "播放失败")
            } finally {
                isRunning = false
            }
        }, "voice-cast").also { it.start() }
    }

    fun stop() {
        stopFlag.set(true)
    }

    private fun runPlayback(
        context: Context,
        samples: FloatArray,
        sampleRate: Int,
        delayMs: Long,
        route: OutputRoute,
        capturePreview: Boolean,
        captureFile: File,
    ) {
        val totalMs = samples.size * 1000L / sampleRate
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (delayMs > 0) {
            var left = delayMs
            while (left > 0 && !stopFlag.get()) {
                CastBus.state.value = CastState.Countdown(left)
                val step = min(50L, left)
                Thread.sleep(step)
                left -= step
            }
            if (stopFlag.get()) {
                CastBus.state.value = CastState.Idle
                return
            }
        }

        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

        var savedVolume = -1
        var previousMode = am.mode
        var track: AudioTrack? = null
        var record: AudioRecord? = null
        var captureHolder: ShortArray? = null
        var capturedFrames = 0

        try {
            try {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    0,
                )
            } catch (_: Throwable) {
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = max(if (minBuf > 0) minBuf else sampleRate, sampleRate * 2 / 5)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufSize * 2)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                CastBus.state.value = CastState.Failed("音轨初始化失败（$sampleRate Hz 不被支持？）")
                return
            }

            applyRoute(am, track, route)

            if (capturePreview) {
                val rec = startCapture(sampleRate)
                if (rec != null) {
                    record = rec
                    val holder = ShortArray(sampleRate * 60)
                    captureHolder = holder
                    val maxFrames = (((totalMs + 300) * sampleRate) / 1000).toInt()
                        .coerceIn(1, holder.size)
                    captureThread = Thread({
                        val buf = ShortArray(1024)
                        var framesRead = 0
                        var sum = 0.0
                        try {
                            while (!stopFlag.get() && framesRead < maxFrames) {
                                val n = rec.read(buf, 0, min(buf.size, maxFrames - framesRead))
                                if (n <= 0) break
                                var peakV = 0f
                                for (i in 0 until n) {
                                    val v = buf[i] / 32768f
                                    val a = abs(v)
                                    if (a > peakV) peakV = a
                                    sum += (v * v).toDouble()
                                }
                                CastBus.captureLevel.value = peakV
                                System.arraycopy(buf, 0, holder, framesRead, n)
                                framesRead += n
                            }
                        } catch (_: Throwable) {
                        } finally {
                            capturedFrames = framesRead
                            captureRms = if (framesRead > 0) {
                                Dsp.toDb(sqrt(sum / framesRead).toFloat())
                            } else {
                                -99f
                            }
                        }
                    }, "voice-capture").also { it.start() }
                }
            }

            track.play()

            val chunk = 1024
            var offset = 0
            while (offset < pcm.size && !stopFlag.get()) {
                val n = min(chunk, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) break
                offset += written
                CastBus.state.value = CastState.Playing(
                    elapsedMs = offset * 1000L / sampleRate,
                    totalMs = totalMs,
                )
            }

            if (!stopFlag.get()) {
                val tail = max(150L, min(600L, totalMs / 10))
                var waited = 0L
                while (waited < tail && !stopFlag.get()) {
                    Thread.sleep(30)
                    waited += 30
                }
            }
        } finally {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }

            val rec = record
            if (rec != null) {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            runCatching { captureThread?.join(800) }

            runCatching {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            }
            runCatching { am.mode = previousMode }
            if (savedVolume >= 0) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
            }

            val capturedFile = writeCapture(captureFile, captureHolder, capturedFrames, sampleRate)
            CastBus.state.value = if (stopFlag.get()) {
                CastState.Idle
            } else {
                CastState.Finished(capturedFile, captureRms)
            }
            CastBus.captureLevel.value = 0f
            captureHolder = null
        }
    }

    private fun writeCapture(file: File, holder: ShortArray?, frames: Int, sampleRate: Int): String? {
        if (holder == null || frames <= 0) return null
        return try {
            val floats = FloatArray(frames) { holder[it] / 32768f }
            WavIo.writeMono16(file, floats, sampleRate)
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyRoute(am: AudioManager, track: AudioTrack, route: OutputRoute) {
        val wantType = when (route) {
            OutputRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            OutputRoute.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        // 首选：直接给这条音轨指定输出设备，不动全局音频模式
        val direct = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == wantType }
        if (direct != null && track.setPreferredDevice(direct)) return

        // 兜底：走通话模式切外放/听筒
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == wantType }?.let {
                    am.setCommunicationDevice(it)
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = route == OutputRoute.SPEAKER
            }
        }
    }

    private fun startCapture(sampleRate: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return null
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, sampleRate / 5),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            rec.startRecording()
            rec
        } catch (_: Throwable) {
            null
        }
    }
}
