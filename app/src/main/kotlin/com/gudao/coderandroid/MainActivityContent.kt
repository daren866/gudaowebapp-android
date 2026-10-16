package com.gudao.coderandroid

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val OFFLINE_DIR = "OfflineAssets"
private const val MAX_CONCURRENT = 4

/** default project 的资产（md5ext）：量小，文档加载早期同步注入，保首屏 storage.load 命中。 */
private val DEFAULT_PROJECT_ASSETS = setOf(
    "cd21514d0531fdffb22204e0ec5ed84a.svg",
    "927d672925e7b99f7813735c484c6922.svg"
)

/**
 * 注入到页面的桥接脚本（不含离线状态回推行，由 buildInjectScript 动态拼接）：
 *  - 复用既有 window 桥（dialog / statusbar / file / image）；
 *  - 定义 window.webkit.messageHandlers.{scratchSave,offlineDownload,offlineGetAsset}，
 *    其 postMessage 转调 Android.bridgePost(name, json)（Android WebView 无 iOS 的
 *    window.webkit.messageHandlers API，需此垫片让网页侧代码无需改动即可复用）。
 */
private const val BRIDGE_SHIM = """
    window.dialog = function(message, title) { Android.showDialog(title, message); };
    window.statusbar = window.statusbar || {};
    window.statusbar.color = function(c){ Android.setStatusBarColor(c); };
    window.file = {
        write: function(name, content, cb){ Android.writeFile(name, content, cb); },
        read: function(name, cb){ Android.readFile(name, cb); }
    };
    window.image = { save: function(b64, cb){ Android.saveImage(b64, cb); } };
    if (!window.webkit) window.webkit = {};
    if (!window.webkit.messageHandlers) window.webkit.messageHandlers = {};
    ['scratchSave','offlineDownload','offlineGetAsset'].forEach(function(n){
        window.webkit.messageHandlers[n] = {
            postMessage: function(msg){ Android.bridgePost(n, JSON.stringify(msg)); }
        };
    });
"""

@Composable
fun MainActivityContent() {
    val context = LocalContext.current
    if (context is android.app.Activity) {
        context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            // 系统栏隐藏时无内边距；用户从边缘临时唤出系统栏时，WebView 自动避开不被遮挡
            .windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    allowFileAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mediaPlaybackRequiresUserGesture = false
                }

                val jsInterface = JavaScriptInterface(ctx, this)
                addJavascriptInterface(jsInterface, "Android")

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // 文档加载早期注入：预置资产 map + 桥垫片（不含离线状态回推）。
                        // 若此时 document 未就绪导致注入失败，onPageFinished 的 injectBridges 会兜底。
                        if (view != null) {
                            view.evaluateJavascript(jsInterface.buildInjectScript(withInit = false), null)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view != null) jsInterface.injectBridges(view)
                    }
                }

                // 加载打包进 assets 的 scratch-gui（含「资源离线包」功能）
                loadUrl("file:///android_asset/index.html")
            }
        }
    )
}

/**
 * JavaScript 接口：桥接 scratch-gui（Web 编辑器）与 Android 原生层。
 * - scratchSave / offlineDownload / offlineGetAsset 经 BRIDGE_SHIM 垫片转调 bridgePost(name, json)。
 * - offlineDownload：原生 HttpURLConnection 并发下载（规避 CORS / HTML 兜底页），魔数校验后写入
 *   filesDir/OfflineAssets 并回推 hydrate；启动时 injectBridges 回推离线状态。
 */
private class JavaScriptInterface(
    context: Context,
    private val webView: WebView
) {
    private val appContext: Context = context.applicationContext
    private val activity: MainActivity? = context as? MainActivity
    private var bridgesInjected = false

    @JavascriptInterface
    fun showDialog(title: String, message: String) {
        val act = activity ?: return
        act.runOnUiThread {
            android.app.AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    @JavascriptInterface
    fun setStatusBarColor(colorCode: String) {
        val act = activity ?: return
        act.runOnUiThread {
            try {
                val color = Color.parseColor(colorCode)
                act.setStatusBarColorFromWeb(color)
            } catch (_: Exception) {
                // 忽略颜色格式错误
            }
        }
    }

    @JavascriptInterface
    fun writeFile(fileName: String, content: String, callback: String) {
        Thread {
            try {
                val file = File(appContext.filesDir, fileName)
                FileOutputStream(file).use { it.write(content.toByteArray()) }
                evaluateJavascript(callback, true, "写入成功")
            } catch (e: Exception) {
                evaluateJavascript(callback, false, e.message)
            }
        }.start()
    }

    @JavascriptInterface
    fun readFile(fileName: String, callback: String) {
        Thread {
            try {
                val file = File(appContext.filesDir, fileName)
                if (!file.exists()) {
                    evaluateJavascript(callback, false, "文件不存在")
                    return@Thread
                }
                val content = FileInputStream(file).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { it.readText() }
                }
                evaluateJavascript(callback, true, content)
            } catch (e: Exception) {
                evaluateJavascript(callback, false, e.message)
            }
        }.start()
    }

    @JavascriptInterface
    fun saveImage(base64Data: String, callback: String) {
        saveImageToAlbum(base64Data, callback)
    }

    // ---- 资源离线包桥接 ----

    @JavascriptInterface
    fun bridgePost(name: String, json: String) {
        when (name) {
            "scratchSave" -> { /* 保存功能暂未实现；占位避免网页侧 JS 报错 */ }
            "offlineDownload" -> handleOfflineDownload(json)
            "offlineGetAsset" -> handleOfflineGetAsset(json)
        }
    }

    /** onPageFinished 后注入垫片与离线状态（仅执行一次）。 */
    fun injectBridges(view: WebView) {
        if (bridgesInjected) return
        bridgesInjected = true
        val script = buildInjectScript(withInit = true)
        view.post { view.evaluateJavascript(script, null) }
    }

    /**
     * 构建注入脚本：
     *  1. window.__scratchBundledAssets = {md5ext: base64}（default project 素材，量小，
     *     网页侧模块加载时同步消费，保证 VM 加载作品时 storage.load 命中缓存，无 hydrate 竞态）；
     *  2. window.__scratchBundledIndex = [md5ext, ...]（随包预置资产全量清单：素材库 1300+ 资产，
     *     启动仅注入清单，网页侧拦截 storage.load 按需懒加载，避免全量 hydrate 拖垮内存）；
     *  3. window.__scratchUserAssets = [md5ext, ...]（用户下载的作品资产清单，filesDir/OfflineAssets）；
     *  4. 桥垫片（BRIDGE_SHIM）；
     *  5. withInit 时：兜底再消费一次预置资产 + 回推离线状态（__scratchOfflineInit，
     *     推用户下载的资产做 hydrate 预热；预置资产走懒加载，不在此逐个 hydrate）。
     *
     * 离线资源包无条件启用：本地资源（预置 + 用户下载）无论联网与否一律优先，
     * 故 enabled 恒为 true。
     */
    fun buildInjectScript(withInit: Boolean): String {
        val sb = StringBuilder()
        sb.append("window.__scratchBundledAssets = ").append(bundledDefaultAssetsJson).append(";\n")
        sb.append("window.__scratchBundledIndex = ").append(bundledIndexJson).append(";\n")
        sb.append("window.__scratchUserAssets = ").append(userAssetsJson).append(";\n")
        if (withInit) {
            // 兜底再消费一次预置资产（防止 onPageStarted 注入过早未执行）
            sb.append("window.__scratchOfflineConsumeBundled && window.__scratchOfflineConsumeBundled();\n")
        }
        sb.append(BRIDGE_SHIM)
        if (withInit) {
            val assets = userDownloadedAssetList()
            val initJson = JSONObject().apply {
                put("enabled", true)
                put("assets", JSONArray(assets))
            }.toString()
            sb.append("window.__scratchOfflineInit && window.__scratchOfflineInit(")
                .append(initJson).append(");")
        }
        return sb.toString()
    }

    /** default project 资产的 base64 map（assets/OfflineAssets 中的少量素材，惰性读取一次）。 */
    private val bundledDefaultAssetsJson: String by lazy {
        val map = JSONObject()
        try {
            for (name in DEFAULT_PROJECT_ASSETS) {
                val data = appContext.assets.open("$OFFLINE_DIR/$name").use { it.readBytes() }
                map.put(name, Base64.encodeToString(data, Base64.NO_WRAP))
            }
        } catch (_: Exception) {
            // 预置资产缺失时为空 map
        }
        map.toString()
    }

    /** 预置资产全量清单（assets/OfflineAssets 文件名列表，惰性读取一次）。 */
    private val bundledIndex: List<String> by lazy {
        try {
            val allowed = setOf("svg", "png", "jpg", "jpeg", "wav", "mp3")
            (appContext.assets.list(OFFLINE_DIR) ?: emptyArray())
                .filter { it.substringAfterLast('.', "").lowercase() in allowed && !it.startsWith(".") }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val bundledIndexJson: String by lazy {
        JSONArray(bundledIndex).toString()
    }

    /** 用户下载的资产清单（filesDir/OfflineAssets 文件名列表，惰性读取一次）。 */
    private val userAssetsJson: String by lazy {
        JSONArray(userDownloadedAssetList()).toString()
    }

    private fun handleOfflineDownload(json: String) {
        val list = parseAssets(json)
        val total = list.size
        if (total == 0) {
            pushOfflineComplete(0, 0, 0, 0)
            return
        }
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENT)
        val done = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val notFound = AtomicInteger(0)
        for ((md5ext, urls) in list) {
            executor.execute {
                var ok = false
                try {
                    // 本地已有（随包预置或此前下载过）则直接回推，不再走网络
                    val existing = readOfflineAsset(md5ext)
                    if (existing != null) {
                        pushOfflineAssetData(md5ext, existing)
                        ok = true
                    } else {
                        val data = downloadFirstValid(md5ext, urls)
                        if (data != null && writeOfflineAsset(md5ext, data)) {
                            pushOfflineAssetData(md5ext, data)
                            ok = true
                        }
                    }
                } catch (_: Exception) {
                    // 单个资产失败不阻断整体
                }
                val d = done.incrementAndGet()
                if (ok) downloaded.incrementAndGet() else notFound.incrementAndGet()
                pushOfflineProgress(d, total, notFound.get())
                if (d == total) {
                    pushOfflineComplete(downloaded.get(), notFound.get(), total, notFound.get())
                    executor.shutdown()
                }
            }
        }
    }

    /** 顺序尝试各候选源，返回第一个 HTTP 200 且通过魔数校验的字节；全部失败返回 null。 */
    private fun downloadFirstValid(md5ext: String, urls: List<String>): ByteArray? {
        for (url in urls) {
            val data = downloadBytes(url)
            if (data != null && isValidAsset(md5ext, data)) {
                android.util.Log.d("offline", "$md5ext OK <- $url")
                return data
            }
        }
        android.util.Log.d("offline", "$md5ext NOT FOUND on all sources")
        return null
    }

    private fun handleOfflineGetAsset(json: String) {
        try {
            val obj = JSONObject(json)
            val md5ext = obj.optString("md5ext")
            if (md5ext.isNotEmpty()) {
                val data = readOfflineAsset(md5ext)
                if (data != null) pushOfflineAssetData(md5ext, data)
            }
        } catch (_: Exception) {
            // 忽略单个资产读取失败
        }
    }

    private fun downloadBytes(urlStr: String): ByteArray? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 魔数 / 内容校验：拒绝 HTML/XML 错误体（404 等）与类型不符的数据。 */
    private fun isValidAsset(md5ext: String, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val ext = md5ext.substringAfterLast('.', "").lowercase()
        val headLen = if (512 < data.size) 512 else data.size
        val head = String(data.copyOfRange(0, headLen), Charsets.UTF_8)
            .trim().lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") ||
            head.startsWith("<head") || head.startsWith("<body")
        ) return false
        return when (ext) {
            "svg" -> head.startsWith("<svg") || head.startsWith("<?xml")
            "png" -> data.bytesStartWith(0x89, 'P'.code, 'N'.code, 'G'.code)
            "jpg", "jpeg" -> data.bytesStartWith(0xFF, 0xD8, 0xFF)
            "wav" -> data.bytesStartWith('R'.code, 'I'.code, 'F'.code, 'F'.code)
            "mp3" -> data.bytesStartWith('I'.code, 'D'.code, '3'.code) ||
                data.bytesStartWith(0xFF, 0xFB)
            else -> false
        }
    }

    private fun writeOfflineAsset(md5ext: String, data: ByteArray): Boolean {
        return try {
            val dir = File(appContext.filesDir, OFFLINE_DIR)
            dir.mkdirs()
            FileOutputStream(File(dir, sanitizeAssetName(md5ext))).use { it.write(data) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readOfflineAsset(md5ext: String): ByteArray? {
        val name = sanitizeAssetName(md5ext)
        // 用户下载的资产优先（filesDir/OfflineAssets），其次随包预置的资产（assets/OfflineAssets）
        val userFile = File(File(appContext.filesDir, OFFLINE_DIR), name)
        try {
            if (userFile.exists()) return userFile.readBytes()
        } catch (_: Exception) {
            // 读用户资产失败则尝试预置资产
        }
        return try {
            appContext.assets.open("$OFFLINE_DIR/$name").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** 用户下载的离线资产列表（filesDir/OfflineAssets）。预置资产走懒加载，不在此列出。 */
    private fun userDownloadedAssetList(): List<String> {
        val allowed = setOf("svg", "png", "jpg", "jpeg", "wav", "mp3")
        val dir = File(appContext.filesDir, OFFLINE_DIR)
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.extension.lowercase() in allowed && !it.name.startsWith(".") }
            .map { it.name }
            .sorted()
    }

    private fun sanitizeAssetName(name: String): String {
        val base = name.substringAfterLast('/')
        val cleaned = base.replace(Regex("[?%*|\\\"<>:]"), "_")
        return if (cleaned.isEmpty()) "asset.bin" else cleaned
    }

    private fun parseAssets(json: String): List<Pair<String, List<String>>> {
        val result = mutableListOf<Pair<String, List<String>>>()
        val seen = mutableSetOf<String>()
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val md5ext = o.optString("md5ext")
                if (md5ext.isEmpty() || seen.contains(md5ext)) continue
                val urls = mutableListOf<String>()
                val urlsArr = o.optJSONArray("urls")
                if (urlsArr != null) {
                    for (j in 0 until urlsArr.length()) {
                        val u = urlsArr.optString(j)
                        if (u.isNotEmpty()) urls.add(u)
                    }
                } else {
                    val single = o.optString("url")
                    if (single.isNotEmpty()) urls.add(single)
                }
                if (urls.isEmpty()) continue
                seen.add(md5ext)
                result.add(Pair(md5ext, urls))
            }
        } catch (_: Exception) {
            // 解析失败返回空列表
        }
        return result
    }

    private fun pushOfflineAssetData(md5ext: String, data: ByteArray) {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("md5ext", md5ext)
            put("data", b64)
        }.toString()
        pushJs("window.__scratchOfflineAsset && window.__scratchOfflineAsset($json);")
    }

    private fun pushOfflineProgress(done: Int, total: Int, skipped: Int) {
        val json = JSONObject().apply {
            put("done", done)
            put("total", total)
            put("skipped", skipped)
        }.toString()
        pushJs("window.__scratchOfflineProgress && window.__scratchOfflineProgress($json);")
    }

    private fun pushOfflineComplete(downloaded: Int, skipped: Int, total: Int, notFound: Int) {
        val json = JSONObject().apply {
            put("downloaded", downloaded)
            put("skipped", skipped)
            put("total", total)
            put("notFound", notFound)
        }.toString()
        pushJs("window.__scratchOfflineComplete && window.__scratchOfflineComplete($json);")
    }

    private fun pushJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // ---- 图片保存到相册（保留既有能力） ----

    private fun saveImageToAlbum(base64Data: String, callback: String) {
        Thread {
            try {
                var imageData = base64Data
                if (imageData.startsWith("data:image/")) {
                    val index = imageData.indexOf(',')
                    if (index > 0) imageData = imageData.substring(index + 1)
                }
                val decoded = Base64.decode(imageData, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    ?: run {
                        evaluateJavascript(callback, false, "图片解码失败")
                        return@Thread
                    }
                val displayName = "gudaoweb_${System.currentTimeMillis()}.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/gudaoweb")
                    }
                    var uri: Uri? = null
                    appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let {
                        uri = it
                        appContext.contentResolver.openOutputStream(it)?.use { os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }
                    if (uri != null) evaluateJavascript(callback, true, "图片已保存到相册")
                    else evaluateJavascript(callback, false, "保存图片失败")
                } else {
                    @Suppress("DEPRECATION")
                    val dcim = File(android.os.Environment.getExternalStorageDirectory(), "DCIM/gudaoweb")
                    dcim.mkdirs()
                    val file = File(dcim, displayName)
                    FileOutputStream(file).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                    android.media.MediaScannerConnection.scanFile(
                        appContext, arrayOf(file.absolutePath), arrayOf("image/png"), null
                    )
                    evaluateJavascript(callback, true, "图片已保存到相册")
                }
            } catch (e: Exception) {
                evaluateJavascript(callback, false, e.message ?: "保存图片异常")
            }
        }.start()
    }

    private fun evaluateJavascript(callback: String, success: Boolean, result: String?) {
        webView.post {
            val js = if (success) {
                "$callback(null, ${escapeJsString(result ?: "")})"
            } else {
                "$callback(${escapeJsString(result ?: "未知错误")}, null)"
            }
            webView.evaluateJavascript(js, null)
        }
    }

    private fun escapeJsString(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

/** 判断字节数组是否以指定字节（按 0xFF 解释的 Int）开头。 */
private fun ByteArray.bytesStartWith(vararg expected: Int): Boolean {
    if (size < expected.size) return false
    for (i in expected.indices) {
        if ((this[i].toInt() and 0xFF) != expected[i]) return false
    }
    return true
}
