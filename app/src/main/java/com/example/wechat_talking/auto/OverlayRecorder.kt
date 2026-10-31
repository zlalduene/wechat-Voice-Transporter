package com.example.wechat_talking.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * 悬浮球菜单里的「录音」。
 *
 * 刷视频时听到一段想发出去的声音，就用它把麦克风收到的声音录下来，
 * 录完交给 [com.example.wechat_talking.audio.VoicePreparer] 处理成可直接发送的语音片段。
 *
 * 为什么不用 MediaProjection 抓系统内录：
 *  - 内录每次都要弹一个系统授权框，悬浮球上没法弹（无障碍悬浮窗不能弹这种对话框）；
 *  - 这个 App 本来就是靠「外放 → 麦克风」这条路的，录音和发送用的是同一条链路，效果一致。
 *
 * 线程模型：start() 在主线程调用，采集跑在独立线程，回调都在采集线程上，
 * 调用方（无障碍服务）自己 post 回主线程。
 */
class OverlayRecorder(private val context: Context) {

    interface Callback {
        /** 录制过程中大约每 200ms 回调一次（在采集线程）。 */
        fun onTick(elapsedMs: Long)

        /** 录完了的原始采样（16kHz 单声道，[-1, 1]），已停止并释放录音器。 */
        fun onFinished(samples: FloatArray, hitLimit: Boolean)

        fun onError(message: String)
    }

    companion object {
        private const val TAG = "OverlayRecorder"

        /** 和发送链路一致，省一次重采样。 */
        const val SAMPLE_RATE = 16000

        /** 微信语音上限 60 秒，留 1 秒余量。 */
        const val MAX_MS = 59_000L
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var abandoned = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var startedAt = 0L

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun start(cb: Callback): Boolean {
        if (isRecording) return false
        if (!hasPermission()) {
            cb.onError("没有麦克风权限：先回 App 主页点一次「允许」")
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            cb.onError("麦克风初始化失败（设备不支持 16k 单声道录音）")
            return false
        }

        val rec = runCatching {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 2, SAMPLE_RATE * 2),
            )
        }.getOrNull()

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            cb.onError("麦克风打不开，可能被其它 App 占着（比如正在语音通话）")
            return false
        }

        val started = runCatching {
            rec.startRecording()
            rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            runCatching { rec.release() }
            cb.onError("麦克风启动失败，等一下再试")
            return false
        }

        record = rec
        stopRequested = false
        abandoned = false
        startedAt = System.currentTimeMillis()
        isRecording = true
        worker = Thread({ readLoop(rec, cb) }, "overlay-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 请求停止（采集线程收尾后才会回调 onFinished）。 */
    fun stop() {
        if (!isRecording) return
        stopRequested = true
    }

    /** 服务被关掉时直接丢弃，不再回调。 */
    fun abandon() {
        abandoned = true
        stopRequested = true
        isRecording = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private fun readLoop(rec: AudioRecord, cb: Callback) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val chunks = ArrayList<ShortArray>()
        val buf = ShortArray(2048)
        val maxSamples = (MAX_MS * SAMPLE_RATE / 1000).toInt()
        var total = 0
        var lastTick = 0L

        try {
            while (!stopRequested && total < maxSamples) {
                val want = minOf(buf.size, maxSamples - total)
                val n = rec.read(buf, 0, want)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                chunks.add(buf.copyOf(n))
                total += n
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed - lastTick >= 200) {
                    lastTick = elapsed
                    cb.onTick(elapsed)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "录音读取中断：${t.message}")
        }

        val hitLimit = total >= maxSamples
        isRecording = false
        runCatching { rec.stop() }
        runCatching { rec.release() }
        record = null

        if (abandoned) return

        val flat = ShortArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, flat, offset, chunk.size)
            offset += chunk.size
        }
        val samples = FloatArray(flat.size) { flat[it] / 32768f }

        if (samples.isEmpty()) {
            cb.onError("没录到声音（麦克风可能被占用）")
            return
        }
        cb.onTick(samples.size * 1000L / SAMPLE_RATE)
        cb.onFinished(samples, hitLimit)
    }
}
