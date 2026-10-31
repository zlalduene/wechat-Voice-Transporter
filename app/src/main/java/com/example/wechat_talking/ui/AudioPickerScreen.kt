package com.example.wechat_talking.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wechat_talking.audio.AudioLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PickerGreen = Color(0xFF07C160)

private enum class SortMode { TIME, NAME }

/**
 * 直接列出手机里的音频文件（来自 MediaStore），支持按名字/文件夹搜索、按时间或名称排序。
 * 底部保留「从文件夹里选…」，想自己翻文件夹的人还是走系统文件选择器。
 */
@Composable
fun AudioPickerScreen(
    context: Context,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPick: (Uri) -> Unit,
    onBrowseFolders: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    var loading by remember { mutableStateOf(hasPermission) }
    var files by remember { mutableStateOf<List<AudioLibrary.Item>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.TIME) }
    var ascending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(hasPermission, reload) {
        if (!hasPermission) {
            loading = false
            files = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { AudioLibrary.scan(context) }
        }
        files = result.getOrElse {
            error = it.message ?: "扫描失败"
            emptyList<AudioLibrary.Item>()
        }
        loading = false
    }

    val shown = remember(files, query, sort, ascending) {
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) {
            files
        } else {
            files.filter {
                it.name.lowercase().contains(q) || it.folder.lowercase().contains(q)
            }
        }
        val sorted = when (sort) {
            SortMode.TIME -> base.sortedBy { it.dateModifiedSec }
            SortMode.NAME -> base.sortedBy { it.name.lowercase() }
        }
        if (ascending) sorted else sorted.reversed()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择音频", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PickerGreen)
                    Text(
                        "手机里扫到的音频，点一下就用；也可以去文件夹里找",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { reload++ }, enabled = hasPermission && !loading) {
                    Text("刷新")
                }
                TextButton(onClick = onClose) { Text("关闭") }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("按名字 / 文件夹搜索") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = sort == SortMode.TIME,
                    onClick = { sort = SortMode.TIME },
                    label = { Text("按时间") },
                )
                FilterChip(
                    selected = sort == SortMode.NAME,
                    onClick = { sort = SortMode.NAME },
                    label = { Text("按名称") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { ascending = !ascending }) {
                    Text(if (ascending) "升序 ↑" else "降序 ↓")
                }
            }

            if (!hasPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1A07C160)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("还没有读取音频的权限", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "授权后就能直接列出手机里的音频。不想授权也没关系，" +
                                "点下面的「从文件夹里选…」用系统文件管理器一样能选。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRequestPermission) { Text("去授权") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            error?.let {
                Text(
                    "扫描出错：$it",
                    fontSize = 12.sp,
                    color = Color(0xFFD32F2F),
                )
            }

            Text(
                when {
                    loading -> "正在扫描…"
                    !hasPermission -> "授权后这里会列出音频文件"
                    else -> "共 ${files.size} 个音频" +
                        if (query.isNotBlank()) " · 匹配 ${shown.size} 个" else ""
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    !hasPermission -> Text(
                        "点上面的「去授权」，或者直接去文件夹里找",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    shown.isEmpty() -> Text(
                        if (files.isEmpty()) {
                            "没扫到音频文件，可以点下面「从文件夹里选…」"
                        } else {
                            "没有匹配「${query.trim()}」的文件"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { item ->
                            AudioFileRow(item) { onPick(item.uri) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBrowseFolders, modifier = Modifier.fillMaxWidth()) {
                Text("从文件夹里选…（系统文件管理器）")
            }
        }
    }
}

@Composable
private fun AudioFileRow(item: AudioLibrary.Item, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (item.durationMs > 0) {
                    append(fmtDuration(item.durationMs))
                    append(" · ")
                }
                if (item.folder.isNotBlank()) {
                    append(item.folder)
                    append(" · ")
                }
                append(fmtSize(item.sizeBytes))
                append(" · ")
                append(fmtDate(item.dateModifiedSec))
            }
            Text(
                meta,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("用这个", fontSize = 12.sp, color = PickerGreen)
    }
}

private fun fmtDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m >= 60) {
        "%d:%02d:%02d".format(m / 60, m % 60, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

private fun fmtDate(sec: Long): String {
    if (sec <= 0) return "未知时间"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
}
