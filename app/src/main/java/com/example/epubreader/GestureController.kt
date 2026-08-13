package com.example.epubreader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

/**
 * 手势控制：左右滑动/边缘点击翻页、中间点击唤出 overlay。
 *
 * 通过 setOnTouchListener 只"观察"事件（始终 return false），不拦截，
 * 因此不会破坏 epub.js iframe 内的文本长按选中与链接点击。
 *
 * 支持单指 / 双指 / 三指横向滑动翻页：
 *  - 曲面屏上手掌或其它手指按在屏幕上时，只要有一根手指横向滑动就会翻页
 *  - 一次手势中只要出现过 ≥2 根手指，就按"多指滑动"处理：以横向位移最大的那根手指
 *    判定方向（静止的手掌不干扰），并且不再走单指 tap/fling，避免静止手掌被当成
 *    主指针导致翻不了页、或误触点击
 *  - 多指手势只认明确的横向滑动，不触发点击（防手掌误触翻页/切栏）
 *
 * 交互约定（与常见阅读 App 一致）：
 *  - 单指：左 1/3 屏幕点击 → 上一页；右 1/3 → 下一页；中间 → 切换 overlay
 *  - 单指快速横向滑动 → 翻页（慢滑/长按交由 WebView 处理选词）
 *  - 长按（>400ms）抑制 tap/fling，保留原生长按选词
 */
class GestureController(
    private val context: Context,
    private val webView: WebView,
    private val onFlingLeft: () -> Unit,
    private val onFlingRight: () -> Unit,
    private val onEdgeTapLeft: (x: Float, y: Float) -> Unit,
    private val onEdgeTapRight: (x: Float, y: Float) -> Unit,
    private val onCenterTap: (x: Float, y: Float) -> Unit,
) {

    private var isLongPress = false

    /** 本次手势是否出现过 ≥2 根手指（出现过就按多指滑动处理） */
    private var multiPointer = false
    /** 本次多指滑动是否已触发翻页（防止一次手势多次翻页） */
    private var multiSwipeFired = false

    /** 每根手指落下时的起始位置（按 pointerId 索引；Android 最大 pointerId 为 31） */
    private val startX = FloatArray(32)
    private val startY = FloatArray(32)

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

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // 出现过多指的手势由多指滑动路径处理，避免与单指 fling 同时触发
                if (isLongPress || e1 == null || multiPointer) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val elapsed = e2.eventTime - e1.eventTime
                // 快速、水平主导、非长按的滑动才翻页。
                // 阈值放宽（位移70px/速度500/容错1.5x）：斜滑或中速滑也翻页，
                // 避免滑动被 WebView 当成滚动而触发滚动条。
                if (elapsed > 600) return false
                if (abs(dx) < 70 || abs(dx) < 1.5f * abs(dy)) {
                    return false
                }
                if (abs(velocityX) < 500f) return false
                if (velocityX < 0) onFlingLeft() else onFlingRight()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 多指手势不当作点击，避免手掌误触触发翻页/切栏
                if (isLongPress || multiPointer) return false
                val x = e.x
                val y = e.y
                val width = webView.width
                val ratio = if (width > 0) x / width else 0f
                when {
                    ratio < 0.33f -> onEdgeTapLeft(x, y)
                    ratio > 0.67f -> onEdgeTapRight(x, y)
                    else -> onCenterTap(x, y)
                }
                return true
            }
        }
    )

    fun attach() {
        webView.setOnTouchListener { _, event ->
            handleMultiTouch(event)
            detector.onTouchEvent(event)
            false
        }
    }

    /**
     * 多指横向滑动识别：手势中出现 ≥2 根手指时，
     * 以横向位移最大的那根手指判定翻页方向（手掌/其它手指静止也不影响）。
     * 位移阈值按屏宽取 10%（至少 100px），且水平位移必须明显大于垂直位移。
     */
    private fun handleMultiTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiPointer = false
                multiSwipeFired = false
                recordStart(event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiPointer = true
                recordStart(event, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multiPointer || multiSwipeFired) return
                var maxDx = 0f
                var maxDy = 0f
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id !in startX.indices) continue
                    val dx = event.getX(i) - startX[id]
                    val dy = event.getY(i) - startY[id]
                    if (abs(dx) > abs(maxDx)) {
                        maxDx = dx
                        maxDy = dy
                    }
                }
                val threshold = maxOf(100f, webView.width * 0.1f)
                // 水平容错放宽到 1.5x：斜滑（带少量垂直分量）也识别为翻页
                if (abs(maxDx) >= threshold && abs(maxDx) > 1.5f * abs(maxDy)) {
                    multiSwipeFired = true
                    if (maxDx < 0) onFlingLeft() else onFlingRight()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // 状态由下一次 ACTION_DOWN 重置
            }
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
