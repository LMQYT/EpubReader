package com.example.epubreader

import android.app.Activity
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog

/**
 * 处理 JS 弹窗。原 HTML 的 confirm()（删除确认）依赖 onJsConfirm，
 * 不实现的话 confirm 会直接返回 false，删除永远执行不了。
 */
class EpubWebChromeClient(private val activity: Activity) : WebChromeClient() {

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            result.cancel()
            return true
        }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> result.confirm() }
            .setNegativeButton("取消") { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsAlert(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            result.confirm()
            return true
        }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> result.confirm() }
            .setOnCancelListener { result.confirm() }
            .show()
        return true
    }
}
