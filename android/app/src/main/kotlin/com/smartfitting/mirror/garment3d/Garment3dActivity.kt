package com.smartfitting.mirror.garment3d

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/** Synthetic mannequin only. This Activity never obtains a camera or a fitting session. */
class Garment3dActivity : ComponentActivity() {
    private companion object { const val NOTICE = "离线合成人台 · 非真人试衣 · 不启用摄像头，不上传照片" }
    internal var webView: WebView? = null
        private set
    private var active = false
    private var destroyed = false
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(250,249,245))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply { text = "返回"; tag = "garment3d.back"; setOnClickListener { finish() } })
        bar.addView(TextView(this).apply {
            text = "3D 人台样件 · 实验"; textSize = 18f; setTextColor(Color.rgb(38,59,63))
        }, LinearLayout.LayoutParams(0,-2,1f).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
        root.addView(bar)
        status = TextView(this).apply {
            tag = "garment3d.status"; text = NOTICE
            textSize = 12f; setTextColor(Color.rgb(76,99,87)); setPadding(dp(12),dp(3),dp(12),dp(5))
        }
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window,root).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        showSample()
    }

    private fun showSample() {
        closeWebView()
        content.removeAllViews()
        status.text = NOTICE
        val missing = Garment3dPolicy.resources.keys.filter { path ->
            runCatching { assets.open("garment3d/$path").use { it.available() > 0 } }.getOrDefault(false).not()
        }
        if (missing.isNotEmpty()) {
            content.addView(TextView(this).apply {
                tag = "garment3d.missing"; textSize = 17f; setPadding(dp(18),dp(20),dp(18),dp(20))
                text = "本安装包尚未包含完整的 3D 样件。\n请由负责人生成模型与动作资源后重新构建 APK；常规试衣仍可使用。"
            })
            return
        }
        runCatching { createWebView() }.onFailure {
            closeWebView()
            content.addView(TextView(this).apply {
                tag = "garment3d.unavailable"; text = "无法启动系统 WebView，请更新系统 WebView 后重试，或返回常规试衣。"
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Only allowlisted APK assets execute; no native JS interface.
    @Suppress("DEPRECATION")
    private fun createWebView() {
        val view = WebView(this).apply { tag = "garment3d.web" }
        webView = view
        with(view.settings) {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true; cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true; setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
        val loader = WebViewAssetLoader.Builder().addPathHandler(Garment3dPolicy.PREFIX) { path ->
            val mime = Garment3dPolicy.resources[path] ?: return@addPathHandler denied()
            runCatching {
                WebResourceResponse(mime,if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null,
                    200,"OK",mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                        "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self' blob:; base-uri 'none'; object-src 'none'; frame-src 'none'"),
                    assets.open("garment3d/$path"))
            }.getOrElse { denied() }
        }.build()
        view.webViewClient = object : WebViewClient() {
            @androidx.annotation.RequiresApi(26)
            override fun onRenderProcessGone(web: WebView,detail: RenderProcessGoneDetail): Boolean {
                if (webView === web) {
                    (web.parent as? ViewGroup)?.removeView(web); web.destroy(); webView = null
                    if (!destroyed) {
                        content.removeAllViews()
                        status.text = "3D 渲染组件已退出，可重新加载样件或返回常规试衣。"
                        content.addView(Button(this@Garment3dActivity).apply {
                            text = "重新加载样件"; tag = "garment3d.retry"; setOnClickListener { showSample() }
                        })
                    }
                }
                return true
            }
            override fun shouldInterceptRequest(web: WebView,request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET" || Garment3dPolicy.resource(request.url.toString()) == null) return denied()
                return loader.shouldInterceptRequest(request.url) ?: denied()
            }
            override fun shouldOverrideUrlLoading(web: WebView,request: WebResourceRequest) = !Garment3dPolicy.document(request.url.toString())
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(web: WebView,url: String) = !Garment3dPolicy.document(url)
            override fun onPageFinished(web: WebView,url: String) { syncActive() }
            override fun onReceivedError(web: WebView,request: WebResourceRequest,error: WebResourceError) {
                if (request.isForMainFrame && !destroyed) status.text = "3D 页面加载失败，可返回试衣后重试。"
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String,callback: GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
        }
        view.setDownloadListener { _,_,_,_,_ -> }
        content.addView(view,LinearLayout.LayoutParams(-1,-1))
        view.loadUrl(Garment3dPolicy.ENTRY)
    }

    override fun onResume() { super.onResume(); active = true; webView?.onResume(); syncActive() }
    override fun onPause() { active = false; syncActive(); webView?.onPause(); super.onPause() }
    override fun onDestroy() { destroyed = true; closeWebView(); super.onDestroy() }
    private fun syncActive() {
        if (destroyed) return
        webView?.evaluateJavascript("window.garment3dHostActive=$active;window.dispatchEvent(new Event('garment3d-host-state'));",null)
    }
    private fun closeWebView() {
        webView?.let { view ->
            view.evaluateJavascript("window.dispatchEvent(new Event('garment3d-host-destroy'));",null)
            view.stopLoading(); (view.parent as? ViewGroup)?.removeView(view); view.removeAllViews(); view.destroy()
        }
        webView = null
    }
    private fun denied() = WebResourceResponse("text/plain","UTF-8",404,"Not Found",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(ByteArray(0)))
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
}
