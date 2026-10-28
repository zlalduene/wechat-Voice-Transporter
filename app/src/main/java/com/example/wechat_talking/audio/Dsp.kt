package com.example.wechat_talking.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一组只依赖 kotlin.math 的轻量 DSP 工具。
 * 目标不是「高保真」，而是把音频调成「微信语音听得清」的样子：
 * 电话频带 + 足够响 + 无爆音。
 */
object Dsp {

    private data class Biquad(
        val b0: Float, val b1: Float, val b2: Float,
        val a1: Float, val a2: Float,
    )

    /** RBJ 二阶低通。 */
    private fun lowPass(sr: Int, freq: Float, q: Float): Biquad {
        val f = min(freq, sr * 0.45f)
        val w0 = 2.0 * PI * f / sr
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            b0 = ((1.0 - cw) / 2.0 / a0).toFloat(),
            b1 = ((1.0 - cw) / a0).toFloat(),
            b2 = ((1.0 - cw) / 2.0 / a0).toFloat(),
            a1 = (-2.0 * cw / a0).toFloat(),
            a2 = ((1.0 - alpha) / a0).toFloat(),
        )
    }

    /** RBJ 二阶高通。 */
    private fun highPass(sr: Int, freq: Float, q: Float): Biquad {
        val w0 = 2.0 * PI * freq / sr
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            b0 = ((1.0 + cw) / 2.0 / a0).toFloat(),
            b1 = (-(1.0 + cw) / a0).toFloat(),
            b2 = ((1.0 + cw) / 2.0 / a0).toFloat(),
            a1 = (-2.0 * cw / a0).toFloat(),
            a2 = ((1.0 - alpha) / a0).toFloat(),
        )
    }

    private fun applyFilter(input: FloatArray, f: Biquad): FloatArray {
        val out = FloatArray(input.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in input.indices) {
            val x = input[i]
            val y = f.b0 * x + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    /** 电话频带（默认 150–3800Hz，4 阶）。 */
    fun telephoneBand(input: FloatArray, sr: Int, hpHz: Float = 150f, lpHz: Float = 3800f): FloatArray {
        var x = input
        if (hpHz > 20f) {
            x = applyFilter(applyFilter(x, highPass(sr, hpHz, 0.7071f)), highPass(sr, hpHz, 0.7071f))
        }
        if (lpHz > 300f) {
            x = applyFilter(applyFilter(x, lowPass(sr, lpHz, 0.7071f)), lowPass(sr, lpHz, 0.7071f))
        }
        return x
    }

    fun removeDc(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        var sum = 0.0
        for (v in input) sum += v
        val mean = (sum / input.size).toFloat()
        if (abs(mean) < 1e-6f) return input
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = input[i] - mean
        return out
    }

    fun linearResample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = max(1, (input.size / ratio).toInt())
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = input[min(i0, input.size - 1)]
            val s1 = input[min(i0 + 1, input.size - 1)]
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }

    fun rms(input: FloatArray): Float {
        if (input.isEmpty()) return 0f
        var sum = 0.0
        for (v in input) sum += v.toDouble() * v.toDouble()
        return sqrt(sum / input.size).toFloat()
    }

    fun peak(input: FloatArray): Float {
        var p = 0f
        for (v in input) {
            val a = abs(v)
            if (a > p) p = a
        }
        return p
    }

    fun toDb(v: Float): Float = (20.0 * kotlin.math.log10(max(v.toDouble(), 1e-9))).toFloat()

    fun fromDb(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    /**
     * 归一化到目标 RMS，再用软限幅压住峰值，避免微信那边录进去爆音。
     */
    fun normalize(input: FloatArray, targetRmsDb: Float = -14f, ceiling: Float = 0.95f): FloatArray {
        val r = rms(input)
        if (r < 1e-6f) return input
        var gain = fromDb(targetRmsDb) / r
        val p = peak(input)
        if (p * gain > ceiling) gain = ceiling / p
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = softClip(input[i] * gain, 0.8f, ceiling)
        return out
    }

    /** 超过 threshold 后平滑压到 ceiling，听感上几乎无失真，但绝不削波。 */
    private fun softClip(v: Float, threshold: Float, ceiling: Float): Float {
        val a = abs(v)
        if (a <= threshold) return v
        val over = (a - threshold) / (ceiling - threshold)
        val shaped = threshold + (ceiling - threshold) * kotlin.math.tanh(over.toDouble()).toFloat()
        return sign(v) * min(shaped, ceiling)
    }

    fun fade(input: FloatArray, sr: Int, fadeInMs: Int = 8, fadeOutMs: Int = 60): FloatArray {
        if (input.isEmpty()) return input
        val out = input.copyOf()
        val inN = min((sr * fadeInMs / 1000).toInt(), out.size)
        for (i in 0 until inN) out[i] *= i.toFloat() / inN
        val outN = min((sr * fadeOutMs / 1000).toInt(), out.size)
        for (i in 0 until outN) {
            out[out.size - 1 - i] *= i.toFloat() / outN
        }
        return out
    }

    /** 给界面画波形用的包络。 */
    fun envelope(input: FloatArray, buckets: Int): FloatArray {
        if (input.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val step = input.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val start = (b * step).toInt()
            val end = min(((b + 1) * step).toInt(), input.size)
            var m = 0f
            for (i in start until end) {
                val a = abs(input[i])
                if (a > m) m = a
            }
            out[b] = m
        }
        val pk = out.maxOrNull() ?: 0f
        if (pk > 1e-6f) for (i in out.indices) out[i] /= pk
        return out
    }

    /** 把静音头尾削掉，但保留一点点余量。 */
    fun trimSilence(input: FloatArray, sr: Int, thresholdDb: Float = -42f, keepMs: Int = 80): FloatArray {
        if (input.isEmpty()) return input
        val th = fromDb(thresholdDb)
        var start = 0
        while (start < input.size && abs(input[start]) < th) start++
        var end = input.size - 1
        while (end > start && abs(input[end]) < th) end--
        if (end <= start) return input
        val pad = (sr * keepMs / 1000)
        val s = max(0, start - pad)
        val e = min(input.size, end + pad + 1)
        return input.copyOfRange(s, e)
    }

    fun durationMs(samples: Int, sr: Int): Long = if (sr <= 0) 0L else samples * 1000L / sr

    @Suppress("unused")
    fun nf(v: Float): String = String.format("%.1f", v)

    @Suppress("unused")
    private fun log(v: Float): Float = ln(v.toDouble()).toFloat()
}
