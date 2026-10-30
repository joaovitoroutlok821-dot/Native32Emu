package org.native32emu.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import java.io.File

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCoverFor: String? = null
    private val pickGameRequest = 4201
    private val pickCoverRequest = 4202

    private val gameDirectory: File by lazy { File(filesDir, "games").apply { mkdirs() } }
    private val coverDirectory: File by lazy { File(filesDir, "covers").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 14, 24)
        window.navigationBarColor = android.graphics.Color.rgb(10, 14, 24)
        window.decorView.systemUiVisibility = 0

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/games/", WebViewAssetLoader.InternalStoragePathHandler(this, gameDirectory))
            .addPathHandler("/covers/", WebViewAssetLoader.InternalStoragePathHandler(this, coverDirectory))
            .build()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(GameLibraryBridge(), "NativeGames")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val isCover = fileChooserParams?.acceptTypes?.any { it.startsWith("image/") } == true
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (isCover) "image/*" else "*/*"
                    if (!isCover) {
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/zip",
                            "application/octet-stream",
                            "application/x-zip-compressed"
                        ))
                    }
                }
                startActivityForResult(intent, if (isCover) pickCoverRequest else pickGameRequest)
                return true
            }
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    @Deprecated("Deprecated Android API retained for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickGameRequest && requestCode != pickCoverRequest) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            callback.onReceiveValue(null)
            pendingCoverFor = null
            return
        }

        val uri = data.data!!
        val displayName = getDisplayName(uri) ?: "arquivo"
        try {
            if (requestCode == pickCoverRequest) {
                val gameName = pendingCoverFor
                pendingCoverFor = null
                if (gameName == null) {
                    callback.onReceiveValue(null)
                    return
                }
                val extension = (displayName.substringAfterLast('.', "jpg").lowercase())
                    .takeIf { it in listOf("png", "jpg", "jpeg", "webp", "gif") } ?: "jpg"
                val destination = File(coverDirectory, "${coverBase(gameName)}.$extension")
                val source = requireNotNull(contentResolver.openInputStream(uri)) {
                    "Não foi possível abrir a imagem selecionada."
                }
                source.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                callback.onReceiveValue(arrayOf(uri))
                webView.postDelayed({
                    webView.evaluateJavascript("window.refreshGameLibrary && window.refreshGameLibrary();", null)
                }, 300)
                return
            }

            if (!isSupportedGame(displayName)) {
                callback.onReceiveValue(null)
                webView.evaluateJavascript(
                    "window.showNativeError && window.showNativeError('Formato não suportado. Escolha .zip, .smf, .sgm ou .ssl.');",
                    null
                )
                return
            }

            val destination = uniqueDestination(displayName)
            val source = requireNotNull(contentResolver.openInputStream(uri)) {
                "Não foi possível abrir o arquivo escolhido."
            }
            source.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            // Return the original SAF URI for immediate playback; keep the copy for the library.
            callback.onReceiveValue(arrayOf(uri))
            webView.postDelayed({
                webView.evaluateJavascript("window.refreshGameLibrary && window.refreshGameLibrary();", null)
            }, 800)
        } catch (_: Exception) {
            callback.onReceiveValue(null)
            pendingCoverFor = null
            webView.evaluateJavascript(
                "window.showNativeError && window.showNativeError('Falha ao importar o arquivo. Verifique o espaço disponível e tente novamente.');",
                null
            )
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private fun isSupportedGame(name: String): Boolean =
        listOf(".zip", ".smf", ".sgm", ".ssl").any { name.lowercase().endsWith(it) }

    private fun safeName(name: String): String =
        File(name).name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "jogo.zip" }

    private fun coverBase(name: String): String =
        File(name).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "jogo" }

    private fun uniqueDestination(name: String): File {
        val safe = safeName(name)
        var file = File(gameDirectory, safe)
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        var index = 2
        while (file.exists()) {
            file = File(gameDirectory, "$base ($index).$ext")
            index++
        }
        return file
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun exitImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        window.statusBarColor = android.graphics.Color.rgb(10, 14, 24)
        window.navigationBarColor = android.graphics.Color.rgb(10, 14, 24)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.leaveGame && window.leaveGame();", null)
        } else {
            super.onBackPressed()
        }
    }

    inner class GameLibraryBridge {
        @JavascriptInterface
        fun listGames(): String {
            val names = gameDirectory.listFiles()
                ?.filter { it.isFile && isSupportedGame(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { it.name }
                ?: emptyList()
            return JSONArray(names).toString()
        }

        @JavascriptInterface
        fun gameUrl(name: String): String {
            val file = File(gameDirectory, File(name).name)
            if (!file.isFile || !isSupportedGame(file.name)) return ""
            return "https://appassets.androidplatform.net/games/" + Uri.encode(file.name).replace("+", "%20")
        }

        @JavascriptInterface
        fun coverUrl(name: String): String {
            val base = coverBase(name)
            val file = coverDirectory.listFiles()
                ?.firstOrNull { it.isFile && it.nameWithoutExtension == base &&
                    it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp", "gif") }
                ?: return ""
            return "https://appassets.androidplatform.net/covers/" + Uri.encode(file.name).replace("+", "%20")
        }

        @JavascriptInterface
        fun prepareCover(name: String) {
            pendingCoverFor = File(name).name
        }

        @JavascriptInterface
        fun deleteGame(name: String): Boolean {
            val file = File(gameDirectory, File(name).name)
            return file.isFile && file.delete()
        }
    }
}
