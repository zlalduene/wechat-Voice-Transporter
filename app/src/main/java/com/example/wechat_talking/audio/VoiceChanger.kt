package com.example.wechat_talking.audio

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * 变声滤镜（ffmpeg 音频滤镜链）。
 * 采样率固定 16000，改调之后用 atempo 把时长拉回原样，听起来只是「换了个嗓子」而不是变速。
 */
enum class VoiceEffect(val filter: String) {
    /** 降调约 18%：低沉的大叔音。 */
    DEEP("asetrate=13120,aresample=16000,atempo=1.2195"),

    /** 升调 30%：细一点的萝莉音。 */
    CHILD("asetrate=20800,aresample=16000,atempo=0.7692"),

    /** 降位深 + 短回声，做出机械电音感。 */
    ROBOT("acrusher=bits=8:mix=0.7:mode=log,aecho=0.8:0.888:40:0.3"),
}

/** 用 FFmpegKit 处理单声道 16k 的 WAV，输出同样是 16k 单声道 WAV。 */
object VoiceChanger {

    fun apply(input: File, output: File, filter: String) {
        val command = buildString {
            append("-y -i \"").append(input.absolutePath).append("\"")
            append(" -vn -af \"").append(filter).append("\"")
            append(" -ac 1 -ar ").append(VoicePreparer.SAMPLE_RATE)
            append(" -c:a pcm_s16le \"")
            append(output.absolutePath).append("\"")
        }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val detail = session.failStackTrace
                ?: session.output?.trim()?.takeLast(300)
                ?: "未知错误"
            throw IllegalStateException("变声处理失败：$detail")
        }
    }
}
