package com.smartfitting.mirror.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.Collections

/** Public poster data only. No guest credentials, redirects or persistent cache. */
internal class VideoPosterLoader : AutoCloseable {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val connections = Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection,Boolean>())
    private val jobs = mutableListOf<Future<*>>()
    private val images = mutableListOf<Pair<ImageView, Bitmap>>()
    private var version = 0
    private var closed = false

    fun load(url: String?, view: ImageView) {
        if (url == null || closed) return
        val ticket = version
        jobs += worker.submit {
            val bitmap = runCatching { read(url) }.onFailure {
                android.util.Log.d("VideoPoster", "Poster unavailable: ${it.javaClass.simpleName}")
            }.getOrNull() ?: return@submit
            main.post {
                if (closed || version != ticket) bitmap.recycle()
                else { view.setImageBitmap(bitmap); images += view to bitmap }
            }
        }
    }
    fun clear() {
        version++
        jobs.forEach { it.cancel(true) }; jobs.clear()
        connections.forEach { it.disconnect() }
        images.forEach { (view, bitmap) -> view.setImageDrawable(null); bitmap.recycle() }; images.clear()
    }
    override fun close() { closed = true; clear(); worker.shutdownNow() }

    private fun read(url: String): Bitmap? {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connections += connection
        val bytes: ByteArray
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            require(connection.responseCode == 200 && connection.contentType?.substringBefore(';') in setOf("image/jpeg", "image/png"))
            require(connection.contentLength <= 2 * 1024 * 1024)
            bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) return null
                    val count = input.read(chunk); if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect(); connections -= connection }
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 16_000_000)
            options.inJustDecodeBounds = false; options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 600) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally { bytes.fill(0) }
    }
}
