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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

const val REQUEST_CODE_WRITE_STORAGE = 1001

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
            MainActivityContent()
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
