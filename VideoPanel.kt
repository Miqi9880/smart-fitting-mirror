package com.smartfitting.mirror.video

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Recommendation cards + player for FR-VID-01/02/05 (#7). Product navigation uses
 * [VideoExperience] inside the shared fitting session. [VideoActivity] is a separate,
 * internal synthetic fixture for component tests.
 *
 * The panel owns a MediaPlayer and releases it (plus any pending clip generation) on
 * [releasePlayer], which hosts must call from onStop / session end. Try-on state lives
 * elsewhere and is never touched here.
 */
class VideoPanel(context: Context) : FrameLayout(context) {
    var onExit: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onInteraction: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val posters = VideoPosterLoader()
    private var volumePreference = 0f
    private var availableCount = 0
    private var prepareTimeout: Runnable? = null
    private var renderedFrame = false
    private var sourceDialog: AlertDialog? = null
    private var endSession: (() -> Unit)? = null
    private var controller: PlaybackController? = null
    private var items: List<VideoItem> = emptyList()
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var loadToken = 0
    private var pendingAutoplay = true
    private var resumePositionMs = 0

    private val listPane: LinearLayout
    private val listScroll: ScrollView
    private val playerPane: FrameLayout
    private val statePane: LinearLayout
    private val texture: TextureView
    private val playerTitle: TextView
    private val playerBadge: TextView
    private val playerStatus: TextView
    private val toggleButton: Button
    private val nextButton: Button
    private val volumeBar: SeekBar
    private val stateMessage: TextView
    private val retryButton: Button

    init {
        setBackgroundColor(Color.rgb(242, 246, 249))
        listPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        listScroll = ScrollView(context).apply { tag = "video.list"; addView(listPane) }
        texture = TextureView(context).apply { tag = "video.surface" }
        playerTitle = overlayLabel("", 18f, bold = true).apply { tag = "video.title" }
        playerBadge = overlayLabel("测试数据 · 程序生成短片，非授权内容", 13f).apply { tag = "video.badge" }
        playerStatus = overlayLabel("", 15f).apply {
            tag = "video.status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        toggleButton = playerButton("暂停", "video.toggle")
        nextButton = playerButton("下一条", "video.next")
        volumeBar = SeekBar(context).apply {
            tag = "video.volume"
            max = 100
            progress = 0
            contentDescription = "音量"
        }
        stateMessage = TextView(context).apply {
            tag = "video.message"
            textSize = 17f
            setTextColor(Color.rgb(26, 43, 55))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        retryButton = Button(context).apply {
            tag = "video.retry"
            text = "重试"
            textSize = 18f
            minHeight = dp(56)
        }
        playerPane = buildPlayerPane(context)
        statePane = buildStatePane(context)
        addView(listScroll)
        addView(playerPane)
        addView(statePane)
        wireControls()
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface?.release()
                surface = Surface(st)
                player?.setSurface(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fitSurface()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        showPane(listScroll)
    }

    /** Renders a repository result: card list, or a readable empty/failure state with a way back. */
    fun present(result: VideoResult) {
        releasePlayer(); posters.clear(); listPane.removeAllViews()
        when (result) {
            is VideoResult.Items -> {
                items = result.videos
                availableCount = result.availableCount
                attachController()
                buildCards()
                showPane(listScroll)
                listPane.findViewWithTag<View>("video.card.0")?.requestFocus()
            }
            is VideoResult.Empty -> showState(result.message, retryable = false)
            is VideoResult.Failure -> showState(result.message, retryable = result.retryable)
        }
    }

    /** Reopens a clip after rotation / returning from background, restoring position and play state. */
    fun resumeClip(index: Int, positionMs: Int, autoplay: Boolean) {
        if (index in items.indices) openClip(index, positionMs, autoplay)
    }

    fun currentIndex(): Int = controller?.snapshot?.index ?: -1
    fun currentPositionMs(): Int = player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: resumePositionMs
    fun isPlaying(): Boolean = controller?.snapshot?.status == PlaybackStatus.PLAYING
    fun volume(): Float = controller?.snapshot?.volume ?: volumePreference
    fun hasRenderedFrame(): Boolean = renderedFrame
    fun isPlayerVisible(): Boolean = playerPane.visibility == View.VISIBLE
    fun showLoading() { releasePlayer(); posters.clear(); showState("正在根据当前搭配查找视频…", false) }
    fun clearContent() {
        releasePlayer(); posters.clear(); sourceDialog?.dismiss(); sourceDialog = null
        listPane.removeAllViews(); items = emptyList(); controller = null
        playerTitle.text = ""; playerStatus.text = ""
    }
    fun enableSessionEnd(action: () -> Unit) {
        endSession = action
        playerPane.findViewWithTag<View>("video.end.player").visibility = View.VISIBLE
        statePane.findViewWithTag<View>("video.end.state").visibility = View.VISIBLE
    }
    fun setVolumePreference(volume: Float) {
        volumePreference = if (volume.isFinite()) volume.coerceIn(0f,1f) else 0f
        controller?.setVolume(volumePreference)
        volumeBar.progress = (volume() * 100).toInt()
    }

    /** Stops playback, cancels pending clip generation and releases the MediaPlayer. */
    fun releasePlayer() {
        val selectedIndex = currentIndex()
        loadToken++
        releaseMediaPlayer()
        controller?.release()
        if (playerPane.visibility == View.VISIBLE) {
            showPane(listScroll)
            listPane.findViewWithTag<View>("video.card.$selectedIndex")?.requestFocus()
        }
    }

    /** Full teardown for host onDestroy. */
    fun releaseAll() {
        releasePlayer()
        worker.shutdownNow()
        posters.close()
        sourceDialog?.dismiss(); sourceDialog = null
        surface?.release()
        surface = null
    }

    private fun attachController() {
        val volume = controller?.snapshot?.volume ?: volumePreference
        controller = PlaybackController(items).also { created ->
            created.setVolume(volume)
            created.listener = { snapshot -> renderControls(snapshot) }
        }
    }

    private fun buildCards() {
        listPane.removeAllViews()
        val heading = TextView(context).apply {
            tag = "video.heading"
            text = "相关视频"
            textSize = 24f
            setTextColor(Color.rgb(26, 43, 55))
            setPadding(0, dp(8), 0, dp(4))
        }
        val note = TextView(context).apply {
            tag = "video.listnote"
            text = if (items.any { it.isTestData }) "测试数据 · 不计入授权视频验收" else
                "显示 ${items.size} 条，共 $availableCount 条相关内容 · 穿搭参考，非同款商品 · 默认静音"
            textSize = 14f
            setTextColor(Color.rgb(192, 57, 43))
            setPadding(0, 0, 0, dp(8))
        }
        listPane.addView(heading)
        listPane.addView(note)
        items.forEachIndexed { index, item -> listPane.addView(card(index, item)) }
        val exit = Button(context).apply {
            tag = "video.exit"
            text = "返回试衣"
            textSize = 18f
            minHeight = dp(56)
            setOnClickListener { onInteraction?.invoke(); onExit?.invoke() }
        }
        listPane.addView(exit)
        if (endSession != null) listPane.addView(endButton("video.end.list"))
    }

    private fun card(index: Int, item: VideoItem): View {
        val minutes = item.durationSeconds / 60
        val seconds = item.durationSeconds % 60
        val orientationWord = if (item.orientation == ClipOrientation.LANDSCAPE) "横屏" else "竖屏"
        val meta = "时长 %d:%02d · %s · %s".format(minutes, seconds, orientationWord, if (item.isTestData) "测试数据" else "授权参考片")
        val tags = "标签：" + item.tags.joinToString(" · ")
        val card = LinearLayout(context).apply {
            tag = "video.card.$index"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = cardBackground()
            isClickable = true
            isFocusable = true
            minimumHeight = dp(56)
            contentDescription = buildString {
                append("播放视频：${item.title}，$meta，$tags")
                if (item.matchesText.isNotBlank()) append("，相关依据：${item.matchesText}")
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener { onInteraction?.invoke(); openClip(index, 0, autoplay = true) }
        }
        card.addView(cardText(item.title, 18f, bold = true))
        if (item.coverUrl != null) {
            val poster = ImageView(context).apply {
                tag = "video.cover.$index"; scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(-1, dp(150))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(poster); posters.load(item.coverUrl, poster)
        }
        card.addView(cardText(meta, 14f))
        card.addView(cardText(tags, 14f))
        if (item.matchesText.isNotBlank()) card.addView(cardText("相关依据：" + item.matchesText, 14f))
        if (item.sourceText.isNotBlank()) card.addView(Button(context).apply {
            text = "来源与许可"; tag = "video.source.$index"; minHeight = dp(48)
            contentDescription = "查看${item.title}的来源与许可"
            setOnClickListener {
                onInteraction?.invoke(); sourceDialog?.dismiss()
                sourceDialog = AlertDialog.Builder(context).setTitle("素材来源与许可")
                    .setMessage(item.sourceText).setPositiveButton("关闭",null).show()
            }
        })
        return card
    }

    private fun openClip(index: Int, positionMs: Int, autoplay: Boolean) {
        val controller = controller ?: return
        if (index !in items.indices) return
        val item = items[index]
        val enteringPlayer = !isPlayerVisible()
        controller.select(index)
        renderPlayerChrome(item)
        showPane(playerPane)
        if (enteringPlayer) playerPane.findViewWithTag<View>("video.back")?.requestFocus()
        val token = ++loadToken
        releaseMediaPlayer()
        pendingAutoplay = autoplay
        resumePositionMs = positionMs
        if (item.playbackUrl != null) {
            startPlayer(null, item)
            return
        }
        if (!item.isTestData) { controller.onError("视频暂不可用，请返回列表选择其他内容。"); return }
        worker.execute {
            val file = try {
                GeneratedClips.ensure(context, item)
            } catch (_: Exception) {
                null
            }
            handler.post {
                if (token != loadToken) return@post
                if (file == null) {
                    controller.onError("测试短片生成失败，请返回列表后重试。")
                } else {
                    startPlayer(file, item)
                }
            }
        }
    }

    private fun startPlayer(file: File?, item: VideoItem) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            if (file != null) mediaPlayer.setDataSource(file.absolutePath)
            else mediaPlayer.setDataSource(context, Uri.parse(item.playbackUrl!!), emptyMap())
        } catch (_: Exception) {
            releaseMediaPlayer(); controller?.onError("视频暂不可用，请返回列表后重试。"); return
        }
        renderedFrame = false
        mediaPlayer.setOnInfoListener { _, what, _ ->
            if (player === mediaPlayer && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) renderedFrame = true
            false
        }
        mediaPlayer.setOnPreparedListener {
            if (player !== mediaPlayer) return@setOnPreparedListener
            prepareTimeout?.let(handler::removeCallbacks); prepareTimeout = null
            fitSurface(item)
            val volume = volume()
            mediaPlayer.setVolume(volume, volume)
            if (resumePositionMs > 0) mediaPlayer.seekTo(resumePositionMs)
            if (pendingAutoplay) {
                mediaPlayer.start()
                controller?.onPrepared()
            } else {
                mediaPlayer.start()
                mediaPlayer.pause()
                controller?.onPrepared()
                controller?.pause()
            }
        }
        mediaPlayer.setOnCompletionListener { if (player === mediaPlayer) controller?.onCompleted() }
        mediaPlayer.setOnErrorListener { _, what, _ ->
            if (player === mediaPlayer) {
                releaseMediaPlayer()
                controller?.onError("视频暂时无法播放（错误码 $what），可返回列表选择其他视频。")
            }
            true
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, _, _ -> if (player === mediaPlayer) fitSurface(item) }
        surface?.let { mediaPlayer.setSurface(it) }
        try { mediaPlayer.prepareAsync() }
        catch (_: Exception) { releaseMediaPlayer(); controller?.onError("视频准备失败，请返回后重试。"); return }
        prepareTimeout = Runnable {
            if (player === mediaPlayer && controller?.snapshot?.status == PlaybackStatus.LOADING) {
                releaseMediaPlayer(); controller?.onError("视频加载超时，请返回列表后重试。")
            }
        }.also { handler.postDelayed(it, 15_000) }
    }

    private fun wireControls() {
        toggleButton.setOnClickListener {
            onInteraction?.invoke()
            val controller = controller ?: return@setOnClickListener
            when (controller.snapshot.status) {
                PlaybackStatus.ERROR -> openClip(controller.snapshot.index, 0, autoplay = true)
                PlaybackStatus.COMPLETED -> {
                    player?.let { media -> runCatching { media.seekTo(0); media.start() } }
                    controller.replay()
                    controller.onPrepared()
                }
                else -> {
                    controller.toggle()
                    applyPlayPause()
                }
            }
        }
        nextButton.setOnClickListener {
            onInteraction?.invoke()
            val controller = controller ?: return@setOnClickListener
            if (controller.next()) {
                openClip(controller.snapshot.index, 0, autoplay = true)
            } else {
                playerStatus.text = "已经是最后一条视频。"
            }
        }
        playerPane.findViewWithTag<Button>("video.back").setOnClickListener { onInteraction?.invoke(); releasePlayer() }
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                onInteraction?.invoke()
                controller?.setVolume(progress / 100f)
                volumePreference = volume()
                val volume = volume()
                player?.setVolume(volume, volume)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        retryButton.setOnClickListener { onInteraction?.invoke(); onRetry?.invoke() }
        statePane.findViewWithTag<Button>("video.state.back").setOnClickListener { onInteraction?.invoke(); onExit?.invoke() }
    }

    private fun applyPlayPause() {
        when (controller?.snapshot?.status) {
            PlaybackStatus.PLAYING -> player?.let { media -> runCatching { if (!media.isPlaying) media.start() } }
            PlaybackStatus.PAUSED -> player?.let { media -> runCatching { if (media.isPlaying) media.pause() } }
            else -> Unit
        }
    }

    private fun renderControls(snapshot: PlaybackSnapshot) {
        toggleButton.text = when (snapshot.status) {
            PlaybackStatus.PLAYING -> "暂停"
            PlaybackStatus.PAUSED -> "继续"
            PlaybackStatus.COMPLETED -> "重新播放"
            PlaybackStatus.ERROR -> "重试"
            else -> "暂停"
        }
        playerStatus.text = when (snapshot.status) {
            PlaybackStatus.LOADING -> "正在准备视频…"
            PlaybackStatus.PLAYING -> if (controller?.current?.isTestData == true) "播放中 · 测试数据" else "播放中 · 授权参考片"
            PlaybackStatus.PAUSED -> "已暂停"
            PlaybackStatus.COMPLETED -> "已播完，可重新播放或切换下一条。"
            PlaybackStatus.ERROR -> snapshot.message
            PlaybackStatus.IDLE -> ""
        }
        nextButton.isEnabled = controller?.hasNext == true
    }

    private fun renderPlayerChrome(item: VideoItem) {
        playerTitle.text = item.title
        playerBadge.visibility = if (item.isTestData) View.VISIBLE else View.GONE
    }

    private fun showState(message: String, retryable: Boolean) {
        releasePlayer()
        items = emptyList()
        stateMessage.text = message
        retryButton.visibility = if (retryable) View.VISIBLE else View.GONE
        showPane(statePane)
        (if (retryable) retryButton else statePane.findViewWithTag<View>("video.state.back"))?.requestFocus()
    }

    private fun showPane(visible: View) {
        listScroll.visibility = if (visible == listScroll) View.VISIBLE else View.GONE
        playerPane.visibility = if (visible == playerPane) View.VISIBLE else View.GONE
        statePane.visibility = if (visible == statePane) View.VISIBLE else View.GONE
    }

    private fun fitSurface(item: VideoItem? = null) {
        val parentWidth = playerPane.width
        val parentHeight = playerPane.height
        if (parentWidth <= 0 || parentHeight <= 0) {
            playerPane.post { fitSurface(item) }
            return
        }
        var videoWidth = player?.videoWidth ?: 0
        var videoHeight = player?.videoHeight ?: 0
        if (videoWidth <= 0 || videoHeight <= 0) {
            val landscape = item?.orientation != ClipOrientation.PORTRAIT
            videoWidth = if (landscape) 16 else 9
            videoHeight = if (landscape) 9 else 16
        }
        val aspect = videoWidth.toFloat() / videoHeight
        var width = parentWidth
        var height = (parentWidth / aspect).toInt()
        if (height > parentHeight) {
            height = parentHeight
            width = (parentHeight * aspect).toInt()
        }
        texture.layoutParams = LayoutParams(width, height, Gravity.CENTER)
    }

    private fun releaseMediaPlayer() {
        prepareTimeout?.let(handler::removeCallbacks); prepareTimeout = null
        renderedFrame = false
        player?.let { media ->
            runCatching { media.setOnPreparedListener(null) }
            runCatching { media.setOnCompletionListener(null) }
            runCatching { media.setOnErrorListener(null) }
            runCatching { media.setOnInfoListener(null) }
            runCatching { media.setOnVideoSizeChangedListener(null) }
            runCatching { if (media.isPlaying) media.stop() }
            runCatching { media.release() }
        }
        player = null
        resumePositionMs = 0
    }

    private fun buildPlayerPane(context: Context): FrameLayout {
        val pane = FrameLayout(context).apply {
            tag = "video.player"
            setBackgroundColor(Color.BLACK)
        }
        pane.addView(texture, LayoutParams(-1, -1, Gravity.CENTER))
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        top.addView(playerTitle)
        top.addView(playerBadge)
        pane.addView(top, LayoutParams(-1, -2, Gravity.TOP))
        playerStatus.setPadding(dp(16), 0, dp(16), 0)
        pane.addView(playerStatus, LayoutParams(-2, -2, Gravity.CENTER))
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            setBackgroundColor(Color.argb(160, 0, 0, 0))
        }
        controls.addView(playerButton("返回列表", "video.back"))
        controls.addView(toggleButton)
        controls.addView(nextButton)
        controls.addView(endButton("video.end.player").apply { visibility = View.GONE })
        val volumeLabel = overlayLabel("音量", 14f)
        controls.addView(volumeLabel)
        controls.addView(volumeBar, LinearLayout.LayoutParams(dp(120), -2))
        // Horizontal scroll keeps every control reachable at 1.3x/1.5x system font sizes.
        val scroller = android.widget.HorizontalScrollView(context).apply { addView(controls) }
        pane.addView(scroller, LayoutParams(-1, -2, Gravity.BOTTOM))
        return pane
    }

    private fun buildStatePane(context: Context): LinearLayout = LinearLayout(context).apply {
        tag = "video.state"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(stateMessage)
        addView(retryButton)
        addView(Button(context).apply {
            tag = "video.state.back"
            text = "返回试衣"
            textSize = 18f
            minHeight = dp(56)
        })
        addView(endButton("video.end.state").apply { visibility = View.GONE })
    }

    private fun endButton(tag: String) = Button(context).apply {
        this.tag = tag; text = "结束体验"; textSize = 16f; minHeight = dp(56)
        setOnClickListener { onInteraction?.invoke(); endSession?.invoke() }
    }

    private fun cardBackground(): Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    private fun cardText(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        textSize = size
        setTextColor(Color.rgb(26, 43, 55))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun overlayLabel(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun playerButton(text: String, tag: String) = Button(context).apply {
        this.text = text
        this.tag = tag
        textSize = 16f
        minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
