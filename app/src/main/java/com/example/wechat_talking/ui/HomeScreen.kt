package com.example.wechat_talking.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wechat_talking.AppHub
import com.example.wechat_talking.audio.Preset
import com.example.wechat_talking.audio.VoicePreparer
import com.example.wechat_talking.play.CastBus
import com.example.wechat_talking.play.CastState
import com.example.wechat_talking.play.OutputRoute
import kotlin.math.min

private val WeChatGreen = Color(0xFF07C160)
private val DimGreen = Color(0x3307C160)
private val DimGray = Color(0x33000000)

@Composable
fun HomeScreen(
    context: Context,
    accessibilityOn: Boolean,
    onPickFile: () -> Unit,
    onPrepare: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelfTest: () -> Unit,
    onToggleBubble: () -> Unit,
    onGuided: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onPreviewClip: () -> Unit,
) {
    val castState by CastBus.state.collectAsState()
    val clip = AppHub.clip

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("语音搬运工", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = WeChatGreen)
            Text(
                "把自己的音频变成微信里的一条语音消息，而不是文件。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HowItWorksCard()

            // ---------------------------------------------------------- 1 选文件
            SectionCard("① 选择音频") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickFile, enabled = AppHub.busy == null) {
                        Text("选择音频文件")
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (AppHub.fileName.isBlank()) "还没选" else AppHub.fileName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (AppHub.sourceDurationMs > 0) {
                            Text(
                                "原始时长 ${fmtSec(AppHub.sourceDurationMs.toFloat())}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 2 裁剪
            if (AppHub.sourcePeaks.isNotEmpty()) {
                SectionCard("② 截取片段") {
                    WaveformView(
                        peaks = AppHub.sourcePeaks,
                        startMs = AppHub.trimStartMs,
                        endMs = AppHub.trimEndMs,
                        totalMs = AppHub.sourceDurationMs,
                        activeColor = WeChatGreen,
                        idleColor = DimGray,
                    )
                    val maxMs = AppHub.sourceDurationMs.toFloat()
                    RangeSlider(
                        value = AppHub.trimStartMs..min(AppHub.trimEndMs, maxMs),
                        onValueChange = { range ->
                            AppHub.trimStartMs = range.start.coerceIn(0f, maxMs)
                            AppHub.trimEndMs = range.endInclusive.coerceIn(0f, maxMs)
                        },
                        valueRange = 0f..maxMs.coerceAtLeast(1f),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "已选 ${fmtSec(AppHub.trimSeconds * 1000f)}",
                            fontSize = 13.sp,
                            color = WeChatGreen,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "微信上限 60s，这里最多 ${VoicePreparer.MAX_MS / 1000}s",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (AppHub.trimSeconds * 1000f > VoicePreparer.MAX_MS) {
                        Text(
                            "选中的片段超过 ${VoicePreparer.MAX_MS / 1000} 秒，会被自动截断",
                            fontSize = 12.sp,
                            color = Color(0xFFD32F2F),
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 3 参数
            SectionCard("③ 处理方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Preset.ALL.forEach { p ->
                        PresetChip(p.name, AppHub.preset.id == p.id) { AppHub.preset = p }
                    }
                }
                Text(
                    AppHub.preset.desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("响度 ${if (AppHub.extraGainDb >= 0) "+" else ""}${"%.1f".format(AppHub.extraGainDb)} dB", fontSize = 13.sp)
                Slider(
                    value = AppHub.extraGainDb,
                    onValueChange = { AppHub.extraGainDb = it },
                    valueRange = -6f..8f,
                )

                ToggleRow(
                    title = "自动削掉首尾静音",
                    subtitle = "录音开头那点空白最容易被对方当成「没说话」",
                    checked = AppHub.autoTrimSilence,
                ) { AppHub.autoTrimSilence = it }

                ToggleRow(
                    title = "回采试听（模拟微信听到的效果）",
                    subtitle = "边播边用麦克风录一遍。注意：真发的时候开这个可能和微信抢录音器，建议只在调试时开",
                    checked = AppHub.capturePreview,
                ) { AppHub.setCapturePreview(context, it) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteChip("外放喇叭", AppHub.route == OutputRoute.SPEAKER) {
                        AppHub.setRoute(context, OutputRoute.SPEAKER)
                    }
                    RouteChip("听筒", AppHub.route == OutputRoute.EARPIECE) {
                        AppHub.setRoute(context, OutputRoute.EARPIECE)
                    }
                }
            }

            // ---------------------------------------------------------- 4 生成
            SectionCard(null) {
                Button(
                    onClick = onPrepare,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = AppHub.busy == null && AppHub.sourcePeaks.isNotEmpty(),
                ) {
                    Text(if (AppHub.busy != null) AppHub.busy!! else "生成语音片段")
                }

                if (AppHub.busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }

                AppHub.message?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (clip != null) {
                    Text(
                        "片段：${fmtSec(clip.durationMs.toFloat())} · " +
                            "RMS ${"%.1f".format(clip.rmsDb)}dB · 峰值 ${"%.1f".format(clip.peakDb)}dB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onPreviewClip) { Text("试听") }
                        OutlinedButton(onClick = onPlay) { Text("按发射音量播一遍") }
                        if (castState is CastState.Playing) {
                            TextButton(onClick = onStop) { Text("停止") }
                        }
                    }
                    PlaybackState(castState)
                }
            }

            // ---------------------------------------------------------- 5 发送
            SectionCard("④ 发到微信") {
                Text(
                    if (accessibilityOn) "无障碍服务：已开启" else "无障碍服务：未开启（自动长按需要它）",
                    fontSize = 12.sp,
                    color = if (accessibilityOn) WeChatGreen else Color(0xFFD32F2F),
                )
                if (!accessibilityOn) {
                    OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                        Text("去开启无障碍服务")
                    }
                    Text(
                        "为什么会反复要求重开：无障碍授权在系统里只是一个设置项，" +
                            "App 一旦被「强行停止」（vivo 的电池管理、应用速冻、从最近任务划掉都可能触发），" +
                            "Android 会直接把它从无障碍列表里删掉，只能重新授权。\n" +
                            "减少复发：i管家 → 应用管理 → 自启动 打开；设置 → 电池 → 后台耗电管理 → 允许后台高耗电；" +
                            "并且不要从最近任务里划掉它。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "按钮位置：${AppHub.calibrationText}",
                    fontSize = 12.sp,
                    color = if (AppHub.calibrationText == "未校准") Color(0xFFD32F2F) else WeChatGreen,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onToggleBubble,
                        modifier = Modifier.weight(1f),
                        enabled = accessibilityOn,
                    ) { Text("显示/隐藏悬浮球") }
                    OutlinedButton(
                        onClick = onGuided,
                        modifier = Modifier.weight(1f),
                        enabled = accessibilityOn && clip != null,
                    ) { Text("倒数（我自己按）") }
                }
                Text(
                    "步骤：\n" +
                        "① 点「显示悬浮球」\n" +
                        "② 切到微信打开聊天，并切到语音输入模式，让「按住 说话」露出来\n" +
                        "③ 第一次点绿色圆球：会让你点一下「按住 说话」的位置，记住坐标\n" +
                        "④ 之后每次点圆球 = 自动长按 + 播放音频 = 发出一条语音消息\n\n" +
                        "长按圆球可以重新校准。微信屏蔽了控件读取，所以只能靠坐标，换机型/换输入法布局后重校一次即可。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { AppHub.diagnose(context) }, enabled = accessibilityOn) {
                    Text("诊断：查看无障碍与校准状态", fontSize = 12.sp)
                }
            }

            // ---------------------------------------------------------- 6 自检
            SectionCard("⑤ 环境自检（第一次用请先跑）") {
                Text(
                    "满音量播一段测试音，同时用麦克风录回来，量化「外放被收进去衰减了多少」。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSelfTest, enabled = AppHub.busy == null) {
                    Text("开始自检（约 3 秒）")
                }
                AppHub.selfTest?.let { r ->
                    Text(
                        "衰减 ${"%.1f".format(r.attenuationDb)} dB  " +
                            "（原始 ${"%.1f".format(r.sourceRmsDb)} / 回采 ${"%.1f".format(r.capturedRmsDb)}）",
                        fontSize = 12.sp,
                    )
                    Text(r.verdict, fontSize = 13.sp, color = WeChatGreen, fontWeight = FontWeight.Medium)
                    if (r.capturedFile != null) {
                        OutlinedButton(onClick = { AppHub.previewFile(r.capturedFile) }) {
                            Text("听一下回采结果")
                        }
                    }
                }
            }

            Text(
                "说明：微信语音是它自己录出来的 SILK 文件，普通 App 不 root 写不进微信的数据目录。" +
                    "所以这里的做法是「让微信自己录一遍」，音质取决于你手机的外放和麦克风。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun PlaybackState(state: CastState) {
    when (state) {
        is CastState.Playing -> {
            val p = if (state.totalMs <= 0) 0f else state.elapsedMs.toFloat() / state.totalMs
            LinearProgressIndicator(progress = p.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            Text(
                "正在播放 ${fmtSec(state.elapsedMs.toFloat())} / ${fmtSec(state.totalMs.toFloat())}",
                fontSize = 11.sp,
            )
        }

        is CastState.Countdown -> Text("倒计时 ${state.remainingMs / 1000.0}s", fontSize = 11.sp)

        is CastState.Finished -> Text(
            if (state.capturedFile != null) {
                "播放完毕 · 回采 RMS ${"%.1f".format(state.capturedRmsDb)}dB（可去自检卡片试听）"
            } else {
                "播放完毕"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is CastState.Failed -> Text(state.message, fontSize = 11.sp, color = Color(0xFFD32F2F))
        CastState.Idle -> Unit
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DimGreen),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("它是怎么做到的", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "1. 解码你的音频，切成微信能听清的语音频带并拉高响度；\n" +
                    "2. 用无障碍服务找到聊天框里的「按住 说话」，替你长按下发手势；\n" +
                    "3. 长按的同时把音频从喇叭放出来，微信的麦克风收进去，于是它就成了" +
                    "一条真正的语音消息（带时长、能转文字、能转发）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            content()
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) WeChatGreen else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = fg, fontSize = 13.sp)
    }
}

@Composable
private fun RouteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    PresetChip(label, selected, onClick)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun fmtSec(ms: Float): String = "%.1f s".format(ms / 1000f)
