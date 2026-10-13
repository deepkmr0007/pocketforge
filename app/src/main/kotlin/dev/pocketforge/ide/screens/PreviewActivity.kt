package dev.pocketforge.ide.screens

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import dev.pocketforge.engine.security.PathGuard
import dev.pocketforge.ide.ui.BaseActivity
import dev.pocketforge.ide.ui.Dialogs
import java.io.ByteArrayInputStream

/**
 * Offline preview for web projects. Files are served from the project through a virtual origin
 * (https://pocketforge.preview/); EVERY other request — internet, file://, content:// — is answered
 * with an empty 403 locally, so the preview cannot reach the network or other app data.
 * Safe Browsing lookups are disabled (they would contact Google). JavaScript can be turned off.
 */
class PreviewActivity : BaseActivity() {
    private lateinit var web: WebView
    private lateinit var guard: PathGuard
    private val console = StringBuilder()
    private var blocked = 0
    private lateinit var info: TextView
    private var js = true
    private lateinit var path: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getStringExtra(Nav.PROJECT) ?: run { finish(); return }
        path = intent.getStringExtra(Nav.PATH) ?: "index.html"
        guard = PathGuard(c.projects.dirOf(pid))
        info = ui.caption("")
        web = try { WebView(this) } catch (e: Exception) {
            // Some devices ship without a working WebView provider; say so instead of crashing.
            toastLong("Android System WebView is unavailable or updating, so the preview cannot open: ${e.javaClass.simpleName}")
            finish(); return
        }
        web.settings.apply {
            javaScriptEnabled = js
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = true
            safeBrowsingEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().startsWith(PREVIEW_ORIGIN)) return false
                blocked++; updateInfo()
                return true // never navigate away from the preview
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url.toString()
                if (!url.startsWith(PREVIEW_ORIGIN)) { blocked++; runOnUiThread { updateInfo() }; return deny() }
                val rel = request.url.path?.trimStart('/')?.ifEmpty { "index.html" } ?: return deny()
                return try {
                    val f = guard.resolve(rel)
                    if (!f.isFile || f.length() > 32L * 1024 * 1024) return notFound()
                    val ext = f.extension.lowercase()
                    val mime = when (ext) { "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "html", "htm" -> "text/html"; "json" -> "application/json"; "svg" -> "image/svg+xml"; "wasm" -> "application/wasm"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }
                    WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "utf-8" else null, f.inputStream()).apply {
                        responseHeaders = mapOf("Content-Security-Policy" to "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src 'self'", "Cache-Control" to "no-store")
                    }
                } catch (e: Exception) { notFound() }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                console.append("[${m.messageLevel()}] ${m.message()} (${m.sourceId().removePrefix(PREVIEW_ORIGIN)}:${m.lineNumber()})\n")
                if (console.length > 100_000) console.delete(0, 50_000)
                updateInfo(); return true
            }
        }
        val bar = ui.topBar("Preview", path, { finish() }, listOf(
            ui.iconButton("⟳", "Reload") { web.reload() },
            ui.iconButton("JS", "Toggle JavaScript") { js = !js; web.settings.javaScriptEnabled = js; web.reload(); updateInfo() },
            ui.iconButton("≡", "Console") { Dialogs.text(this, "Console", console.toString().ifEmpty { "(no console output)" }) },
        ))
        setScreen(ui.vertical {
            addView(bar, ui.lp())
            addView(info.apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); setBackgroundColor(p.surface) }, ui.lp())
            addView(web, ui.lp(h = 0, weight = 1f))
        })
        updateInfo()
        web.loadUrl(PREVIEW_ORIGIN + path)
    }

    private fun updateInfo() {
        info.text = "Offline preview · JavaScript ${if (js) "on" else "off"} · ${console.lines().count { it.startsWith("[ERROR]") }} console error(s) · $blocked outside request(s) blocked"
    }

    private fun deny() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by PocketForge", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream("Not found".toByteArray()))

    override fun onDestroy() {
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }

    companion object { const val PREVIEW_ORIGIN = "https://pocketforge.preview/" }
}
