package com.smartfitting.mirror

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.smartfitting.mirror.tryon.PreviewViewport
import com.smartfitting.mirror.tryon.TryOnView
import kotlin.math.roundToInt

/** Expands the existing, still attached renderer. No second camera, bitmap copy or reparenting. */
internal class PreviewPanel(context: Context, private val header: View, val preview: TryOnView,
                            private val footer: View, toggleInput: () -> Unit,
                            private val toggleFullscreen: () -> Unit) : LinearLayout(context) {
    private val fullButton = button("全屏预览","fitting.preview.fullscreen") { toggleFullscreen() }
    private val minus = button("－","fitting.preview.minus") { preview.viewport = preview.viewport.zoomBy(1 / 1.25f) }
    private val plus = button("＋","fitting.preview.plus") { preview.viewport = preview.viewport.zoomBy(1.25f) }
    private val reset = button("恢复全景","fitting.preview.reset") { preview.viewport = PreviewViewport() }
    private val input = button("开始预览","fitting.preview.input") { toggleInput() }
    private val zoomText = TextView(context).apply {
        tag = "fitting.preview.zoom"; textSize = 13f; setTextColor(Color.DKGRAY)
        setPadding(dp(8),dp(2),dp(8),dp(2))
    }
    val sourceStatus = TextView(context).apply {
        tag = "fitting.preview.source"; textSize = 12f; setTextColor(Color.DKGRAY)
        setPadding(dp(8),0,dp(8),dp(3)); text = "尚未开启预览"
    }
    var fullscreen: Boolean = false
        private set

    init {
        orientation = VERTICAL
        tag = "fitting.preview.panel"
        setBackgroundColor(Color.rgb(242,246,249))
        minus.contentDescription = "缩小预览"; plus.contentDescription = "放大预览"
        reset.contentDescription = "恢复全景，重置缩放和位置"
        addView(header)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf(fullButton to 1.5f, minus to .7f, plus to .7f, reset to 1.3f, input to 1.4f).forEach { (view, weight) ->
                addView(view,LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,weight))
            }
        })
        addView(zoomText); addView(sourceStatus)
        addView(preview)
        addView(footer)
        setFullscreen(false)
        renderZoom()
    }

    fun setFullscreen(value: Boolean) {
        fullscreen = value
        header.visibility = if (value) GONE else VISIBLE
        footer.visibility = if (value) GONE else VISIBLE
        fullButton.text = if (value) "退出全屏" else "全屏预览"
        fullButton.contentDescription = fullButton.text
        setPadding(if (value) 0 else dp(18),if (value) 0 else dp(12),if (value) 0 else dp(18),if (value) 0 else dp(12))
        val normalHeight = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 300 else 400
        preview.layoutParams = if (value) LayoutParams(-1,0,1f) else LayoutParams(-1,dp(normalHeight))
        // fillViewport gives the weighted renderer the remaining visible height.
        layoutParams = FrameLayout.LayoutParams(-1,if (value) -1 else -2)
        preview.gesturesEnabled = value
        renderZoom()
        requestLayout()
    }

    fun renderZoom() {
        val zoom = preview.viewport.zoom
        zoomText.text = "${(zoom * 100).roundToInt()}% · " + if (zoom == 1f) "全景；＋可查看搭配细节" else
            if (fullscreen) "双指缩放，单指拖动；恢复全景可查看完整画面" else "局部细节；全屏后可拖动"
        minus.isEnabled = zoom > 1f; plus.isEnabled = zoom < 3f
    }

    fun renderInput(running: Boolean, failed: Boolean, rgb: Boolean) {
        input.text = when { running -> "停止预览"; failed -> "重试预览"; else -> "开始预览" }
        input.contentDescription = "${input.text}：${if (rgb) "RGB 摄像头，保持已选相机通道" else "合成样例，非真人"}"
    }

    private fun button(text: String, tag: String, action: () -> Unit) = Button(context).apply {
        this.text = text; this.tag = tag; textSize = 14f; minWidth = 0; minimumWidth = 0
        setPadding(dp(4),0,dp(4),0); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
