package com.smartfitting.mirror.garment3d

import java.net.URI

/** Fixed, offline-only origin. No file/content URLs, remote fallback or JS bridge. */
internal object Garment3dPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PREFIX = "/garment3d/"
    const val ENTRY = "$ORIGIN${PREFIX}index.html?host=android"
    val resources = mapOf(
        "index.html" to "text/html", "style.css" to "text/css",
        "viewer.mjs" to "text/javascript", "viewer-lifecycle.mjs" to "text/javascript",
        "rig.mjs" to "text/javascript", "motion-player.mjs" to "text/javascript",
        "vendor/three.module.js" to "text/javascript", "vendor/three.core.js" to "text/javascript",
        "vendor/addons/controls/OrbitControls.js" to "text/javascript",
        "vendor/addons/loaders/GLTFLoader.js" to "text/javascript",
        "vendor/addons/utils/BufferGeometryUtils.js" to "text/javascript",
        "assets/blue-tee.glb" to "model/gltf-binary", "assets/source.png" to "image/png",
        "assets/sample.json" to "application/json", "assets/motion.json" to "application/json",
        "assets/motion.bin" to "application/octet-stream",
    )
    fun resource(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || uri.port != -1 || uri.rawUserInfo != null) return null
        if (!uri.rawPath.startsWith(PREFIX) || uri.rawPath.contains('%') || uri.rawPath.contains('\\')) return null
        val path = uri.rawPath.removePrefix(PREFIX)
        if (path !in resources || (uri.rawQuery != null && !(path == "index.html" && uri.rawQuery == "host=android"))) null else path
    }.getOrNull()
    fun document(url: String) = resource(url) == "index.html"
}
