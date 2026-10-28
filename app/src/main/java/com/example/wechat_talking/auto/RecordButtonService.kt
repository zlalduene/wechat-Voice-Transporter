package com.example.wechat_talking.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.example.wechat_talking.audio.PreparedClip
import com.example.wechat_talking.play.CastBus
import com.example.wechat_talking.play.CastState
import com.example.wechat_talking.play.OutputRoute
import com.example.wechat_talking.play.VoiceCastEngine
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
 */
class RecordButtonService : AccessibilityService() {

    companion object {
        private const val TAG = "RecordButton"

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
    private val bubbleParams = WindowManager.LayoutParams()
    private var hintView: TextView? = null
    private var bigHintView: TextView? = null
    private var calibView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    val isBubbleShown: Boolean get() = bubbleView != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        hideBubble()
        stopCalibration()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        hideBubble()
        stopCalibration()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮球

    fun showBubble() {
        if (bubbleView != null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
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

        bubbleParams.apply {
            width = size
            height = size
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (12 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false

        val longPress = Runnable {
            longPressed = true
            startCalibration()
        }

        circle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, 600)
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
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(circle, bubbleParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) onBubbleTapped()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
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
        }
    }

    fun hideBubble() {
        val v = bubbleView ?: return
        runCatching { windowManager?.removeView(v) }
        bubbleView = null
    }

    fun refreshBubbleColor() {
        val v = bubbleView ?: return
        (v.background as? GradientDrawable)?.setColor(
            if (pendingClip != null) 0xFF07C160.toInt() else 0xFF9AA0A6.toInt()
        )
    }

    private fun onBubbleTapped() {
        val clip = pendingClip
        if (clip == null) {
            showHint("还没有准备好音频，先回 App 里生成一次")
            return
        }
        if (VoiceCastEngine.isRunning) {
            showHint("正在播放中…")
            return
        }
        val dm = resources.displayMetrics
        val calib = AutoSettings.calibration(this)
        if (calib == null || !calib.stillValid(dm.widthPixels, dm.heightPixels)) {
            // 微信屏蔽了控件读取，第一次只能靠你点一下记住位置
            showHint("第一次用：点一下微信的「按住 说话」按钮位置")
            handler.postDelayed({ startCalibration() }, 700)
            return
        }
        val target = resolveTarget()
        if (target == null) {
            showHint("定位失败，长按这个圆球重新校准一次")
            saveTreeDump()
            return
        }
        Log.i(TAG, "目标：${target.label} @(${target.x},${target.y}) 来源=${target.source}")
        autoHoldAndPlay(target, clip)
    }

    // ---------------------------------------------------------------- 坐标校准

    fun startCalibration() {
        val wm = windowManager ?: return
        if (calibView != null) return
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

        Log.i(TAG, "长按 (${target.x}, ${target.y}) ${holdMs}ms，音频 ${clip.durationMs}ms")

        CastBus.state.value = CastState.Idle
        VoiceCastEngine.play(
            context = this,
            samples = clip.samples,
            sampleRate = clip.sampleRate,
            delayMs = lead,
            route = parseRoute(),
            capturePreview = AutoSettings.capturePreview(this),
            captureFile = File(cacheDir, "captured.wav"),
        )

        showHint("正在录… ${"%.1f".format(clip.durationMs / 1000.0)} 秒", 1500)
        handler.postDelayed({ refreshBubbleColor() }, holdMs + 300)
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
                sb.append("到它的界面 → 长按悬浮球 → 点一下「按住 说话」位置")
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

    fun showBigHint(text: String) {
        val wm = windowManager ?: return
        handler.post {
            val tv = bigHintView ?: TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 34f
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            }.also { bigHintView = it }
            tv.text = text
            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.8f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            runCatching {
                if (tv.parent == null) wm.addView(tv, params) else wm.updateViewLayout(tv, params)
            }
        }
    }

    fun hideBigHint() {
        handler.post {
            bigHintView?.let { runCatching { windowManager?.removeView(it) } }
            bigHintView = null
        }
    }

    /** 手动模式：倒数三下，然后开播。用户自己按住按钮。 */
    fun startGuidedSession(clip: PreparedClip) {
        handler.post {
            val steps = listOf("3", "2", "1", "按住 说话！")
            steps.forEachIndexed { index, label ->
                handler.postDelayed({ showBigHint(label) }, index * 900L)
            }
            val cueDelay = steps.size * 900L
            handler.postDelayed({
                hideBigHint()
                VoiceCastEngine.play(
                    context = this,
                    samples = clip.samples,
                    sampleRate = clip.sampleRate,
                    delayMs = 0,
                    route = parseRoute(),
                    capturePreview = AutoSettings.capturePreview(this),
                    captureFile = File(cacheDir, "captured.wav"),
                )
            }, cueDelay + 250L)
        }
    }
}
