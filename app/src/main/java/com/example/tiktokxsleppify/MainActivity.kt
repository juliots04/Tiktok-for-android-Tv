package com.example.tiktokxsleppify

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabColorSchemeParams

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var googleLoginWebView: WebView? = null  // WebView separado para login de Google
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var retryButton: Button
    private var preloadedBridgeScript: String = ""

    // --- Bloqueo de telemetría/ads para acelerar el arranque en hardware débil ---
    // Recorta peticiones de red y trabajo JS que no afectan al feed.
    // ⚠️ Si el feed dejara de cargar, pon blockTrackers = false.
    private val blockTrackers = true
    private val blockedHosts = listOf(
        "google-analytics.com", "googletagmanager", "doubleclick",
        "googlesyndication", "scorecardresearch.com", "facebook.net",
        "analytics.tiktok.com", "mon.tiktok.com", "mcs.tiktok.com", "stat.tiktok.com", "log.tiktok.com",
        "mon.tiktokv.com", "mon-va.tiktokv.com", "mcs.tiktokv.com", "mcs-va.tiktokv.com",
        "log.tiktokv.com", "log-va.tiktokv.com"
    )

    // Virtual Mouse State
    private var isVMEnabled = false
    private var vmView: View? = null
    private var vmHotspotX = 0f // desfase del punto de click respecto a la esquina de la vista
    private var vmHotspotY = 0f
    private var vmX = 960f // center of 1080p
    private var vmY = 540f
    private var isVmHoldingOk = false
    private val vmHandler = Handler(Looper.getMainLooper())
    
    // Physics properties for Virtual Mouse
    private var vmVx = 0f
    private var vmVy = 0f
    private var vmAccX = 0f
    private var vmAccY = 0f
    private val vmMaxSpeed = 26f // Cruza 1080p en ~1.2s, controlable
    private val vmAcceleration = 1.6f // Respuesta viva sin ser brusca
    private val vmFriction = 0.82f // Inercia estilo LG: deslizamiento ~120px tras soltar

    private var touchDownTime = 0L

    private val okLongPressRunnable = Runnable {
        toggleVirtualMouse()
    }

    private val vMLoop = object : Runnable {
        override fun run() {
            if (isVMEnabled) {
                // Física: aplicar aceleración a la velocidad
                vmVx += vmAccX
                vmVy += vmAccY

                // Física: aplicar fricción para una parada suave progresiva (solo si no hay aceleración activa)
                if (vmAccX == 0f) vmVx *= vmFriction
                if (vmAccY == 0f) vmVy *= vmFriction
                
                // Limitar velocidad punta
                vmVx = vmVx.coerceIn(-vmMaxSpeed, vmMaxSpeed)
                vmVy = vmVy.coerceIn(-vmMaxSpeed, vmMaxSpeed)

                // Zona muerta: sin aceleración, asienta la velocidad residual a cero (no repta sub-pixel)
                if (vmAccX == 0f && Math.abs(vmVx) < 0.4f) vmVx = 0f
                if (vmAccY == 0f && Math.abs(vmVy) < 0.4f) vmVy = 0f

                // Renderizar y aplicar límites con reseteo de velocidad en colisión
                if (Math.abs(vmVx) > 0.4f || Math.abs(vmVy) > 0.4f) {
                    val nextX = vmX + vmVx
                    val nextY = vmY + vmVy
                    
                    // Colisión X
                    if (nextX < 0f || nextX > webView.width.toFloat()) {
                        vmVx = 0f
                        vmX = nextX.coerceIn(0f, webView.width.toFloat())
                    } else {
                        vmX = nextX
                    }
                    
                    // Colisión Y
                    if (nextY < 0f || nextY > webView.height.toFloat()) {
                        vmVy = 0f
                        vmY = nextY.coerceIn(0f, webView.height.toFloat())
                    } else {
                        vmY = nextY
                    }

                    vmView?.translationX = vmX - vmHotspotX
                    vmView?.translationY = vmY - vmHotspotY

                    if (isVmHoldingOk) {
                        dispatchNativeTouch(MotionEvent.ACTION_MOVE)
                    }
                }
                vmHandler.postDelayed(this, 16) // ~60fps
            }
        }
    }

    companion object {
        private const val TAG = "TikTokTV"
        private const val TIKTOK_URL = "https://www.tiktok.com/foryou"
        private const val REQUEST_CODE_GOOGLE_LOGIN = 1001
    }

    // Variable para almacenar la URL de retorno después del login de Google
    private var pendingGoogleReturnUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        
        try {
            preloadedBridgeScript = assets.open("tiktok_tv_bridge.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload JS bridge", e)
        }
        
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        retryButton = findViewById(R.id.retryButton)

        setupWebView()

        retryButton.setOnClickListener {
            errorOverlay.visibility = View.GONE
            loadingOverlay.visibility = View.VISIBLE
            webView.reload()
        }

        webView.loadUrl(TIKTOK_URL)
    }

    private fun setupFullscreen() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // EXPERIMENTO RENDIMIENTO: el manifest ya tiene hardwareAccelerated=true, así que el WebView
        // usa el compositor GPU de Chromium. Forzar una capa de hardware sobre TODO el WebView obligaba
        // a re-subir una textura del tamaño de la pantalla en cada frame del feed (GPU Mali débil).
        // LAYER_TYPE_NONE mantiene la aceleración por GPU sin esa penalización.
        // ⚠️ Si el VIDEO se pusiera EN NEGRO en la Xiaomi, revertir esta línea a LAYER_TYPE_HARDWARE.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // BUGFIX RENDIMIENTO: LOAD_DEFAULT priorizará la memoria real si es fresca, 
            // evitando el infierno de lectura al disco I/O forzado de LOAD_CACHE_ELSE_NETWORK.
            cacheMode = WebSettings.LOAD_DEFAULT 
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            
            // Restore WideViewport so Desktop layout has enough logical width (fixes left bar clipping)
            useWideViewPort = true
            loadWithOverviewMode = true

            // Optimización adicional para Android TV: mantiene el raster listo (menos parpadeo)
            offscreenPreRaster = true
            
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { safeBrowsingEnabled = false }
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Solo exponer el socket de depuración en builds debug (seguridad + sin overhead en producción)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {

            // Corta telemetría/ads antes de que peguen a la red: arranque más ligero.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (blockTrackers) {
                    val host = request?.url?.host ?: ""
                    if (host.isNotEmpty() && blockedHosts.any { host.contains(it) }) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Interceptar URLs de Google y abrirlas en Chrome Custom Tabs
                if (url.contains("accounts.google.com") || 
                    url.contains("accounts.youtube.com") ||
                    url.contains("google.com/signin") ||
                    url.contains("google.com/o/oauth2")) {
                    
                    Log.d(TAG, "Interceptando URL de Google: $url")
                    openGoogleLoginInCustomTabs(url)
                    return true
                }
                
                return false
            }
            
            private fun openGoogleLoginInCustomTabs(originalUrl: String) {
                // Google bloquea todos los WebViews y Custom Tabs para login OAuth
                // Mostrar directamente alternativa al usuario
                showGoogleLoginAlternative()
            }
            
            private fun showGoogleLoginAlternative() {
                val message = """
                    El login con Google está restringido en dispositivos Android TV.
                    
                    Por favor, usa una de estas opciones:
                    • Email y contraseña
                    • Número de teléfono
                    • Facebook
                    • Twitter
                    
                    ¿Deseas ir a la página de login alternativa?
                """.trimIndent()
                
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Login con Google no disponible")
                    .setMessage(message)
                    .setPositiveButton("Ir a Login") { _, _ ->
                        webView.loadUrl("https://www.tiktok.com/login")
                    }
                    .setNegativeButton("Cancelar") { dialog, _ ->
                        dialog.dismiss()
                        if (webView.canGoBack()) webView.goBack()
                    }
                    .setCancelable(false)
                    .show()
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                
                // No inyectar scripts en URLs de Google (por si acaso llegan a cargarse)
                if (url != null && (url.contains("accounts.google.com") || url.contains("google.com/signin"))) {
                    return
                }

                // FIX RECORTE/"detecta como celular": el código anterior hacía document.head.appendChild
                // en onPageStarted, pero ahí el head suele ser null -> el try/catch lo tragaba y el
                // viewport 1280 NUNCA se aplicaba, así que TikTok caía en su layout angosto (~960px) con
                // el video recortado (cover). Esta versión es head-safe, BORRA el viewport de TikTok y
                // fuerza uno solo (width=1280 -> 1280x720 = 16:9, overview mode lo ajusta sin recortar).
                val antiTouchScript = """
                    (function() {
                        try {
                            Object.defineProperty(navigator, 'maxTouchPoints', { configurable: true, get: function () { return 0; } });
                            Object.defineProperty(navigator, 'msMaxTouchPoints', { configurable: true, get: function () { return 0; } });
                            if ('ontouchstart' in window) { try { delete window.ontouchstart; } catch (e) {} }
                        } catch (e) {}
                        function forceVP() {
                            try {
                                var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                                if (!head) return;
                                var old = document.querySelectorAll('meta[name="viewport"]');
                                for (var i = 0; i < old.length; i++) { if (old[i].parentNode) old[i].parentNode.removeChild(old[i]); }
                                var m = document.createElement('meta');
                                m.name = 'viewport';
                                m.content = 'width=1280';
                                head.appendChild(m);
                            } catch (e) {}
                        }
                        forceVP();
                        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', forceVP, { once: true });
                    })();
                """.trimIndent()
                view?.evaluateJavascript(antiTouchScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBridge()
                // Mantener la carga hasta que un video esté realmente listo (o tope de seguridad)
                webView.postDelayed({ hideLoadingWhenReady(0) }, 800)
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loadingOverlay.visibility = View.GONE
                    errorOverlay.visibility = View.VISIBLE
                }
            }
            
        } // Fin del WebViewClient
        webView.webChromeClient = WebChromeClient()
    }
    
    private fun openInExternalBrowser(url: String) {
        try {
            var cleanUrl = url.replace("&authError=", "").replace("?authError=", "?")
            
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(cleanUrl))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.setPackage("com.android.chrome")
            
            try {
                startActivity(intent)
            } catch (e: Exception) {
                intent.setPackage(null)
                startActivity(intent)
            }
            
            pendingGoogleReturnUrl = TIKTOK_URL
            Toast.makeText(this@MainActivity, "Abriendo Chrome externo...", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir navegador", e)
            // Mostrar mensaje al usuario para usar login alternativo
            Toast.makeText(this@MainActivity, 
                "Google Login no disponible. Usa email/contraseña o número de teléfono.", 
                Toast.LENGTH_LONG).show()
            // Redirigir al login normal de TikTok
            webView.loadUrl("https://www.tiktok.com/login")
        }
    }
    
    private fun removeGoogleLoginWebView() {
        googleLoginWebView?.let { wv ->
            val root = findViewById<View>(android.R.id.content) as? FrameLayout
            root?.removeView(wv)
            wv.destroy()
        }
        googleLoginWebView = null
    }

    private fun hideLoadingWhenReady(attempt: Int) {
        if (loadingOverlay.visibility != View.VISIBLE) return
        // Oculta la pantalla de carga solo cuando ya hay un video con datos (o al llegar al tope)
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return (v && v.readyState>=2 && v.videoWidth>0)?'ready':'wait';})()"
        ) { result ->
            val ready = result != null && result.contains("ready")
            if (ready || attempt >= 14) { // tope ~ 800ms + 14*500ms ≈ 7.8s
                loadingOverlay.visibility = View.GONE
            } else {
                webView.postDelayed({ hideLoadingWhenReady(attempt + 1) }, 500)
            }
        }
    }

    private fun injectBridge() {
        if (preloadedBridgeScript.isEmpty()) return
        try {
            webView.evaluateJavascript(preloadedBridgeScript) { result ->
                webView.postDelayed({ sendBridgeCommand("playIfPaused") }, 1500)
            }
        } catch (e: Exception) {}
    }

    private fun sendBridgeCommand(command: String) {
        val js = "if(window.TikTokTV && window.TikTokTV.$command) { window.TikTokTV.$command(); } else { 'NOT_FOUND'; }"
        webView.evaluateJavascript(js) {}
    }

    // --- VIRTUAL MOUSE IMPLEMENTATION ---
    private fun toggleVirtualMouse() {
        isVMEnabled = !isVMEnabled
        if (isVMEnabled) {
            setupVMViewIfNeeded()
            vmX = webView.width / 2f
            vmY = webView.height / 2f
            vmView?.visibility = View.VISIBLE
            vmView?.translationX = vmX - vmHotspotX
            vmView?.translationY = vmY - vmHotspotY
            vmHandler.post(vMLoop)
        } else {
            vmView?.visibility = View.GONE
            vmHandler.removeCallbacks(vMLoop)
            vmAccX = 0f
            vmAccY = 0f
            vmVx = 0f
            vmVy = 0f
            if (isVmHoldingOk) {
                isVmHoldingOk = false
                dispatchNativeTouch(MotionEvent.ACTION_UP)
                vmView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.alpha(1.0f)?.setDuration(150)?.start()
            }
        }
    }

    private fun setupVMViewIfNeeded() {
        if (vmView == null) {
            // Tamaño escalado al panel (no 50px fijos): ~59px @1080p, tope para paneles 4K
            val size = (resources.displayMetrics.heightPixels * 0.055f).toInt().coerceIn(56, 110)
            // Hotspot = punta del pin (arriba-izquierda) = esquina (0,0) de la vista
            vmHotspotX = 0f
            vmHotspotY = 0f
            vmView = object : View(this) {
                // Pin de mapa magenta apuntando ARRIBA-IZQUIERDA (sin fantasma cian, sin borde blanco)
                private val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FE2C55")
                    style = android.graphics.Paint.Style.FILL
                    setShadowLayer(size * 0.10f, 0f, size * 0.05f, Color.parseColor("#66000000"))
                }
                // Agujerito central (efecto anillo del pin)
                private val hole = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#40000000")
                    style = android.graphics.Paint.Style.FILL
                }
                private val path = android.graphics.Path()
                private val tri = android.graphics.Path()

                private fun buildPin(w: Float) {
                    val bcx = w * 0.53f
                    val bcy = w * 0.53f
                    val bR = w * 0.42f
                    path.reset()
                    path.addCircle(bcx, bcy, bR, android.graphics.Path.Direction.CW)
                    tri.reset()
                    tri.moveTo(0f, 0f) // PUNTA arriba-izquierda = hotspot exacto
                    tri.lineTo(bcx - bR * 0.28f, bcy - bR * 0.88f)
                    tri.lineTo(bcx - bR * 0.88f, bcy - bR * 0.28f)
                    tri.close()
                    path.op(tri, android.graphics.Path.Op.UNION)
                }

                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val w = width.toFloat()
                    buildPin(w)
                    canvas.drawPath(path, body)
                    canvas.drawCircle(w * 0.53f, w * 0.53f, w * 0.15f, hole)
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                elevation = 100f
                // Capa software: garantiza que setShadowLayer dibuje la sombra
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            val root = findViewById<View>(android.R.id.content) as? FrameLayout
            root?.addView(vmView)
        }
    }

    private fun dispatchNativeTouch(action: Int) {
        val eventTime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = eventTime
        val event = MotionEvent.obtain(touchDownTime, eventTime, action, vmX, vmY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun handleBack() {
        // La SPA cierra lo que tenga abierto (sidebar, modal, la X del modal, Escape).
        // Si de verdad no hay nada que cerrar, sale de la app.
        webView.evaluateJavascript(
            "if(window.TikTokTV && window.TikTokTV.back) { window.TikTokTV.back(); } else { 'nav_back'; }"
        ) { result ->
            val handled = result != null && result != "\"nav_back\"" && result != "null"
            if (!handled) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.repeatCount == 0) {
                    vmHandler.postDelayed(okLongPressRunnable, 800)
                    if (isVMEnabled) {
                        isVmHoldingOk = true
                        dispatchNativeTouch(MotionEvent.ACTION_DOWN)
                        // Feedbacks Visuales: Sutil pinza estilo LG
                        vmView?.animate()?.scaleX(0.85f)?.scaleY(0.85f)?.alpha(0.8f)?.setDuration(120)?.start()
                    }
                }
                return true
            }

            if (isVMEnabled) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        vmAccY = -vmAcceleration
                        vmHandler.removeCallbacks(okLongPressRunnable)
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        vmAccY = vmAcceleration
                        vmHandler.removeCallbacks(okLongPressRunnable)
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        vmAccX = -vmAcceleration
                        vmHandler.removeCallbacks(okLongPressRunnable)
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        vmAccX = vmAcceleration
                        vmHandler.removeCallbacks(okLongPressRunnable)
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        handleBack()
                    }
                }
                return true
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { sendBridgeCommand("scrollDown"); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { sendBridgeCommand("scrollUp"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { sendBridgeCommand("enterSidebar"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { sendBridgeCommand("exitSidebar"); return true }
                KeyEvent.KEYCODE_BACK -> {
                    handleBack()
                    return true
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                vmHandler.removeCallbacks(okLongPressRunnable)
                if (isVMEnabled) {
                    if (isVmHoldingOk) {
                        dispatchNativeTouch(MotionEvent.ACTION_UP)
                        isVmHoldingOk = false
                        // Feedback Visual: Retaurar tamaño
                        vmView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.alpha(1.0f)?.setDuration(250)?.start()
                    }
                } else {
                    if (event.eventTime - event.downTime < 800) {
                        if (errorOverlay.visibility == View.VISIBLE) retryButton.performClick()
                        else sendBridgeCommand("select")
                    }
                }
                return true
            }

            if (isVMEnabled) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        vmAccY = 0f // sin vmVy=0f: deja que la fricción lo deslice suave (estilo LG)
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        vmAccX = 0f // sin vmVx=0f: glide con inercia en vez de freno en seco
                    }
                }
                return true
            }

            // Aislador Anti-Fugas: consumir D-PAD soltado para evitar que el navegador asuma control nativo (como retroceder a otro TikTok)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, 
                KeyEvent.KEYCODE_BACK -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
        
        // Recargar la página cuando regresa del login de Google para reflejar el estado de autenticación
        if (pendingGoogleReturnUrl != null) {
            webView.loadUrl(pendingGoogleReturnUrl ?: TIKTOK_URL)
            pendingGoogleReturnUrl = null
        }
    }

    override fun onDestroy() {
        vmHandler.removeCallbacksAndMessages(null)
        removeGoogleLoginWebView()
        webView.destroy()
        super.onDestroy()
    }
    
    override fun onBackPressed() {
        // Si hay un WebView de Google Login activo, cerrarlo primero
        if (googleLoginWebView != null) {
            removeGoogleLoginWebView()
            return
        }
        super.onBackPressed()
    }
}