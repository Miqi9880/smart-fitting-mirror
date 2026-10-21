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
    private lateinit var outfitStatus: TextView
    private lateinit var mockLabel: TextView
    private lateinit var address: EditText
    private lateinit var connect: Button
    private lateinit var generate: Button
    private lateinit var retry: Button
    private lateinit var cancel: Button
    private lateinit var finishSession: Button
    private lateinit var garments: Spinner
    private lateinit var catalog: Button
    private var browser: GarmentBrowser? = null
    private lateinit var before: OwnedImage
    private lateinit var after: OwnedImage
    private var listed = emptyList<Garment>()
    private var selecting = false
    private var activeVision: String? = null
    private var hasFrame = false
    private var captureBusy = false
    private var visible = false
    private var pendingPermission = false
    private data class AssetKey(val controller: FittingSession, val item: Garment)
    private val assetJobs = mutableMapOf<String,Job>()
    private val assetKeys = mutableMapOf<String,AssetKey>()
    private val loadedAssets = mutableSetOf<String>()
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
        root.addView(label("智能穿衣镜", 23f))
        root.addView(label("选择上衣、裤子或帽子，开启摄像头组合试穿。", 13f))
        address = EditText(this).apply {
            tag = "fitting.address"; setSingleLine(); setText(model.baseUrl)
            hint = "服务地址，例如 http://10.0.2.2:8000"; contentDescription = "试衣服务地址"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(address)
        connect = button("连接服务", "fitting.connect") {
            try { model.connect(address.text.toString()); if (model.running) beginVision() }
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
        catalog = button("浏览衣柜与服装详情", "fitting.catalog") {
            val controller = model.controller ?: return@button
            browser?.dismiss()
            browser = GarmentBrowser(this,controller,lifecycleScope,model.catalogAssets,model.catalogQuery) { model.catalogQuery = it }
            browser!!.show()
        }
        root.addView(catalog)
        outfitStatus = label("当前搭配为空；选择单品后自动加入对应品类。",12f).apply { tag = "fitting.outfit" }
        root.addView(outfitStatus)
        root.addView(row(button("加入搭配","fitting.outfit.add") {
            model.state.value.selected?.let { model.outfit = model.outfit + (it.category to it) }; renderOutfit()
        },button("移除本类","fitting.outfit.remove") {
            model.state.value.selected?.let { model.outfit = model.outfit - it.category }; renderOutfit()
        },button("清空搭配","fitting.outfit.clear") { model.outfit = emptyMap(); renderOutfit() }))
        root.addView(row(button("合成样例", "fitting.mock") {
            pendingPermission = false; model.rgb = false; model.running = true; beginVision()
        }, button("RGB 摄像头", "fitting.rgb") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                pendingPermission = false; model.rgb = true; model.running = true; beginVision()
            } else { pendingPermission = true; permission.launch(Manifest.permission.CAMERA) }
        }))
        visionStatus = label("点击 RGB 摄像头开始真人预览；合成样例用于开发检查。", 12f).apply { tag = "fitting.vision" }
        root.addView(visionStatus)
        preview = TryOnView(this).apply { tag = "fitting.preview"; mirrored = model.mirrored; showLandmarks = model.showLandmarks; contentDescription = "实时上衣预览" }
        preview.mode = if (model.meshMode) TryOnView.Mode.OBJ_MESH else TryOnView.Mode.PHOTO
        val height = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 240 else 340
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)))
        assetStatus = label("连接服务后加载透明服装", 12f).apply { tag = "fitting.asset" }
        root.addView(assetStatus)
        root.addView(row(Switch(this).apply {
            text = "镜面显示"; isChecked = model.mirrored
            setOnCheckedChangeListener { _, value -> model.mirrored = value; preview.mirrored = value }
        }, button("重载服装", "fitting.reload") { loadOutfit(true) }))
        root.addView(Switch(this).apply {
            text = "显示关键点"; tag = "fitting.landmarks"; isChecked = model.showLandmarks
            setOnCheckedChangeListener { _, value -> model.showLandmarks = value; preview.showLandmarks = value }
        })
        root.addView(Switch(this).apply {
            text = "OBJ 网格实验（二维关键点驱动，无真实体深度）"; tag = "fitting.mesh"; isChecked = model.meshMode
            setOnCheckedChangeListener { _, value ->
                model.meshMode = value; preview.mode = if (value) TryOnView.Mode.OBJ_MESH else TryOnView.Mode.PHOTO
            }
        })
        controls = SyntheticControls(this, mock) { settings ->
            pendingPermission = false; stopVision(); model.synthetic = settings; mock.configure(settings)
            if (model.running) beginVision()
        }
        root.addView(controls)
        controls.render(!model.rgb,model.running)
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
        root.addView(button("镜子主屏幕设置", "fitting.home") {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { visionStatus.text = "设备未开放主屏幕设置，请联系管理员配置开机入口。" }
        })
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
        browser?.dismiss(); browser = null
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
        loadOutfit(true)
    }
    private fun stopVision() {
        activeVision = null; hasFrame = false; captureBusy = false
        assetJobs.values.forEach { it.cancel() }; assetJobs.clear(); assetKeys.clear(); loadedAssets.clear()
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
        catalog.isEnabled = value.canSelect && !captureBusy
        if (value.connected && value.garments.isNotEmpty())
            model.catalogAssets = (model.catalogAssets + value.garments).distinctBy { it.previewImage.url }
        browser?.render(value)
        mockLabel.text = when (value.job?.isMock) {
            true -> "MOCK · 模拟结果，非真实 AI 换装"
            false -> "真实生成任务"
            null -> "尚无生成结果"
        }
        before.update(value.original); after.update(value.result)
        if (value.phase in setOf(FittingPhase.ENDING, FittingPhase.CLOSED)) {
            model.running = false; stopVision(); before.clear(); after.clear()
            model.catalogAssets = emptyList(); model.catalogQuery = ""; model.outfit = emptyMap(); model.lastOutfitSelection = null
            outfitStatus.text = "当前搭配已清空"; browser?.dismiss(); browser = null
            assetStatus.text = "会话结束，服装与本地图像已清理"
        } else {
            val current = value.garments.associateBy { it.garmentId }
            model.outfit = model.outfit.mapNotNull { (_,item) -> current[item.garmentId]?.let { it.category to it } }.toMap()
            if (value.selected != model.lastOutfitSelection) {
                value.selected?.let { model.outfit = model.outfit + (it.category to it) }
                model.lastOutfitSelection = value.selected
            }
            renderOutfit()
        }
    }
    private fun renderOutfit() {
        outfitStatus.text = "当前搭配：" + model.outfit.values.joinToString(" ＋ ") { "${it.category.label}：${it.name}" }.ifBlank { "无" }
        loadOutfit()
    }
    private fun loadOutfit(force: Boolean = false) {
        val controller = model.controller ?: return
        if (!visible || !model.running) return
        val items = model.outfit.values.toList()
        val wanted = items.associate { it.garmentId to AssetKey(controller,it) }
        for (id in assetKeys.keys.toList()) if (force || assetKeys[id] != wanted[id]) {
            assetJobs.remove(id)?.cancel(); assetKeys.remove(id); loadedAssets.remove(id)
        }
        if (force) preview.setOutfit(emptyList())
        preview.setOutfit(items)
        if (items.isEmpty()) { assetStatus.text = "搭配已清空；选择单品或点击加入搭配"; return }
        for (garment in items) {
            val key = wanted.getValue(garment.garmentId)
            if (assetKeys[garment.garmentId] == key) continue
            assetKeys[garment.garmentId] = key
            assetStatus.text = "正在加载搭配素材"
            assetJobs[garment.garmentId] = lifecycleScope.launch {
                var bytes: ByteArray? = null
                var decoded: Bitmap? = null
                try {
                    bytes = controller.outfitPreview(garment)
                    withContext(Dispatchers.Default) { decoded = decodeBounded(bytes!!) }
                    ensureActive()
                    if (visible && key == assetKeys[garment.garmentId]) {
                        preview.setLayerAsset(garment, requireNotNull(decoded)); decoded = null
                        loadedAssets += garment.garmentId
                        assetStatus.text = "已加载 ${loadedAssets.size}/${model.outfit.size} 件 · 裤子需完整腿部，帽子需双耳 · 未启用遮挡"
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (key == assetKeys[garment.garmentId]) {
                        assetKeys.remove(garment.garmentId)
                        assetStatus.text = "${garment.name}加载失败：${error.message}；可点击重载"
                    }
                } finally { bytes?.fill(0); decoded?.recycle() }
            }
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
