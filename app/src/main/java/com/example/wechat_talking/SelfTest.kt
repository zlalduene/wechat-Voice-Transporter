package com.example.wechat_talking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.example.wechat_talking.audio.WavIo
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 环境自检：满音量放一段测试音，同时用麦克风录回来。
 *
 * 这是判断「外放被麦克风收进去到底衰减多少」的唯一可靠办法 ——
 * 很多机型会在录音时做回声消除/降噪，如果衰减太狠，纯软件路线就不成立，
 * 得上耳机孔回环线。测完会给出结论和一段可以试听的回采音频。
 */
object SelfTest {

    private const val TAG = "SELFTEST"
    private const val RATE = 16000

    data class Result(
        val sourceRmsDb: Float,
        val capturedRmsDb: Float,
        val attenuationDb: Float,
        val verdict: String,
        val capturedFile: String?,
    )

    fun run(context: Context, seconds: Float = 2.5f): Result {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val totalFrames = (RATE * (seconds + 0.5f)).toInt()
        val signalFrames = (RATE * seconds).toInt()
        val source = buildTestSignal(signalFrames)
        val sourceRmsDb = rmsDb(source)

        var savedVolume = -1
        var track: AudioTrack? = null
        var record: AudioRecord? = null
        val captured = ShortArray(totalFrames)

        try {
            runCatching {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    0,
                )
            }

            val minRec = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minRec, RATE / 4),
            )
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return Result(0f, -99f, -99f, "麦克风打不开（检查录音权限）", null)
            }

            val minPlay = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
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
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minPlay, RATE) * 2)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return Result(0f, -99f, -99f, "音轨初始化失败", null)
            }

            rec.startRecording()
            track.play()

            val pcm = ShortArray(source.size) { (source[it] * 32767f).toInt().toShort() }
            val captureThread = Thread({
                var filled = 0
                val buf = ShortArray(1024)
                while (filled < captured.size) {
                    val n = rec.read(buf, 0, min(buf.size, captured.size - filled))
                    if (n <= 0) break
                    System.arraycopy(buf, 0, captured, filled, n)
                    filled += n
                }
            }, "selftest-rec")
            captureThread.start()

            var offset = 0
            while (offset < pcm.size) {
                val n = min(1024, pcm.size - offset)
                val written = track.write(pcm, offset, n)
                if (written <= 0) break
                offset += written
            }
            captureThread.join(4000)
            rec.stop()

            // 掐掉前 120ms（音轨起来之前的静音），只算发声那段
            val skip = RATE * 120 / 1000
            val take = min(signalFrames, captured.size - skip)
            if (take <= 0) return Result(sourceRmsDb, -99f, -99f, "回采不到数据", null)
            var sum = 0.0
            for (i in 0 until take) {
                val v = captured[skip + i] / 32768.0
                sum += v * v
            }
            val capturedRmsDb = rmsDbFromSum(sum, take)
            val attenuation = capturedRmsDb - sourceRmsDb

            val file = File(context.filesDir, "selftest_capture.wav")
            val floats = FloatArray(take) { captured[skip + it] / 32768f }
            runCatching { WavIo.writeMono16(file, floats, RATE) }

            val verdict = when {
                attenuation > -10f -> "采集很干净，外放→麦克风几乎无损，放心用"
                attenuation > -22f -> "可用，会有一定音质损失，属于正常范围"
                attenuation > -36f -> "偏弱：手机可能开了通话降噪。靠近一点、安静环境，或用回环线"
                else -> "几乎收不到：系统把外放声音消掉了。这种情况必须用耳机孔回环线"
            }

            Log.i(
                TAG,
                "sourceRms=${fmt(sourceRmsDb)}dB capturedRms=${fmt(capturedRmsDb)}dB " +
                    "attenuation=${fmt(attenuation)}dB verdict=$verdict",
            )
            return Result(sourceRmsDb, capturedRmsDb, attenuation, verdict, file.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "自检失败", t)
            return Result(sourceRmsDb, -99f, -99f, "自检失败：${t.message}", null)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            if (savedVolume >= 0) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
            }
        }
    }

    /** 400Hz + 1200Hz 双音，加一点慢速幅度调制，模拟人声又不刺耳。 */
    private fun buildTestSignal(frames: Int): FloatArray {
        val out = FloatArray(frames)
        var phase1 = 0.0
        var phase2 = 0.0
        val w1 = 2 * PI * 400.0 / RATE
        val w2 = 2 * PI * 1200.0 / RATE
        for (i in 0 until frames) {
            phase1 += w1
            phase2 += w2
            val mod = 0.55 + 0.45 * sin(2 * PI * 3.0 * i / RATE)
            out[i] = ((sin(phase1) * 0.6 + sin(phase2) * 0.4) * mod * 0.35).toFloat()
        }
        // 两端淡入淡出，避免爆音
        val fade = RATE / 50
        for (i in 0 until min(fade, frames)) {
            out[i] *= i.toFloat() / fade
            out[frames - 1 - i] *= i.toFloat() / fade
        }
        return out
    }

    private fun rmsDb(x: FloatArray): Float {
        if (x.isEmpty()) return -99f
        var s = 0.0
        for (v in x) s += v.toDouble() * v.toDouble()
        return rmsDbFromSum(s, x.size)
    }

    private fun rmsDbFromSum(sum: Double, n: Int): Float {
        if (n <= 0 || sum <= 0.0) return -99f
        return (20.0 * kotlin.math.log10(sqrt(sum / n))).toFloat()
    }

    private fun fmt(v: Float) = String.format("%.1f", v)

    @Suppress("unused")
    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) if (abs(v) > p) p = abs(v)
        return p
    }
}
