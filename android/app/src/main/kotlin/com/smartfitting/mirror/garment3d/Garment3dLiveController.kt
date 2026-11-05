package com.smartfitting.mirror.garment3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** No HTTP session, photo file, cloud calls or browser camera. Pixels live in memory only. */
internal class Garment3dLiveController(
    private val activity: ComponentActivity,
    private val web: () -> WebView?,
    private val changed: (String,String) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private val gate = LiveFrameGate()
    private var generation = 0L
    private var session: String? = null
    private var hints: ImagePoseHints? = null
    private var started = 0L
    private var counter = 0L
    private var closed = false
    private var starting = false
    private var replayTask: Runnable? = null
    var mode = "sample"
        private set
    var mirrored = true
    var showKeypoints = true
    var cameraId: String? = null
    private val camera = CameraRgbSource(activity,activity,
        targetRotation = { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation },
        cameraId = { cameraId },onImagePose = { hints = it })

    fun start(source: String) {
        require(source in setOf("camera","replay"))
        stop()
        val view=web() ?: return
        val ticket=++generation
        val id=UUID.randomUUID().toString()
        val config=JSONObject().put("session",id).put("source",source).toString()
        starting=true
        view.evaluateJavascript("window.garment3dLive?.begin($config)===true") { result ->
            if (ticket!=generation || closed || activity.isFinishing) return@evaluateJavascript
            starting=false
            if(result!="true") { changed("sample","样件还未就绪，请加载完成后重试。");return@evaluateJavascript }
            session=id;mode=source;gate.start();started=SystemClock.elapsedRealtime();counter=0
            changed(mode,if(source=="replay")"姿态回放（非真人）· 不开启摄像头" else "本地真人驱动 · 不上传照片 · 等待画面")
            if(source=="replay") beginReplay() else camera.start(id,{ frame ->
                val pose=hints
                if(pose?.sessionId==frame.metadata.sessionId && pose.frameId==frame.metadata.frameId) offer(frame,pose.points)
            },{ status ->
                if(session==id && status.state==SourceState.ERROR) { stop();changed("sample",status.message) }
            })
        }
    }

    private fun beginReplay() {
        val task=object : Runnable {
            override fun run() {
                val id=session ?: return
                if(mode!="replay")return
                val points=ReplayPose.points((SystemClock.elapsedRealtime()-started)/1000.0)
                val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(232,236,233))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(134,154,143);strokeWidth=26f;strokeCap=Paint.Cap.ROUND }
                fun line(a: KeypointName,b: KeypointName) {
                    val p=points.getValue(a);val q=points.getValue(b);canvas.drawLine(p.x*640,p.y*480,q.x*640,q.y*480,paint)
                }
                for(side in listOf("LEFT","RIGHT")) {
                    line(KeypointName.valueOf("${side}_SHOULDER"),KeypointName.valueOf("${side}_HIP"))
                    line(KeypointName.valueOf("${side}_SHOULDER"),KeypointName.valueOf("${side}_ELBOW"))
                    line(KeypointName.valueOf("${side}_ELBOW"),KeypointName.valueOf("${side}_WRIST"))
                }
                val ls=points.getValue(KeypointName.LEFT_SHOULDER);val rs=points.getValue(KeypointName.RIGHT_SHOULDER)
                canvas.drawCircle((ls.x+rs.x)*320,(ls.y+rs.y)*240-42,25f,paint)
                paint.textSize=19f;paint.color=Color.rgb(43,69,53);canvas.drawText("CG POSE REPLAY / NOT A PERSON",18f,30f,paint)
                val keypoints=Keypoints.fromMap(points.mapValues { (_,p)->Landmark(p.x,p.y,p.confidence) })
                val frame=FrameLease(VisionFrame(sessionId=id,frameId=++counter,timestampMs=SystemClock.elapsedRealtime(),
                    image=ImageMetadata("cg_$counter",640,480),trackingState=keypoints.state(),keypoints=keypoints),BitmapPixels(bitmap)) { it.bitmap.recycle() }
                try { offer(frame,points) } finally { frame.release() }
                handler.postDelayed(this,100)
            }
        }
        replayTask=task;handler.post(task)
    }

    private fun offer(frame: FrameLease<BitmapPixels>,points: Map<KeypointName,ImagePosePoint>) {
        val id=session ?: return
        if(frame.metadata.sessionId!=id)return
        val ticket=gate.acquire(frame.metadata.frameId,SystemClock.elapsedRealtime()) ?: return
        val source=mode;val mirror=mirrored;val keys=showKeypoints;val retained=frame.retain()
        encoder.execute {
            val payload=runCatching {
                val original=retained.pixels.bitmap
                val factor=minOf(1.0,640.0/maxOf(original.width,original.height))
                val scaled=if(factor<1)Bitmap.createScaledBitmap(original,(original.width*factor).toInt(),(original.height*factor).toInt(),true) else original
                try {
                    val bytes=ByteArrayOutputStream().use { stream -> check(scaled.compress(Bitmap.CompressFormat.JPEG,78,stream));stream.toByteArray() }
                    check(bytes.size<=450_000)
                    val mapped=JSONObject()
                    points.forEach { (name,p)->mapped.put(name.name.lowercase(java.util.Locale.ROOT),JSONArray(listOf(p.x,p.y,p.z,p.confidence))) }
                    JSONObject().put("version",1).put("session",id).put("source",source).put("sequence",ticket.sequence)
                        .put("width",scaled.width).put("height",scaled.height).put("mirrored",mirror).put("showKeypoints",keys).put("points",mapped)
                        .put("jpeg",Base64.encodeToString(bytes,Base64.NO_WRAP)).toString().also { bytes.fill(0) }
                } finally { if(scaled!==original)scaled.recycle() }
            }
            retained.release()
            handler.post {
                if(!gate.accepts(ticket) || session!=id)return@post
                val view=web()
                if(view==null || payload.isFailure) { gate.complete(ticket);stop();changed("sample","画面处理失败，请重新开启预览。");return@post }
                // JSON data only. No page-to-native interface; origin/navigation remain allowlisted.
                view.evaluateJavascript("window.garment3dLive?.active()?window.garment3dLive.push(${payload.getOrThrow()}):'stopped'") { result ->
                    if(gate.accepts(ticket)){
                        gate.complete(ticket)
                        if(result=="\"stopped\"") { stop();changed("sample","3D 页面已停止驱动，摄像头与画面已清理，请重试。") }
                    }
                }
                handler.postDelayed({
                    if(gate.accepts(ticket)) { stop();changed("sample","渲染响应超时，已清除画面，请重试。") }
                },2000)
            }
        }
    }

    fun stop() {
        generation++;gate.stop();session=null;hints=null
        replayTask?.let { handler.removeCallbacks(it) };replayTask=null
        camera.stop()
        if(mode!="sample" || starting)web()?.evaluateJavascript("window.garment3dLive?.end()",null)
        starting=false
        mode="sample";changed(mode,"未开启摄像头 · 可先用姿态回放检查驱动")
    }
    override fun close() { if(closed)return;stop();closed=true;camera.close();encoder.shutdown() }
}
