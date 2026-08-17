package com.example.epubreader

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS 桥：注入名为 window.AndroidBridge。
 *
 * @JavascriptInterface 运行在工作线程，任何 UI / evaluateJavascript 必须 post 回主线程。
 */
class EpubBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val store: EpubStore,
    private val sync: WebDavSync,
    private val launchOpen: () -> Unit,
) {

    @JavascriptInterface
    fun bridgeVersion(): String = "1.0"

    /** 真实 App 版本号（读安装包的 versionName，供书架标题动态显示，避免硬编码过期） */
    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val pm = activity.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(activity.packageName, 0)
            }
            info.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun importEpub() {
        webView.post { launchOpen() }
    }

    @JavascriptInterface
    fun listEpubs(): String {
        val arr = JSONArray()
        store.listFiles().forEach { info ->
            arr.put(JSONObject().apply {
                put("name", info.name)
                put("size", info.size)
                put("timestamp", info.timestamp)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun deleteEpub(name: String): String {
        return try {
            val ok = store.delete(name)
            if (ok) JSONObject().put("ok", true).toString()
            else JSONObject().put("ok", false).put("error", "删除失败").toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "删除失败")
        }
    }

    /** 删除书籍（原生）：弹原生三选一对话框（是否同时删云端），结果经 callJs("handleBookDeleted") 回调 JS */
    @JavascriptInterface
    fun deleteBookFromShelf(name: String) {
        webView.post { activity.promptDeleteBook(name) }
    }

    @JavascriptInterface
    fun notifyState(state: String) {
        val active = state == "reader"
        webView.post { activity.onReaderState(active) }
    }

    /** 手机下拉菜单开合通知：打开时原生禁用点击翻页/沉浸，避免误操作 */
    @JavascriptInterface
    fun notifyMenuOpen(open: Boolean) {
        webView.post { activity.menuOpen = open }
    }

    /** 书架「云同步」按钮：打开原生 WebDAV 配置页 */
    @JavascriptInterface
    fun openWebDavConfig() {
        webView.post { activity.launchWebDavConfig() }
    }

    /** 查询 WebDAV 同步状态（供 JS 显示按钮状态） */
    @JavascriptInterface
    fun getSyncStatus(): String {
        return sync.getConfig().toString()
    }

    /** SAF 选中文件后回调（主线程），导入成功后通知 JS */
    fun onImportResult(uri: Uri) {
        webView.post {
            try {
                val info = store.importFile(uri)
                callJs("handleNativeImport", JSONObject().apply { put("name", info.name) }.toString())
            } catch (e: Exception) {
                callJs(
                    "handleNativeImport",
                    JSONObject().apply { put("error", e.message ?: "导入失败") }.toString()
                )
            }
        }
    }

    /** 固定全局回调约定：数据走 JSON 字符串，不拼装 JS 代码 */
    private fun callJs(fn: String, json: String) {
        webView.evaluateJavascript("window.$fn && window.$fn($json)", null)
    }

    private fun errorJson(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()
}
