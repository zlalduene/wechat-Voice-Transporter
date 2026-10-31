package com.example.wechat_talking

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.wechat_talking.auto.RecordButtonService
import com.example.wechat_talking.ui.AudioPickerScreen
import com.example.wechat_talking.ui.HomeScreen
import com.example.wechat_talking.ui.theme.WeChat_talkingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var accessibilityOn by mutableStateOf(false)

    private var pendingPermissionAction: (() -> Unit)? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            AppHub.pick(this, uri)
        }
    }

    /** 直接列手机音频用的读取权限（Android 13+ 是 READ_MEDIA_AUDIO）。 */
    private val audioLibraryPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private var showAudioPicker by mutableStateOf(false)
    private var audioLibraryGranted by mutableStateOf(false)

    private val requestAudioLibrary = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioLibraryGranted = granted
        if (!granted) {
            AppHub.message = "没给读取音频的权限，列表会是空的；可以点「从文件夹里选…」"
        }
        showAudioPicker = true
    }

    private val requestRecord = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) action?.invoke()
        else AppHub.message = "没有录音权限，自检和回采试听用不了（发送不受影响）"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHub.loadSettings(this)

        handleIncoming(intent)
        if (intent?.getBooleanExtra("autotest", false) == true) {
            runAutoTestAndFinish()
        }
        if (intent?.getBooleanExtra("request_record", false) == true) {
            // 悬浮球菜单点了「录音」但还没授权，回到前台把这个权限要一下。
            window.decorView.post { requestRecordFromOverlay() }
        }

        setContent {
            WeChat_talkingTheme {
                if (showAudioPicker) {
                    AudioPickerScreen(
                        context = this,
                        hasPermission = audioLibraryGranted,
                        onRequestPermission = {
                            if (hasAudioLibraryPermission()) {
                                audioLibraryGranted = true
                            } else {
                                runCatching { requestAudioLibrary.launch(audioLibraryPermission) }
                                    .onFailure {
                                        AppHub.message = "申请权限失败，请到系统设置里给「音乐和音频」权限"
                                    }
                            }
                        },
                        onPick = { uri ->
                            showAudioPicker = false
                            AppHub.pick(this, uri)
                        },
                        onBrowseFolders = {
                            showAudioPicker = false
                            runCatching {
                                pickAudio.launch(arrayOf("audio/*", "application/octet-stream"))
                            }
                        },
                        onClose = { showAudioPicker = false },
                    )
                } else {
                    HomeScreen(
                        context = this,
                        accessibilityOn = accessibilityOn,
                        onPickFile = { openAudioLibrary() },
                        onPrepare = { AppHub.prepare(this) },
                        onPlay = { AppHub.playClip(this) },
                        onStop = { AppHub.stopPlayback() },
                        onSelfTest = { withRecordPermission { AppHub.runSelfTest(this) } },
                        onToggleBubble = { AppHub.toggleBubble(this) },
                        onOpenAccessibility = { openAccessibilityForThisApp() },
                        onPreviewClip = { AppHub.togglePreview(AppHub.clip?.file?.absolutePath) },
                    )
                }
            }
        }
    }

    /** 先把权限要到，再打开音频列表；不想授权的也能在列表里走文件管理器。 */
    private fun openAudioLibrary() {
        audioLibraryGranted = hasAudioLibraryPermission()
        if (audioLibraryGranted) {
            showAudioPicker = true
            return
        }
        runCatching { requestAudioLibrary.launch(audioLibraryPermission) }.onFailure {
            AppHub.message = "申请权限失败，可以点「从文件夹里选…」"
            showAudioPicker = true
        }
    }

    private fun hasAudioLibraryPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        audioLibraryPermission,
    ) == PackageManager.PERMISSION_GRANTED

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncoming(intent)
        if (intent?.getBooleanExtra("request_record", false) == true) {
            window.decorView.post { requestRecordFromOverlay() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
        AppHub.refreshCalibration(this)
        audioLibraryGranted = hasAudioLibraryPermission()
        scope.launch {
            repeat(20) {
                delay(700)
                refreshAccessibilityState()
            }
        }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.extras?.get(Intent.EXTRA_STREAM) as? Uri
            else -> intent.data
        }
        if (uri != null) AppHub.pick(this, uri)
    }

    private fun refreshAccessibilityState() {
        accessibilityOn = RecordButtonService.instance != null || serviceEnabledInSettings()
    }

    private fun serviceEnabledInSettings(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
    }

    private fun withRecordPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingPermissionAction = action
            requestRecord.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 从悬浮球菜单跳过来要麦克风权限；已经有就直接提示可以回去录音了。 */
    private fun requestRecordFromOverlay() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            AppHub.message = "麦克风权限已经有了：回到悬浮球，长按 →「开始录音」"
            return
        }
        withRecordPermission {
            AppHub.message = "麦克风权限已开启：回到悬浮球，长按 →「开始录音」"
        }
    }

    /**
     * 直接跳到「设置 → 无障碍 → 本 App 服务」那一页，省得用户在列表里找。
     *
     * 用的是 AOSP Settings 认识的 fragment 参数（:settings:fragment_args_key），
     * 大部分 ROM 都认；万一不认，也只是停在无障碍列表页，不会出错。
     */
    private fun openAccessibilityForThisApp() {
        val component = ComponentName(this, RecordButtonService::class.java)
        val key = component.flattenToString()
        val deepLink = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", key)
            putExtra(
                ":settings:show_fragment_args",
                Bundle().apply { putString(":settings:fragment_args_key", key) },
            )
        }
        val opened = runCatching { startActivity(deepLink) }.isSuccess ||
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
        if (!opened) {
            AppHub.message = "打不开无障碍设置，请手动到 系统设置 → 无障碍 里开启"
        }
    }

    /**
     * 给自动化测试用：`adb shell am start -n .../.MainActivity --ez autotest true`
     * 跑完自检把结果打进 logcat（tag=SELFTEST），然后退出。
     */
    private fun runAutoTestAndFinish() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { SelfTest.run(applicationContext) }
            Log.i(
                "SELFTEST",
                "AUTOTEST_RESULT attenuation=${result.attenuationDb} " +
                    "source=${result.sourceRmsDb} captured=${result.capturedRmsDb} " +
                    "verdict=${result.verdict} file=${result.capturedFile}",
            )
            delay(500)
            finish()
        }
    }
}
