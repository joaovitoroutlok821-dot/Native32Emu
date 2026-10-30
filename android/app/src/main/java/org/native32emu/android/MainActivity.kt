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
    private val pickGameRequest = 4201
    private val gameDirectory: File by lazy {
        File(filesDir, "games").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 14, 24)
        window.navigationBarColor = android.graphics.Color.rgb(10, 14, 24)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/games/", WebViewAssetLoader.InternalStoragePathHandler(this, gameDirectory))
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
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed"
                    ))
                }
                startActivityForResult(intent, pickGameRequest)
                return true
            }
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    @Deprecated("Deprecated in Android but still supported for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickGameRequest) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            callback.onReceiveValue(null)
            return
        }

        val uri = data.data!!
        val displayName = getDisplayName(uri) ?: "jogo"
        if (!isSupportedGame(displayName)) {
            callback.onReceiveValue(null)
            webView.evaluateJavascript(
                "window.showNativeError && window.showNativeError('Formato não suportado. Escolha .zip, .smf, .sgm ou .ssl.');",
                null
            )
            return
        }

        try {
            val destination = uniqueDestination(displayName)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Não foi possível abrir o arquivo escolhido." }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            // Return the original SAF URI so the WebView can create a File/Blob
            // for immediate loading. A private copy is kept for the library.
            callback.onReceiveValue(arrayOf(uri))
            webView.postDelayed({
                webView.evaluateJavascript("window.refreshGameLibrary && window.refreshGameLibrary();", null)
            }, 800)
        } catch (e: Exception) {
            callback.onReceiveValue(null)
            webView.evaluateJavascript(
                "window.showNativeError && window.showNativeError('Falha ao importar o jogo. Verifique o espaço disponível e tente novamente.');",
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

    private fun uniqueDestination(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "jogo.zip" }
        var file = File(gameDirectory, safeName)
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
            val safeName = File(name).name
            val file = File(gameDirectory, safeName)
            if (!file.isFile || !isSupportedGame(file.name)) return ""
            return "https://appassets.androidplatform.net/games/" +
                Uri.encode(file.name).replace("+", "%20")
        }

        @JavascriptInterface
        fun deleteGame(name: String): Boolean {
            val file = File(gameDirectory, File(name).name)
            return file.isFile && file.delete()
        }
    }
}
