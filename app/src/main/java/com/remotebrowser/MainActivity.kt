package com.remotebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.*
import android.widget.FrameLayout
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId = UUID.randomUUID().toString().take(8)

    // ---- サーバー設定 ----
    private val SERVER_URL = "ws://116.80.62.19:8580"

    // スクショ送信間隔(ms)
    private val CAPTURE_INTERVAL = 500L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView をフルスクリーンで表示
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = settings.userAgentString.replace("; wv", "")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 現在のURLを管理画面に通知
                    url?.let { sendCurrentUrl(it) }
                }
            }

            webChromeClient = WebChromeClient()
        }

        val layout = FrameLayout(this)
        layout.addView(webView)
        setContentView(layout)

        // 初期ページ
        webView.loadUrl("https://www.google.com")

        // WebSocket 接続
        connectWebSocket()

        // 定期スクリーンショット送信開始
        startCapture()
    }

    // ---- WebSocket ----
    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("$SERVER_URL?role=phone&id=$deviceId")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    android.util.Log.i("RemoteBrowser", "Connected as $deviceId")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { handleCommand(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.postDelayed({ connectWebSocket() }, 3000)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.postDelayed({ connectWebSocket() }, 5000)
            }
        })
    }

    // ---- コマンド処理 ----
    private fun handleCommand(json: String) {
        val msg = JSONObject(json)
        when (msg.optString("type")) {
            "navigate" -> {
                val url = msg.getString("url")
                webView.loadUrl(url)
            }
            "back" -> {
                if (webView.canGoBack()) webView.goBack()
            }
            "forward" -> {
                if (webView.canGoForward()) webView.goForward()
            }
            "refresh" -> {
                webView.reload()
            }
            "tap" -> {
                val x = msg.getInt("x")
                val y = msg.getInt("y")
                simulateTap(x, y)
            }
            "input_text" -> {
                val text = msg.getString("text")
                injectText(text)
            }
            "scroll" -> {
                val direction = msg.getString("direction")
                val distance = 500
                when (direction) {
                    "up" -> webView.scrollBy(0, -distance)
                    "down" -> webView.scrollBy(0, distance)
                }
            }
        }
    }

    // JavaScript経由でタップをシミュレート
    private fun simulateTap(x: Int, y: Int) {
        // WebViewの密度に合わせてCSS座標に変換
        val density = resources.displayMetrics.density
        val cssX = (x / density).toInt()
        val cssY = (y / density).toInt()

        webView.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                if (el) {
                    el.focus();
                    el.click();
                    // input/textarea/select ならフォーカスを確実に
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                        el.focus();
                    }
                }
            })();
        """, null)
    }

    // JavaScriptでテキスト入力
    private fun injectText(text: String) {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webView.evaluateJavascript("""
            (function() {
                var el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    var nativeSetter = Object.getOwnPropertyDescriptor(
                        window.HTMLInputElement.prototype, 'value'
                    ) || Object.getOwnPropertyDescriptor(
                        window.HTMLTextAreaElement.prototype, 'value'
                    );
                    if (nativeSetter && nativeSetter.set) {
                        nativeSetter.set.call(el, '$escaped');
                    } else {
                        el.value = '$escaped';
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
            })();
        """, null)
    }

    // ---- スクリーンショット送信 ----
    private fun startCapture() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                captureAndSend()
                handler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }, CAPTURE_INTERVAL)
    }

    private fun captureAndSend() {
        if (ws == null) return
        // WebView の描画をビットマップにキャプチャ
        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        webView.draw(canvas)

        // JPEG に圧縮して送信
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
        bitmap.recycle()

        val bytes = stream.toByteArray()
        ws?.send(ByteString.of(*bytes))
    }

    private fun sendCurrentUrl(url: String) {
        val json = JSONObject().apply {
            put("type", "current_url")
            put("url", url)
        }
        ws?.send(json.toString())
    }

    override fun onDestroy() {
        ws?.close(1000, "App closed")
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
