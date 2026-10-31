package com.example.wechat_talking.audio

import android.content.Context
import android.net.Uri
import java.io.File

/** 处理预设。微信语音的有效带宽很窄，所以预设的差别主要在频带和响度。 */
data class Preset(
    val id: String,
    val name: String,
    val desc: String,
    val hpHz: Float,
    val lpHz: Float,
    val targetRmsDb: Float,
) {
    companion object {
        val NATURAL = Preset("natural", "保真", "不切频带，只做响度对齐", 20f, 0f, -16f)
        val CLEAR = Preset("clear", "清晰", "切到电话频带，人声最突出（推荐）", 150f, 3800f, -14f)
        val LOUD = Preset("loud", "响", "更窄的频带 + 更高电平，穿透力最强", 200f, 3200f, -10f)
        val ALL = listOf(CLEAR, NATURAL, LOUD)
    }
}

data class PreparedClip(
    val file: File,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val peaks: FloatArray,
    val rmsDb: Float,
    val peakDb: Float,
)

object VoicePreparer {

    const val SAMPLE_RATE = 16000

    /** 微信自己最多录 60 秒，留出余量给长按手势的前后沿。 */
    const val MAX_MS = 56_000L

    private const val PEAK_BUCKETS = 1200

    fun prepare(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        preset: Preset,
        extraGainDb: Float,
        autoTrimSilence: Boolean,
        decodedIn: AudioDecoder.Pcm? = null,
    ): PreparedClip {
        val decoded = decodedIn ?: AudioDecoder.decode(context, uri)

        var samples = Dsp.linearResample(decoded.samples, decoded.sampleRate, SAMPLE_RATE)

        val totalMs = Dsp.durationMs(samples.size, SAMPLE_RATE)
        val s = startMs.coerceIn(0, totalMs)
        val e = endMs.coerceIn(s + 50, totalMs)
        val from = (s * SAMPLE_RATE / 1000).toInt().coerceIn(0, samples.size)
        val to = (e * SAMPLE_RATE / 1000).toInt().coerceIn(from, samples.size)
        samples = samples.copyOfRange(from, to)

        return finish(context, samples, preset, extraGainDb, autoTrimSilence, "clip.wav")
    }

    /**
     * 直接处理一段内存里的采样（悬浮球录的音走这里）。
     * 后面所有处理跟选文件完全一样，所以录出来的东西和导入的音频听感一致。
     */
    fun prepareFromSamples(
        context: Context,
        input: FloatArray,
        sampleRate: Int,
        preset: Preset,
        extraGainDb: Float,
        autoTrimSilence: Boolean,
        fileName: String = "record.wav",
    ): PreparedClip {
        require(input.isNotEmpty()) { "录音是空的" }
        val samples = Dsp.linearResample(input, sampleRate, SAMPLE_RATE)
        return finish(context, samples, preset, extraGainDb, autoTrimSilence, fileName)
    }

    /** 采样已经是 [SAMPLE_RATE] 之后的统一后处理。 */
    private fun finish(
        context: Context,
        samplesIn: FloatArray,
        preset: Preset,
        extraGainDb: Float,
        autoTrimSilence: Boolean,
        fileName: String,
    ): PreparedClip {
        require(samplesIn.isNotEmpty()) { "没有可用的音频" }
        var samples = Dsp.removeDc(samplesIn)

        if (preset.hpHz > 20f || preset.lpHz > 300f) {
            samples = Dsp.telephoneBand(
                samples,
                SAMPLE_RATE,
                hpHz = preset.hpHz,
                lpHz = if (preset.lpHz > 300f) preset.lpHz else 7000f,
            )
        }

        if (autoTrimSilence) {
            samples = Dsp.trimSilence(samples, SAMPLE_RATE)
        }

        // 硬上限，超了就截断（后面还有淡出，不会爆音）
        val maxSamples = (MAX_MS * SAMPLE_RATE / 1000).toInt()
        if (samples.size > maxSamples) samples = samples.copyOfRange(0, maxSamples)

        samples = Dsp.normalize(samples, preset.targetRmsDb + extraGainDb, 0.95f)
        samples = Dsp.fade(samples, SAMPLE_RATE, 8, 60)

        val outFile = File(File(context.cacheDir, "clips").apply { mkdirs() }, fileName)
        WavIo.writeMono16(outFile, samples, SAMPLE_RATE)

        return PreparedClip(
            file = outFile,
            samples = samples,
            sampleRate = SAMPLE_RATE,
            durationMs = Dsp.durationMs(samples.size, SAMPLE_RATE),
            peaks = Dsp.envelope(samples, PEAK_BUCKETS),
            rmsDb = Dsp.toDb(Dsp.rms(samples)),
            peakDb = Dsp.toDb(Dsp.peak(samples)),
        )
    }

    /** 只读波形包络，用于选完文件后立刻画图（不解码整段的话也可以，这里简单起见解码一次）。 */
    fun peekPeaks(context: Context, uri: Uri, buckets: Int = PEAK_BUCKETS): Pair<FloatArray, Long> {
        val decoded = AudioDecoder.decode(context, uri)
        val env = Dsp.envelope(decoded.samples, buckets)
        val ms = Dsp.durationMs(decoded.samples.size, decoded.sampleRate)
        return env to ms
    }
}
