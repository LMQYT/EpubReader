package com.example.epubreader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.epubreader.databinding.ActivityMainBinding
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 唯一入口：WebView 加载 EPUB 阅读器。
 * 负责 WebView 配置、JS 桥、沉浸式、手势翻页、音量键翻页、返回键行为。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 通过 WebViewAssetLoader 同源加载 assets 里的阅读器 */
        const val ASSET_READER_URL =
            "https://appassets.androidplatform.net/assets/reader-bookshelf.html"
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var bridge: EpubBridge
    private lateinit var immersive: ImmersiveController
    private val store by lazy { EpubStore(this) }
    private val sync by lazy { WebDavSync(this, store) }
    private lateinit var syncExecutor: ExecutorService

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

    /** WebDAV 配置页：返回后若配置有改动且启用项开了自动同步，则触发一次自动同步 */
    private val webDavLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == WebDavSettingsActivity.ACTION_SAVE) {
                // 回到书架先刷新云同步按钮的配置名（切换配置即时生效，不等重启）
                webView.post { evalJs("window.applyWebDavButtonName && window.applyWebDavButtonName()") }
                if (sync.getAutoSync()) {
                    runSync(WebDavSync.Mode.AUTO)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC
        SyncCoordinator.host = this

        webView = binding.webView
        immersive = ImmersiveController(this, binding.root)
        syncExecutor = Executors.newSingleThreadExecutor()
        bridge = EpubBridge(this, webView, store, sync) {
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
        webView.webViewClient = EpubWebViewClient(this, store) { autoSyncOnLoad() }
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
            onSwipeLeft = { if (isReaderActive && !menuOpen) evalJs("window.nextPage && window.nextPage()") },
            onSwipeRight = { if (isReaderActive && !menuOpen) evalJs("window.prevPage && window.prevPage()") },
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

    // ---------- WebDAV 云同步 ----------

    /** 打开 WebDAV 配置页 */
    fun launchWebDavConfig() {
        webDavLauncher.launch(Intent(this, WebDavSettingsActivity::class.java))
    }

    /** 页面首载完成：尊重自动同步开关，只触发一次 */
    private var autoSyncTriggered = false

    private fun autoSyncOnLoad() {
        if (autoSyncTriggered) return
        autoSyncTriggered = true
        if (!sync.isConfigured() || !sync.getAutoSync()) return
        runSync(WebDavSync.Mode.AUTO)
    }

    /** 按模式执行同步（书籍后台，进度经 JS 收集后交换）。结果同时报给配置页状态行（SyncCoordinator）。 */
    internal fun runSync(mode: WebDavSync.Mode) {
        if (!sync.isConfigured()) {
            val msg = "未启用任何云同步配置，请先启用一个"
            SyncCoordinator.report("done", msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }
        SyncCoordinator.report("running", when (mode) {
            WebDavSync.Mode.UPLOAD_ALL -> "正在覆盖上传…"
            WebDavSync.Mode.DOWNLOAD_ALL -> "正在云端下载…"
            WebDavSync.Mode.AUTO -> "正在自动同步…"
        })
        syncExecutor.execute {
            val result = sync.syncBooks(mode)
            val summary = syncSummary(mode, result)
            // 结果反馈：原生 Toast + 配置页状态行（双保险，不依赖网页 DOM）
            webView.post {
                if (summary != null) {
                    SyncCoordinator.report("done", summary)
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                }
                // 主线程：刷新书架（下载可能新增/覆盖书）
                evalJs("window.refreshShelfFromSync && window.refreshShelfFromSync()")
                // 收集本地进度 JSON（JS 返回值即 JSON 对象字符串）
                webView.evaluateJavascript(
                    "window.collectSyncData ? window.collectSyncData() : '{}'"
                ) { collected ->
                    val progressJson = collected ?: "{}"
                    syncExecutor.execute {
                        when (mode) {
                            WebDavSync.Mode.UPLOAD_ALL -> sync.uploadProgress(progressJson)
                            WebDavSync.Mode.DOWNLOAD_ALL ->
                                applyRemoteProgress(overwrite = true)
                            WebDavSync.Mode.AUTO -> {
                                // 自动同步合并上传：保住云端已有进度/封面，防止空快照冲掉
                                sync.uploadProgressMerged(progressJson)
                                applyRemoteProgress(overwrite = false)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 下载云端 progress.json 并交给 JS 应用 */
    private fun applyRemoteProgress(overwrite: Boolean) {
        val remote = sync.downloadProgress()
        if (remote != null) {
            webView.post { callJs("applySyncData", remote, overwrite) }
        }
    }

    // ---------------- 删除书籍（含云端） ----------------

    /**
     * 删除书籍（由 JS 书架点击触发）：该书在云端有记录时弹三选一（取消/仅删本地/同时删云端），
     * 没同步过的本地书弹二选一（取消/删除）。删完经 callJs("handleBookDeleted") 让 JS 清理并刷新书架。
     */
    fun promptDeleteBook(name: String) {
        val hasCloud = sync.hasCloudCopy(name)
        val builder = AlertDialog.Builder(this)
            .setTitle("删除书籍")
            .setMessage("确定删除「$name」吗？")
            .setNegativeButton("取消", null)
        if (hasCloud) {
            builder
                .setMessage("确定删除「$name」吗？\n该书已同步到云端，可一并删除云端文件与记录。")
                .setPositiveButton("同时删云端") { _, _ -> performDelete(name, true) }
                .setNeutralButton("仅删本地") { _, _ -> performDelete(name, false) }
        } else {
            builder.setPositiveButton("删除") { _, _ -> performDelete(name, false) }
        }
        builder.show()
    }

    private fun performDelete(name: String, deleteCloud: Boolean) {
        val localOk = try {
            store.delete(name)
        } catch (e: Exception) {
            Log.e(TAG, "delete local $name failed", e)
            false
        }
        if (!localOk) {
            Toast.makeText(this, "本地文件删除失败", Toast.LENGTH_SHORT).show()
            return
        }
        // 云端删除后台执行，完成后 Toast 结果（不阻塞书架刷新）
        if (deleteCloud) {
            syncExecutor.execute {
                val err = sync.deleteCloudBook(name)
                webView.post {
                    Toast.makeText(
                        this,
                        if (err == null) "已同时删除云端文件与记录" else "云端删除失败：$err",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        // 通知 JS 清理 localStorage 进度/封面并刷新书架
        callJs(
            "handleBookDeleted",
            JSONObject().apply { put("name", name) }.toString()
        )
    }

    /**
     * 同步结果的文案。手动操作（覆盖上传/云端下载）任何时候都明确给出结果；
     * 自动同步仅在出错或有实际变化时返回文案，已是最新返回 null（不打扰）。
     * 同时供 Toast 与配置页状态行使用。
     */
    private fun syncSummary(mode: WebDavSync.Mode, r: WebDavSync.SyncResult): String? {
        return when {
            r.error != null -> "☁️ ${r.error}"
            mode == WebDavSync.Mode.UPLOAD_ALL ->
                if (r.uploaded > 0) "☁️ 覆盖上传完成：上传 ${r.uploaded} 本 · 跳过 ${r.skippedUpload} 本（云端已一致）"
                else if (r.skippedUpload > 0) "☁️ 覆盖上传完成：全部 ${r.skippedUpload} 本已在云端，无需重复上传"
                else "☁️ 覆盖上传完成：本地书架为空，没有可上传的书"
            mode == WebDavSync.Mode.DOWNLOAD_ALL ->
                if (r.downloaded > 0) "☁️ 云端下载完成：下载 ${r.downloaded} 本 · 跳过 ${r.skippedDownload} 本（本地已一致）"
                else if (r.skippedDownload > 0) "☁️ 云端下载完成：全部 ${r.skippedDownload} 本已在本地，无需重复下载"
                else "☁️ 云端下载完成：云端没有新书"
            else -> // AUTO
                if (r.uploaded > 0 || r.downloaded > 0)
                    "☁️ 自动同步完成：上传 ${r.uploaded} · 下载 ${r.downloaded}"
                else null
        }
    }

    /** 通用 callJs：首参为 JSON 字符串原样嵌入，后续参数按原样 */
    private fun callJs(fn: String, vararg args: Any) {
        val argStr = args.joinToString(",") { a -> a.toString() }
        webView.evaluateJavascript("window.$fn && window.$fn($argStr)", null)
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
        SyncCoordinator.host = null
        if (::syncExecutor.isInitialized) syncExecutor.shutdown()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
