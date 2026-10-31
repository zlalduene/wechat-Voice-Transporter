package com.example.wechat_talking.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 从系统媒体库里把所有音频文件扫出来，省得用户一个个文件夹去翻。
 *
 * 只读 MediaStore 索引，不做全盘递归扫描：
 *  - 速度快（几千条也就几百毫秒）；
 *  - 不需要「所有文件访问权限」；
 *  - Android 10 之后分区存储下也能正常拿到文件名 / 时长 / 大小 / uri。
 *
 * 想自己进文件夹找的，仍然保留系统文件选择器那条路。
 */
object AudioLibrary {

    data class Item(
        val id: Long,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val folder: String,
        val uri: Uri,
    )

    /** Android 13 起读音频用 READ_MEDIA_AUDIO，之前用 READ_EXTERNAL_STORAGE。 */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun scan(context: Context, limit: Int = 5000): List<Item> {
        val out = ArrayList<Item>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
        )

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val path = cursor.getString(dataIdx)
                    out.add(
                        Item(
                            id = id,
                            name = name,
                            durationMs = cursor.getLong(durationIdx),
                            sizeBytes = cursor.getLong(sizeIdx),
                            dateModifiedSec = cursor.getLong(dateIdx),
                            folder = prettyFolder(path),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                id,
                            ),
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun prettyFolder(path: String?): String {
        if (path.isNullOrBlank()) return ""
        var dir = path.substringBeforeLast('/', "")
        if (dir.isBlank()) return ""
        // 只显示相对用户存储根目录的那一段，太长的路径不好看
        listOf("/storage/emulated/0/", "/storage/emulated/0", "/sdcard/").forEach { prefix ->
            if (dir.startsWith(prefix)) dir = dir.removePrefix(prefix)
        }
        return dir.trim('/')
    }
}
