package com.remotebrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
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

    // Chrome完全偽装JS: ページのJSより先に注入し、全検出ベクトルを潰す
    private val CHROME_SPOOF_JS = """
        (function(){
            // --- Function.prototype.toString 偽装(最重要) ---
            // 偽装した関数がnativeに見えるようにtoString自体をフック
            var spoofed=new WeakSet();
            var origToString=Function.prototype.toString;
            var nameMap=new WeakMap();
            function markNative(fn,name){spoofed.add(fn);nameMap.set(fn,name);return fn}
            Function.prototype.toString=function(){
                if(spoofed.has(this)){return'function '+nameMap.get(this)+'() { [native code] }'}
                return origToString.call(this);
            };
            spoofed.add(Function.prototype.toString);
            nameMap.set(Function.prototype.toString,'toString');

            // --- window.chrome ---
            if(!window.chrome){
                window.chrome={
                    runtime:{id:undefined,
                        connect:markNative(function(){},'connect'),
                        sendMessage:markNative(function(){},'sendMessage'),
                        onMessage:{addListener:markNative(function(){},'addListener'),removeListener:markNative(function(){},'removeListener'),hasListeners:markNative(function(){return false},'hasListeners')},
                        onConnect:{addListener:markNative(function(){},'addListener'),removeListener:markNative(function(){},'removeListener'),hasListeners:markNative(function(){return false},'hasListeners')},
                        OnInstalledReason:{CHROME_UPDATE:'chrome_update',INSTALL:'install',SHARED_MODULE_UPDATE:'shared_module_update',UPDATE:'update'},
                        PlatformArch:{ARM:'arm',ARM64:'arm64',MIPS:'mips',MIPS64:'mips64',X86_32:'x86-32',X86_64:'x86-64'},
                        PlatformOs:{ANDROID:'android',CROS:'cros',LINUX:'linux',MAC:'mac',OPENBSD:'openbsd',WIN:'win'}
                    },
                    app:{isInstalled:false,
                        getDetails:markNative(function(){return null},'getDetails'),
                        getIsInstalled:markNative(function(){return false},'getIsInstalled'),
                        installState:markNative(function(cb){if(cb)cb({state:'disabled'})},'installState'),
                        InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'}
                    },
                    csi:markNative(function(){return{startE:Date.now(),onloadT:Date.now(),pageT:0,tran:15}},'csi'),
                    loadTimes:markNative(function(){return{commitLoadTime:Date.now()/1000,connectionInfo:'h2',finishDocumentLoadTime:Date.now()/1000,finishLoadTime:Date.now()/1000,firstPaintAfterLoadTime:0,firstPaintTime:Date.now()/1000,navigationType:'Other',npnNegotiatedProtocol:'h2',requestTime:Date.now()/1000,startLoadTime:Date.now()/1000,wasAlternateProtocolAvailable:false,wasFetchedViaSpdy:true,wasNpnNegotiated:true}},'loadTimes')
                };
            }

            // --- navigator.plugins (Android Chromeは空だがlengthプロパティが正しい) ---
            try{
                var fp=[];fp.item=markNative(function(i){return fp[i]||null},'item');
                fp.namedItem=markNative(function(n){return null},'namedItem');
                fp.refresh=markNative(function(){},'refresh');
                Object.defineProperty(navigator,'plugins',{get:function(){return fp},configurable:true});
            }catch(e){}

            // --- navigator.mimeTypes ---
            try{
                var fm=[];fm.item=markNative(function(i){return fm[i]||null},'item');
                fm.namedItem=markNative(function(n){return null},'namedItem');
                Object.defineProperty(navigator,'mimeTypes',{get:function(){return fm},configurable:true});
            }catch(e){}

            // --- navigator.webdriver → undefined(Chromeの通常状態) ---
            try{Object.defineProperty(navigator,'webdriver',{get:function(){return undefined},configurable:true});}catch(e){}

            // --- Notification API(WebViewには不在) ---
            try{
                if(typeof Notification==='undefined'){
                    var N=markNative(function Notification(t,o){},'Notification');
                    N.permission='default';
                    N.requestPermission=markNative(function(){return Promise.resolve('default')},'requestPermission');
                    N.maxActions=2;
                    window.Notification=N;
                }else{
                    Object.defineProperty(Notification,'permission',{get:function(){return'default'},configurable:true});
                }
            }catch(e){}

            // --- navigator.permissions(WebViewでは未実装) ---
            try{
                if(!navigator.permissions||!navigator.permissions.query){
                    var grantedSet=['geolocation','accelerometer','gyroscope','magnetometer'];
                    navigator.permissions={
                        query:markNative(function(desc){
                            var s=grantedSet.indexOf(desc.name)>=0?'granted':'prompt';
                            return Promise.resolve({state:s,status:s,onchange:null});
                        },'query')
                    };
                }else{
                    var oq=navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query=markNative(function(desc){
                        if(desc&&desc.name==='notifications'){return Promise.resolve({state:'prompt',status:'prompt',onchange:null})}
                        return oq(desc);
                    },'query');
                }
            }catch(e){}

            // --- navigator.serviceWorker(WebViewには不在) ---
            try{
                if(!navigator.serviceWorker){
                    var swc={
                        ready:new Promise(function(){}),
                        controller:null,
                        register:markNative(function(){return Promise.reject(new DOMException('','SecurityError'))},'register'),
                        getRegistration:markNative(function(){return Promise.resolve(undefined)},'getRegistration'),
                        getRegistrations:markNative(function(){return Promise.resolve([])},'getRegistrations'),
                        addEventListener:markNative(function(){},'addEventListener'),
                        removeEventListener:markNative(function(){},'removeEventListener'),
                        oncontrollerchange:null,onmessage:null,onmessageerror:null
                    };
                    Object.defineProperty(navigator,'serviceWorker',{get:function(){return swc},configurable:true});
                }
            }catch(e){}

            // --- navigator.share / canShare(WebViewには不在) ---
            try{
                if(!navigator.share){
                    Object.defineProperty(navigator,'share',{value:markNative(function(){return Promise.reject(new DOMException('','AbortError'))},'share'),configurable:true,writable:true});
                }
                if(!navigator.canShare){
                    Object.defineProperty(navigator,'canShare',{value:markNative(function(){return true},'canShare'),configurable:true,writable:true});
                }
            }catch(e){}

            // --- speechSynthesis(WebViewでは欠落) ---
            try{
                if(!window.speechSynthesis){
                    window.speechSynthesis={
                        speaking:false,pending:false,paused:false,
                        getVoices:markNative(function(){return[]},'getVoices'),
                        speak:markNative(function(){},'speak'),
                        cancel:markNative(function(){},'cancel'),
                        pause:markNative(function(){},'pause'),
                        resume:markNative(function(){},'resume'),
                        addEventListener:markNative(function(){},'addEventListener'),
                        removeEventListener:markNative(function(){},'removeEventListener'),
                        onvoiceschanged:null
                    };
                    window.SpeechSynthesisUtterance=markNative(function(t){this.text=t||'';this.lang='';this.voice=null;this.volume=1;this.rate=1;this.pitch=1},'SpeechSynthesisUtterance');
                }
            }catch(e){}

            // --- screen.orientation.lock/unlock(WebViewでは削除されている) ---
            try{
                if(screen.orientation&&!screen.orientation.lock){
                    screen.orientation.lock=markNative(function(){return Promise.reject(new DOMException('','NotSupportedError'))},'lock');
                    screen.orientation.unlock=markNative(function(){},'unlock');
                }
            }catch(e){}

            // --- document.fullscreenEnabled ---
            try{
                if(!document.fullscreenEnabled){
                    Object.defineProperty(document,'fullscreenEnabled',{get:function(){return true},configurable:true});
                }
                if(!Element.prototype.requestFullscreen){
                    Element.prototype.requestFullscreen=markNative(function(){return Promise.reject(new TypeError())},'requestFullscreen');
                }
                if(!document.exitFullscreen){
                    document.exitFullscreen=markNative(function(){return Promise.resolve()},'exitFullscreen');
                }
            }catch(e){}

            // --- window.print(WebViewでは未実装) ---
            try{if(!window.print||window.print.toString().indexOf('native')===-1){window.print=markNative(function(){},'print')}}catch(e){}

            // --- MediaDevices.getDisplayMedia(WebViewには不在) ---
            try{
                if(navigator.mediaDevices&&!navigator.mediaDevices.getDisplayMedia){
                    navigator.mediaDevices.getDisplayMedia=markNative(function(){return Promise.reject(new DOMException('','NotAllowedError'))},'getDisplayMedia');
                }
            }catch(e){}

            // --- PaymentRequest(WebViewではデフォルト無効) ---
            try{
                if(!window.PaymentRequest){
                    window.PaymentRequest=markNative(function(){throw new DOMException('','NotSupportedError')},'PaymentRequest');
                }
            }catch(e){}

            // --- navigator.connection ---
            try{
                if(!navigator.connection){
                    Object.defineProperty(navigator,'connection',{get:function(){return{effectiveType:'4g',rtt:50,downlink:10,saveData:false,type:'wifi',downlinkMax:100,onchange:null,addEventListener:function(){},removeEventListener:function(){}}},configurable:true});
                }
            }catch(e){}

            // --- navigator.usb/serial/hid(WebViewには不在) ---
            try{if(!navigator.usb){Object.defineProperty(navigator,'usb',{get:function(){return{getDevices:markNative(function(){return Promise.resolve([])},'getDevices'),requestDevice:markNative(function(){return Promise.reject(new DOMException('','SecurityError'))},'requestDevice'),addEventListener:markNative(function(){},'addEventListener'),removeEventListener:markNative(function(){},'removeEventListener'),onconnect:null,ondisconnect:null}},configurable:true})}}catch(e){}
            try{if(!navigator.serial){Object.defineProperty(navigator,'serial',{get:function(){return{getPorts:markNative(function(){return Promise.resolve([])},'getPorts'),requestPort:markNative(function(){return Promise.reject(new DOMException('','SecurityError'))},'requestPort'),addEventListener:markNative(function(){},'addEventListener'),removeEventListener:markNative(function(){},'removeEventListener'),onconnect:null,ondisconnect:null}},configurable:true})}}catch(e){}
            try{if(!navigator.hid){Object.defineProperty(navigator,'hid',{get:function(){return{getDevices:markNative(function(){return Promise.resolve([])},'getDevices'),requestDevice:markNative(function(){return Promise.reject(new DOMException('','SecurityError'))},'requestDevice'),addEventListener:markNative(function(){},'addEventListener'),removeEventListener:markNative(function(){},'removeEventListener'),onconnect:null,ondisconnect:null}},configurable:true})}}catch(e){}

            // --- Client Hints: NavigatorUAData(sec-ch-uaをChrome相当に) ---
            try{
                if(navigator.userAgentData){
                    var brands=navigator.userAgentData.brands;
                    if(brands){
                        var fixed=[];
                        for(var i=0;i<brands.length;i++){
                            var b=brands[i];
                            if(b.brand&&b.brand.indexOf('WebView')>=0){
                                fixed.push({brand:'Google Chrome',version:b.version});
                            }else{fixed.push(b)}
                        }
                        Object.defineProperty(navigator.userAgentData,'brands',{get:function(){return fixed},configurable:true});
                    }
                    if(navigator.userAgentData.getHighEntropyValues){
                        var origHE=navigator.userAgentData.getHighEntropyValues.bind(navigator.userAgentData);
                        navigator.userAgentData.getHighEntropyValues=markNative(function(hints){
                            return origHE(hints).then(function(v){
                                if(v.brands){for(var i=0;i<v.brands.length;i++){if(v.brands[i].brand&&v.brands[i].brand.indexOf('WebView')>=0)v.brands[i].brand='Google Chrome'}}
                                if(v.fullVersionList){for(var i=0;i<v.fullVersionList.length;i++){if(v.fullVersionList[i].brand&&v.fullVersionList[i].brand.indexOf('WebView')>=0)v.fullVersionList[i].brand='Google Chrome'}}
                                return v;
                            });
                        },'getHighEntropyValues');
                    }
                }
            }catch(e){}
        })();
    """.trimIndent()

    // スクショ送信間隔(ms)
    private val CAPTURE_INTERVAL = 400L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView をフルスクリーンで表示
        webView = WebView(this).apply {
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
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // ページのJSより先にChromeプロパティを偽装
                    view?.evaluateJavascript(CHROME_SPOOF_JS, null)
                }
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
            "navigate" -> webView.loadUrl(msg.getString("url"))
            "back" -> if (webView.canGoBack()) webView.goBack()
            "forward" -> if (webView.canGoForward()) webView.goForward()
            "refresh" -> webView.reload()
            "tap" -> simulateTap(msg.getInt("x"), msg.getInt("y"))
            "input_text" -> injectText(msg.getString("text"))     // 全置換(入力ボックス用)
            "input_char" -> insertAtCaret(msg.getString("char"))  // ライブ入力(カーソル位置に挿入)
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

    // JavaScript経由でタップをシミュレート。
    // タップ後に「入力欄にフォーカスが入ったか」を判定して管理画面に返す
    // (管理画面はこれを見てカーソル形状=入力モードを切り替える)
    private fun simulateTap(x: Int, y: Int) {
        val density = resources.displayMetrics.density
        val cssX = (x / density).toInt()
        val cssY = (y / density).toInt()
        webView.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                if (!el) return 'false';
                ['mousedown','mouseup','click'].forEach(function(t){
                    el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window, clientX:$cssX, clientY:$cssY}));
                });
                if (typeof el.focus === 'function') el.focus();
                var a = document.activeElement;
                if (!a) return 'false';
                var tag = a.tagName;
                if (a.isContentEditable) return 'true';
                if (tag === 'TEXTAREA') return 'true';
                if (tag === 'INPUT') {
                    var t = (a.type || 'text').toLowerCase();
                    var noText = ['checkbox','radio','button','submit','reset','file','image','range','color'];
                    return noText.indexOf(t) === -1 ? 'true' : 'false';
                }
                return 'false';
            })();
        """) { result ->
            val editable = result?.contains("true") == true
            sendEditableState(editable)
        }
    }

    // 入力欄にフォーカスがあるかを管理画面へ通知
    private fun sendEditableState(editable: Boolean) {
        val json = JSONObject().apply {
            put("type", "focus_state")
            put("editable", editable)
        }
        ws?.send(json.toString())
    }

    // 全置換入力(入力ボックスからの一括入力)
    private fun injectText(text: String) {
        val t = esc(text)
        webView.evaluateJavascript("""
            (function() {
                var el = document.activeElement;
                if (!el) return;
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA') {
                    var proto = (tag === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (setter && setter.set) setter.set.call(el, '$t'); else el.value = '$t';
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } else if (el.isContentEditable) {
                    el.textContent = '$t';
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
            })();
        """, null)
    }

    // ライブ入力: カーソル位置に文字列を挿入
    private fun insertAtCaret(text: String) {
        val t = esc(text)
        webView.evaluateJavascript("""
            (function(t) {
                var el = document.activeElement;
                if (!el) return;
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA') {
                    var s = el.selectionStart, e = el.selectionEnd, v = el.value;
                    if (s == null) { s = v.length; e = v.length; }
                    var nv = v.slice(0, s) + t + v.slice(e);
                    var proto = (tag === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (setter && setter.set) setter.set.call(el, nv); else el.value = nv;
                    var p = s + t.length;
                    try { el.selectionStart = el.selectionEnd = p; } catch(_) {}
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                } else if (el.isContentEditable) {
                    document.execCommand('insertText', false, t);
                }
            })('$t');
        """, null)
    }

    // 個別キー処理
    private fun handleKey(key: String) {
        val js = when (key) {
            "Enter" -> """
                (function(){
                    var el=document.activeElement; if(!el) return;
                    var tag=el.tagName;
                    if(tag==='TEXTAREA'){
                        var s=el.selectionStart,e=el.selectionEnd,v=el.value;
                        var nv=v.slice(0,s)+'\n'+v.slice(e);
                        var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value');
                        if(setter&&setter.set) setter.set.call(el,nv); else el.value=nv;
                        try{el.selectionStart=el.selectionEnd=s+1;}catch(_){}
                        el.dispatchEvent(new Event('input',{bubbles:true}));
                    } else if(el.isContentEditable){
                        document.execCommand('insertLineBreak');
                    } else {
                        ['keydown','keypress','keyup'].forEach(function(t){
                            el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
                        });
                        if(el.form){ try{ el.form.requestSubmit?el.form.requestSubmit():el.form.submit(); }catch(e){} }
                    }
                })();
            """
            "Backspace" -> deleteJs(true)
            "Delete" -> deleteJs(false)
            "ArrowLeft" -> moveCaretJs(-1)
            "ArrowRight" -> moveCaretJs(1)
            "Home" -> homeEndJs(true)
            "End" -> homeEndJs(false)
            "Tab" -> """
                (function(){
                    var el=document.activeElement;
                    var f=Array.prototype.slice.call(document.querySelectorAll('input,textarea,select,button,a[href],[tabindex]'))
                        .filter(function(x){return !x.disabled && x.offsetParent!==null;});
                    var i=f.indexOf(el);
                    if(i>=0 && i+1<f.length){ f[i+1].focus(); }
                    else if(f.length){ f[0].focus(); }
                })();
            """
            "ArrowUp", "ArrowDown" -> """
                (function(){
                    var el=document.activeElement||document.body;
                    ['keydown','keyup'].forEach(function(t){
                        el.dispatchEvent(new KeyboardEvent(t,{key:'$key',code:'$key',bubbles:true,cancelable:true}));
                    });
                })();
            """
            else -> return
        }
        webView.evaluateJavascript(js, null)
    }

    private fun deleteJs(back: Boolean): String = """
        (function(){
            var el=document.activeElement; if(!el) return;
            var tag=el.tagName;
            if(tag==='INPUT'||tag==='TEXTAREA'){
                var s=el.selectionStart, e=el.selectionEnd, v=el.value;
                if(s==null) return;
                var ns, np;
                if(s!==e){ ns=v.slice(0,s)+v.slice(e); np=s; }
                else if($back){ if(s===0) return; ns=v.slice(0,s-1)+v.slice(e); np=s-1; }
                else { if(s>=v.length) return; ns=v.slice(0,s)+v.slice(e+1); np=s; }
                var proto=(tag==='TEXTAREA')?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
                var setter=Object.getOwnPropertyDescriptor(proto,'value');
                if(setter&&setter.set) setter.set.call(el,ns); else el.value=ns;
                try{el.selectionStart=el.selectionEnd=np;}catch(_){}
                el.dispatchEvent(new Event('input',{bubbles:true}));
            } else if(el.isContentEditable){
                document.execCommand($back?'delete':'forwardDelete',false,null);
            }
        })();
    """

    private fun moveCaretJs(delta: Int): String = """
        (function(){
            var el=document.activeElement; if(!el) return;
            if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){
                var s=el.selectionStart,e=el.selectionEnd,len=el.value.length,p;
                if(s!==e){ p=($delta<0)? s : e; }
                else { p=Math.max(0,Math.min(len, s+($delta))); }
                try{el.selectionStart=el.selectionEnd=p;}catch(_){}
            }
        })();
    """

    private fun homeEndJs(home: Boolean): String = """
        (function(){
            var el=document.activeElement; if(!el) return;
            if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){
                var p=$home?0:el.value.length;
                try{el.selectionStart=el.selectionEnd=p;}catch(_){}
            }
        })();
    """

    // JS文字列リテラル用エスケープ
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

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
