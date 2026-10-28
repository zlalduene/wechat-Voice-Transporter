package com.example.wechat_talking

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
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

        setContent {
            WeChat_talkingTheme {
                HomeScreen(
                    context = this,
                    accessibilityOn = accessibilityOn,
                    onPickFile = {
                        runCatching { pickAudio.launch(arrayOf("audio/*", "application/octet-stream")) }
                    },
                    onPrepare = { AppHub.prepare(this) },
                    onPlay = { AppHub.playClip(this) },
                    onStop = { AppHub.stopPlayback() },
                    onSelfTest = { withRecordPermission { AppHub.runSelfTest(this) } },
                    onToggleBubble = { AppHub.toggleBubble(this) },
                    onGuided = { AppHub.guidedSend(this) },
                    onOpenAccessibility = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    onPreviewClip = { AppHub.previewFile(AppHub.clip?.file?.absolutePath) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
        AppHub.refreshCalibration(this)
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
