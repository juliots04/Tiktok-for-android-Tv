package com.example.tiktokxsleppify

import android.annotation.SuppressLint
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
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
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
    private lateinit var dotRed: View
    private lateinit var dotCyan: View
    private lateinit var errorOverlay: LinearLayout
    private lateinit var retryButton: Button
    private var preloadedBridgeScript: String = ""

    // Virtual Mouse State
    private var isVMEnabled = false
    private var vmView: View? = null
    private var vmX = 960f // center of 1080p
    private var vmY = 540f
    private var isVmHoldingOk = false
    private val vmHandler = Handler(Looper.getMainLooper())
    
    // Physics properties for Virtual Mouse
    private var vmVx = 0f
    private var vmVy = 0f
    private var vmAccX = 0f
    private var vmAccY = 0f
    private val vmMaxSpeed = 22f // Tope más controlado
    private val vmAcceleration = 1.2f // Aceleración muy suave para precisión
    private val vmFriction = 0.8f // Fricción interna

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

                // Renderizar y aplicar límites con reseteo de velocidad en colisión
                if (Math.abs(vmVx) > 0.1f || Math.abs(vmVy) > 0.1f) {
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

                    vmView?.translationX = vmX
                    vmView?.translationY = vmY

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
        dotRed = findViewById(R.id.dotRed)
        dotCyan = findViewById(R.id.dotCyan)
        startDotsAnimation()
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

    private fun startDotsAnimation() {
        val animRed = ObjectAnimator.ofFloat(dotRed, "translationX", -15f, 15f)
        animRed.duration = 600
        animRed.repeatCount = ObjectAnimator.INFINITE
        animRed.repeatMode = ObjectAnimator.REVERSE
        animRed.interpolator = AccelerateDecelerateInterpolator()

        val animCyan = ObjectAnimator.ofFloat(dotCyan, "translationX", 15f, -15f)
        animCyan.duration = 600
        animCyan.repeatCount = ObjectAnimator.INFINITE
        animCyan.repeatMode = ObjectAnimator.REVERSE
        animCyan.interpolator = AccelerateDecelerateInterpolator()

        animRed.start()
        animCyan.start()
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
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
            
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { safeBrowsingEnabled = false }
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(AndroidHost(), "AndroidHost")

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {
            
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

                val antiTouchScript = """
                    (function() {
                        try {
                            // Spoof pure PC
                            Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });
                            Object.defineProperty(navigator, 'msMaxTouchPoints', { get: () => 0 });
                            if ('ontouchstart' in window) delete window.ontouchstart;
                            
                            // Forzar Viewport Ancho de Escritorio para que la UI no se colapse en pantallas de baja densidad
                            var meta = document.createElement('meta');
                            meta.name = 'viewport';
                            meta.content = 'width=1280, initial-scale=1';
                            document.head.appendChild(meta);
                        } catch (e) {}
                    })();
                """.trimIndent()
                view?.evaluateJavascript(antiTouchScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBridge()
                webView.postDelayed({ loadingOverlay.visibility = View.GONE }, 1000)
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
            vmView?.translationX = vmX
            vmView?.translationY = vmY
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
            val size = 50 // LG Magic Remote scale
            vmView = object : View(this) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FA1859") // LG Pink
                    style = android.graphics.Paint.Style.FILL
                    setShadowLayer(8f, 0f, 4f, Color.parseColor("#80000000"))
                }
                val path = android.graphics.Path()

                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val w = width.toFloat()
                    val h = height.toFloat()
                    
                    val cx = w * 0.55f
                    val cy = h * 0.55f
                    val radius = w * 0.35f
                    
                    path.reset()
                    // Cuerpo circular
                    path.addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
                    
                    // Deformar hacia una punta triangular gruesa integrada
                    val arrow = android.graphics.Path()
                    arrow.moveTo(w * 0.15f, h * 0.15f) // Punta
                    arrow.lineTo(cx + radius * 0.6f, cy - radius * 0.5f) // Lateral superior (más abierto/grueso)
                    arrow.lineTo(cx - radius * 0.5f, cy + radius * 0.6f) // Lateral inferior (más abierto/grueso)
                    arrow.close()
                    
                    path.op(arrow, android.graphics.Path.Op.UNION)
                    canvas.drawPath(path, paint)
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                elevation = 100f
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
                        webView.evaluateJavascript("if(window.TikTokTV && window.TikTokTV.back) { window.TikTokTV.back(); } else { 'nav_back'; }") { result ->
                            if (result == "\"nav_back\"" || result == "null") {
                                if (webView.canGoBack()) webView.goBack() else finish()
                            }
                        }
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
                    webView.evaluateJavascript("if(window.TikTokTV && window.TikTokTV.back) { window.TikTokTV.back(); } else { 'nav_back'; }") { result ->
                        if (result == "\"nav_back\"" || result == "null") {
                            if (webView.canGoBack()) webView.goBack() else finish()
                        }
                    }
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
                        vmAccY = 0f
                        vmVy = 0f // Freno en seco al soltar
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        vmAccX = 0f
                        vmVx = 0f // Freno en seco al soltar
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

    inner class AndroidHost {
        @JavascriptInterface
        fun saveDOM(html: String) {
            try {
                val file = java.io.File(getExternalFilesDir(null), "dom_dump.html")
                file.writeText(html)
                android.util.Log.d("TikTokTV", "DOM SAVED TO " + file.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}