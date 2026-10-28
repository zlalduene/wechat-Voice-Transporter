package com.example.wechat_talking

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.wechat_talking.audio.AudioDecoder
import com.example.wechat_talking.audio.Dsp
import com.example.wechat_talking.audio.Preset
import com.example.wechat_talking.audio.PreparedClip
import com.example.wechat_talking.audio.VoicePreparer
import com.example.wechat_talking.auto.AutoSettings
import com.example.wechat_talking.auto.RecordButtonService
import com.example.wechat_talking.play.CastBus
import com.example.wechat_talking.play.OutputRoute
import com.example.wechat_talking.play.VoiceCastEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局状态。这个 App 只有一屏，用一个单例比 ViewModel 省事，
 * 顺带让无障碍服务能直接读到「当前准备好的音频」。
 */
object AppHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var fileName by mutableStateOf("")
        private set
    var sourceDurationMs by mutableStateOf(0L)
        private set
    var sourcePeaks by mutableStateOf(FloatArray(0))
        private set

    var trimStartMs by mutableStateOf(0f)
    var trimEndMs by mutableStateOf(0f)

    var preset by mutableStateOf(Preset.CLEAR)
    var extraGainDb by mutableStateOf(0f)
    var autoTrimSilence by mutableStateOf(true)
    var capturePreview by mutableStateOf(false)

    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var clip by mutableStateOf<PreparedClip?>(null)
        private set
    var selfTest by mutableStateOf<SelfTest.Result?>(null)
        private set

    var calibrationText by mutableStateOf("未校准")
        private set

    fun refreshCalibration(ctx: Context) {
        val c = AutoSettings.calibration(ctx)
        calibrationText = if (c == null) {
            "未校准"
        } else {
            val dm = ctx.resources.displayMetrics
            val valid = c.stillValid(dm.widthPixels, dm.heightPixels)
            "已校准 (${c.x.toInt()}, ${c.y.toInt()})" + if (valid) "" else " · 需重设"
        }
    }

    private var uri: Uri? = null
    private var decoded: AudioDecoder.Pcm? = null

    val trimSeconds: Float
        get() = (trimEndMs - trimStartMs) / 1000f

    val route: OutputRoute
        get() = if (autoRoute == OutputRoute.EARPIECE) OutputRoute.EARPIECE else OutputRoute.SPEAKER

    private var autoRoute = OutputRoute.SPEAKER

    fun loadSettings(ctx: Context) {
        autoRoute = if (AutoSettings.route(ctx) == OutputRoute.EARPIECE.name) {
            OutputRoute.EARPIECE
        } else {
            OutputRoute.SPEAKER
        }
        capturePreview = AutoSettings.capturePreview(ctx)
    }

    fun setRoute(ctx: Context, r: OutputRoute) {
        autoRoute = r
        AutoSettings.setRoute(ctx, r.name)
    }

    fun setCapturePreview(ctx: Context, enabled: Boolean) {
        capturePreview = enabled
        AutoSettings.setCapturePreview(ctx, enabled)
    }

    fun pick(ctx: Context, picked: Uri?) {
        if (picked == null) return
        busy = "正在读取音频…"
        message = null
        scope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { AudioDecoder.decode(ctx, picked) }
                val peaks = withContext(Dispatchers.Default) { Dsp.envelope(pcm.samples, 1200) }
                val ms = Dsp.durationMs(pcm.samples.size, pcm.sampleRate)
                decoded = pcm
                uri = picked
                fileName = picked.lastPathSegment?.substringAfterLast('/') ?: "音频"
                sourcePeaks = peaks
                sourceDurationMs = ms
                trimStartMs = 0f
                trimEndMs = ms.toFloat().coerceAtMost(VoicePreparer.MAX_MS.toFloat())
                clip = null
                RecordButtonService.pendingClip = null
                RecordButtonService.instance?.refreshBubbleColor()
                busy = null
            } catch (t: Throwable) {
                busy = null
                message = t.message ?: "读取失败"
                decoded = null
                uri = null
                sourcePeaks = FloatArray(0)
                sourceDurationMs = 0L
            }
        }
    }

    fun prepare(ctx: Context) {
        val u = uri ?: run {
            message = "先选一个音频文件"
            return
        }
        busy = "正在处理音频…"
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    VoicePreparer.prepare(
                        context = ctx,
                        uri = u,
                        startMs = trimStartMs.toLong(),
                        endMs = trimEndMs.toLong(),
                        preset = preset,
                        extraGainDb = extraGainDb,
                        autoTrimSilence = autoTrimSilence,
                        decodedIn = decoded,
                    )
                }
                clip = result
                RecordButtonService.pendingClip = result
                RecordButtonService.instance?.refreshBubbleColor()
                busy = null
                message = "已生成 ${"%.1f".format(result.durationMs / 1000f)} 秒的语音片段"
            } catch (t: Throwable) {
                busy = null
                message = t.message ?: "处理失败"
            }
        }
    }

    fun playClip(ctx: Context) {
        val c = clip ?: run {
            message = "先生成一次片段"
            return
        }
        CastBus.state.value = com.example.wechat_talking.play.CastState.Idle
        VoiceCastEngine.play(
            context = ctx,
            samples = c.samples,
            sampleRate = c.sampleRate,
            delayMs = 0,
            route = route,
            capturePreview = capturePreview,
            captureFile = File(ctx.cacheDir, "captured.wav"),
        )
    }

    fun stopPlayback() = VoiceCastEngine.stop()

    fun runSelfTest(ctx: Context) {
        busy = "正在测外放→麦克风…"
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { SelfTest.run(ctx) }
            selfTest = r
            busy = null
            message = null
        }
    }

    fun diagnose(ctx: Context) {
        val svc = RecordButtonService.instance ?: run {
            message = "无障碍服务没连上，先去系统设置里打开它"
            return
        }
        message = svc.diagnose(AutoSettings.keyword(ctx))
    }

    fun toggleBubble(ctx: Context) {        val svc = RecordButtonService.instance ?: run {
            message = "先开启无障碍服务，才能显示悬浮按钮"
            return
        }
        if (svc.isBubbleShown) {
            svc.hideBubble()
        } else {
            RecordButtonService.pendingClip = clip
            svc.showBubble()
        }
    }

    fun guidedSend(ctx: Context) {
        val c = clip ?: run {
            message = "先生成一次片段"
            return
        }
        val svc = RecordButtonService.instance ?: run {
            message = "手动倒数需要无障碍服务来画倒数提示"
            return
        }
        svc.startGuidedSession(c)
    }

    private var preview: android.media.MediaPlayer? = null

    /** 试听任意本地 wav（处理后片段 / 自检回采）。 */
    fun previewFile(path: String?) {
        if (path == null) return
        runCatching {
            preview?.stop()
            preview?.release()
        }
        preview = null
        runCatching {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener {
                runCatching { it.release() }
                preview = null
            }
            preview = mp
        }
    }
}
