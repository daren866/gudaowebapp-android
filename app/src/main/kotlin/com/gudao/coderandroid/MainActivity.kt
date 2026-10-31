package com.gudao.coderandroid

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.system.exitProcess

const val REQUEST_CODE_WRITE_STORAGE = 1001
private const val PREFS_NAME = "app_prefs"
private const val KEY_AGREED_TERMS = "agreed_terms"

class MainActivity : ComponentActivity() {
    private var pendingSaveImageCallback: ((Boolean, String) -> Unit)? = null
    private var pendingSaveImageBase64: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val callback = pendingSaveImageCallback
        val base64Data = pendingSaveImageBase64
        pendingSaveImageCallback = null
        pendingSaveImageBase64 = null

        if (isGranted) {
            callback?.invoke(true, "")
        } else {
            callback?.invoke(false, "存储权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        setContent {
            // 首次启动同意闸门：未同意前不加载 WebView，避免未经同意即联网/写 localStorage
            var consented by remember {
                mutableStateOf(
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(KEY_AGREED_TERMS, false)
                )
            }
            // 查看协议用的临时查看器：非空时显示对应 assets HTML
            var viewerAsset by remember { mutableStateOf<String?>(null) }

            if (consented) {
                MainActivityContent()
            }

            if (!consented) {
                AlertDialog(
                    // 必须二选一，禁止点击外部或返回关闭
                    onDismissRequest = { },
                    title = { Text("用户协议与隐私政策") },
                    text = {
                        Column {
                            Text("欢迎使用 Gudaocode。在进入应用前，请阅读并同意以下协议：")
                            Spacer(Modifier.height(8.dp))
                            Row {
                                TextButton(onClick = { viewerAsset = "privacy.html" }) {
                                    Text("《隐私政策》")
                                }
                                TextButton(onClick = { viewerAsset = "user_agreement.html" }) {
                                    Text("《用户协议》")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("若不同意，应用将退出。", color = ComposeColor.Gray)
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_AGREED_TERMS, true)
                                .apply()
                            consented = true
                        }) { Text("同意并继续") }
                    },
                    dismissButton = {
                        Button(onClick = { finishAndExit() }) { Text("不同意并退出") }
                    }
                )
            }

            viewerAsset?.let { asset ->
                val title = if (asset == "privacy.html") "隐私政策" else "用户协议"
                Dialog(
                    onDismissRequest = { viewerAsset = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        color = ComposeColor.White
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(title)
                                TextButton(onClick = { viewerAsset = null }) { Text("关闭") }
                            }
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = false
                                        loadUrl("file:///android_asset/$asset")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 对话框弹出/关闭等导致窗口焦点变化后，重新隐藏系统栏，保持全屏
        if (hasFocus) hideSystemBars()
    }

    /**
     * 沉浸式全屏：隐藏状态栏与导航栏（横屏）。
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 允许用户从屏幕边缘临时唤出系统栏，松手后自动再隐藏。
     */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun finishAndExit() {
        finish()
        // 仅此一个 Activity，直接结束进程以保证「不同意即退出」生效
        exitProcess(0)
    }

    fun setStatusBarColorFromWeb(color: Int) {
        runOnUiThread {
            @Suppress("DEPRECATION")
            window.statusBarColor = color
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val isLight = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) > 186
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLight
            }
        }
    }

    fun requestWriteStoragePermission(base64Data: String, callback: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingSaveImageCallback = callback
                pendingSaveImageBase64 = base64Data
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                callback(true, "")
            }
        } else {
            callback(true, "")
        }
    }
}
