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

    // 端末固定ID: ANDROID_ID を使うことで再起動しても同じIDになり、
    // 管理画面側で付けた名前が保持される(取得失敗時のみランダム)
    private val deviceId: String by lazy {
        val aid = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { null }
        (aid ?: UUID.randomUUID().toString()).take(8)
    }

    // ---- サーバー設定 ----
    private val SERVER_URL = "wss://jp.serveirc.com/remote/"

    // スクショ送信間隔(ms)
    private val CAPTURE_INTERVAL = 500L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView をフルスクリーンで表示
        webView = WebView(this).apply {
            // ソフトウェアレイヤーで描画させる。
            // ハードウェア描画のままだと webView.draw() でスクショを撮ったとき、
            // スクロールで新しく出た部分がキャプチャされず真っ白になるため。
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
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
            "key" -> {
                val key = msg.optString("key")
                if (key == "Enter") pressEnter()
            }
            "scroll" -> {
                // dy 指定(ホイール)を優先、無ければ direction(上下ボタン)で ±500
                val dy = if (msg.has("dy")) msg.getInt("dy") else {
                    if (msg.optString("direction") == "up") -500 else 500
                }
                // window とタップ地点の要素の両方をスクロールし、
                // ページ内スクロールコンテナにも効くようにする
                webView.evaluateJavascript(
                    "window.scrollBy(0, $dy);", null
                )
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
                if (!el) return;
                // クリック相当のマウスイベントを順に発火(タップに近い挙動)
                ['mousedown','mouseup','click'].forEach(function(t){
                    el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window, clientX:$cssX, clientY:$cssY}));
                });
                if (typeof el.focus === 'function') el.focus();
                // 入力欄はスクロールで見える位置へ
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                    el.focus();
                }
            })();
        """, null)
    }

    // JavaScriptでテキスト入力(input/textarea/contenteditable 対応)
    private fun injectText(text: String) {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webView.evaluateJavascript("""
            (function() {
                var el = document.activeElement;
                if (!el) return;
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA') {
                    var proto = (tag === 'TEXTAREA')
                        ? window.HTMLTextAreaElement.prototype
                        : window.HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (setter && setter.set) {
                        setter.set.call(el, '$escaped');
                    } else {
                        el.value = '$escaped';
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } else if (el.isContentEditable) {
                    el.textContent = '$escaped';
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
            })();
        """, null)
    }

    // Enter キー送出(検索確定・フォーム送信)
    private fun pressEnter() {
        webView.evaluateJavascript("""
            (function() {
                var el = document.activeElement;
                if (!el) return;
                ['keydown','keypress','keyup'].forEach(function(t){
                    el.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
                });
                if (el.form) {
                    try {
                        if (el.form.requestSubmit) el.form.requestSubmit();
                        else el.form.submit();
                    } catch(e){}
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
        if (webView.width <= 0 || webView.height <= 0) return
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
