package com.example.epubreader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * 手势控制：左右滑动翻页、边缘/中间点击、长按选词。
 *
 * **点击与滑动是两个互相独立的识别器，靠「位移」互斥判定，不依赖速度/时长**：
 *  - 滑动 = 手指横向位移一旦达到阈值就立即触发（慢速滑动、滑动前有停顿都必翻页）；
 *  - 点击 = 抬起时位移未达滑动阈值才算（纯点击按左/中/右区域分派；有横向位移但没滑够的
 *    慢滑/斜滑按位移方向兜底翻页，消除旧实现的「速度死区」）；
 *  - 长按（GestureDetector 判定，>400ms 无大位移）→ 不翻页不点击，交给 WebView 原生长按选词；
 *  - 竖向明显主导（|dy| > 1.5|dx|）→ 锁定为竖向意图（选词/滚动），本轮不再翻页；
 *  - 多指（曲面屏手掌误触）：以横向位移最大的手指判定方向（静止手掌不干扰），
 *    滑动照常触发；但多指手势不作为点击（防手掌误触翻页/切栏）。
 *
 * 始终 return false「只观察不拦截」——滑动触发后不消费事件，避免 WebView 触摸序列被中断
 * （收不到 UP 可能让 epub.js/WebView 状态悬挂）；双触发由 swipeFired 标记杜绝（滑过就不再
 * 算点击，WebView 对横向拖拽本无翻页/滚动行为）。
 *
 * 交互约定（与常见阅读 App 一致）：
 *  - 单指：左 1/3 屏幕点击 → 上一页；右 1/3 → 下一页；中间 → 切换 overlay
 *  - 单指任意速度横向滑动 → 翻页（位移达标即触发，阈值 = max(80px, 屏宽10%)，水平需 1.5x 于垂直）
 */
class GestureController(
    private val context: Context,
    private val webView: WebView,
    private val onSwipeLeft: () -> Unit,
    private val onSwipeRight: () -> Unit,
    private val onEdgeTapLeft: (x: Float, y: Float) -> Unit,
    private val onEdgeTapRight: (x: Float, y: Float) -> Unit,
    private val onCenterTap: (x: Float, y: Float) -> Unit,
) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---------- 本次手势状态 ----------
    /** 长按（>400ms 无大位移）：交给 WebView 选词，不翻页不点击 */
    private var isLongPress = false
    /** 本次已触发滑动翻页（防一次手势重复翻页、滑动后再误判点击） */
    private var swipeFired = false
    /** 本次先判定为竖向主导：锁定为选词/滚动意图，不再翻页 */
    private var verticalLocked = false
    /** 本次手势是否出现过 ≥2 根手指（出现过多指就不作为点击，防手掌误触） */
    private var multiPointer = false

    /** 每根手指落下时的起始位置（按 pointerId 索引；Android 最大 pointerId 为 31） */
    private val startX = FloatArray(32)
    private val startY = FloatArray(32)
    /** 首指按下位置（点击分派用） */
    private var downX = 0f
    private var downY = 0f

    /** 滑动触发阈值：屏宽 10%，至少 80px */
    private val swipeThreshold: Float
        get() = max(80f, webView.width * 0.1f)

    /** 仅用于判定长按（保留原生长按选词）；点击/滑动不再交给它判定 */
    private val detector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                isLongPress = false
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                isLongPress = true
            }
        }
    )

    fun attach() {
        // 始终 return false：只观察不拦截，WebView/iframe 触摸序列完整（长按选词、链接点击、滑动尾段都照常收到）
        webView.setOnTouchListener { _, event ->
            handleTouch(event)
            detector.onTouchEvent(event)
            false
        }
    }

    /**
     * 触摸识别：位移判定滑动/点击/竖向/多指。不返回拦截语义（见 attach 注释），
     * 只用 swipeFired 防止滑动后再误判为点击。
     */
    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isLongPress = false
                swipeFired = false
                verticalLocked = false
                multiPointer = false
                downX = event.x
                downY = event.y
                recordStart(event, 0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiPointer = true
                recordStart(event, event.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (swipeFired || isLongPress || verticalLocked) return

                // 以横向位移最大的手指判定方向（手掌等静止手指位移≈0，不干扰）
                var maxDx = 0f
                var maxDy = 0f
                val count = event.pointerCount
                for (i in 0 until count) {
                    val id = event.getPointerId(i)
                    if (id !in startX.indices) continue
                    val dx = event.getX(i) - startX[id]
                    val dy = event.getY(i) - startY[id]
                    if (abs(dx) > abs(maxDx)) {
                        maxDx = dx
                        maxDy = dy
                    }
                }

                val threshold = swipeThreshold
                when {
                    // 横向位移达标且水平主导 → 立即翻页（不依赖速度/时长）
                    abs(maxDx) >= threshold && abs(maxDx) > 1.5f * abs(maxDy) -> {
                        swipeFired = true
                        if (maxDx < 0) onSwipeLeft() else onSwipeRight()
                    }
                    // 明显竖向 → 锁定为竖向意图，本轮不再翻页（选词拖动/滚动）
                    abs(maxDy) > 1.5f * abs(maxDx) && abs(maxDy) >= threshold * 0.5f -> {
                        verticalLocked = true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                // 已滑过/长按选词/竖向/多指：不再当作点击
                if (swipeFired || isLongPress || verticalLocked || multiPointer) return
                val dx = event.x - downX
                val dy = event.y - downY
                when {
                    // 有横向位移但没滑够阈值（慢滑/斜滑）：按位移方向兜底翻页，消除「速度死区」
                    abs(dx) > touchSlop && abs(dx) > abs(dy) ->
                        if (dx < 0) onEdgeTapLeft(event.x, event.y) else onEdgeTapRight(event.x, event.y)
                    // 干净点击（或微位移）：按位置分左/中/右
                    else -> routeZoneTap(event.x, event.y)
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> Unit
        }
    }

    private fun routeZoneTap(x: Float, y: Float) {
        val width = webView.width
        val ratio = if (width > 0) x / width else 0f
        when {
            ratio < 0.33f -> onEdgeTapLeft(x, y)
            ratio > 0.67f -> onEdgeTapRight(x, y)
            else -> onCenterTap(x, y)
        }
    }

    private fun recordStart(event: MotionEvent, pointerIndex: Int) {
        val id = event.getPointerId(pointerIndex)
        if (id in startX.indices) {
            startX[id] = event.getX(pointerIndex)
            startY[id] = event.getY(pointerIndex)
        }
    }
}
