package com.smartfitting.mirror

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var viewfinder: VisionDebugView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var rgb: CameraRgbSource
    private val mock = SyntheticVisionSource()
    private var selected: VisionSource<BitmapPixels> = mock
    private var shouldRun = true
    private var activeSession: String? = null
    private var visible = false
    private var captureDialog: AlertDialog? = null
    private var previewBitmap: Bitmap? = null
    private var captureButton: Button? = null
    private var captureBusy = false
    private var lastMetricAt = 0L
    private var metricFrames = 0
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { selected = rgb; shouldRun = true; if (visible) begin() }
        else { status.text = "未获得摄像头权限，可重试或打开权限设置；合成样例仍可使用。" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rgb = CameraRgbSource(this, this, targetRotation = { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation })
        if (savedInstanceState?.getBoolean("rgb") == true) selected = rgb
        shouldRun = savedInstanceState?.getBoolean("running", true) ?: true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(Color.rgb(242, 246, 249))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(12) + bars.bottom)
            insets
        }
        root.addView(label("智能穿衣镜 · 人体视觉", 22f))
        root.addView(label("开发诊断页｜关键点、原始帧与生命周期验证", 12f))
        root.addView(row(button("合成样例") { selected = mock; shouldRun = true; begin() },
            button("RGB 摄像头") { permission.launch(Manifest.permission.CAMERA) }))
        status = label(if (shouldRun) "准备数据源" else "已停止，临时图像已清理。", 13f)
        root.addView(status)
        metrics = label("无活动会话", 12f)
        root.addView(metrics)
        viewfinder = VisionDebugView(this)
        root.addView(viewfinder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val mirror = Switch(this).apply {
            text = "镜面显示（仅显示层翻转）"; isChecked = savedInstanceState?.getBoolean("mirror", true) ?: true
            viewfinder.mirrored = isChecked
            setOnCheckedChangeListener { _, checked -> viewfinder.mirrored = checked }
        }
        root.addView(mirror)
        val capture = button("查看原始帧") { capture() }.apply { isEnabled = false }
        captureButton = capture
        root.addView(row(capture, button("停止并清理") {
            shouldRun = false; end(); status.text = "已停止，临时图像已清理。"
        }))
        root.addView(row(button("重新开始") { shouldRun = true; begin() }, button("权限设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }))
        root.addView(label("本页不上传、不保存照片；深度、分割、衣服与云端生成尚未接入。", 11f))
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            viewfinder.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
            setContentView(ScrollView(this).apply { addView(root) })
        } else setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
    override fun onStart() { super.onStart(); visible = true; if (shouldRun) begin() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("rgb", selected === rgb)
        outState.putBoolean("running", shouldRun)
        outState.putBoolean("mirror", viewfinder.mirrored)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { visible = false; end(); super.onStop() }
    override fun onDestroy() { rgb.close(); mock.close(); super.onDestroy() }

    private fun begin() {
        if (!visible) return
        end()
        val id = UUID.randomUUID().toString()
        activeSession = id
        lastMetricAt = SystemClock.elapsedRealtime()
        metricFrames = 0
        selected.start(id, { frame ->
            if (activeSession != frame.metadata.sessionId) return@start
            viewfinder.submit(frame)
            captureButton?.isEnabled = !captureBusy
            metricFrames++
            val now = SystemClock.elapsedRealtime()
            if (now - lastMetricAt >= 500) {
                val fps = metricFrames * 1000f / (now - lastMetricAt)
                metrics.text = "${selected.capabilities.source} · ${frame.metadata.trackingState} · " +
                    "${frame.metadata.image.width}×${frame.metadata.image.height} · %.1f FPS（处理输出）".format(fps)
                metricFrames = 0; lastMetricAt = now
            }
        }, { update ->
            if (activeSession == id) {
                status.text = update.message
                if (update.state == SourceState.ERROR) { viewfinder.clear(); captureButton?.isEnabled = false }
            }
        })
    }
    private fun end() {
        activeSession = null
        captureBusy = false
        rgb.stop(); mock.stop()
        captureDialog?.dismiss(); captureDialog = null
        previewBitmap?.recycle(); previewBitmap = null
        if (::viewfinder.isInitialized) viewfinder.clear()
        captureButton?.isEnabled = false
        if (::metrics.isInitialized) metrics.text = "无活动会话"
    }
    private fun capture() {
        val id = activeSession ?: return
        if (captureBusy) return
        captureBusy = true
        captureButton?.isEnabled = false
        selected.captureOriginal { result ->
            if (!visible || activeSession != id) { result.getOrNull()?.close(); return@captureOriginal }
            captureBusy = false
            captureButton?.isEnabled = true
            result.fold(onSuccess = { photo ->
                captureDialog?.dismiss()
                val bitmap = photo.use { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
                if (bitmap == null) { status.text = "原始帧解码失败"; return@fold }
                previewBitmap = bitmap
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                }
                captureDialog = AlertDialog.Builder(this)
                    .setTitle(if (selected === mock) "合成原始帧（无关键点叠加）" else "原始帧（未镜像、无叠加）")
                    .setView(image).setPositiveButton("关闭并清理", null)
                    .setOnDismissListener {
                        image.setImageDrawable(null)
                        if (previewBitmap === bitmap) previewBitmap = null
                        if (!bitmap.isRecycled) bitmap.recycle()
                        captureDialog = null
                    }.show()
            }, onFailure = { status.text = it.message ?: "拍照失败，请重试" })
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(25, 43, 59)); setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }
}
