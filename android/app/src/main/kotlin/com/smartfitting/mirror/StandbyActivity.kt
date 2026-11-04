package com.smartfitting.mirror

import android.Manifest
import android.app.Application
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.HttpFittingApi
import com.smartfitting.mirror.vision.*
import java.util.UUID

/** Only device connection settings persist. Sensing consent/guest state stay in process memory. */
internal class StandbyModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("device_connection", android.content.Context.MODE_PRIVATE)
    var sensingRequested = false
    var synthetic = false
    var cameraId: String? = settings.getString("camera_id", null)
    var baseUrl = settings.getString("origin", "http://10.0.2.2:8000")!!
    var waitingGuest = false
    var notice = ""
    fun saveConnection() { settings.edit().putString("origin", baseUrl).putString("camera_id", cameraId).apply() }
}

/** Product entry. It never captures/uploads and starts no camera without the user's opt-in. */
class StandbyActivity : ComponentActivity() {
    private val model: StandbyModel by viewModels()
    private val mock = SyntheticVisionSource()
    private lateinit var rgb: CameraRgbSource
    private val source: VisionSource<BitmapPixels> get() = if (model.synthetic) mock else rgb
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var guest: Button
    private lateinit var video: Button
    private lateinit var sensing: Button
    private var visible = false
    private var ticket: String? = null
    private var pendingPermission = false
    private var choiceDialog: AlertDialog? = null
    private var configDialog: AlertDialog? = null
    private var assessment: StandingAssessment? = null
    internal val sensingActive get() = ticket != null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!pendingPermission) return@registerForActivityResult
        pendingPermission = false
        model.sensingRequested = granted
        if (granted) {
            model.notice = ""
            if (visible) beginSensing()
        } else { model.notice = "人体感应未获授权，仍可直接开始游客体验。" }
        render()
    }
    private val experience = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.waitingGuest = false
        result.data?.getStringExtra(EXTRA_ORIGIN)?.let { model.baseUrl = it }
        if (result.data?.hasExtra(EXTRA_CAMERA) == true) model.cameraId = result.data?.getStringExtra(EXTRA_CAMERA)
        model.saveConnection()
        val acknowledged = result.data?.getBooleanExtra(EXTRA_CONFIRMED, false) == true
        val reason = result.data?.getStringExtra(EXTRA_REASON)?.let { runCatching { GuestEndReason.valueOf(it) }.getOrNull() }
        model.notice = (reason?.let { StandingWords.ended(it) } ?: "本次体验已结束") +
            if (acknowledged) "，临时内容已清理。" else "，本机临时内容已清理，服务端清理暂未确认。"
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rgb = CameraRgbSource(this, this,
            targetRotation = { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation }, cameraId = { model.cameraId })
        mock.configure(SyntheticSettings(SyntheticScenario.STILL))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(Color.rgb(242, 246, 249))
        }
        title = label("智能穿衣镜", 30f).apply { tag = "home.title" }
        status = label("", 18f).apply { tag = "home.status"; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(title); root.addView(label("游客体验 · 无需登录", 20f)); root.addView(status)
        guest = button("游客开始试衣", "home.start") {
            if (model.waitingGuest) return@button
            pendingPermission = false; stopSensing(); model.waitingGuest = true; model.notice = ""; render()
            experience.launch(Intent(this, TryOnActivity::class.java).putExtra(EXTRA_GUEST, true)
                .putExtra(EXTRA_ORIGIN, model.baseUrl).putExtra(EXTRA_CAMERA, model.cameraId))
        }
        root.addView(guest)
        root.addView(button("AI试妆（暂不可用）", "home.makeup") {}.apply { isEnabled = false })
        video = button("视频推荐", "home.video") {
            if (model.waitingGuest) return@button
            pendingPermission = false; stopSensing(); model.waitingGuest = true; model.notice = ""; render()
            experience.launch(Intent(this, TryOnActivity::class.java).putExtra(EXTRA_GUEST,true)
                .putExtra(EXTRA_VIDEO,true).putExtra(EXTRA_ORIGIN,model.baseUrl).putExtra(EXTRA_CAMERA,model.cameraId))
        }
        root.addView(video)
        root.addView(button("3D 人台样件（实验，不开启摄像头）", "home.garment3d") {
            if (model.waitingGuest) return@button
            pendingPermission = false; model.sensingRequested = false; stopSensing(); render()
            startActivity(Intent(this, com.smartfitting.mirror.garment3d.Garment3dActivity::class.java))
        })
        sensing = button("开启本地人体感应", "home.sensing") {
            model.notice = ""
            if (model.sensingRequested) {
                pendingPermission = false; model.sensingRequested = false; stopSensing(); render()
            } else {
                model.synthetic = false
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    model.sensingRequested = true; beginSensing()
                } else { pendingPermission = true; permission.launch(Manifest.permission.CAMERA) }
            }
        }
        root.addView(sensing)
        root.addView(button("选择感应相机", "home.camera") {
            choiceDialog?.dismiss()
            choiceDialog = CameraChooser.show(this, model.cameraId) { id ->
                model.cameraId = id
                model.saveConnection()
                if (model.sensingRequested && !model.synthetic) beginSensing()
            }
        })
        root.addView(button("合成引导演示（非真人）", "home.demo") {
            pendingPermission = false; model.notice = ""; model.synthetic = true
            model.sensingRequested = true; beginSensing()
        })
        root.addView(button("服务连接设置", "home.settings") { configureService() })
        root.addView(button("设备诊断", "home.diagnostics") { startActivity(Intent(this, MainActivity::class.java)) })
        root.addView(label("人体感应仅在本机运行；首次进入保持关闭。开始试衣不自动拍照或上传，拍摄生成需另行点击。", 14f))
        root.addView(label("连续30秒未检测到人体、5分钟无操作或会话到期后，自动结束并清理本次体验。", 14f))
        val scroll = ScrollView(this).apply { tag = "home.scroll"; isFillViewport = true; addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, scroll).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        render()
    }

    override fun onStart() {
        super.onStart(); visible = true
        if (model.sensingRequested) {
            if (model.synthetic || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) beginSensing()
            else { model.sensingRequested = false; render() }
        }
    }
    override fun onStop() {
        visible = false; stopSensing()
        choiceDialog?.dismiss(); choiceDialog = null; configDialog?.dismiss(); configDialog = null
        super.onStop()
    }
    override fun onDestroy() { rgb.close(); mock.close(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (model.waitingGuest && intent.hasExtra(EXTRA_REASON)) {
            model.notice = "体验已结束，本机临时内容已清理；正在确认服务状态。"; render()
        }
    }

    private fun beginSensing() {
        if (!visible || !model.sensingRequested) return
        stopSensing()
        val id = UUID.randomUUID().toString(); ticket = id
        source.start(id, { frame ->
            if (ticket != frame.metadata.sessionId) return@start
            assessment = StandingGuide.assess(frame.metadata)
            render()
        }, { update ->
            if (ticket == id && update.state == SourceState.ERROR) {
                model.sensingRequested = false; stopSensing()
                model.notice = "人体感应暂不可用，仍可直接开始游客体验。${update.message}"; render()
            }
        })
        render()
    }
    private fun stopSensing() { ticket = null; assessment = null; mock.stop(); rgb.stop() }
    private fun render() {
        val present = assessment?.presence == BodyPresence.PRESENT
        title.text = if (present && model.synthetic) "合成演示：欢迎状态" else if (present) "欢迎体验智能穿衣镜" else "智能穿衣镜"
        status.text = when {
            model.waitingGuest -> "体验正在进行或确认结束中，请稍候。"
            model.notice.isNotBlank() -> model.notice
            present -> (if (model.synthetic) "合成演示，非真人 · " else "") + "欢迎，请点击游客开始试衣。"
            ticket != null -> (if (model.synthetic) "合成演示，非真人 · " else "") + StandingWords.summary(assessment)
            else -> "人体感应未开启，可直接点击游客开始试衣。"
        }
        guest.isEnabled = !model.waitingGuest
        video.isEnabled = !model.waitingGuest
        sensing.text = if (model.sensingRequested) { if (model.synthetic) "停止合成演示" else "停止人体感应" } else "开启本地人体感应"
        sensing.isEnabled = !model.waitingGuest
    }
    private fun configureService() {
        val input = EditText(this).apply { setSingleLine(); setText(model.baseUrl); contentDescription = "试衣服务地址" }
        configDialog?.dismiss()
        configDialog = AlertDialog.Builder(this).setTitle("服务连接设置").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                try {
                    HttpFittingApi(input.text.toString()).use { }
                    model.baseUrl = input.text.toString().trim(); model.saveConnection()
                    model.notice = "服务地址已设置。"; render()
                } catch (_: IllegalArgumentException) { model.notice = "地址无效，请填写服务的协议、主机和端口。"; render() }
            }.show()
    }
    private fun button(text: String, tag: String, action: () -> Unit) = Button(this).apply {
        this.text = text; this.tag = tag; textSize = 18f; minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(-1, -2); setOnClickListener { action() }
    }
    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(26, 43, 55)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    internal companion object {
        const val EXTRA_GUEST = "com.smartfitting.mirror.GUEST"
        const val EXTRA_VIDEO = "com.smartfitting.mirror.VIDEO_ENTRY"
        const val EXTRA_ORIGIN = "com.smartfitting.mirror.ORIGIN"
        const val EXTRA_CAMERA = "com.smartfitting.mirror.CAMERA"
        const val EXTRA_REASON = "com.smartfitting.mirror.END_REASON"
        const val EXTRA_CONFIRMED = "com.smartfitting.mirror.CLEANUP_CONFIRMED"
    }
}
