package com.smartfitting.mirror

import android.content.Context
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.smartfitting.mirror.contracts.CapturedPhoto
import kotlinx.coroutines.*
import java.io.InputStream

/** Activity-owned pending input. Selection never uploads or persists a photo. */
internal class StaticImagePanel(context: Context, private val scope: CoroutineScope,
    onPick: () -> Unit, private val onSubmit: (CapturedPhoto) -> Boolean,
    private val onBusyChanged: () -> Unit) : LinearLayout(context) {
    private val message = TextView(context).apply {
        tag = "fitting.import.status"; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val image = ImageView(context).apply {
        tag = "fitting.import.preview"; contentDescription = "所选照片本机预览，尚未上传"
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val owned = OwnedImage(image, scope) { message.text = it }
    private var pending: CapturedPhoto? = null
    private var loading: Job? = null
    @Volatile private var epoch = 0
    private val streamLock = Any()
    private var activeStream: InputStream? = null
    var busy = false
        private set
    private var interactive = false
    private val choose = Button(context).apply {
        tag = "fitting.import"; text = "选择照片（仅本机预览）"
        setOnClickListener { onPick() }
    }
    private val upload = Button(context).apply {
        tag = "fitting.import.generate"; text = "上传所选照片生成"
        setOnClickListener {
            val photo = pending ?: return@setOnClickListener
            if (!interactive || busy) return@setOnClickListener
            // The session takes ownership only when the caller accepts this handoff.
            if (onSubmit(photo)) {
                pending = null; owned.clear()
                message.text = "照片已交给生成流程，可在下方查看结果或结束并清理"
                render(interactive)
            } else message.text = "当前无法生成，请确认服务与可生成服装后重试"
        }
    }
    private val discard = Button(context).apply {
        tag = "fitting.import.clear"; text = "清除待上传照片"
        setOnClickListener { clear() }
    }

    init {
        tag = "fitting.import.panel"; orientation = VERTICAL
        addView(TextView(context).apply {
            text = "也可导入获准的 JPEG/PNG 照片（最多 10 MiB、1600 万像素）。选择后仅本机预览；点击上传才提交给上方服务。离开页面或旋转屏幕会清除待上传照片。"
        })
        addView(choose); addView(message)
        addView(image, LayoutParams(-1, (180 * resources.displayMetrics.density).toInt()))
        addView(upload); addView(discard)
        clear()
    }

    fun render(enabled: Boolean) {
        interactive = enabled
        choose.isEnabled = enabled && !busy
        upload.isEnabled = enabled && !busy && pending != null
        discard.isEnabled = busy || pending != null
        image.visibility = if (pending != null) VISIBLE else GONE
    }

    fun clear() {
        epoch++; loading?.cancel(); loading = null
        val stream = synchronized(streamLock) { activeStream.also { activeStream = null } }
        runCatching { stream?.close() }
        pending?.close(); pending = null; owned.clear(); busy = false
        message.text = "尚未选择照片；不会自动上传"
        render(interactive); onBusyChanged()
    }

    /** The supplied stream is owned and closed here, including cancellation/replacement. */
    fun load(open: () -> InputStream) {
        if (!interactive) return
        clear()
        val request = epoch
        busy = true; message.text = "正在本机检查照片与方向…"
        render(interactive); onBusyChanged()
        loading = scope.launch {
            var prepared: CapturedPhoto? = null
            try {
                withContext(Dispatchers.IO) {
                    val stream = open()
                    try {
                        synchronized(streamLock) {
                            if (epoch != request) throw CancellationException()
                            activeStream = stream
                        }
                        val jobContext = currentCoroutineContext()
                        prepared = StaticImageInput.read(stream) { jobContext.ensureActive() }
                    } finally {
                        synchronized(streamLock) { if (activeStream === stream) activeStream = null }
                        stream.close()
                    }
                }
                ensureActive()
                if (epoch == request) {
                    val photo = requireNotNull(prepared)
                    pending = photo; prepared = null
                    owned.update(photo.bytes)
                    message.text = "本机预览 · ${photo.width} × ${photo.height} · 已整理方向，尚未上传"
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                if (epoch == request) {
                    message.text = "导入失败：${error.message ?: "无法读取图片"}；可重新选择"
                }
            } finally {
                prepared?.close()
                if (epoch == request) {
                    busy = false; loading = null; render(interactive); onBusyChanged()
                }
            }
        }
    }
}
