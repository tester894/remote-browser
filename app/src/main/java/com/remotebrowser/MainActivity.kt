package com.remotebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: RemoteWebView
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

    // 高エントロピーClient Hintsに入れる端末の実値。
    // WebViewは model/platformVersion を空で返すため、空だと逆に不自然(本物Chromeは実値を返す)。
    // Build.* の実値を入れることで、端末ごとにばらけ、本物Chromeの挙動に一致する。
    // JS文字列に埋めるので ' と \ は除去(model名に紛れても壊れないように)。
    private val jsModel = (Build.MODEL ?: "").replace("\\", "").replace("'", "")
    private val jsPlatformVersion = run {
        val rel = (Build.VERSION.RELEASE ?: "10").replace("\\", "").replace("'", "")
        val parts = rel.split(".").toMutableList()
        while (parts.size < 3) parts.add("0")
        parts.take(3).joinToString(".")
    }

    // 【削除済み】以前は onPageStarted で CHROME_SPOOF_JS を注入し
    // window.chrome追加 / Client Hints の brands・model偽装をJSで試みていたが、
    // 実測で「一度も適用されていない」ことが判明(getHighEntropyValues=native、brandsにownプロパティ無し、
    // window.chrome=無し)。onPageStartedのevaluateJavascriptは新文書に載らないため。
    // Client Hints は setUserAgentMetadata でネイティブに設定する方式に全面移行した(下記)。
    // window.chrome はネイティブに追加する手段が無く、JSで関数を足すとCAPTCHAを誘発したため、
    // 追加しない(無いままでもCAPTCHAは出ないことを実機確認済み)。

    // スクショ送信間隔(ms): 操作直後は高速、アイドル時は省電力
    private val CAPTURE_FAST = 150L
    private val CAPTURE_IDLE = 1000L
    private val ACTIVE_WINDOW = 2000L   // 最後の操作から2秒間は高速モード
    private var lastCommandTime = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView をフルスクリーンで表示。
        // 本物の入力経路(InputConnection / KeyEvent / MotionEvent)を使うため、
        // 通常のWebViewを使い、InputConnectionの参照を保持する専用サブクラスにする。
        // これで本物のカーソル・カーソル位置・サイト内部状態の同期が得られる。
        // ソフトキーボードはおばあちゃんの画面に出るが、Manifestの adjustNothing で
        // WebViewは縮まず、管理画面のPixelCopyスクショにもIMEは写らない(別ウィンドウ)。
        webView = RemoteWebView(this).apply {
            // API 26 未満は PixelCopy が使えないため、draw() で撮る。
            // その場合ハードウェア描画だとスクロール後に白抜けするので、
            // 古い端末だけソフトウェアレイヤーにする。
            // API 26 以上は PixelCopy を使うのでハードウェア描画のままにする。
            if (Build.VERSION.SDK_INT < 26) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // モバイル版サイトを確実に出すため、UAを「モバイルChrome」で明示的に組み立てる。
            // Android 10; K は Chrome UA Reduction の標準形(WebViewマーカーは含まない)。
            // 末尾 "Mobile Safari" を必ず残すことで、サイトがモバイル版を返す。
            //
            // 重要: 本物のChromeは "UA Reduction" でバージョンを メジャー.0.0.0 に丸める
            // (例 138.0.0.0)。全Chromeが同じ値を名乗るので目立たない。
            // フルの4桁バージョン(例 138.0.7204.179)を出すと逆に珍しい値=指紋が濃くなる。
            // そのため端末のChromeからメジャー番号だけ取り、残りは 0.0.0 に固定する。
            // 【切り分けVariant2】UAのメジャーを System WebView の実バージョン優先にする。
            // Client Hints は WebViewエンジン由来なので、UAもWebView由来にすれば
            // 「UA と CH のバージョン不一致」(本物Chromeでは起きない)を解消できる。
            val webviewMajor = Regex("Chrome/(\\d+)").find(settings.userAgentString)?.groupValues?.get(1)
            // フルのビルド番号(例 138.0.7204.179)。高エントロピーCHでは本物Chromeもフル版を返すので合わせる。
            // UA文字列だけは Reduction で major.0.0.0 に丸める(そちらは全員同じが正解)。
            val webviewFull = Regex("Chrome/([\\d.]+)").find(settings.userAgentString)?.groupValues?.get(1)
            val installedMajor = try {
                (packageManager.getPackageInfo("com.android.chrome", 0).versionName)
                    ?.substringBefore('.')
            } catch (_: Exception) {
                try { (packageManager.getPackageInfo("com.chrome.beta", 0).versionName)?.substringBefore('.') }
                catch (_: Exception) { null }
            }
            val major = webviewMajor ?: installedMajor ?: "138"
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Mobile Safari/537.36"

            // ---- Client Hints をネイティブに設定(JS改ざんではない正攻法) ----
            // 実測で判明: CHROME_SPOOF_JS の Client Hints 偽装は
            // onPageStarted の evaluateJavascript では新しい文書に載らず一度も効いていなかった
            // (getHighEntropyValues が native のまま / brands に own プロパティ無し)。
            // その結果 brands に "Android WebView" が残り、mobile も false のままだった。
            //
            // setUserAgentMetadata は JS の書き換えと違い、
            //   ・navigator.userAgentData と sec-ch-ua ヘッダーの両方に効く
            //   ・JSの改ざん痕跡(getterやtoString)が一切残らない
            // ため、検出リスクを上げずに直せる唯一の方法。
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                try {
                    val fullVer = webviewFull ?: "$major.0.0.0"
                    fun brand(name: String, majorV: String, fullV: String) =
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand(name).setMajorVersion(majorV).setFullVersion(fullV).build()
                    // 本物のChromeと同じ3ブランド構成("Android WebView" を "Google Chrome" に)。
                    // majorVersion(低エントロピー)は丸めた major、fullVersion(高エントロピー)は実ビルド番号。
                    val brands = listOf(
                        brand("Not)A;Brand", "8", "8.0.0.0"),
                        brand("Chromium", major, fullVer),
                        brand("Google Chrome", major, fullVer)
                    )
                    val meta = UserAgentMetadata.Builder()
                        .setBrandVersionList(brands)
                        .setFullVersion(fullVer)
                        .setPlatform("Android")
                        // 高エントロピーは端末の実値。端末ごとに自然にばらけ、全員同一を避ける。
                        .setPlatformVersion(jsPlatformVersion)
                        .setModel(jsModel)
                        .setArchitecture("")          // Androidの本物Chromeは空を返す
                        .setMobile(true)              // UAが Mobile なので true が正。falseだと矛盾
                        .setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                        .setWow64(false)
                        .build()
                    WebSettingsCompat.setUserAgentMetadata(settings, meta)
                } catch (_: Exception) { /* 失敗しても既定動作のまま続行 */ }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // viewport指定が無いサイトはPC幅(980px)で表示されるため、
                    // 無い場合だけモバイル幅を補う(既にある指定は尊重する)
                    view?.evaluateJavascript(
                        "(function(){if(!document.querySelector('meta[name=viewport]')){" +
                        "var m=document.createElement('meta');m.name='viewport';" +
                        "m.content='width=device-width, initial-scale=1';" +
                        "(document.head||document.documentElement).appendChild(m);}})();",
                        null
                    )
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
        lastCommandTime = System.currentTimeMillis()
        val msg = JSONObject(json)
        when (msg.optString("type")) {
            "navigate" -> webView.loadUrl(msg.getString("url"))
            "back" -> if (webView.canGoBack()) webView.goBack() else webView.evaluateJavascript("history.back()", null)
            "forward" -> if (webView.canGoForward()) webView.goForward() else webView.evaluateJavascript("history.forward()", null)
            "refresh" -> webView.reload()
            "tap" -> simulateTap(msg.getInt("x"), msg.getInt("y"))
            "input_text" -> commitText(msg.getString("text"))     // まとめ入力(カーソル位置に挿入)
            "input_char" -> commitText(msg.getString("char"))     // ライブ入力(本物の入力経路で挿入)
            "key" -> handleKey(msg.optString("key"))
            "scroll" -> {
                // dy 指定(ホイール)を優先、無ければ direction(上下ボタン)で ±500
                val dy = if (msg.has("dy")) msg.getInt("dy") else {
                    if (msg.optString("direction") == "up") -500 else 500
                }
                webView.evaluateJavascript("window.scrollBy(0, $dy);", null)
            }
        }
        // 操作の結果をすぐ画面に反映させるため、少し待って即キャプチャ
        // (定期送信=400msを待たずに反応が見えるので操作感が上がる)
        handler.postDelayed({ captureAndSend() }, 90)
    }

    // 本物のタッチをWebViewに送る。JSの疑似クリックと違い、WebViewが
    // ネイティブに入力欄をフォーカスし、押した位置に本物のカーソルを表示する。
    // x,y はスクショ(=WebViewのピクセル)座標なので、そのままView座標として使える。
    private fun simulateTap(x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        val fx = x.toFloat(); val fy = y.toFloat()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fx, fy, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, fx, fy, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        // タップ後に「入力欄にフォーカスが入ったか」を判定して管理画面に返す
        // (管理画面はこれを見てカーソル形状=入力モードを切り替える)
        handler.postDelayed({
            webView.evaluateJavascript("""
                (function(){
                    var a=document.activeElement; if(!a) return 'false';
                    var tag=a.tagName;
                    if(a.isContentEditable) return 'true';
                    if(tag==='TEXTAREA') return 'true';
                    if(tag==='INPUT'){
                        var t=(a.type||'text').toLowerCase();
                        var noText=['checkbox','radio','button','submit','reset','file','image','range','color'];
                        return noText.indexOf(t)===-1 ? 'true' : 'false';
                    }
                    return 'false';
                })();
            """) { result -> sendEditableState(result?.contains("true") == true) }
        }, 120)
    }

    // 入力欄にフォーカスがあるかを管理画面へ通知
    private fun sendEditableState(editable: Boolean) {
        val json = JSONObject().apply {
            put("type", "focus_state")
            put("editable", editable)
        }
        ws?.send(json.toString())
    }

    // 文字入力: WebViewの本物の入力経路(InputConnection.commitText)で
    // 現在のカーソル位置に挿入する。日本語もOK、サイト内部の状態も正しく更新される。
    // (JSでのvalue書き換えと違い、カーソル位置・制御コンポーネントの状態が壊れない)
    private fun commitText(text: String) {
        val ic = webView.inputConnection ?: return
        try { ic.commitText(text, 1) } catch (_: Exception) {}
    }

    // 個別キー: 本物のキーイベントを送る。物理キーボードと全く同じ挙動になる。
    // Enterはinputなら送信/textareaなら改行をWebViewが自動で判断する。
    private fun handleKey(key: String) {
        val code = when (key) {
            "Enter" -> KeyEvent.KEYCODE_ENTER
            "Backspace" -> KeyEvent.KEYCODE_DEL
            "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT
            "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
            "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
            "Home" -> KeyEvent.KEYCODE_MOVE_HOME
            "End" -> KeyEvent.KEYCODE_MOVE_END
            "Tab" -> KeyEvent.KEYCODE_TAB
            else -> return
        }
        sendKey(code)
    }

    private fun sendKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        val ic = webView.inputConnection
        if (ic != null) {
            // 入力欄にフォーカスがある時は InputConnection 経由で確実にその欄へ送る
            try { ic.sendKeyEvent(down); ic.sendKeyEvent(up); return } catch (_: Exception) {}
        }
        // フォーカスが無い/失敗時はWebViewへ直接(戻る等のグローバルキー用)
        webView.dispatchKeyEvent(down)
        webView.dispatchKeyEvent(up)
    }

    // ---- スクリーンショット送信 ----
    private fun startCapture() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                captureAndSend()
                val interval = if (System.currentTimeMillis() - lastCommandTime < ACTIVE_WINDOW)
                    CAPTURE_FAST else CAPTURE_IDLE
                handler.postDelayed(this, interval)
            }
        }, CAPTURE_FAST)
    }

    private fun captureAndSend() {
        if (ws == null) return
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return

        if (Build.VERSION.SDK_INT >= 26) {
            capturePixelCopy(w, h)
        } else {
            captureDraw(w, h)
        }
    }

    // API 26+: 実際に画面に描画されたピクセルをそのままコピー。
    // ハードウェア描画のレイヤーも取得できるので、スクロールしても白抜けしない。
    private fun capturePixelCopy(w: Int, h: Int) {
        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            webView.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    sendBitmap(bitmap)
                } else {
                    bitmap.recycle()
                }
            }, handler)
        } catch (e: Exception) {
            captureDraw(w, h)
        }
    }

    // API 25 以下向けフォールバック(ソフトウェアレイヤーで draw)
    private fun captureDraw(w: Int, h: Int) {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        webView.draw(canvas)
        sendBitmap(bitmap)
    }

    private fun sendBitmap(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
        bitmap.recycle()
        ws?.send(ByteString.of(*stream.toByteArray()))
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

// フォーカス中の InputConnection を保持するWebView。
// 本物の入力経路(commitText / sendKeyEvent)を使うために参照をキャッシュする。
// これで本物のカーソル・カーソル位置・サイト内部状態の同期が得られる。
private class RemoteWebView(context: android.content.Context) : WebView(context) {
    var inputConnection: InputConnection? = null
        private set
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        if (ic != null) inputConnection = ic
        return ic
    }
}
