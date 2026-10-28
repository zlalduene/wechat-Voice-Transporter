package com.example.wechat_talking.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 纯 Canvas 画的波形条，选中区间高亮，两侧压暗。
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    startMs: Float,
    endMs: Float,
    totalMs: Long,
    activeColor: Color,
    idleColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(88.dp)) {
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            if (peaks.isEmpty() || totalMs <= 0L) return@Canvas
            val w = size.width
            val h = size.height
            val n = peaks.size
            val slot = w / n
            val barW = max(1f, slot * 0.62f)

            val selStartX = (startMs / totalMs) * w
            val selEndX = (endMs / totalMs) * w

            for (i in 0 until n) {
                val x = i * slot
                val amp = peaks[i].coerceIn(0f, 1f)
                val barH = max(2f, amp * h * 0.92f)
                val inside = x >= selStartX - slot && x <= selEndX
                drawRect(
                    color = if (inside) activeColor else idleColor,
                    topLeft = Offset(x, (h - barH) / 2f),
                    size = Size(barW, barH),
                )
            }

            drawLine(
                color = activeColor,
                start = Offset(selStartX, 0f),
                end = Offset(selStartX, h),
                strokeWidth = 3f,
            )
            drawLine(
                color = activeColor,
                start = Offset(selEndX, 0f),
                end = Offset(selEndX, h),
                strokeWidth = 3f,
            )
        }
    }
}
