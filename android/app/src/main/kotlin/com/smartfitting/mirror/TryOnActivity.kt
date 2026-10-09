package com.smartfitting.mirror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.vision.*
import kotlinx.coroutines.*
import java.util.UUID

/** First integration surface; product interaction ownership belongs to yyyrrrxxx. */
class TryOnActivity : ComponentActivity() {
    private val model: TryOnModel by viewModels()
    private val mock = SyntheticVisionSource()
    private lateinit var rgb: CameraRgbSource
    private val source: VisionSource<BitmapPixels> get() = if (model.rgb) rgb else mock
    private lateinit var preview: TryOnView
    private lateinit var controls: SyntheticControls
    private lateinit var status: TextView
    private lateinit var visionStatus: TextView
    private lateinit var assetStatus: TextView
    private lateinit var mockLabel: TextView
    private lateinit var address: EditText
    private lateinit var connect: Button
    private lateinit var generate: Button
    private lateinit var retry: Button
    private lateinit var cancel: Button
    private lateinit var finishSession: Button
    private lateinit var garments: Spinner
    private lateinit var before: OwnedImage
    private lateinit var after: OwnedImage
    private var listed = emptyList<Garment>()
    private var selecting = false
    private var activeVision: String? = null
    private var hasFrame = false
    private var captureBusy = false
    private var visible = false
    private var pendingPermission = false
    private var assetJob: Job? = null
    private var assetKey: Pair<FittingSession, String>? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pendingPermission) return@registerForActivityResult
        pendingPermission = false
        if (granted) { model.rgb = true; model.running = true; if (visible) beginVision() }
        else visionStatus.text = "摄像头未授权，可重试或打开权限设置；合成样例可继续使用。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermission = savedInstanceState?.getBoolean("pending_permission") ?: false
        rgb = CameraRgbSource(this, this, targetRotation = { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation })
        mock.configure(model.synthetic); mock.setPlaying(model.playing)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12)); setBackgroundColor(Color.rgb(242, 246, 249))
        }
        root.addView(label("智能穿衣镜 · 试衣联调", 23f))
        root.addView(label("选择上衣，预览后主动拍摄生成。本版可连接本地模拟服务。", 13f))
        address = EditText(this).apply {
            tag = "fitting.address"; setSingleLine(); setText(model.baseUrl)
            hint = "服务地址，例如 http://10.0.2.2:8000"; contentDescription = "试衣服务地址"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(address)
        connect = button("连接服务", "fitting.connect") {
            try { model.connect(address.text.toString()); model.running = true; beginVision() }
            catch (error: IllegalArgumentException) { status.text = "地址无效：${error.message}" }
        }
        finishSession = button("结束并清理", "fitting.end") {
            pendingPermission = false; model.running = false; stopVision()
            before.clear(); after.clear(); model.controller?.end()
        }
        root.addView(row(connect, finishSession))
        status = label("连接服务以加载衣服", 14f).apply { tag = "fitting.status" }
        root.addView(status)
        garments = Spinner(this).apply { tag = "fitting.garments"; contentDescription = "选择试穿上衣" }
        garments.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!selecting) listed.getOrNull(position)?.let { model.controller?.select(it.garmentId) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(garments)
        root.addView(row(button("合成样例", "fitting.mock") {
            pendingPermission = false; model.rgb = false; model.running = true; beginVision()
        }, button("RGB 摄像头", "fitting.rgb") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                pendingPermission = false; model.rgb = true; model.running = true; beginVision()
            } else { pendingPermission = true; permission.launch(Manifest.permission.CAMERA) }
        }))
        visionStatus = label("准备数据源", 12f).apply { tag = "fitting.vision" }
        root.addView(visionStatus)
        preview = TryOnView(this).apply { tag = "fitting.preview"; mirrored = model.mirrored; contentDescription = "实时上衣预览" }
        val height = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 240 else 340
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)))
        assetStatus = label("连接服务后加载透明服装", 12f).apply { tag = "fitting.asset" }
        root.addView(assetStatus)
        root.addView(row(Switch(this).apply {
            text = "镜面显示"; isChecked = model.mirrored
            setOnCheckedChangeListener { _, value -> model.mirrored = value; preview.mirrored = value }
        }, button("重载服装", "fitting.reload") { loadAsset(model.state.value.selected, true) }))
        controls = SyntheticControls(this, mock) { settings ->
            pendingPermission = false; stopVision(); model.synthetic = settings; mock.configure(settings)
            if (model.running) beginVision()
        }
        root.addView(controls)
        root.addView(label("点击下方按钮会将未镜像的原始照片上传到上面的服务；实时预览不会逐帧上传。", 12f))
        generate = button("拍摄并上传生成", "fitting.generate") { capture() }
        cancel = button("取消生成", "fitting.cancel") { model.controller?.cancel() }
        retry = button("重试请求", "fitting.retry") { model.controller?.retry() }
        root.addView(generate); root.addView(row(cancel, retry))
        mockLabel = label("尚无生成结果", 13f).apply { tag = "fitting.result.kind" }
        root.addView(mockLabel)
        root.addView(label("前后对比（均为未镜像原图方向）", 16f))
        val beforeView = ImageView(this).apply { tag = "fitting.before"; contentDescription = "拍摄原图"; scaleType = ImageView.ScaleType.FIT_CENTER }
        val afterView = ImageView(this).apply { tag = "fitting.after"; contentDescription = "生成结果"; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(row(label("原始照片", 12f), label("生成结果", 12f)))
        root.addView(row(beforeView, afterView).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(220)) })
        before = OwnedImage(beforeView, lifecycleScope) { status.text = it }
        after = OwnedImage(afterView, lifecycleScope) { status.text = it }
        root.addView(row(button("硬件诊断", "fitting.diagnostics") { startActivity(Intent(this, MainActivity::class.java)) },
            button("权限设置", "fitting.permissions") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }))
        root.addView(label("当前没有深度测距、人体分割或遮挡。合成样例不代表真实人体识别效果。", 11f))
        val scroll = ScrollView(this).apply {
            isFillViewport = true; addView(root); setBackgroundColor(Color.rgb(242, 246, 249))
        }
        // Insets belong outside the scrolling content so controls/images never
        // move underneath the status and navigation bars when viewing results.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } } }
    }

    override fun onStart() {
        super.onStart(); visible = true; model.controller?.setVisible(true)
        if (model.running) beginVision()
    }
    override fun onStop() {
        visible = false; model.playing = mock.playing; model.synthetic = mock.settings
        model.controller?.setVisible(false); stopVision(); before.clear(); after.clear(); super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_permission", pendingPermission)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { rgb.close(); mock.close(); super.onDestroy() }

    private fun beginVision() {
        if (!visible || !model.running) return
        stopVision()
        val id = UUID.randomUUID().toString(); activeVision = id
        source.start(id, { frame ->
            if (activeVision != frame.metadata.sessionId) return@start
            hasFrame = true
            preview.submit(frame, model.state.value.selected)
            controls.render(!model.rgb, true, frame.metadata.trackingState)
            visionStatus.text = "${if (model.rgb) "RGB 摄像头" else "合成输入（非真人）"} · ${frame.metadata.trackingState} · 无深度/遮挡"
            generate.isEnabled = model.state.value.canCapture && !captureBusy
        }, { update ->
            if (activeVision == id) {
                if (update.state == SourceState.ERROR) { model.running = false; stopVision() }
                visionStatus.text = update.message ?: update.state.name
            }
        })
        loadAsset(model.state.value.selected, true)
    }
    private fun stopVision() {
        activeVision = null; hasFrame = false; captureBusy = false
        assetJob?.cancel(); assetJob = null; assetKey = null
        mock.stop(); rgb.stop(); preview.clear(); generate.isEnabled = false
        visionStatus.text = "采集已停止，临时预览帧已清理"
        controls.render(!model.rgb, false)
    }
    private fun capture() {
        val controller = model.controller ?: return
        val id = activeVision ?: return
        if (!controller.state.value.canCapture || captureBusy) return
        captureBusy = true; generate.isEnabled = false; garments.isEnabled = false
        source.captureOriginal { outcome ->
            val photo = outcome.getOrNull()
            if (!visible || activeVision != id || model.controller !== controller || photo?.sessionId?.let { it != id } == true) {
                photo?.close(); return@captureOriginal
            }
            captureBusy = false
            if (photo != null) controller.generate(photo)
            else status.text = outcome.exceptionOrNull()?.message ?: "拍摄失败，请重试"
            generate.isEnabled = hasFrame && controller.state.value.canCapture
            garments.isEnabled = controller.state.value.canSelect
        }
    }
    private fun render(value: FittingState) {
        status.text = value.message
        val idle = !value.connected && value.phase != FittingPhase.CONNECTING && value.phase != FittingPhase.ENDING
        connect.isEnabled = idle; address.isEnabled = idle
        finishSession.isEnabled = value.connected || value.phase == FittingPhase.CONNECTING
        generate.isEnabled = value.canCapture && hasFrame && !captureBusy
        cancel.isEnabled = value.canCancel; retry.isEnabled = value.retry != RetryAction.NONE
        selecting = true
        if (listed != value.garments) {
            listed = value.garments
            garments.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listed.map { it.name + if (it.generationReferenceUrl == null) "（仅预览）" else "" })
        }
        val position = listed.indexOfFirst { it.garmentId == value.selectedId }
        if (position >= 0 && garments.selectedItemPosition != position) garments.setSelection(position)
        garments.isEnabled = value.canSelect && !captureBusy; selecting = false
        mockLabel.text = when (value.job?.isMock) {
            true -> "MOCK · 模拟结果，非真实 AI 换装"
            false -> "真实生成任务"
            null -> "尚无生成结果"
        }
        before.update(value.original); after.update(value.result)
        if (value.phase in setOf(FittingPhase.ENDING, FittingPhase.CLOSED)) {
            model.running = false; stopVision(); before.clear(); after.clear()
            assetStatus.text = "会话结束，服装与本地图像已清理"
        } else if (model.running) loadAsset(value.selected)
    }
    private fun loadAsset(garment: Garment?, force: Boolean = false) {
        val controller = model.controller ?: return
        if (!visible || !model.running) return
        val key = garment?.let { controller to it.garmentId }
        if (!force && key == assetKey) return
        assetJob?.cancel(); assetKey = key; preview.setAsset(null, null)
        if (garment == null) return
        assetStatus.text = "正在加载服装"
        assetJob = lifecycleScope.launch {
            var bytes: ByteArray? = null
            var decoded: Bitmap? = null
            try {
                bytes = controller.preview(garment)
                withContext(Dispatchers.Default) { decoded = decodeBounded(bytes!!) }
                ensureActive()
                if (visible && key == assetKey) {
                    preview.setAsset(garment, decoded); decoded = null
                    assetStatus.text = "${garment.name} · 四点形变预览 · 未启用遮挡"
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                assetStatus.text = "服装加载失败：${error.message}；可点击重载"
            } finally { bytes?.fill(0); decoded?.recycle() }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(26, 43, 55)); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(value: String, id: String, action: () -> Unit) = Button(this).apply { text = value; tag = id; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
    }
}
