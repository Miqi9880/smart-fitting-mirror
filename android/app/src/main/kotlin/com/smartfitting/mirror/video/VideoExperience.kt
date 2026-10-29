package com.smartfitting.mirror.video

import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.smartfitting.mirror.TryOnModel
import com.smartfitting.mirror.contracts.Garment
import com.smartfitting.mirror.contracts.VideoRecommendationRequest
import com.smartfitting.mirror.network.ApiFailure
import com.smartfitting.mirror.network.FittingPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Public catalog metadata / display preferences only; no token, photo, view or player. */
class VideoExperienceMemory {
    var open = false
    var requestedFromHome = false
    var result: VideoResult? = null
    var garments: List<Garment> = emptyList()
    var selectedId: String? = null
    var positionMs = 0
    var volume = 0f
    var retryAtMs = 0L
    fun clear() {
        open = false; requestedFromHome = false; result = null; garments = emptyList()
        selectedId = null; positionMs = 0; volume = 0f; retryAtMs = 0
    }
}

/** Embedded in the same activity/session so navigation never creates a second guest. */
class VideoExperience(
    private val activity: ComponentActivity,
    private val model: TryOnModel,
    private val fittingPage: View,
    host: FrameLayout,
    private val stopPreview: () -> Unit,
    private val resumePreview: () -> Unit,
    private val interacted: () -> Unit,
    endSession: () -> Unit,
) {
    private val memory get() = model.videoExperience
    val panel = VideoPanel(activity).apply { tag = "fitting.video.panel"; visibility = View.GONE }
    private var query: Job? = null
    private var foreground = false
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            interacted()
            if (panel.isPlayerVisible()) panel.releasePlayer() else hide()
        }
    }
    init {
        host.addView(panel, FrameLayout.LayoutParams(-1,-1))
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        panel.onExit = { hide() }; panel.onRetry = { load() }; panel.onInteraction = interacted
        panel.enableSessionEnd(endSession)
    }

    fun onStart() { foreground = true; if (memory.open) show() else onSessionChanged() }
    fun onStop() {
        foreground = false
        if (memory.open) {
            save()
            if (query?.isActive == true && memory.result == null)
                memory.result = VideoResult.Failure("视频加载已暂停，请点击重试继续。")
        }
        query?.cancel(); query = null; panel.clearContent()
    }
    fun dispose() { query?.cancel(); panel.releaseAll() }

    fun onSessionChanged() {
        if (model.state.value.phase in setOf(FittingPhase.ENDING,FittingPhase.CLOSED) || !model.state.value.connected && memory.open && model.controller == null) clear()
        if (memory.open && memory.garments != model.outfit.values.sortedBy { it.garmentId }) {
            query?.cancel(); query = null; memory.garments = model.outfit.values.sortedBy { it.garmentId }
            memory.selectedId = null; memory.positionMs = 0
            memory.result = VideoResult.Failure("搭配已更新，请点击重试查看相关视频。")
            panel.present(memory.result!!)
        }
        if (memory.requestedFromHome && foreground && model.state.value.canSelect) {
            memory.requestedFromHome = false; show()
        }
    }

    fun show() {
        if (!foreground || model.endReason != null) return
        if (!memory.open) interacted()
        memory.open = true; stopPreview(); fittingPage.visibility = View.GONE; panel.visibility = View.VISIBLE; back.isEnabled = true
        val selection = model.outfit.values.sortedBy { it.garmentId }
        if (selection != memory.garments) {
            memory.result = null; memory.selectedId = null; memory.positionMs = 0; memory.garments = selection
        }
        panel.setVolumePreference(memory.volume)
        val cached = memory.result
        if (cached == null) load() else {
            panel.present(cached)
            val items = (cached as? VideoResult.Items)?.videos.orEmpty()
            val index = items.indexOfFirst { it.videoId == memory.selectedId }
            if (index >= 0) panel.resumeClip(index,memory.positionMs,autoplay=false)
        }
    }

    private fun load() {
        query?.cancel(); query = null
        if (!foreground || !memory.open) return
        val session = model.controller
        if (session == null || !model.state.value.canSelect) {
            panel.present(VideoResult.Failure("请先连接服务并选择服装，再查看相关视频。")); return
        }
        val remaining = memory.retryAtMs - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            memory.result = VideoResult.Failure("请求较多，请在 ${(remaining+999)/1000} 秒后重试。")
            panel.present(memory.result!!); return
        }
        memory.result = null; memory.selectedId = null; memory.positionMs = 0
        val selection = model.outfit.values.sortedBy { it.garmentId }.toList()
        memory.garments = selection
        val request = VideoRecommendationRequest(garmentIds=selection.map { it.garmentId })
        panel.showLoading()
        query = activity.lifecycleScope.launch {
            try {
                val result = session.recommendVideos(request).forDisplay()
                if (!foreground || !memory.open || model.controller !== session || selection != model.outfit.values.sortedBy { it.garmentId }) return@launch
                memory.result = result; panel.present(result); panel.setVolumePreference(memory.volume)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!foreground || !memory.open || model.controller !== session || model.state.value.phase == FittingPhase.CLOSED) return@launch
                val apiError = error as? ApiFailure
                if (apiError?.status == 429) memory.retryAtMs = SystemClock.elapsedRealtime() + (apiError.retryAfterSeconds ?: 1).coerceAtMost(86400) * 1000
                val message = when {
                    apiError?.status == 404 -> "当前服务尚未提供视频推荐，请更新服务后重试。"
                    apiError?.status == 429 -> "请求较多，请稍后点击重试。"
                    else -> "视频加载失败，请检查服务连接后重试。"
                }
                memory.result = VideoResult.Failure(message, apiError?.retryable ?: true)
                panel.present(memory.result!!)
            }
        }
    }

    private fun save() {
        val items = (memory.result as? VideoResult.Items)?.videos.orEmpty()
        memory.selectedId = items.getOrNull(panel.currentIndex())?.videoId
        memory.positionMs = panel.currentPositionMs(); memory.volume = panel.volume()
    }
    fun hide() {
        save(); memory.open = false; query?.cancel(); query = null; panel.clearContent()
        panel.visibility = View.GONE; fittingPage.visibility = View.VISIBLE; back.isEnabled = false
        if (foreground && model.endReason == null) resumePreview()
    }
    fun clear() {
        memory.clear(); query?.cancel(); query = null; panel.clearContent()
        panel.visibility = View.GONE; fittingPage.visibility = View.VISIBLE; back.isEnabled = false
    }
}
