package com.example.epubreader

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.epubreader.databinding.ActivityMainBinding

/**
 * 唯一入口：WebView 加载 EPUB 阅读器。
 * 负责 WebView 配置、JS 桥、沉浸式、手势翻页、音量键翻页、返回键行为。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 通过 WebViewAssetLoader 同源加载 assets 里的阅读器 */
        const val ASSET_READER_URL =
            "https://appassets.androidplatform.net/assets/reader-bookshelf.html"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var bridge: EpubBridge
    private lateinit var immersive: ImmersiveController
    private val store by lazy { EpubStore(this) }

    /** 由 JS 通过 bridge.notifyState 汇报：true=阅读器渲染中，false=书架 */
    var isReaderActive = false

    /** 手机下拉菜单是否打开（打开时禁用点击翻页/沉浸，避免误操作） */
    var menuOpen = false

    private var currentWebUrl: String? = null

    /** 返回键兜底：调用 showBookshelf 后若 JS 长时间无响应则直接退出 */
    private var returnPending = false

    /** SAF 文件选择器：选中的 EPUB 交给 bridge 导入 */
    private val openDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) bridge.onImportResult(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = binding.webView
        immersive = ImmersiveController(this, binding.root)
        bridge = EpubBridge(this, webView, store) {
            openDocLauncher.launch(arrayOf("*/*"))
        }

        configureWebView()
        setupBackPressed()

        val savedUrl = savedInstanceState?.getString("current_url")
        if (savedUrl != null) {
            currentWebUrl = savedUrl
            webView.loadUrl(savedUrl)
        } else {
            loadAssetReader()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true        // localStorage：设置/元数据/阅读进度
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100                  // 避免 WebView 字体缩放干扰 epub.js 布局
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE // 强制加载最新 assets，避免缓存旧版 HTML
        }
        webView.clearCache(true) // 清除 WebView 缓存，确保读取最新文件
        webView.webViewClient = EpubWebViewClient(this, store)
        webView.webChromeClient = EpubWebChromeClient(this)
        webView.setBackgroundColor(0xFF1A1A1A.toInt())
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        // 禁用 WebView 自带的 overscroll 回弹（避免分页模式下内容被上下拖动）
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // 禁用 WebView 自带滚动条：阅读器分页模式，滑动只用于翻页，不显示滚动条
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        GestureController(
            context = this,
            webView = webView,
            onFlingLeft = { if (isReaderActive && !menuOpen) evalJs("window.nextPage && window.nextPage()") },
            onFlingRight = { if (isReaderActive && !menuOpen) evalJs("window.prevPage && window.prevPage()") },
            onEdgeTapLeft = { x, y ->
                if (isReaderActive) handleTap(x, y) { evalJs("window.prevPage && window.prevPage()") }
            },
            onEdgeTapRight = { x, y ->
                if (isReaderActive) handleTap(x, y) { evalJs("window.nextPage && window.nextPage()") }
            },
            onCenterTap = { x, y ->
                if (isReaderActive) handleTap(x, y) {
                    immersive.toggle()
                    evalJs("window.toggleChrome && window.toggleChrome()")
                }
            }
        ).attach()
    }

    private fun evalJs(script: String) {
        webView.evaluateJavascript(script, null)
    }

    /**
     * 点击正文的统一入口：若目录侧栏开着且点击在正文区域，先关闭它并跳过本次操作；
     * 若点击在目录侧栏内（选章节），不关闭也不翻页，交由网页跳转。
     */
    private fun handleTap(x: Float, y: Float, fn: () -> Unit) {
        webView.evaluateJavascript(
            "(function(x,y){var dpr=window.devicePixelRatio||1;var cx=x/dpr,cy=y/dpr;" +
                "function inRect(el){if(!el)return false;var r=el.getBoundingClientRect();return cx>=r.left&&cx<=r.right&&cy>=r.top&&cy<=r.bottom;}" +
                "if(inRect(document.querySelector('.reader-header'))){return 'in';}" +
                "if(inRect(document.querySelector('.reader-controls'))){return 'in';}" +
                "var dd=document.getElementById('controlsDropdown');" +
                "if(dd&&dd.classList.contains('open')){" +
                "if(inRect(dd)){return 'in';}" +
                "dd.classList.remove('open');" +
                "if(window.AndroidBridge&&window.AndroidBridge.notifyMenuOpen)window.AndroidBridge.notifyMenuOpen(false);" +
                "if(typeof toggleChrome==='function')toggleChrome();" +
                "return 'chrome';}" +
                "var s=document.getElementById('tocSidebar');" +
                "if(s&&!s.classList.contains('hidden')){" +
                "if(inRect(s)){return 'in';}" +
                "s.classList.add('hidden');" +
                "if(typeof rendition!=='undefined'&&rendition){setTimeout(function(){try{rendition.resize()}catch(e){}},350);}" +
                "return true;}" +
                "if(!document.body.classList.contains('chrome-hidden')){if(typeof toggleChrome==='function')toggleChrome();return 'chrome';}" +
                "return false;})($x,$y)"
        ) { r ->
            when {
                r == "chrome" -> {
                    menuOpen = false // 收起下拉后进入沉浸
                    immersive.enterImmersive()
                }
                r == "false" || r == "null" || r.isNullOrEmpty() -> fn()
            }
        }
    }

    fun loadAssetReader() {
        currentWebUrl = ASSET_READER_URL
        immersive.enterImmersive()
        webView.loadUrl(ASSET_READER_URL)
    }

    // ---------- 阅读状态（由 JS 通过 bridge 汇报） ----------

    fun onReaderState(active: Boolean) {
        isReaderActive = active
        returnPending = false
        immersive.cancelAutoHide()
        if (active) immersive.enterImmersive() else immersive.exitImmersive()
    }

    // ---------- 返回键 ----------

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isReaderActive) {
                    // 阅读器内：先回书架（showBookshelf 会重载页面渲染书架，
                    // 需要几秒），若 JS 一直无响应则兜底退出
                    returnPending = true
                    evalJs("window.showBookshelf && window.showBookshelf()")
                    webView.postDelayed({ if (returnPending) finish() }, 6000)
                } else {
                    finish()
                }
            }
        })
    }

    // ---------- 音量键翻页（固定开启） ----------
    // 约定：音量加 = 上一页，音量减 = 下一页（与常见阅读器一致）

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isReaderActive && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    evalJs("window.prevPage && window.prevPage()")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    evalJs("window.nextPage && window.nextPage()")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- 生命周期 ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentWebUrl?.let { outState.putString("current_url", it) }
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存吃紧时释放 WebView 占用的内存，避免系统回收崩溃
        if (level >= TRIM_MEMORY_MODERATE && ::webView.isInitialized) {
            webView.freeMemory()
        }
    }

    override fun onDestroy() {
        immersive.cancelAutoHide()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
