package com.example.wechat_talking.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wechat_talking.AppHub
import com.example.wechat_talking.MainActivity
import com.example.wechat_talking.audio.PreparedClip
import com.example.wechat_talking.audio.VoicePreparer
import com.example.wechat_talking.play.CastBus
import com.example.wechat_talking.play.CastState
import com.example.wechat_talking.play.OutputRoute
import com.example.wechat_talking.play.VoiceCastEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 定位微信聊天窗口的「按住 说话」并替用户长按，同时把音频放出来。
 *
 * 定位分三层，越靠前越可靠：
 *  1. 用户手动校准过坐标 —— 跟微信版本完全解耦；
 *  2. 控件树里按文字/描述找（部分微信版本不暴露文字）；
 *  3. 按几何结构找：占据大半屏宽、贴屏幕底部的那条可长按控件。
 *
 * 悬浮球交互：
 *  - 点一下 = 发送当前片段（录音中则是停止录音）；
 *  - 长按    = 弹出菜单（发送 / 录音 / 重新校准 / 使用说明 / 诊断 / 隐藏）；
 *  - 拖动    = 移动位置，坐标会被记住，并且永远不会跑出屏幕。
 */
class RecordButtonService : AccessibilityService() {

    companion object {
        private const val TAG = "RecordButton"

        /** 长按多久弹菜单。 */
        private const val LONG_PRESS_MS = 600L

        @Volatile
        var instance: RecordButtonService? = null
            private set

        /** 有音频就绪时才允许发送。 */
        @Volatile
        var pendingClip: PreparedClip? = null

        private val FALLBACK_KEYWORDS = listOf("按住 说话", "按住说话", "Hold to Talk", "Hold to talk")
    }

    private data class Target(val x: Float, val y: Float, val label: String, val source: String)

    private var windowManager: WindowManager? = null

    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null
    private val bubbleParams = WindowManager.LayoutParams()

    private var menuRoot: View? = null
    private var menuPanel: LinearLayout? = null
    private var menuSendTitle: TextView? = null
    private var menuSendSub: TextView? = null
    private var menuRecordTitle: TextView? = null
    private var menuRecordSub: TextView? = null

    private var helpView: View? = null
    private var hintView: TextView? = null
    private var recTipView: TextView? = null
    private var calibView: View? = null

    /** 点击坐标的红点标记（播放时告诉用户到底按在了哪）。 */
    private var tapDotRoot: View? = null
    private var tapDotHide: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    private var recorder: OverlayRecorder? = null
    private var preparingRecording = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 播放/发送中（倒计时或正在出声）。悬浮球图标和菜单文案都靠它切换。 */
    private var casting = false

    /** 这次播放是不是从悬浮球发起的，用来决定要不要弹「发送完成/已停止」。 */
    private var bubbleCastActive = false

    init {
        scope.launch {
            CastBus.state.collect { state ->
                val nowCasting = state is CastState.Countdown || state is CastState.Playing
                // Playing 状态每写一小块就会更新一次，这里只在「开始/结束」时刷界面
                if (nowCasting != casting) {
                    casting = nowCasting
                    refreshBubbleColor()
                    refreshMenu()
                }
                when (state) {
                    is CastState.Finished -> {
                        if (bubbleCastActive) {
                            bubbleCastActive = false
                            hideTapDot()
                            showHint("发送完成 ✓", 2200)
                        }
                    }

                    is CastState.Idle -> {
                        if (bubbleCastActive) {
                            bubbleCastActive = false
                            hideTapDot()
                            showHint("已停止发送", 2200)
                        }
                    }

                    is CastState.Failed -> {
                        if (bubbleCastActive) {
                            bubbleCastActive = false
                            hideTapDot()
                            showHint("发送失败：${state.message}", 3200)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    val isBubbleShown: Boolean get() = bubbleView != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏/分屏后旧坐标可能落在屏幕外，重新夹一次。
        closeMenu()
        closeHelp()
        hideTapDot()
        handler.post {
            val v = bubbleView ?: return@post
            clampBubble()
            runCatching { windowManager?.updateViewLayout(v, bubbleParams) }
            saveBubblePosition()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        shutdownOverlays()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        shutdownOverlays()
        super.onDestroy()
    }

    private fun shutdownOverlays() {
        runCatching { scope.cancel() }
        runCatching { recorder?.abandon() }
        recorder = null
        preparingRecording = false
        casting = false
        bubbleCastActive = false
        stopCalibration()
        hideBubble()
        closeHelp()
        hideRecordingTip()
        hideTapDot()
        hideHintRunnable.run()
    }

    // ---------------------------------------------------------------- 悬浮球

    fun showBubble() {
        if (bubbleView != null) return
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val density = dm.density
        val size = (56 * density).toInt()

        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (pendingClip != null) 0xFF07C160.toInt() else 0xFF9AA0A6.toInt())
                setStroke((2 * density).toInt(), 0x40FFFFFF)
            }
            elevation = 6 * density
        }
        val label = TextView(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        circle.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        bubbleLabel = label

        val restored = AutoSettings.bubblePosition(this)
        bubbleParams.apply {
            width = size
            height = size
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // 关键：不能加 FLAG_LAYOUT_NO_LIMITS，否则窗口能画到屏幕外面去。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            if (restored != null && restored.stillValid(dm.widthPixels, dm.heightPixels)) {
                x = restored.x
                y = restored.y
            } else {
                x = dm.widthPixels - size - (12 * density).toInt()
                y = (dm.heightPixels * 0.55f).toInt()
            }
        }
        clampBubble()

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var limitX = 0
        var limitY = 0
        var moved = false
        var longPressed = false

        val longPress = Runnable {
            longPressed = true
            showBubbleMenu()
        }

        circle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    val area = usableScreen()
                    limitX = max(0, area.width() - bubbleParams.width)
                    limitY = max(0, area.height() - bubbleParams.height)
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > 14 || abs(dy) > 14) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        // 菜单开着的时候继续拖球，就把菜单收起来，免得位置错乱
                        if (longPressed) closeMenu()
                        // 拖动时实时夹住，球永远停在屏幕里
                        bubbleParams.x = (startX + dx.toInt()).coerceIn(0, limitX)
                        bubbleParams.y = (startY + dy.toInt()).coerceIn(0, limitY)
                        runCatching { wm.updateViewLayout(circle, bubbleParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        clampBubble()
                        runCatching { wm.updateViewLayout(circle, bubbleParams) }
                        saveBubblePosition()
                    } else if (!longPressed) {
                        onBubbleTapped()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        clampBubble()
                        runCatching { wm.updateViewLayout(circle, bubbleParams) }
                        saveBubblePosition()
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            wm.addView(circle, bubbleParams)
            bubbleView = circle
            refreshBubbleColor()
        }.onFailure {
            Log.w(TAG, "悬浮球添加失败：${it.message}")
            return
        }

        val firstTime = !AutoSettings.tipsSeen(this)
        if (firstTime) AutoSettings.setTipsSeen(this, true)
        handler.postDelayed({
            if (bubbleView == null) return@postDelayed
            if (firstTime) {
                showHint("点一下球 = 发送语音\n长按球 = 菜单（录音 / 校准 / 说明）", 6000)
            } else {
                showHint("点一下 = 发送 · 长按 = 菜单", 1600)
            }
        }, 400)
    }

    fun hideBubble() {
        // 隐藏时如果正在录音，先把录音收尾，录到的东西照样能用。
        if (recorder?.isRecording == true) recorder?.stop()
        closeMenu()
        closeHelp()
        hideRecordingTip()
        val v = bubbleView ?: return
        runCatching { windowManager?.removeView(v) }
        bubbleView = null
        bubbleLabel = null
    }

    fun refreshBubbleColor() {
        val v = bubbleView ?: return
        val recording = recorder?.isRecording == true
        (v.background as? GradientDrawable)?.setColor(
            when {
                recording -> 0xFFD32F2F.toInt()
                casting -> 0xFFF57C00.toInt()
                pendingClip != null -> 0xFF07C160.toInt()
                else -> 0xFF9AA0A6.toInt()
            }
        )
        // 图标跟着状态切换：录音 ■ / 发送中 Ⅱ / 空闲 ▶
        bubbleLabel?.text = when {
            recording -> "■"
            casting -> "Ⅱ"
            else -> "▶"
        }
    }

    /** 屏幕里真正能放的区域（去掉状态栏/导航栏/刘海），坐标原点在状态栏下方。 */
    private fun usableScreen(): Rect {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            runCatching {
                val metrics = wm.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                return Rect(
                    0,
                    0,
                    max(0, bounds.width() - insets.left - insets.right),
                    max(0, bounds.height() - insets.top - insets.bottom),
                )
            }
        }
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun clampBubble() {
        val area = usableScreen()
        val maxX = max(0, area.width() - bubbleParams.width)
        val maxY = max(0, area.height() - bubbleParams.height)
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)
    }

    private fun saveBubblePosition() {
        val dm = resources.displayMetrics
        AutoSettings.setBubblePosition(
            this,
            bubbleParams.x,
            bubbleParams.y,
            dm.widthPixels,
            dm.heightPixels,
        )
    }

    private fun onBubbleTapped() {
        if (recorder?.isRecording == true) {
            stopRecording()
            return
        }
        if (casting || VoiceCastEngine.isRunning) {
            stopSending()
            return
        }
        if (preparingRecording) {
            showHint("录音还在处理，稍等一下", 1800)
            return
        }
        val clip = pendingClip
        if (clip == null) {
            showHint("还没有可发的音频：先回 App 生成一次\n或者长按球 →「开始录音」", 3600)
            return
        }
        val dm = resources.displayMetrics
        val calib = AutoSettings.calibration(this)
        if (calib == null || !calib.stillValid(dm.widthPixels, dm.heightPixels)) {
            // 微信屏蔽了控件读取，第一次只能靠你点一下记住位置
            showHint("先校准一次：点一下微信的「按住 说话」按钮\n（马上打开校准，只记坐标，不会发送）", 3600)
            handler.postDelayed({ startCalibration() }, 1000)
            return
        }
        val target = resolveTarget()
        if (target == null) {
            showHint("定位失败：长按悬浮球 →「重新校准按钮位置」", 3200)
            saveTreeDump()
            return
        }
        Log.i(TAG, "目标：${target.label} @(${target.x},${target.y}) 来源=${target.source}")
        autoHoldAndPlay(target, clip)
    }

    /** 再点一下球 / 菜单里的「停止发送」：把正在出声的播放掐掉。 */
    private fun stopSending() {
        if (!casting && !VoiceCastEngine.isRunning) return
        // 停掉时红点也要立刻消失，别一直挂在那儿
        hideTapDot()
        // 保证停止后由 CastBus 的状态回调弹一句「已停止发送」
        bubbleCastActive = true
        VoiceCastEngine.stop()
    }

    // ---------------------------------------------------------------- 长按菜单

    private fun showBubbleMenu() {
        val wm = windowManager ?: return
        if (menuRoot != null) {
            positionMenu()
            return
        }
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val area = usableScreen()
        val panelWidth = min((240 * density).toInt(), area.width() - margin * 2)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xF018191C.toInt())
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            setPadding(
                (8 * density).toInt(), (10 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt(),
            )
            elevation = 12 * density
        }

        panel.addView(
            TextView(this).apply {
                text = "点球发送 · 长按球菜单"
                setTextColor(0xFF07C160.toInt())
                textSize = 12f
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), (4 * density).toInt())
            },
        )
        panel.addView(menuDivider())

        panel.addView(sendMenuRow())
        panel.addView(recordMenuRow())
        panel.addView(
            menuRow(
                "⌖  重新校准按钮位置",
                "换机型 / 微信改版后点这里",
            ) {
                closeMenu()
                startCalibration()
            },
        )
        panel.addView(
            menuRow(
                "☰  使用说明",
                "第一次用先看这个",
            ) {
                closeMenu()
                showHelp()
            },
        )
        panel.addView(
            menuRow(
                "◇  诊断状态",
                "检查能不能找到微信的按钮",
            ) {
                closeMenu()
                showHint(diagnose(AutoSettings.keyword(this)), 8000)
            },
        )
        panel.addView(
            menuRow(
                "✕  隐藏悬浮球",
                "回 App 主页可以再打开",
            ) {
                closeMenu()
                hideBubble()
            },
        )

        val root = FrameLayout(this)
        // 点菜单外面任何地方都关掉菜单
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) closeMenu()
            true
        }
        root.addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = margin
                topMargin = margin
            },
        )

        val rootParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            wm.addView(root, rootParams)
            menuRoot = root
            menuPanel = panel
            panel.post { positionMenu() }
        }.onFailure {
            Log.w(TAG, "菜单打开失败：${it.message}")
            menuRoot = null
            menuPanel = null
            showHint("菜单打不开：${it.message}", 3000)
        }
    }

    private fun positionMenu() {
        val panel = menuPanel ?: return
        if (panel.width <= 0 || panel.height <= 0) return
        val area = usableScreen()
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val gap = (10 * density).toInt()
        val maxLeft = max(margin, area.width() - panel.width - margin)
        val maxTop = max(margin, area.height() - panel.height - margin)

        val bubbleCenterX = bubbleParams.x + bubbleParams.width / 2
        val leftIfLeft = bubbleParams.x - panel.width - gap
        val leftIfRight = bubbleParams.x + bubbleParams.width + gap
        val left = (if (bubbleCenterX > area.width() / 2) leftIfLeft else leftIfRight)
            .coerceIn(margin, maxLeft)
        val top = (bubbleParams.y + bubbleParams.height / 2 - panel.height / 2)
            .coerceIn(margin, maxTop)

        (panel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.leftMargin = left
            lp.topMargin = top
            panel.layoutParams = lp
        }
    }

    private fun refreshMenu() {
        menuSendTitle?.let { title ->
            title.text = if (casting) "■  停止发送" else "▶  发送当前语音"
        }
        menuSendSub?.text = when {
            casting -> "正在发出这条语音… 点这里停止"
            pendingClip == null -> "先在 App 里生成片段，或长按球录音"
            VoiceCastEngine.isRunning -> "正在播放…"
            else -> "自动长按微信「按住 说话」并播放"
        }
        val title = menuRecordTitle ?: return
        val recording = recorder?.isRecording == true
        title.text = if (recording) "■  停止录音" else "●  开始录音"
        menuRecordSub?.text = when {
            recording -> "正在录… 点这里停止（最长 59 秒）"
            preparingRecording -> "上一段录音还在处理…"
            else -> "刷视频时录下这段声音（最长 59 秒，记得外放）"
        }
    }

    private fun sendMenuRow(): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * density).toInt(), (8 * density).toInt(),
                (10 * density).toInt(), (8 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            background = selectableRowBackground()
            setOnClickListener {
                closeMenu()
                onBubbleTapped()
            }
        }
        val title = TextView(this).apply {
            text = if (casting) "■  停止发送" else "▶  发送当前语音"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val sub = TextView(this).apply {
            text = if (casting) {
                "正在发出这条语音… 点这里停止"
            } else {
                "自动长按微信「按住 说话」并播放"
            }
            setTextColor(0xFFB0B4BA.toInt())
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        row.addView(title)
        row.addView(sub)
        menuSendTitle = title
        menuSendSub = sub
        return row
    }

    private fun closeMenu() {
        val root = menuRoot ?: return
        runCatching { windowManager?.removeView(root) }
        menuRoot = null
        menuPanel = null
        menuSendTitle = null
        menuSendSub = null
        menuRecordTitle = null
        menuRecordSub = null
    }

    private fun menuRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * density).toInt(), (8 * density).toInt(),
                (10 * density).toInt(), (8 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            background = selectableRowBackground()
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
            },
        )
        row.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(0xFFB0B4BA.toInt())
                textSize = 11f
                setPadding(0, (2 * density).toInt(), 0, 0)
            },
        )
        return row
    }

    private fun recordMenuRow(): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * density).toInt(), (8 * density).toInt(),
                (10 * density).toInt(), (8 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            background = selectableRowBackground()
            setOnClickListener { toggleRecording() }
        }
        val title = TextView(this).apply {
            text = if (recorder?.isRecording == true) "■  停止录音" else "●  开始录音"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val sub = TextView(this).apply {
            text = if (recorder?.isRecording == true) {
                "正在录… 点这里停止（最长 59 秒）"
            } else {
                "刷视频时录下这段声音（最长 59 秒，记得外放）"
            }
            setTextColor(0xFFB0B4BA.toInt())
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        row.addView(title)
        row.addView(sub)
        menuRecordTitle = title
        menuRecordSub = sub
        return row
    }

    private fun menuDivider(): View = View(this).apply {
        setBackgroundColor(0x22FFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            max(1, resources.displayMetrics.density.toInt()),
        ).apply {
            topMargin = (4 * resources.displayMetrics.density).toInt()
            bottomMargin = (4 * resources.displayMetrics.density).toInt()
        }
    }

    private fun selectableRowBackground(): Drawable? {
        val value = TypedValue()
        val found = theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return if (found && value.resourceId != 0) {
            ContextCompat.getDrawable(this, value.resourceId)
        } else {
            null
        }
    }

    // ---------------------------------------------------------------- 录音

    private fun toggleRecording() {
        if (recorder?.isRecording == true) {
            stopRecording()
            return
        }
        if (preparingRecording) {
            showHint("上一段录音还在处理，稍等一下", 1800)
            return
        }
        if (VoiceCastEngine.isRunning) {
            showHint("正在播放/发送中，等它结束再录音", 2200)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val rec = recorder ?: OverlayRecorder(this).also { recorder = it }
        if (!rec.hasPermission()) {
            closeMenu()
            showHint("录音需要麦克风权限\n正在打开 App，点「允许」后再回来长按球", 4600)
            openAppToGrantMic()
            return
        }

        val callback = object : OverlayRecorder.Callback {
            override fun onTick(elapsedMs: Long) {
                handler.post { showRecordingTip(elapsedMs) }
            }

            override fun onFinished(samples: FloatArray, hitLimit: Boolean) {
                handler.post {
                    hideRecordingTip()
                    refreshBubbleColor()
                    refreshMenu()
                    if (hitLimit) {
                        showHint("已经录满 ${OverlayRecorder.MAX_MS / 1000} 秒，自动停止", 2200)
                    }
                    handleRecordedSamples(samples)
                }
            }

            override fun onError(message: String) {
                handler.post {
                    hideRecordingTip()
                    refreshBubbleColor()
                    refreshMenu()
                    showHint(message, 3200)
                }
            }
        }

        if (!rec.start(callback)) return

        closeMenu()
        refreshBubbleColor()
        showRecordingTip(0)
        showHint("开始录音… 再点一下悬浮球停止", 2600)
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        if (!rec.isRecording) return
        showHint("正在保存录音…", 1400)
        rec.stop()
    }

    private fun handleRecordedSamples(samples: FloatArray) {
        val sourceMs = samples.size * 1000L / OverlayRecorder.SAMPLE_RATE
        if (sourceMs < 400) {
            showHint("录得太短了（$sourceMs 毫秒），至少录半秒", 2600)
            return
        }
        preparingRecording = true
        AppHub.setBusyHint("正在处理录音…")

        val preset = AppHub.preset
        val gain = AppHub.extraGainDb
        val trim = AppHub.autoTrimSilence

        Thread({
            val result = runCatching {
                VoicePreparer.prepareFromSamples(
                    context = this@RecordButtonService,
                    input = samples,
                    sampleRate = OverlayRecorder.SAMPLE_RATE,
                    preset = preset,
                    extraGainDb = gain,
                    autoTrimSilence = trim,
                    fileName = "record.wav",
                )
            }
            handler.post {
                preparingRecording = false
                refreshBubbleColor()
                refreshMenu()
                result.onSuccess { clip ->
                    AppHub.adoptRecordedClip(this@RecordButtonService, clip, sourceMs)
                    showHint(
                        "录好了 ${"%.1f".format(clip.durationMs / 1000f)} 秒\n点一下悬浮球就能发到微信",
                        3400,
                    )
                }.onFailure {
                    AppHub.setBusyHint(null)
                    AppHub.message = "录音处理失败：${it.message}"
                    showHint("录音处理失败：${it.message}", 3200)
                }
            }
        }, "record-prepare").start()
    }

    private fun showRecordingTip(elapsedMs: Long) {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val tv = recTipView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(
                (14 * density).toInt(), (7 * density).toInt(),
                (14 * density).toInt(), (7 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xF0D32F2F.toInt())
            }
            elevation = 6 * density
        }.also { recTipView = it }

        tv.text = "● 录音中 ${"%.1f".format(elapsedMs / 1000f)} s · 点球停止"
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (56 * density).toInt()
        }
        runCatching {
            if (tv.parent == null) wm.addView(tv, params) else wm.updateViewLayout(tv, params)
        }
    }

    private fun hideRecordingTip() {
        val v = recTipView ?: return
        runCatching { windowManager?.removeView(v) }
        recTipView = null
    }

    // ---------------------------------------------------------------- 点击坐标红点

    /**
     * 在 (x, y) 处画一个红点，告诉用户这次到底点在了屏幕的哪里。
     * x/y 是屏幕坐标（和 getBoundsInScreen / 校准时的 rawX rawY 一致），
     * 所以这里用 FLAG_LAYOUT_IN_SCREEN 的全屏透明窗，坐标原点就是屏幕左上角。
     */
    private fun showTapDot(x: Float, y: Float, lifeMs: Long = 1600L) {
        val wm = windowManager ?: return
        handler.post {
            hideTapDot()
            val density = resources.displayMetrics.density
            val dm = resources.displayMetrics
            val boxSize = (56 * density).toInt()
            val ringSize = (26 * density).toInt()
            val dotSize = (14 * density).toInt()

            val ring = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * density).toInt(), 0xFFFF5252.toInt())
                }
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE53935.toInt())
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
                elevation = 4 * density
            }

            val marker = FrameLayout(this)
            marker.addView(
                ring,
                FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER),
            )
            marker.addView(
                dot,
                FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER),
            )

            val root = FrameLayout(this)
            val left = (x - boxSize / 2f).toInt().coerceIn(0, max(0, dm.widthPixels - boxSize))
            val top = (y - boxSize / 2f).toInt().coerceIn(0, max(0, dm.heightPixels - boxSize))
            root.addView(
                marker,
                FrameLayout.LayoutParams(boxSize, boxSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = left
                    topMargin = top
                },
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val added = runCatching {
                wm.addView(root, params)
                true
            }.getOrDefault(false)
            if (!added) {
                Log.w(TAG, "红点标记添加失败")
                return@post
            }
            tapDotRoot = root

            // 扩散环闪两次，给个「按这里」的动效
            val pulse = object : Runnable {
                var times = 2

                override fun run() {
                    if (tapDotRoot == null || times <= 0) return
                    times--
                    ring.scaleX = 0.7f
                    ring.scaleY = 0.7f
                    ring.alpha = 1f
                    ring.animate()
                        .scaleX(2.2f)
                        .scaleY(2.2f)
                        .alpha(0f)
                        .setDuration(620L)
                        .start()
                    handler.postDelayed(this, 480L)
                }
            }
            handler.post(pulse)

            val hide = Runnable { hideTapDot() }
            tapDotHide = hide
            handler.postDelayed(hide, max(400L, lifeMs))
        }
    }

    private fun hideTapDot() {
        tapDotHide?.let { handler.removeCallbacks(it) }
        tapDotHide = null
        tapDotRoot?.let { runCatching { windowManager?.removeView(it) } }
        tapDotRoot = null
    }

    private fun openAppToGrantMic() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("request_record", true)
                },
            )
        }.onFailure {
            Log.w(TAG, "打开 App 申请录音权限失败：${it.message}")
            showHint("请手动回到 App 里授予麦克风权限", 3200)
        }
    }

    // ---------------------------------------------------------------- 使用说明

    private fun showHelp() {
        val wm = windowManager ?: return
        if (helpView != null) return
        val density = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (18 * density).toInt(), (16 * density).toInt(),
                (18 * density).toInt(), (14 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xF518191C.toInt())
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            elevation = 14 * density
        }
        card.addView(
            TextView(this).apply {
                text = "第一次用？按这个顺序来"
                setTextColor(0xFF07C160.toInt())
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        card.addView(
            TextView(this).apply {
                text = HELP_TEXT
                setTextColor(0xFFE6E8EB.toInt())
                textSize = 13f
                setLineSpacing(5f * density, 1f)
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            },
        )
        card.addView(
            TextView(this).apply {
                text = "点任意位置关闭"
                setTextColor(0xFFB0B4BA.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
            },
        )

        val root = FrameLayout(this).apply { setBackgroundColor(0xCC000000.toInt()) }
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) closeHelp()
            true
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = (16 * density).toInt()
                rightMargin = (16 * density).toInt()
                topMargin = (24 * density).toInt()
                bottomMargin = (24 * density).toInt()
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            wm.addView(root, params)
            helpView = root
        }.onFailure {
            Log.w(TAG, "说明打不开：${it.message}")
            showHint("说明打不开：${it.message}", 3000)
        }
    }

    private fun closeHelp() {
        val v = helpView ?: return
        runCatching { windowManager?.removeView(v) }
        helpView = null
    }

    // ---------------------------------------------------------------- 坐标校准

    fun startCalibration() {
        val wm = windowManager ?: return
        if (calibView != null) return
        closeMenu()
        closeHelp()
        val density = resources.displayMetrics.density

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
        }
        val tip = TextView(this).apply {
            text = "点一下微信的「按住 说话」按钮\n（这次只记坐标，不会发送）"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (120 * density).toInt())
        }
        root.addView(
            tip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val dm = resources.displayMetrics
                AutoSettings.setCalibration(this, ev.rawX, ev.rawY, dm.widthPixels, dm.heightPixels)
                Log.i(TAG, "校准到 (${ev.rawX}, ${ev.rawY})")
                stopCalibration()
                showTapDot(ev.rawX, ev.rawY)
                showHint("已记住按钮位置\n现在点悬浮球就开始发送")
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            wm.addView(root, params)
            calibView = root
        }.onFailure {
            Log.w(TAG, "校准层添加失败：${it.message}")
            showHint("校准层打不开：${it.message}")
        }
    }

    fun stopCalibration() {
        val v = calibView ?: return
        runCatching { windowManager?.removeView(v) }
        calibView = null
    }

    // ---------------------------------------------------------------- 找目标

    private fun resolveTarget(): Target? {
        // 1. 校准坐标
        val dm = resources.displayMetrics
        AutoSettings.calibration(this)?.let { c ->
            if (c.stillValid(dm.widthPixels, dm.heightPixels)) {
                return Target(c.x, c.y, "校准坐标", "calib")
            }
        }

        // 2. 文字 / 描述
        val keyword = AutoSettings.keyword(this)
        findNodeByText(keyword)?.let { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val label = node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: keyword
                return Target(rect.exactCenterX(), rect.exactCenterY(), label, "text")
            }
        }
        for (k in FALLBACK_KEYWORDS) {
            if (k == keyword) continue
            findNodeByText(k)?.let { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    return Target(rect.exactCenterX(), rect.exactCenterY(), k, "text")
                }
            }
        }

        // 3. 几何结构
        findNodeByGeometry()?.let { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return Target(rect.exactCenterX(), rect.exactCenterY(), "底部横条控件", "geometry")
            }
        }
        return null
    }

    /** 遍历所有非本 App 的窗口找文字，不强制要求 isVisibleToUser。 */
    private fun findNodeByText(keyword: String): AccessibilityNodeInfo? {
        if (keyword.isBlank()) return null
        for (root in candidateRoots()) {
            val found = bfs(root) { node ->
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                (text != null && text.contains(keyword)) || (desc != null && desc.contains(keyword))
            }
            if (found != null) return found
        }
        return null
    }

    /**
     * 找不到文字时按结构猜：占屏宽一半以上、高度像输入条、位置靠屏幕底部的那条控件。
     * 微信语音模式的录音按钮恰好是这个形状。
     */
    private fun findNodeByGeometry(): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val minW = (screenW * 0.5f).toInt()
        val minH = (24 * dm.density).toInt()
        val maxH = (110 * dm.density).toInt()
        val minTop = (screenH * 0.5f).toInt()

        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1

        for (root in candidateRoots()) {
            bfs(root) { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val ok = rect.width() >= minW &&
                    rect.height() in minH..maxH &&
                    rect.top >= minTop &&
                    rect.bottom <= screenH &&
                    (node.isClickable || node.isLongClickable)
                if (ok && rect.bottom > bestBottom) {
                    bestBottom = rect.bottom
                    best = node
                }
                false
            }
        }
        return best
    }

    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString()
                if (pkg == packageName) continue
                result.add(r)
            }
        }
        if (result.isEmpty()) {
            rootInActiveWindow?.let {
                if (it.packageName?.toString() != packageName) result.add(it)
            }
        }
        return result
    }

    private inline fun bfs(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 6000) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 自动长按

    private fun autoHoldAndPlay(target: Target, clip: PreparedClip) {
        val lead = AutoSettings.leadMs(this).toLong()
        val tail = AutoSettings.tailMs(this).toLong()
        val maxHold = GestureDescription.getMaxGestureDuration().toLong() - 800L
        val holdMs = min(lead + clip.durationMs + tail, maxHold)

        val path = Path().apply { moveTo(target.x, target.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, max(200L, holdMs)))
            .build()

        val accepted = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        if (!accepted) {
            showHint("系统拒绝了下发手势，确认无障碍服务还开着")
            return
        }

        // 把这次按下去的坐标用红点标出来，按住多久就显示多久
        showTapDot(target.x, target.y, holdMs + 300L)

        Log.i(TAG, "长按 (${target.x}, ${target.y}) ${holdMs}ms，音频 ${clip.durationMs}ms")

        CastBus.state.value = CastState.Idle
        bubbleCastActive = true
        VoiceCastEngine.play(
            context = this,
            samples = clip.samples,
            sampleRate = clip.sampleRate,
            delayMs = lead,
            route = parseRoute(),
            capturePreview = AutoSettings.capturePreview(this),
            captureFile = File(cacheDir, "captured.wav"),
        )

        showHint(
            "正在发送语音… ${"%.1f".format(clip.durationMs / 1000.0)} 秒\n再点一下悬浮球可以停止",
            4000,
        )
    }

    private fun parseRoute(): OutputRoute =
        if (AutoSettings.route(this) == OutputRoute.EARPIECE.name) {
            OutputRoute.EARPIECE
        } else {
            OutputRoute.SPEAKER
        }

    // ---------------------------------------------------------------- 诊断

    fun diagnose(keyword: String): String {
        val dm = resources.displayMetrics
        val sb = StringBuilder()
        val calib = AutoSettings.calibration(this)
        sb.append(
            if (calib == null) "坐标校准：未设置\n"
            else "坐标校准：(${"%.0f".format(calib.x)}, ${"%.0f".format(calib.y)})" +
                if (calib.stillValid(dm.widthPixels, dm.heightPixels)) "（有效）\n" else "（屏幕尺寸变了，需重设）\n"
        )

        val roots = candidateRoots()
        val pkgs = roots.mapNotNull { it.packageName?.toString() }.distinct()
        val readable = roots.any { it.childCount > 0 }
        sb.append("可读窗口：").append(if (pkgs.isEmpty()) "无" else pkgs.joinToString())
        sb.append('\n').append("控件树：").append(if (readable) "可读" else "读不到（界面被 App 屏蔽）").append('\n')

        val target = resolveTarget()
        return if (target == null) {
            val path = saveTreeDump()
            sb.append("没找到「").append(keyword).append("」\n")
            if (!readable) {
                sb.append("这个 App 把界面屏蔽了无障碍读取，只能靠坐标校准：\n")
                sb.append("长按悬浮球 →「重新校准按钮位置」")
            } else {
                sb.append("控件树已存到：").append(path)
            }
            sb.toString()
        } else {
            sb.append("找到「").append(target.label).append("」\n")
            sb.append("命中方式：").append(
                when (target.source) {
                    "calib" -> "校准坐标"
                    "text" -> "控件文字"
                    else -> "几何结构推测"
                }
            ).append('\n')
            sb.append("位置：(${"%.0f".format(target.x)}, ${"%.0f".format(target.y)})")
            sb.toString()
        }
    }

    /** 把当前界面的控件树存下来，方便自己调整。 */
    fun saveTreeDump(): String {
        val sb = StringBuilder()
        for (root in candidateRoots()) {
            sb.append("=== window pkg=").append(root.packageName).append(" ===\n")
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 40) return
                val rect = Rect()
                n.getBoundsInScreen(rect)
                val text = n.text?.toString()?.replace("\n", " ")
                val desc = n.contentDescription?.toString()?.replace("\n", " ")
                val flags = buildString {
                    if (n.isClickable) append("C")
                    if (n.isLongClickable) append("L")
                    if (n.isVisibleToUser) append("V")
                }
                if (!text.isNullOrBlank() || !desc.isNullOrBlank() || flags.contains("L")) {
                    sb.append("  ".repeat(depth))
                        .append(n.className?.toString()?.substringAfterLast('.'))
                        .append(" id=").append(n.viewIdResourceName?.substringAfterLast('/'))
                        .append(" text=").append(text)
                        .append(" desc=").append(desc)
                        .append(" f=").append(flags)
                        .append(" bounds=").append(rect.toShortString())
                        .append('\n')
                }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
        }
        val file = File(getExternalFilesDir(null) ?: filesDir, "ui_dump.txt")
        runCatching { file.writeText(sb.toString()) }
        Log.i(TAG, "控件树已保存：${file.absolutePath}")
        return file.absolutePath
    }

    // ---------------------------------------------------------------- 覆盖层提示

    private fun showHint(text: String, durationMs: Long = 2600) {
        val wm = windowManager ?: return
        handler.post {
            val density = resources.displayMetrics.density
            val tv = hintView ?: TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(
                    (16 * density).toInt(), (10 * density).toInt(),
                    (16 * density).toInt(), (10 * density).toInt(),
                )
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(0xE6222222.toInt())
                }
                maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
                gravity = Gravity.CENTER
            }.also { hintView = it }

            tv.text = text
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (96 * density).toInt()
            }
            runCatching {
                if (tv.parent == null) wm.addView(tv, params) else wm.updateViewLayout(tv, params)
            }
            handler.removeCallbacks(hideHintRunnable)
            handler.postDelayed(hideHintRunnable, durationMs)
        }
    }

    private val hideHintRunnable = Runnable {
        hintView?.let { runCatching { windowManager?.removeView(it) } }
        hintView = null
    }
}

private const val HELP_TEXT = "① 回 App 主页，开启「无障碍服务」（只需要一次）\n" +
    "② 打开微信聊天，切到语音输入模式，让「按住 说话」露出来\n" +
    "③ 第一次点悬浮球：按提示点一下「按住 说话」，记住坐标\n" +
    "④ 之后点一下球 = 自动长按 + 播放音频 = 一条真语音\n\n" +
    "长按球会弹出菜单：\n" +
    "· 发送当前语音\n" +
    "· 开始录音（刷视频时录下这段声音）\n" +
    "· 重新校准按钮位置\n" +
    "· 使用说明 / 诊断 / 隐藏悬浮球\n\n" +
    "录音怎么用：\n" +
    "长按球 →「开始录音」→ 录完点一下球停止，\n" +
    "处理完会变绿，点球就能发到微信。\n\n" +
    "注意：录音走的是麦克风，视频要外放，戴耳机就录不到了。\n\n" +
    "悬浮球可以拖动，位置会记住，拖不出屏幕。"
