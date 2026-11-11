package com.smartfitting.mirror

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
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
    private lateinit var standingPanel: StandingGuidePanel
    private lateinit var assetStatus: TextView
    private lateinit var outfitStatus: TextView
    private lateinit var mockLabel: TextView
    private lateinit var address: EditText
    private lateinit var connect: Button
    private lateinit var generate: Button
    private lateinit var outfitMode: Switch
    private lateinit var generationSummary: TextView
    private lateinit var staticImage: StaticImagePanel
    private lateinit var retry: Button
    private lateinit var cancel: Button
    private lateinit var finishSession: Button
    private lateinit var garments: Spinner
    private lateinit var catalog: Button
    private var browser: GarmentBrowser? = null
    private lateinit var videos: com.smartfitting.mirror.video.VideoExperience
    private lateinit var videoButton: Button
    private lateinit var comparison: ResultComparisonPanel
    private var listed = emptyList<Garment>()
    private var selecting = false
    private var activeVision: String? = null
    private var hasFrame = false
    private var latestTracking: TrackingState? = null
    private var captureBusy = false
    private var captureCountdown: CountDownTimer? = null
    private var visible = false
    private var pendingPermission = false
    private var cameraDialog: AlertDialog? = null
    private val guestRoute get() = intent.getBooleanExtra(StandbyActivity.EXTRA_GUEST, false)
    private var returningGuest = false
    private data class AssetKey(val controller: FittingSession, val item: Garment)
    private val assetJobs = mutableMapOf<String,Job>()
    private val assetKeys = mutableMapOf<String,AssetKey>()
    private val loadedAssets = mutableSetOf<String>()
    private val assetErrors = mutableMapOf<String,String>()
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Picker result delivery can precede onStart. Dispatch after this lifecycle callback.
        if (uri != null) pageScroll.post {
            if (visible) staticImage.load {
                contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开所选照片")
            }
        }
    }
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
        if (guestRoute) {
            if (savedInstanceState == null && model.controller == null) {
                intent.getStringExtra(StandbyActivity.EXTRA_ORIGIN)?.let { model.baseUrl = it }
                model.cameraId = intent.getStringExtra(StandbyActivity.EXTRA_CAMERA)
            }
            if (model.endReason == null) model.startExperience()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = endExperience(GuestEndReason.MANUAL)
            })
        }
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
            try { model.connect(address.text.toString()); staticImage.clear(); comparison.clear(); if (model.running) beginVision() }
            catch (error: IllegalArgumentException) { status.text = "地址无效：${error.message}" }
        }
        finishSession = button("结束并清理", "fitting.end") { endExperience(GuestEndReason.MANUAL) }
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
        catalog = button("浏览衣柜", "fitting.catalog") {
            val controller = model.controller ?: return@button
            browser?.dismiss()
            browser = GarmentBrowser(
                this, controller, lifecycleScope, model.catalogAssets, model.catalogQuery,
                rememberQuery = { model.catalogQuery = it },
                selectedAccessories = { model.outfitAccessories },
                selectAccessory = { category, item ->
                    model.outfitAccessories = if (item == null) model.outfitAccessories - category
                    else model.outfitAccessories + (category to item)
                    model.generateOutfit = true
                    renderOutfit()
                },
            )
            browser!!.show()
        }
        root.addView(catalog)
        outfitStatus = label("当前搭配为空；选择单品后自动加入对应品类。",12f).apply { tag = "fitting.outfit" }
        root.addView(outfitStatus)
        videoButton = button("查看当前搭配的相关视频", "fitting.videos") { videos.show() }
        root.addView(videoButton)
        root.addView(button("3D 人台样件（实验，不开启摄像头）", "fitting.garment3d") {
            pendingPermission = false; model.running = false; model.visionFailure = null; stopVision()
            if (model.previewFullscreen) setPreviewFullscreen(false)
            startActivity(Intent(this, com.smartfitting.mirror.garment3d.Garment3dActivity::class.java))
        }.apply { if (guestRoute) visibility = View.GONE })
        root.addView(row(button("加入搭配","fitting.outfit.add") {
            model.state.value.selected?.let { model.outfit = OutfitSelection.add(model.outfit,it) }; renderOutfit()
        },button("移除本类","fitting.outfit.remove") {
            model.state.value.selected?.let { model.outfit = model.outfit - it.category }; renderOutfit()
        },button("清空搭配","fitting.outfit.clear") {
            model.outfit = emptyMap(); model.outfitAccessories = emptyMap(); renderOutfit()
        }))
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
        root.addView(Switch(this).apply {
            text = "镜面显示"; isChecked = model.mirrored
            setOnCheckedChangeListener { _, value -> model.mirrored = value; preview.mirrored = value }
        })
        root.addView(Switch(this).apply {
            text = "上衣避开头部（近似）"; tag = "fitting.head.protection"; isChecked = model.protectHead
            setOnCheckedChangeListener { _, value -> model.protectHead = value; preview.protectHead = value }
        })
        fitControls = FitControls(this, { category, fit ->
            model.setFit(category, fit)
            preview.outfitFits = model.outfitFits
        }, { category ->
            model.fitTarget = category; model.fitTargetExplicit = true
            renderFitControls()
        })
        root.addView(Switch(this).apply {
            text = "记住本机贴合设置"; tag = "fitting.fit.remember"
            contentDescription = "开启后仅在本机按品类保存宽度、纵向和上下偏移数值，不保存人像、关键点或会话"
            isChecked = model.fitPreferences.enabled
            setOnCheckedChangeListener { _, value -> model.setFitSaveEnabled(value) }
        })
        root.addView(fitControls)
        standingPanel = StandingGuidePanel(this)
        root.addView(standingPanel)
        val tail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val landmarks = Switch(this).apply {
            text = "显示关键点"
            tag = "fitting.landmarks"
            visibility = View.GONE
            isChecked = model.showLandmarks
            setOnCheckedChangeListener { _, value ->
                model.showLandmarks = value
                preview.showLandmarks = value
            }
        }
        tail.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "预览调试"
            tag = "fitting.preview.debug"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                val expand = landmarks.visibility == View.GONE
                landmarks.visibility = if (expand) View.VISIBLE else View.GONE
                text = if (expand) "收起预览调试" else "预览调试"
            }
        })
        tail.addView(landmarks)
        assetStatus = label("连接服务后加载透明服装", 12f).apply { tag = "fitting.asset" }
        tail.addView(assetStatus)
        tail.addView(button("重载服装", "fitting.reload") { loadOutfit(true) })
        controls = SyntheticControls(this, mock) { settings ->
            pendingPermission = false; stopVision(); model.synthetic = settings; mock.configure(settings)
            if (model.running) beginVision()
        }
        tail.addView(controls)
        controls.render(!model.rgb,model.running)
        tail.addView(label("点击下方按钮会将未镜像的原始照片上传到上面的服务；实时预览不会逐帧上传。", 12f))
        outfitMode = Switch(this).apply {
            text = "生成当前搭配"; tag = "fitting.generation.outfit"; isChecked = model.generateOutfit
            setOnCheckedChangeListener { _, checked -> model.generateOutfit = checked; updateCaptureActions() }
        }
        generationSummary = label("", 12f).apply { tag = "fitting.generation.summary" }
        tail.addView(outfitMode)
        tail.addView(generationSummary)
        generate = button("拍摄并上传生成", "fitting.generate") { requestCapture() }
        val delays = listOf(0, 3, 5, 10)
        val delayChoice = Spinner(this).apply {
            tag = "fitting.capture.delay"
            contentDescription = "拍摄倒计时"
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@TryOnActivity, android.R.layout.simple_spinner_item,
                delays.map { if (it == 0) "拍摄倒计时 · 关闭" else "拍摄倒计时 · ${it} 秒" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(delays.indexOf(model.captureDelaySeconds))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    model.captureDelaySeconds = delays[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        cancel = button("取消生成", "fitting.cancel") { model.controller?.cancel() }
        retry = button("重试请求", "fitting.retry") { model.controller?.retry() }
        tail.addView(row(generate, delayChoice)); tail.addView(row(cancel, retry))
        staticImage = StaticImagePanel(this, lifecycleScope, {
            imagePicker.launch(arrayOf("image/jpeg", "image/png"))
        }, { photo ->
            val controller = model.controller
            val outfit = generationRequest()
            if (!visible || captureBusy || captureCountdown != null || controller?.state?.value?.canGenerate(outfit) != true) false
            else { controller.generate(photo, outfit); updateCaptureActions(); true }
        }) { updateCaptureActions() }
        tail.addView(staticImage)
        mockLabel = label("", 13f).apply { tag = "fitting.result.kind"; visibility = View.GONE }
        tail.addView(mockLabel)
        comparison = ResultComparisonPanel(this, lifecycleScope,
            { model.resultComparisonMode = it }, { status.text = it })
        tail.addView(comparison)
        tail.addView(row(button("硬件诊断", "fitting.diagnostics") { startActivity(Intent(this, MainActivity::class.java)) }.apply { if (guestRoute) visibility = View.GONE },
            button("权限设置", "fitting.permissions") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }))
        tail.addView(button("镜子主屏幕设置", "fitting.home") {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { visionStatus.text = "设备未开放主屏幕设置，请联系管理员配置开机入口。" }
        }.apply { if (guestRoute) visibility = View.GONE })
        tail.addView(label("上衣头部保护仅在双耳可靠时近似生效，侧脸或缺点时不启用；无人体分割、深度测距或精细遮挡。放大只改变显示，不增加相机细节。合成样例非真人。", 11f))
        tail.addView(Switch(this).apply {
            text = "OBJ 网格实验（二维关键点驱动，无真实体深度）"; tag = "fitting.mesh"; isChecked = model.meshMode
            setOnCheckedChangeListener { _, value ->
                model.meshMode = value; preview.mode = if (value) TryOnView.Mode.OBJ_MESH else TryOnView.Mode.PHOTO
            }
        })
        previewPanel = PreviewPanel(this,root,preview,tail, cancelCountdown = { setPreviewFullscreen(false) }, toggleInput = {
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
        val contentHost = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(242,246,249)); addView(pageScroll, FrameLayout.LayoutParams(-1,-1))
        }
        videos = com.smartfitting.mirror.video.VideoExperience(this,model,pageScroll,contentHost,
            stopPreview = { if (model.previewFullscreen) setPreviewFullscreen(false); stopVision() },
            resumePreview = { if (visible && model.running && model.endReason == null) beginVision() },
            interacted = { model.interact() }, endSession = { endExperience(GuestEndReason.MANUAL) })
        setContentView(contentHost)
        WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        setPreviewFullscreen(model.previewFullscreen)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.standing.collect {
            standingPanel.render(it, !model.rgb); refreshPreviewSummary()
        } } }
        lifecycleScope.launch { model.exitRequests.collect { if (it != null) endExperience(it) } }
        lifecycleScope.launch { model.state.collect {
            if (guestRoute) {
                if (it.phase == FittingPhase.CLOSED && model.endReason == null) endExperience(GuestEndReason.SESSION_EXPIRED)
                if (model.endReason != null && (it.phase == FittingPhase.CLOSED ||
                    (it.phase == FittingPhase.ERROR && it.retry == RetryAction.END))) completeGuestReturn(it.phase == FittingPhase.CLOSED)
            }
        } }
        if (guestRoute && model.endReason != null) returnToStandby()
        else if (guestRoute && model.controller == null) {
            try { model.connect(model.baseUrl) }
            catch (_: IllegalArgumentException) { status.text = "服务地址无效，请修改后连接。" }
        }
        if (guestRoute && savedInstanceState == null && intent.getBooleanExtra(StandbyActivity.EXTRA_VIDEO, false))
            model.videoExperience.requestedFromHome = true
    }

    override fun onStart() {
        super.onStart(); visible = true; model.controller?.setVisible(true)
        updateCaptureActions()
        val expired = model.checkExperienceTimeout()
        if (expired != null) endExperience(expired)
        if (model.endReason != null && guestRoute) returnToStandby()
        else if (expired == null) {
            videos.onStart()
            if (model.running && !model.videoExperience.open) beginVision()
        }
    }
    override fun onPause() {
        if (captureCountdown != null) setPreviewFullscreen(false)
        super.onPause()
    }
    override fun onStop() {
        videos.onStop()
        cameraDialog?.dismiss(); cameraDialog = null
        browser?.dismiss(); browser = null
        visible = false; model.playing = mock.playing; model.synthetic = mock.settings
        staticImage.clear()
        model.controller?.setVisible(false); stopVision(); comparison.clear(); super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_permission", pendingPermission)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { videos.dispose(); rgb.close(); mock.close(); super.onDestroy() }
    override fun onUserInteraction() { super.onUserInteraction(); model.interact() }

    private fun endExperience(reason: GuestEndReason) {
        if (model.endReason != null) return
        model.markEnded(reason)
        videos.clear()
        if (model.previewFullscreen) setPreviewFullscreen(false)
        pendingPermission = false; model.running = false; model.visionFailure = null; stopVision()
        staticImage.clear(); comparison.clear(); model.resultComparisonMode = ResultComparisonMode.SIDE_BY_SIDE
        browser?.dismiss(); browser = null; cameraDialog?.dismiss(); cameraDialog = null
        model.outfitAccessories = emptyMap(); model.generateOutfit = false
        model.outfit = emptyMap(); model.fitTarget = null; model.fitTargetExplicit = false; model.lastOutfitSelection = null
        model.catalogAssets = emptyList(); model.catalogQuery = ""
        if (model.controller?.state?.value?.phase != FittingPhase.CLOSED) model.controller?.end()
        if (guestRoute) returnToStandby()
    }
    private fun returnToStandby() {
        if (!guestRoute || returningGuest || isFinishing) return
        returningGuest = true
        if (visible) startActivity(Intent(this, StandbyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra(StandbyActivity.EXTRA_REASON, model.endReason?.name))
        if (model.controller == null || model.controller?.state?.value?.phase == FittingPhase.CLOSED) completeGuestReturn(true)
        else lifecycleScope.launch {
            val remaining = (5_000L - (SystemClock.elapsedRealtime() - (model.endStartedAtMs ?: SystemClock.elapsedRealtime()))).coerceAtLeast(0)
            delay(remaining); completeGuestReturn(false)
        }
    }
    private fun completeGuestReturn(confirmed: Boolean) {
        if (isFinishing || !guestRoute || model.endReason == null) return
        setResult(RESULT_OK, Intent().putExtra(StandbyActivity.EXTRA_CONFIRMED, confirmed)
            .putExtra(StandbyActivity.EXTRA_ORIGIN, model.baseUrl).putExtra(StandbyActivity.EXTRA_CAMERA, model.cameraId)
            .putExtra(StandbyActivity.EXTRA_REASON, model.endReason?.name))
        finish()
    }

    private fun setPreviewFullscreen(value: Boolean) {
        if (!value && captureCountdown != null) {
            captureCountdown?.cancel()
            captureCountdown = null
            previewPanel.renderCountdown(null)
            updateCaptureActions()
        }
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
        model.startExperience(); model.interact()
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
            model.observeBody(StandingGuide.assess(frame.metadata,
                source.capabilities.alignedDepth && frame.metadata.depth != null && frame.pixels.alignedDepth?.metadata == frame.metadata.depth))
            preview.submit(frame, model.state.value.selected)
            controls.render(!model.rgb, true, frame.metadata.trackingState)
            visionStatus.text = "${if (model.rgb) "RGB 摄像头（通道 ${rgb.activeCameraId ?: "未知"}）" else "合成输入（非真人）"} · ${frame.metadata.trackingState} · 无深度/精细遮挡"
            refreshPreviewSummary()
            updateCaptureActions()
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
        model.sourceStopped()
        activeVision = null; hasFrame = false; latestTracking = null; captureBusy = false
        if (captureCountdown != null) setPreviewFullscreen(false)
        assetJobs.values.forEach { it.cancel() }; assetJobs.clear(); assetKeys.clear(); loadedAssets.clear(); assetErrors.clear()
        mock.stop(); rgb.stop(); preview.clear(); generate.isEnabled = false
        visionStatus.text = model.visionFailure ?: "采集已停止，临时预览帧已清理"
        previewPanel.sourceStatus.text = visionStatus.text
        previewPanel.renderInput(model.running,model.visionFailure != null,model.rgb)
        updateCaptureActions()
        controls.render(!model.rgb, false)
    }
    private fun requestCapture() {
        if (!generate.isEnabled || captureCountdown != null) return
        val seconds = model.captureDelaySeconds
        if (seconds == 0) {
            capture()
            return
        }
        val timer = object : CountDownTimer(seconds * 1_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                previewPanel.renderCountdown(((millisUntilFinished + 999L) / 1_000L).toInt())
            }

            override fun onFinish() {
                if (captureCountdown !== this) return
                setPreviewFullscreen(false)
                capture()
            }
        }
        captureCountdown = timer
        previewPanel.renderCountdown(seconds)
        setPreviewFullscreen(true)
        updateCaptureActions()
        timer.start()
    }

    private fun capture() {
        val controller = model.controller ?: return
        val id = activeVision ?: return
        val outfit = generationRequest()
        if (!visible || !hasFrame || !controller.state.value.canGenerate(outfit) || captureBusy || staticImage.busy) return
        captureBusy = true; updateCaptureActions()
        source.captureOriginal { outcome ->
            val photo = outcome.getOrNull()
            if (!visible || activeVision != id || model.controller !== controller || photo?.sessionId?.let { it != id } == true) {
                photo?.close(); return@captureOriginal
            }
            captureBusy = false
            if (photo != null) controller.generate(photo, outfit)
            else status.text = outcome.exceptionOrNull()?.message ?: "拍摄失败，请重试"
            updateCaptureActions()
        }
    }
    private fun render(value: FittingState) {
        status.text = value.message
        val idle = !value.connected && value.phase != FittingPhase.CONNECTING && value.phase != FittingPhase.ENDING
        connect.isEnabled = idle; address.isEnabled = idle
        finishSession.isEnabled = value.connected || value.phase == FittingPhase.CONNECTING
        updateCaptureActions()
        cancel.isEnabled = value.canCancel; retry.isEnabled = value.retry != RetryAction.NONE
        selecting = true
        if (listed != value.garments) {
            listed = value.garments
            garments.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listed.map { "${it.category.label} · ${it.name}" })
        }
        val position = listed.indexOfFirst { it.garmentId == value.selectedId }
        if (position >= 0 && garments.selectedItemPosition != position) garments.setSelection(position)
        selecting = false
        updateCaptureActions()
        if (value.connected && value.garments.isNotEmpty())
            model.catalogAssets = (model.catalogAssets + value.garments).distinctBy { it.previewImage.url }
        browser?.render(value)
        mockLabel.text = when (value.job?.isMock) {
            true -> "MOCK · 模拟结果，非真实 AI 换装"
            false -> if (value.job?.outfit != null) "真实搭配生成任务" else "真实单件生成任务"
            null -> ""
        }
        mockLabel.visibility = if (value.job == null) View.GONE else View.VISIBLE
        comparison.render(value.original, value.result, model.resultComparisonMode, value.generationInProgress)
        if (value.phase in setOf(FittingPhase.ENDING, FittingPhase.CLOSED)) {
            staticImage.clear()
            if (model.previewFullscreen) setPreviewFullscreen(false)
            model.running = false; model.visionFailure = null; stopVision(); comparison.clear()
            model.resultComparisonMode = ResultComparisonMode.SIDE_BY_SIDE
            model.catalogAssets = emptyList(); model.catalogQuery = ""; model.outfit = emptyMap()
            model.outfitAccessories = emptyMap(); model.generateOutfit = false
            model.fitTarget = null; model.fitTargetExplicit = false; model.lastOutfitSelection = null
            outfitStatus.text = "当前搭配已清空"; browser?.dismiss(); browser = null
            fitControls.render(emptyList(),null,OutfitFit())
            assetStatus.text = "会话结束，服装与本地图像已清理"
        } else {
            val current = value.garments.associateBy { it.garmentId }
            model.outfitAccessories = model.outfitAccessories.filterValues { it in value.accessories }
            model.outfit = model.outfit.values.mapNotNull { current[it.garmentId] }
                .fold(emptyMap()) { selection, item -> OutfitSelection.add(selection,item) }
            if (value.selected != model.lastOutfitSelection) {
                value.selected?.let { model.outfit = OutfitSelection.add(model.outfit,it) }
                model.lastOutfitSelection = value.selected
            }
            renderOutfit()
        }
        videos.onSessionChanged()
    }
    private fun generationRequest(): OutfitRequest? = if (model.generateOutfit) {
        OutfitRequest(model.outfit.values.map { it.garmentId }, model.outfitAccessories.values.map { it.accessoryId })
    } else null

    private fun updateCaptureActions() {
        val value = model.controller?.state?.value ?: model.state.value
        val capturing = captureBusy || captureCountdown != null
        val allow = visible && value.canGenerate(generationRequest()) && !capturing
        val importing = ::staticImage.isInitialized && staticImage.busy
        if (::generationSummary.isInitialized) {
            outfitMode.visibility = if (value.outfitAvailable) View.VISIBLE else View.GONE
            outfitMode.isEnabled = value.canSelect && !capturing && !importing
            if (outfitMode.isChecked != model.generateOutfit) outfitMode.isChecked = model.generateOutfit
            generationSummary.text = if (model.generateOutfit) {
                val names = model.outfit.values.map { it.name } + model.outfitAccessories.values.map { it.name }
                if (names.isEmpty()) "先选择服装或配饰" else "将生成：${names.joinToString(" ＋ ")}。拍摄时请让衣摆和腰部清楚入镜。"
            } else "单件生成：${value.selected?.name ?: "未选服装"}"
        }
        generate.text = if (generationRequest() == null) "拍摄并上传生成" else "拍摄并生成当前搭配"
        generate.isEnabled = allow && hasFrame && !importing
        if (::staticImage.isInitialized) staticImage.render(allow)
        garments.isEnabled = value.canSelect && !capturing && !importing
        catalog.isEnabled = value.canSelect && !capturing && !importing
        videoButton.isEnabled = value.canSelect && !capturing && !importing
    }
    private fun renderOutfit() {
        val labels = model.outfit.values.map { "${it.category.label}：${it.name}" } +
            model.outfitAccessories.values.map { it.name }
        outfitStatus.text = "当前搭配：" + labels.joinToString(" ＋ ").ifBlank { "无" }
        renderFitControls()
        loadOutfit()
        updateCaptureActions()
    }
    private fun renderFitControls() {
        val available = model.outfit.keys.toList()
        val retained = model.fitTarget?.takeIf { it in available }
        val category = retained?.takeIf { model.fitTargetExplicit }
            ?: model.state.value.selected?.category?.takeIf { it in available }
            ?: available.firstOrNull()
        model.fitTarget = category
        model.fitTargetExplicit = model.fitTargetExplicit && retained != null
        fitControls.render(available,category,category?.let { model.outfitFits[it] ?: OutfitFit.defaultFor(it) } ?: OutfitFit())
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
            " · ${StandingWords.summary(model.standing.value)}" +
            (if (assetErrors.isEmpty()) "" else " · 有加载失败，请退出全屏重载")
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(26, 43, 55)); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(value: String, id: String, action: () -> Unit) = Button(this).apply { text = value; tag = id; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
    }
}
