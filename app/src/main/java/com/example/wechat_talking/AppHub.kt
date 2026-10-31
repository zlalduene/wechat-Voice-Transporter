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

    /** 正在解析刚选中的音频（界面把「选择音频文件」换成「正在解析」）。 */
    var parsing by mutableStateOf(false)
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
        stopPreview()
        parsing = true
        busy = "正在读取音频…"
        message = null
        scope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { AudioDecoder.decode(ctx, picked) }
                val peaks = withContext(Dispatchers.Default) { Dsp.envelope(pcm.samples, 1200) }
                val name = withContext(Dispatchers.IO) { resolveDisplayName(ctx, picked) }
                val ms = Dsp.durationMs(pcm.samples.size, pcm.sampleRate)
                decoded = pcm
                uri = picked
                fileName = name
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
            } finally {
                parsing = false
            }
        }
    }

    /** 从内容 uri 里把原始文件名拿出来（MediaStore 的 lastPathSegment 只是个数字 id）。 */
    private fun resolveDisplayName(ctx: Context, uri: Uri): String {
        val fromProvider = runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null
            }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        return uri.lastPathSegment?.substringAfterLast('/') ?: "音频"
    }

    fun prepare(ctx: Context) {
        val u = uri ?: run {
            message = "先选一个音频文件"
            return
        }
        stopPreview()
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
        stopPreview()
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

    /**
     * 悬浮球录音处理完就绪：直接当成当前片段，点球就能发。
     * 录进来的东西和主页选的文件不是一回事，所以把裁剪条清掉，避免误以为还能按原文件重新生成。
     */
    fun adoptRecordedClip(ctx: Context, clip: PreparedClip, sourceMs: Long) {
        stopPreview()
        this.clip = clip
        fileName = "悬浮窗录音 ${"%.1f".format(sourceMs / 1000f)}s"
        sourcePeaks = FloatArray(0)
        sourceDurationMs = sourceMs
        trimStartMs = 0f
        trimEndMs = 0f
        RecordButtonService.pendingClip = clip
        RecordButtonService.instance?.refreshBubbleColor()
        busy = null
        message = "已录好 ${"%.1f".format(clip.durationMs / 1000f)} 秒，点悬浮球就能发到微信"
    }

    /** 给无障碍服务报告进度用（busy 是 private set）。 */
    fun setBusyHint(text: String?) {
        busy = text
    }

    private var preview: android.media.MediaPlayer? = null

    /** 当前正在试听的本地文件；界面靠它把按钮切成「停止试听」。 */
    var previewingPath by mutableStateOf<String?>(null)
        private set

    /** 试听任意本地 wav（处理后片段 / 自检回采）。同一个文件再点一次 = 停止。 */
    fun togglePreview(path: String?) {
        if (path == null) return
        if (previewingPath == path && preview != null) {
            stopPreview()
            return
        }
        stopPreview()
        runCatching {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (preview === it) {
                    preview = null
                    previewingPath = null
                }
            }
            mp.setOnErrorListener { player, _, _ ->
                runCatching { player.release() }
                if (preview === player) {
                    preview = null
                    previewingPath = null
                }
                true
            }
            mp.start()
            preview = mp
            previewingPath = path
        }.onFailure {
            preview = null
            previewingPath = null
            message = "试听失败：${it.message}"
        }
    }

    /** 停止试听（没在放就什么也不做）。 */
    fun stopPreview() {
        val mp = preview
        preview = null
        previewingPath = null
        runCatching { if (mp?.isPlaying == true) mp.stop() }
        runCatching { mp?.release() }
    }
}
