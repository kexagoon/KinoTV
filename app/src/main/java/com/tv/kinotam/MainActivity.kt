package com.tv.kinotam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    
    // --- ПЕРЕМЕННЫЕ ДЛЯ МЕНЮ ---
    private lateinit var selectionMenu: LinearLayout
    private lateinit var btnKinotut: LinearLayout
    private lateinit var btnGidonline: LinearLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    
    private var activeSearchInputId: String? = null 
    private val VOICE_SEARCH_REQUEST_CODE = 100

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningDialog: AlertDialog? = null

    // --- ПЕРЕМЕННЫЕ ДЛЯ ВИРТУАЛЬНОЙ МЫШКИ ---
    private lateinit var cursorView: View
    private var cursorX = 0f
    private var cursorY = 0f
    
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val hideCursorRunnable = Runnable {
        cursorView.animate().alpha(0f).setDuration(500).start()
    }

    private val adBlockerScript: String = """
        (function() {
            function blockAds() {
                var closeButtons = document.querySelectorAll('.ad-close, .close-btn, [class*="close_button"], [id*="close_button"], .popunder-close, .js-popunder-close');
                for (var i = 0; i < closeButtons.length; i++) { closeButtons[i].click(); }
                var ads = document.querySelectorAll('div[id^="ad-"], div[class*="-banner"], div[id*="ad_overlay"], iframe[src*="telegram"]');
                for (var j = 0; j < ads.length; j++) { ads[j].style.display = 'none'; }
                var fixedBanners = document.querySelectorAll('div[style*="position: fixed"][style*="bottom: 0"], div[style*="position:absolute"][style*="bottom: 0"]');
                for (var k = 0; k < fixedBanners.length; k++) { fixedBanners[k].style.display = 'none'; }
            }
            setInterval(blockAds, 500); 
            blockAds();
        })();
    """.trimIndent()   
	 private val tvInterfaceJs: String = """
        javascript:(function() {
            function addMicButtons() {
                // Проверяем, на каком сайте мы сейчас находимся
                var isKinotut = window.location.hostname.includes("kinotam");
                
                var inputs = document.querySelectorAll('input[type="search"], input[type="text"]');
                for (var i = 0; i < inputs.length; i++) {
                    var input = inputs[i];
                    
                    var n = (input.name || '').toLowerCase();
                    var id = (input.id || '').toLowerCase();
                    var p = (input.placeholder || '').toLowerCase();
                    var c = (input.className || '').toLowerCase();
                    
                    var isSearch = n.includes("search") || id.includes("search") || p.includes("поиск") || 
                                   n === "s" || id === "s" || n === "story" || id === "story" || 
                                   id.includes("sbox") || n === "q" || id === "q" || c.includes("search");
                                   
                    if (isSearch) {
                        if (!input.id) input.id = 'tv_search_' + Math.random().toString(36).substring(7);
                        if (input.hasAttribute('data-mic-added')) continue;
                        input.setAttribute('data-mic-added', 'true');

                        var micBtn = document.createElement('button');
                        
                        if (isKinotut) {
                            // СТАРЫЙ СТИЛЬ: Большая кнопка "Голос" для КиноТут
                            micBtn.innerHTML = '🎤 Голос';
                            micBtn.style.cssText = 'background: #E91E63 !important; color: white !important; border: 2px solid white !important; padding: 6px 15px !important; margin-left: 10px !important; border-radius: 8px !important; font-size: 16px !important; cursor: pointer !important; vertical-align: middle !important;';
                        } else {
                            // НОВЫЙ СТИЛЬ: Компактный микрофон для ГидОнлайн и остальных
                            micBtn.innerHTML = '🎤';
                            var h = input.offsetHeight > 15 ? input.offsetHeight : 30;
                            micBtn.style.cssText = 'background: #E91E63 !important; color: white !important; border: none !important; padding: 0 12px !important; margin-left: 5px !important; border-radius: 4px !important; font-size: 16px !important; cursor: pointer !important; height: ' + h + 'px !important; vertical-align: middle !important;';
                            input.parentNode.style.whiteSpace = 'nowrap'; 
                        }
                        
                        micBtn.type = 'button'; 
                        
                        (function(inputId) {
                            micBtn.onclick = function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                TVBridge.startVoiceSearchFromWeb(inputId);
                            };
                        })(input.id);
                        
                        input.parentNode.insertBefore(micBtn, input.nextSibling);
                    }
                }
            }
            setInterval(addMicButtons, 1000);
            addMicButtons();
        })();
    """.trimIndent()




    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        progressBar = findViewById(R.id.progressBar)

        // Инициализация меню
        selectionMenu = findViewById(R.id.selectionMenu)
        btnKinotut = findViewById(R.id.btnKinotut)
        btnGidonline = findViewById(R.id.btnGidonline)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.addJavascriptInterface(this, "TVBridge")

        setupSpeechRecognizer()
        setupVirtualCursor() 
        
        // Прячем курсор мыши, пока открыто меню
        cursorView.visibility = View.GONE

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(adBlockerScript, null)
                view?.evaluateJavascript(tvInterfaceJs, null) 
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE
                fullscreenContainer.addView(view)
                
                cursorHandler.removeCallbacks(hideCursorRunnable)
                hideCursorRunnable.run()
            }

            override fun onHideCustomView() {
                super.onHideCustomView()
                if (customView == null) return
                webView.visibility = View.VISIBLE
                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeView(customView)
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
                
                wakeUpCursor()
            }
        }

        // Настраиваем кнопки меню
        setupMenuTile(btnKinotut, "https://kinotam.org/tv-serials/")
        setupMenuTile(btnGidonline, "https://io.gidonline.site/")
    }

    private fun setupMenuTile(tile: View, url: String) {
        tile.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
            } else {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }

        tile.setOnClickListener {
            selectionMenu.visibility = View.GONE
            webView.visibility = View.VISIBLE
            cursorView.visibility = View.VISIBLE 
            webView.loadUrl(url)
            webView.requestFocus()
        }
    }

    // --- ЛОГИКА ВИРТУАЛЬНОЙ МЫШКИ ---

    private fun setupVirtualCursor() {
        cursorView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(36, 36)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E91E63")) 
                setStroke(4, Color.WHITE)
            }
            elevation = 100f
            translationZ = 100f
        }
        
        val rootView = findViewById<FrameLayout>(android.R.id.content)
        rootView.addView(cursorView)

        val metrics = resources.displayMetrics
        cursorX = metrics.widthPixels / 2f
        cursorY = metrics.heightPixels / 2f
        updateCursorPosition()
        
        wakeUpCursor() 
    }

    private fun updateCursorPosition() {
        cursorView.x = cursorX
        cursorView.y = cursorY
    }

    private fun wakeUpCursor() {
        if (cursorView.alpha < 1f) {
            cursorView.animate().alpha(1f).setDuration(150).start()
        }
        cursorHandler.removeCallbacks(hideCursorRunnable)
        cursorHandler.postDelayed(hideCursorRunnable, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Если меню выбора видимо, пульт управляет плитками, а не мышкой
        if (::selectionMenu.isInitialized && selectionMenu.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val speed = 45f 
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0f, speed); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0f, -speed); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-speed, 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(speed, 0f); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        wakeUpCursor() 

        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()

        cursorX += dx
        cursorY += dy

        if (cursorX < 0f) cursorX = 0f
        if (cursorX > screenW - 36f) cursorX = screenW - 36f

        if (cursorY < 0f) cursorY = 0f
        if (cursorY > screenH - 36f) cursorY = screenH - 36f

        if (customView == null) {
            if (cursorY < 100f) {
                webView.scrollBy(0, -50)
            } else if (cursorY > screenH - 100f) {
                webView.scrollBy(0, 50)
            }
        }

        updateCursorPosition()
    }

    private fun clickAtCursor() {
        wakeUpCursor() 

        cursorView.animate().scaleX(0.6f).scaleY(0.6f).setDuration(100).withEndAction {
            cursorView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()

        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val clickX = cursorX + 18f
        val clickY = cursorY + 18f

        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, clickX, clickY, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime + 50, MotionEvent.ACTION_UP, clickX, clickY, 0)

        val targetView = if (customView != null) fullscreenContainer else webView
        targetView.dispatchTouchEvent(downEvent)
        targetView.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    // --- ЛОГИКА ГОЛОСОВОГО ПОИСКА И ПРОЧЕЕ ---

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listeningDialog?.dismiss() }
                override fun onError(error: Int) { listeningDialog?.dismiss() }
                
                override fun onResults(results: Bundle?) {
                    listeningDialog?.dismiss()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && activeSearchInputId != null) {
                        val recognizedText = matches[0]
                        injectTextToWebsite(recognizedText)
                    }
                }
                
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startDirectVoiceRecognition() {
        if (speechRecognizer == null) {
            AlertDialog.Builder(this).setMessage("Голосовой ввод не поддерживается.").show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }

        listeningDialog = AlertDialog.Builder(this)
            .setTitle("Голосовой поиск")
            .setMessage("Говорите прямо сейчас...\n(На аэромыши нужно зажать кнопку микрофона)")
            .setCancelable(true)
            .setOnCancelListener { speechRecognizer?.cancel() }
            .show()

        speechRecognizer?.startListening(intent)
    }

    private fun injectTextToWebsite(text: String) {
        val js = """
            javascript:(function() {
                var el = document.getElementById('$activeSearchInputId');
                if (el) {
                    el.value = '$text';
                    var event = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true });
                    el.dispatchEvent(event);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        activeSearchInputId = null
    }

    @JavascriptInterface
    fun startVoiceSearchFromWeb(inputId: String) {
        activeSearchInputId = inputId
        runOnUiThread { checkPermissionAndStartVoiceRecognition() }
    }

    private fun checkPermissionAndStartVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), VOICE_SEARCH_REQUEST_CODE)
        } else {
            startDirectVoiceRecognition()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VOICE_SEARCH_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDirectVoiceRecognition()
            } else {
                AlertDialog.Builder(this).setMessage("Нужен доступ к микрофону.").setPositiveButton("ОК", null).show()
            }
        }
    }
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        
        // Если мы на сайте (меню плиток скрыто)
        if (selectionMenu.visibility == View.GONE) {
            val dialogBuilder = AlertDialog.Builder(this)
                .setTitle("Навигация")
                .setMessage("Что вы хотите сделать?")
                
            // Кнопка "Назад" (возврат на предыдущую страницу сайта) - показываем, только если есть куда возвращаться
            if (webView.canGoBack()) {
                dialogBuilder.setPositiveButton("Назад") { _, _ ->
                    webView.goBack()
                }
            }
            
            // Кнопка "На главную" (возврат к нашему меню с плитками)
            dialogBuilder.setNeutralButton("На главную") { _, _ ->
                webView.visibility = View.GONE
                cursorView.visibility = View.GONE
                webView.loadUrl("about:blank") 
                selectionMenu.visibility = View.VISIBLE
                btnKinotut.requestFocus() // Фокус на первую плитку
            }
            
            // Кнопка "Отмена" (просто закрыть окно и остаться смотреть фильм)
            dialogBuilder.setNegativeButton("Отмена", null)
            
            dialogBuilder.show()
        } else {
            // Если мы УЖЕ в главном меню с плитками
            AlertDialog.Builder(this)
                .setTitle("Выход")
                .setMessage("Вы действительно хотите выйти из приложения?")
                .setPositiveButton("Да") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Нет", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        cursorHandler.removeCallbacks(hideCursorRunnable) 
    }
}
