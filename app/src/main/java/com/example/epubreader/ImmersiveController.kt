package com.example.epubreader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸模式控制：隐藏/显示系统栏（状态栏+导航栏），支持自动隐藏。
 */
class ImmersiveController(
    private val activity: Activity,
    root: View,
) {

    private val controller: WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(activity.window, root)

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { enterImmersive() }
    private var isImmersive = false

    init {
        // 内容延伸至系统栏区域（edge-to-edge）
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun enterImmersive() {
        handler.removeCallbacks(autoHideRunnable)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        isImmersive = true
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
    }

    fun exitImmersive() {
        handler.removeCallbacks(autoHideRunnable)
        controller.show(WindowInsetsCompat.Type.systemBars())
        isImmersive = false
        // 唤出工具栏时状态栏与工具栏同色（紫色渐变起点），避免颜色不一致
        activity.window.statusBarColor = 0xFF667EEA.toInt()
    }

    /** 切换沉浸/非沉浸（阅读时点屏幕中间唤出/隐藏系统栏） */
    fun toggle() {
        if (isImmersive) exitImmersive() else enterImmersive()
    }

    fun scheduleAutoHide(delayMs: Long) {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, delayMs)
    }

    fun cancelAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
    }
}
