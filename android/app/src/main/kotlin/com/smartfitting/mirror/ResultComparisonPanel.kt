package com.smartfitting.mirror

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope

internal enum class ResultComparisonMode { ORIGINAL, RESULT, SIDE_BY_SIDE }

/** Reuses the two existing image owners. Switching only changes visibility/layout. */
internal class ResultComparisonPanel(context: Context, scope: CoroutineScope,
    private val onModeChanged: (ResultComparisonMode) -> Unit,
    private val onError: (String) -> Unit) : LinearLayout(context) {
    private var originalSource: ByteArray? = null
    private var resultSource: ByteArray? = null
    private var originalFailed = false
    private var resultFailed = false
    private var generating = false
    private var preferred = ResultComparisonMode.SIDE_BY_SIDE
    private val originalView = image("fitting.before", "原始照片，未镜像")
    private val resultView = image("fitting.after", "生成结果，原图方向")
    private val original = OwnedImage(originalView, scope) {
        originalFailed = true; updateLayout(); onError("原始照片：$it")
    }
    private val result = OwnedImage(resultView, scope) {
        resultFailed = true; updateLayout(); onError("生成结果：$it")
    }
    private val originalButton = button("查看原图", "fitting.comparison.original", ResultComparisonMode.ORIGINAL)
    private val resultButton = button("查看结果", "fitting.comparison.result", ResultComparisonMode.RESULT)
    private val pairButton = button("并排对比", "fitting.comparison.pair", ResultComparisonMode.SIDE_BY_SIDE)
    private val message = label("", 13f).apply {
        tag = "fitting.comparison.status"; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context).apply {
        tag = "fitting.comparison.progress"
        isIndeterminate = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val originalColumn = column("原始照片", originalView)
    private val resultColumn = column("生成结果", resultView)
    private val images = LinearLayout(context).apply {
        orientation = HORIZONTAL; tag = "fitting.comparison.images"
        addView(originalColumn, LayoutParams(0, -1, 1f))
        addView(resultColumn, LayoutParams(0, -1, 1f))
    }

    init {
        orientation = VERTICAL; tag = "fitting.comparison.panel"
        addView(label("前后对比（均为未镜像原图方向）", 16f))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(originalButton, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(resultButton, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(pairButton, LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            addView(message, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(images, LayoutParams(-1, dp(220)))
        updateLayout()
    }

    /** Sources remain owned by FittingSession; OwnedImage holds only decoded/private copies. */
    fun render(before: ByteArray?, after: ByteArray?, mode: ResultComparisonMode, generating: Boolean = false) {
        if (originalSource !== before) originalFailed = false
        if (resultSource !== after) resultFailed = false
        originalSource = before; resultSource = after; preferred = mode
        this.generating = generating
        original.update(before); result.update(after)
        updateLayout()
    }

    fun clear() {
        original.clear(); result.clear()
        originalSource = null; resultSource = null
        originalFailed = false; resultFailed = false
        generating = false
        preferred = ResultComparisonMode.SIDE_BY_SIDE
        updateLayout()
    }

    private fun choose(mode: ResultComparisonMode) {
        val hasOriginal = originalSource != null && !originalFailed
        val hasResult = resultSource != null && !resultFailed
        if ((mode == ResultComparisonMode.ORIGINAL && !hasOriginal) ||
            (mode == ResultComparisonMode.RESULT && !hasResult) ||
            (mode == ResultComparisonMode.SIDE_BY_SIDE && !(hasOriginal && hasResult))) return
        preferred = mode; onModeChanged(mode); updateLayout()
    }

    private fun updateLayout() {
        val hasOriginal = originalSource != null && !originalFailed
        val hasResult = resultSource != null && !resultFailed
        val mode = when {
            hasOriginal && hasResult -> preferred
            hasOriginal -> ResultComparisonMode.ORIGINAL
            hasResult -> ResultComparisonMode.RESULT
            else -> null
        }
        originalButton.isEnabled = hasOriginal; resultButton.isEnabled = hasResult
        pairButton.isEnabled = hasOriginal && hasResult
        listOf(originalButton to ResultComparisonMode.ORIGINAL, resultButton to ResultComparisonMode.RESULT,
            pairButton to ResultComparisonMode.SIDE_BY_SIDE).forEach { (button, choice) ->
            button.isSelected = mode == choice
            button.contentDescription = "${button.text}${if (button.isSelected) "，当前显示" else ""}"
        }
        originalColumn.visibility = if (hasOriginal && mode != ResultComparisonMode.RESULT) VISIBLE else GONE
        resultColumn.visibility = if (hasResult && mode != ResultComparisonMode.ORIGINAL) VISIBLE else GONE
        images.visibility = if (mode == null) GONE else VISIBLE
        val singleHeight = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 260 else 360
        images.layoutParams?.height = dp(if (mode == ResultComparisonMode.SIDE_BY_SIDE) 220 else singleHeight)
        images.requestLayout()
        progress.visibility = if (generating) VISIBLE else GONE
        val status = when {
            originalFailed || resultFailed -> listOfNotNull(
                if (originalFailed) "原始照片无法读取" else null,
                if (resultFailed) "生成结果无法读取" else null).joinToString("；")
            generating && hasOriginal -> "当前：原始照片，搭配生成中..."
            generating -> "搭配生成中..."
            mode == null -> "尚无对比图片，生成后可一键切换查看"
            mode == ResultComparisonMode.ORIGINAL -> "当前：原始照片"
            mode == ResultComparisonMode.RESULT -> "当前：生成结果"
            else -> "当前：并排对比 · 左侧原图，右侧结果"
        }
        if (message.text.toString() != status) message.text = status
    }

    private fun column(title: String, view: ImageView) = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label(title, 13f).apply { gravity = Gravity.CENTER })
        addView(view, LayoutParams(-1, 0, 1f))
    }
    private fun image(tag: String, description: String) = ImageView(context).apply {
        this.tag = tag; contentDescription = description; scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
    }
    private fun button(text: String, tag: String, mode: ResultComparisonMode) = Button(context).apply {
        this.text = text; this.tag = tag
        setOnClickListener { choose(mode) }
    }
    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(26, 43, 55))
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
