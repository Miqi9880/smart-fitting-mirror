package com.smartfitting.mirror.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Program-generated component-test clips, separate from the licensed HTTP library.
 * This class renders short H.264/MP4 clips locally with MediaCodec + MediaMuxer: a cycling color background, a
 * moving block and a "测试视频 TEST" label, so the footage is unmistakably synthetic.
 * No media is downloaded and nothing here is presented as licensed content.
 *
 * Clips are cached under cacheDir and reused. [ensure] blocks; call it off the UI thread.
 */
object GeneratedClips {
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1
    private val lock = Any()

    /** Returns a playable local file for [item], generating it on first use. */
    fun ensure(context: Context, item: VideoItem): File {
        require(item.videoId.matches(Regex("^[a-z][a-z0-9_-]{0,63}$")) && item.durationSeconds in 1..30)
        val landscape = item.orientation == ClipOrientation.LANDSCAPE
        val width = if (landscape) 640 else 360
        val height = if (landscape) 360 else 640
        val dir = File(context.cacheDir, "generated-clips").apply { mkdirs() }
        val file = File(dir, "${item.videoId}-${width}x${height}-${item.durationSeconds}s.mp4")
        synchronized(lock) {
            if (file.length() == 0L) {
                val tmp = File(dir, file.name + ".tmp")
                if (tmp.exists()) tmp.delete()
                try {
                    generate(tmp, width, height, item.durationSeconds, item.videoId)
                    if (file.exists()) file.delete()
                    check(tmp.renameTo(file)) { "无法保存生成的测试短片" }
                } finally { if (tmp.exists()) tmp.delete() }
            }
        }
        return file
    }

    private fun generate(file: File, width: Int, height: Int, seconds: Int, label: String) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val info = MediaCodec.BufferInfo()
        val frameCount = seconds * FRAME_RATE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0
            var inputDone = false
            var outputDone = false
            var stalls = 0
            while (!outputDone) {
                check(!Thread.currentThread().isInterrupted) { "测试短片生成已取消" }
                var progressed = false
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        progressed = true
                        if (frame < frameCount) {
                            drawFrame(canvas, bitmap, pixels, width, height, frame, frameCount, label)
                            val image = codec.getInputImage(inputIndex)
                                ?: throw IllegalStateException("编码器输入不可用")
                            fillYuv(image, pixels, width, height)
                            codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, frame * 1_000_000L / FRAME_RATE, 0)
                            frame++
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, frameCount * 1_000_000L / FRAME_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        progressed = true
                        val buffer = codec.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("编码器输出不可用")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                stalls = if (progressed) 0 else stalls + 1
                check(stalls < 1_000) { "生成测试短片超时" }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }
            }
            muxer?.release()
            bitmap.recycle()
        }
    }

    private fun drawFrame(
        canvas: Canvas,
        bitmap: Bitmap,
        pixels: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        total: Int,
        label: String,
    ) {
        val progress = frame.toFloat() / total
        val background = Paint().apply {
            color = Color.HSVToColor(floatArrayOf((200f + progress * 160f) % 360f, 0.55f, 0.85f))
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val blockSize = width / 6f
        val blockX = (width - blockSize) * progress
        val block = Paint().apply { color = Color.WHITE }
        canvas.drawRect(blockX, height * 0.55f, blockX + blockSize, height * 0.55f + blockSize, block)
        val text = Paint().apply {
            color = Color.BLACK
            textSize = width / 12f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("测试视频 TEST", width / 2f, height * 0.32f, text)
        val small = Paint(text).apply { textSize = width / 20f; isFakeBoldText = false }
        canvas.drawText("$label · ${frame + 1}/$total", width / 2f, height * 0.44f, small)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Fills the encoder's flexible YUV420 input image from ARGB pixels (BT.601 studio swing). */
    private fun fillYuv(image: Image, pixels: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until height) {
            var yOffset = row * yPlane.rowStride
            val sourceRow = row * width
            val chromaRow = row / 2 * uPlane.rowStride
            for (col in 0 until width) {
                val argb = pixels[sourceRow + col]
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                yBuffer.put(yOffset, y.coerceIn(0, 255).toByte())
                yOffset += yPlane.pixelStride
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    val chromaCol = col / 2
                    uBuffer.put(chromaRow + chromaCol * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                    vBuffer.put(chromaRow + chromaCol * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
