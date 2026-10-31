package com.example.wechat_talking.auto

import android.content.Context

/** 允许在界面上调整的自动化参数，避免每台微信版本都要改代码重编译。 */
object AutoSettings {

    private const val PREF = "auto"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun keyword(ctx: Context): String =
        prefs(ctx).getString("keyword", "按住") ?: "按住"

    fun setKeyword(ctx: Context, v: String) = prefs(ctx).edit().putString("keyword", v).apply()

    /** 长按开始到真正出声的间隔，给微信一点时间把录音器拉起来。 */
    fun leadMs(ctx: Context): Int = prefs(ctx).getInt("lead", 350)

    fun setLeadMs(ctx: Context, v: Int) = prefs(ctx).edit().putInt("lead", v).apply()

    /** 音频放完后继续按住的时间，防止微信把结尾切断。 */
    fun tailMs(ctx: Context): Int = prefs(ctx).getInt("tail", 400)

    fun setTailMs(ctx: Context, v: Int) = prefs(ctx).edit().putInt("tail", v).apply()

    fun capturePreview(ctx: Context): Boolean = prefs(ctx).getBoolean("capture", false)

    fun setCapturePreview(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("capture", v).apply()

    fun route(ctx: Context): String = prefs(ctx).getString("route", "SPEAKER") ?: "SPEAKER"

    fun setRoute(ctx: Context, v: String) = prefs(ctx).edit().putString("route", v).apply()

    // ---------------------------------------------------------------- 坐标校准
    // 微信的「按住 说话」在不少版本里不把文字交给无障碍，靠文字找并不可靠。
    // 所以允许用户手动点一次，把坐标记下来，之后直接按坐标长按。

    data class Calibration(val x: Float, val y: Float, val screenW: Int, val screenH: Int) {
        fun stillValid(w: Int, h: Int) = screenW == w && screenH == h
    }

    fun calibration(ctx: Context): Calibration? {
        val p = prefs(ctx)
        val x = p.getFloat("calib_x", -1f)
        val y = p.getFloat("calib_y", -1f)
        val w = p.getInt("calib_w", -1)
        val h = p.getInt("calib_h", -1)
        if (x < 0f || y < 0f || w <= 0 || h <= 0) return null
        return Calibration(x, y, w, h)
    }

    fun setCalibration(ctx: Context, x: Float, y: Float, screenW: Int, screenH: Int) {
        prefs(ctx).edit()
            .putFloat("calib_x", x)
            .putFloat("calib_y", y)
            .putInt("calib_w", screenW)
            .putInt("calib_h", screenH)
            .apply()
    }

    fun clearCalibration(ctx: Context) {
        prefs(ctx).edit()
            .remove("calib_x").remove("calib_y")
            .remove("calib_w").remove("calib_h")
            .apply()
    }

    // ---------------------------------------------------------------- 悬浮球位置
    // 记住拖到哪，下次显示还在那儿；但屏幕尺寸变了（转屏/换分辨率）就回默认位置，
    // 否则会落在屏幕外面。

    data class BubblePos(val x: Int, val y: Int, val screenW: Int, val screenH: Int) {
        fun stillValid(w: Int, h: Int) = screenW == w && screenH == h
    }

    fun bubblePosition(ctx: Context): BubblePos? {
        val p = prefs(ctx)
        val x = p.getInt("bubble_x", Int.MIN_VALUE)
        val y = p.getInt("bubble_y", Int.MIN_VALUE)
        val w = p.getInt("bubble_w", -1)
        val h = p.getInt("bubble_h", -1)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || w <= 0 || h <= 0) return null
        return BubblePos(x, y, w, h)
    }

    fun setBubblePosition(ctx: Context, x: Int, y: Int, screenW: Int, screenH: Int) {
        prefs(ctx).edit()
            .putInt("bubble_x", x)
            .putInt("bubble_y", y)
            .putInt("bubble_w", screenW)
            .putInt("bubble_h", screenH)
            .apply()
    }

    // ---------------------------------------------------------------- 首次提示

    fun tipsSeen(ctx: Context): Boolean = prefs(ctx).getBoolean("tips_seen", false)

    fun setTipsSeen(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("tips_seen", v).apply()
}
