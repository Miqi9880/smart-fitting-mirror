package com.smartfitting.mirror

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.tryon.OutfitSelection
import com.smartfitting.mirror.tryon.OutfitFit
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
    private lateinit var previewPanel: PreviewPanel
    private lateinit var pageScroll: ScrollView
    private lateinit var fullscreenBack: OnBackPressedCallback
    private var normalScrollY = 0
    private lateinit var fitControls: FitControls
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
    private var latestTracking: TrackingState? = null
    private var captureBusy = false
    private var visible = false
    private var pendingPermission = false
    private var cameraDialog: AlertDialog? = null
    private data class AssetKey(val controller: FittingSession, val item: Garment)
    private val assetJobs = mutableMapOf<String,Job>()
    private val assetKeys = mutableMapOf<String,AssetKey>()
    private val loadedAssets = mutableSetOf<String>()
    private val assetErrors = mutableMapOf<String,String>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pendingPermission) return@registerForActivityResult
        pendingPermission = false
        if (granted) { model.rgb = true; model.running = true; model.visionFailure = null; if (visible) beginVision() }
        else {
            val message = "摄像头未授权，可重试或打开权限设置；合成样例可继续使用。"
            if (!model.running) { model.visionFailure = message; stopVision() }
            visionStatus.text = message; previewPanel.sourceStatus.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermission = savedInstanceState?.getBoolean("pending_permission") ?: false
        rgb = CameraRgbSource(this, this,
            targetRotation = { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation },
            cameraId = { model.cameraId })
        mock.configure(model.synthetic); mock.setPlaying(model.playing)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(label("智能穿衣镜", 23f))
        root.addView(label("选择衣服、裙裤或帽子，开启摄像头组合试穿。", 13f))
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
            if (model.previewFullscreen) setPreviewFullscreen(false)
            pendingPermission = false; model.running = false; model.visionFailure = null; stopVision()
            before.clear(); after.clear(); model.controller?.end()
        }
        root.addView(row(connect, finishSession))
        status = label("连接服务以加载衣服", 14f).apply { tag = "fitting.status" }
        root.addView(status)
        garments = Spinner(this).apply { tag = "fitting.garments"; contentDescription = "选择试穿单品" }
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
            model.state.value.selected?.let { model.outfit = OutfitSelection.add(model.outfit,it) }; renderOutfit()
        },button("移除本类","fitting.outfit.remove") {
            model.state.value.selected?.let { model.outfit = model.outfit - it.category }; renderOutfit()
        },button("清空搭配","fitting.outfit.clear") { model.outfit = emptyMap(); renderOutfit() }))
        root.addView(row(button("合成样例", "fitting.mock") {
            requestPreview(false)
        }, button("RGB 摄像头", "fitting.rgb") {
            requestPreview(true)
        }))
        val cameraChoice = button(CameraChooser.label(model.cameraId), "fitting.camera.select") { }
        cameraChoice.setOnClickListener {
            cameraDialog?.dismiss()
            cameraDialog = CameraChooser.show(this,model.cameraId) { id ->
                if (model.cameraId != id) {
                    pendingPermission = false
                    model.visionFailure = null
                    model.cameraId = id; cameraChoice.text = CameraChooser.label(id)
                    if (model.running && model.rgb) beginVision()
                    else {
                        visionStatus.text = "${CameraChooser.label(id)}；点击 RGB 摄像头启动"
                        previewPanel.sourceStatus.text = visionStatus.text
                        previewPanel.renderInput(model.running,false,model.rgb)
                    }
                }
            }
        }
        root.addView(cameraChoice)
        visionStatus = label("点击 RGB 摄像头开始真人预览；合成样例用于开发检查。", 12f).apply { tag = "fitting.vision" }
        root.addView(visionStatus)
        preview = TryOnView(this).apply {
            tag = "fitting.preview"; mirrored = model.mirrored; showLandmarks = model.showLandmarks
            outfitFits = model.outfitFits; contentDescription = "实时组合试衣预览"
            protectHead = model.protectHead; viewport = model.previewViewport
        }
        preview.mode = if (model.meshMode) TryOnView.Mode.OBJ_MESH else TryOnView.Mode.PHOTO
        // Keep both switches before the tall preview so landmarks are discoverable.
        root.addView(row(Switch(this).apply {
            text = "镜面显示"; isChecked = model.mirrored
            setOnCheckedChangeListener { _, value -> model.mirrored = value; preview.mirrored = value }
        }, Switch(this).apply {
            text = "显示关键点"; tag = "fitting.landmarks"; isChecked = model.showLandmarks
            setOnCheckedChangeListener { _, value -> model.showLandmarks = value; preview.showLandmarks = value }
        }))
        root.addView(Switch(this).apply {
            text = "上衣避开头部（近似）"; tag = "fitting.head.protection"; isChecked = model.protectHead
            setOnCheckedChangeListener { _, value -> model.protectHead = value; preview.protectHead = value }
        })
        fitControls = FitControls(this) { category, fit ->
            model.setFit(category, fit)
            preview.outfitFits = model.outfitFits
        }
        root.addView(Switch(this).apply {
            text = "记住本机贴合设置"; tag = "fitting.fit.remember"
            contentDescription = "开启后仅在本机按品类保存宽度、纵向和上下偏移数值，不保存人像、关键点或会话"
            isChecked = model.fitPreferences.enabled
            setOnCheckedChangeListener { _, value -> model.setFitSaveEnabled(value) }
        })
        root.addView(fitControls)
        val tail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        assetStatus = label("连接服务后加载透明服装", 12f).apply { tag = "fitting.asset" }
        tail.addView(assetStatus)
        tail.addView(button("重载服装", "fitting.reload") { loadOutfit(true) })
        tail.addView(Switch(this).apply {
            text = "OBJ 网格实验（二维关键点驱动，无真实体深度）"; tag = "fitting.mesh"; isChecked = model.meshMode
            setOnCheckedChangeListener { _, value ->
                model.meshMode = value; preview.mode = if (value) TryOnView.Mode.OBJ_MESH else TryOnView.Mode.PHOTO
            }
        })
        controls = SyntheticControls(this, mock) { settings ->
            pendingPermission = false; stopVision(); model.synthetic = settings; mock.configure(settings)
            if (model.running) beginVision()
        }
        tail.addView(controls)
        controls.render(!model.rgb,model.running)
        tail.addView(label("点击下方按钮会将未镜像的原始照片上传到上面的服务；实时预览不会逐帧上传。", 12f))
        generate = button("拍摄并上传生成", "fitting.generate") { capture() }
        cancel = button("取消生成", "fitting.cancel") { model.controller?.cancel() }
        retry = button("重试请求", "fitting.retry") { model.controller?.retry() }
        tail.addView(generate); tail.addView(row(cancel, retry))
        mockLabel = label("尚无生成结果", 13f).apply { tag = "fitting.result.kind" }
        tail.addView(mockLabel)
        tail.addView(label("前后对比（均为未镜像原图方向）", 16f))
        val beforeView = ImageView(this).apply { tag = "fitting.before"; contentDescription = "拍摄原图"; scaleType = ImageView.ScaleType.FIT_CENTER }
        val afterView = ImageView(this).apply { tag = "fitting.after"; contentDescription = "生成结果"; scaleType = ImageView.ScaleType.FIT_CENTER }
        tail.addView(row(label("原始照片", 12f), label("生成结果", 12f)))
        tail.addView(row(beforeView, afterView).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(220)) })
        before = OwnedImage(beforeView, lifecycleScope) { status.text = it }
        after = OwnedImage(afterView, lifecycleScope) { status.text = it }
        tail.addView(row(button("硬件诊断", "fitting.diagnostics") { startActivity(Intent(this, MainActivity::class.java)) },
            button("权限设置", "fitting.permissions") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }))
        tail.addView(button("镜子主屏幕设置", "fitting.home") {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { visionStatus.text = "设备未开放主屏幕设置，请联系管理员配置开机入口。" }
        })
        tail.addView(label("上衣头部保护仅在双耳可靠时近似生效，侧脸或缺点时不启用；无人体分割、深度测距或精细遮挡。放大只改变显示，不增加相机细节。合成样例非真人。", 11f))
        previewPanel = PreviewPanel(this,root,preview,tail, {
            if (model.running) {
                pendingPermission = false; model.running = false; model.visionFailure = null; stopVision()
            } else requestPreview(model.rgb)
        }) { setPreviewFullscreen(!model.previewFullscreen) }
        previewPanel.renderInput(model.running,model.visionFailure != null,model.rgb)
        model.visionFailure?.let { visionStatus.text = it; previewPanel.sourceStatus.text = it }
        preview.viewportChanged = { model.previewViewport = it; previewPanel.renderZoom() }
        pageScroll = ScrollView(this).apply {
            tag = "fitting.scroll"; isFillViewport = true; addView(previewPanel); setBackgroundColor(Color.rgb(242, 246, 249))
        }
        // Insets belong outside the scrolling content so controls/images never
        // move underneath the status and navigation bars when viewing results.
        ViewCompat.setOnApplyWindowInsetsListener(pageScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(maxOf(bars.left,cutout.left), maxOf(bars.top,cutout.top),
                maxOf(bars.right,cutout.right), maxOf(bars.bottom,cutout.bottom)); insets
        }
        fullscreenBack = object : OnBackPressedCallback(model.previewFullscreen) {
            override fun handleOnBackPressed() = setPreviewFullscreen(false)
        }
        onBackPressedDispatcher.addCallback(this,fullscreenBack)
        setContentView(pageScroll)
        WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        setPreviewFullscreen(model.previewFullscreen)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } } }
    }

    override fun onStart() {
        super.onStart(); visible = true; model.controller?.setVisible(true)
        if (model.running) beginVision()
    }
    override fun onStop() {
        cameraDialog?.dismiss(); cameraDialog = null
        browser?.dismiss(); browser = null
        visible = false; model.playing = mock.playing; model.synthetic = mock.settings
        model.controller?.setVisible(false); stopVision(); before.clear(); after.clear(); super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_permission", pendingPermission)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { rgb.close(); mock.close(); super.onDestroy() }

    private fun setPreviewFullscreen(value: Boolean) {
        if (value && !model.previewFullscreen) normalScrollY = pageScroll.scrollY
        model.previewFullscreen = value; fullscreenBack.isEnabled = value
        previewPanel.setFullscreen(value)
        WindowCompat.getInsetsController(window,pageScroll).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (value) {
                currentFocus?.clearFocus()
                hide(WindowInsetsCompat.Type.ime())
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
        pageScroll.post { pageScroll.scrollTo(0,if (value) 0 else normalScrollY) }
    }

    private fun requestPreview(rgbInput: Boolean) {
        pendingPermission = false
        if (rgbInput && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = true; permission.launch(Manifest.permission.CAMERA)
            return
        }
        model.rgb = rgbInput; model.running = true; model.visionFailure = null
        beginVision()
    }

    private fun beginVision() {
        if (!visible || !model.running) return
        stopVision()
        val id = UUID.randomUUID().toString(); activeVision = id
        source.start(id, { frame ->
            if (activeVision != frame.metadata.sessionId) return@start
            hasFrame = true
            latestTracking = frame.metadata.trackingState
            preview.submit(frame, model.state.value.selected)
            controls.render(!model.rgb, true, frame.metadata.trackingState)
            visionStatus.text = "${if (model.rgb) "RGB 摄像头（通道 ${rgb.activeCameraId ?: "未知"}）" else "合成输入（非真人）"} · ${frame.metadata.trackingState} · 无深度/精细遮挡"
            refreshPreviewSummary()
            generate.isEnabled = model.state.value.canCapture && !captureBusy
        }, { update ->
            if (activeVision == id) {
                if (update.state == SourceState.ERROR) {
                    model.running = false; model.visionFailure = update.message; stopVision()
                }
                visionStatus.text = update.message ?: update.state.name
                previewPanel.sourceStatus.text = visionStatus.text
            }
        })
        loadOutfit(true)
    }
    private fun stopVision() {
        activeVision = null; hasFrame = false; latestTracking = null; captureBusy = false
        assetJobs.values.forEach { it.cancel() }; assetJobs.clear(); assetKeys.clear(); loadedAssets.clear(); assetErrors.clear()
        mock.stop(); rgb.stop(); preview.clear(); generate.isEnabled = false
        visionStatus.text = model.visionFailure ?: "采集已停止，临时预览帧已清理"
        previewPanel.sourceStatus.text = visionStatus.text
        previewPanel.renderInput(model.running,model.visionFailure != null,model.rgb)
        garments.isEnabled = model.state.value.canSelect
        catalog.isEnabled = model.state.value.canSelect
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
            if (model.previewFullscreen) setPreviewFullscreen(false)
            model.running = false; model.visionFailure = null; stopVision(); before.clear(); after.clear()
            model.catalogAssets = emptyList(); model.catalogQuery = ""; model.outfit = emptyMap(); model.lastOutfitSelection = null
            outfitStatus.text = "当前搭配已清空"; browser?.dismiss(); browser = null
            fitControls.render(null,OutfitFit())
            assetStatus.text = "会话结束，服装与本地图像已清理"
        } else {
            val current = value.garments.associateBy { it.garmentId }
            model.outfit = model.outfit.values.mapNotNull { current[it.garmentId] }
                .fold(emptyMap()) { selection, item -> OutfitSelection.add(selection,item) }
            if (value.selected != model.lastOutfitSelection) {
                value.selected?.let { model.outfit = OutfitSelection.add(model.outfit,it) }
                model.lastOutfitSelection = value.selected
            }
            renderOutfit()
        }
    }
    private fun renderOutfit() {
        outfitStatus.text = "当前搭配：" + model.outfit.values.joinToString(" ＋ ") { "${it.category.label}：${it.name}" }.ifBlank { "无" }
        val category = model.state.value.selected?.category
        fitControls.render(category,category?.let { model.outfitFits[it] ?: OutfitFit.defaultFor(it) } ?: OutfitFit())
        loadOutfit()
    }
    private fun loadOutfit(force: Boolean = false) {
        val controller = model.controller ?: return
        if (!visible || !model.running) return
        val items = model.outfit.values.toList()
        val wanted = items.associate { it.garmentId to AssetKey(controller,it) }
        for (id in assetKeys.keys.toList()) if (force || assetKeys[id] != wanted[id]) {
            assetJobs.remove(id)?.cancel(); assetKeys.remove(id); loadedAssets.remove(id); assetErrors.remove(id)
        }
        if (force) preview.setOutfit(emptyList())
        preview.setOutfit(items)
        refreshPreviewSummary()
        if (items.isEmpty()) { assetStatus.text = "搭配已清空；选择单品或点击加入搭配"; return }
        for (garment in items) {
            val key = wanted.getValue(garment.garmentId)
            if (assetKeys[garment.garmentId] == key) continue
            assetKeys[garment.garmentId] = key
            assetErrors.remove(garment.garmentId)
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
                        renderAssetStatus()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (key == assetKeys[garment.garmentId]) {
                        assetKeys.remove(garment.garmentId)
                        assetErrors[garment.garmentId] = "${garment.name}：${error.message}"
                        renderAssetStatus()
                    }
                } finally { bytes?.fill(0); decoded?.recycle() }
            }
        }
    }
    private fun renderAssetStatus() {
        refreshPreviewSummary()
        assetStatus.text = "已加载 ${loadedAssets.size}/${model.outfit.size} 件 · 长裤／长裙需完整腿部，短款需髋膝，帽子需双耳 · 上衣可用近似头部保护，无精细遮挡" +
            if (assetErrors.isEmpty()) "" else "\n未加载：${assetErrors.values.joinToString("；")}；可点击重载"
    }
    private fun refreshPreviewSummary() {
        if (!hasFrame) return
        previewPanel.sourceStatus.text = "${if (model.rgb) "真人 RGB" else "合成输入（非真人）"} · $latestTracking · 已加载 ${loadedAssets.size}/${model.outfit.size} 件" +
            if (assetErrors.isEmpty()) "" else " · 有加载失败，请退出全屏重载"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(26, 43, 55)); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(value: String, id: String, action: () -> Unit) = Button(this).apply { text = value; tag = id; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
    }
}
