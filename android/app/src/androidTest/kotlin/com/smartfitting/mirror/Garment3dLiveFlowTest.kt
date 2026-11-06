package com.smartfitting.mirror

import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.garment3d.Garment3dActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Drives the production frame encoder/host/page with CG input. Never opens a camera. */
@RunWith(AndroidJUnit4::class)
class Garment3dLiveFlowTest {
    private fun js(scenario: ActivityScenario<Garment3dActivity>,script: String): String {
        val value=AtomicReference<String>();val latch=CountDownLatch(1)
        scenario.onActivity { it.webView!!.evaluateJavascript(script) { result->value.set(result);latch.countDown() } }
        assertTrue("JS timed out",latch.await(10,TimeUnit.SECONDS));return value.get()
    }
    private fun await(s: ActivityScenario<Garment3dActivity>,condition: String) {
        val end=SystemClock.elapsedRealtime()+50_000
        while(SystemClock.elapsedRealtime()<end){if(js(s,condition)=="true")return;SystemClock.sleep(150)}
        fail("State: "+js(s,"[document.querySelector('#load-status')?.textContent,JSON.stringify(document.querySelector('#viewport')?.dataset)].join(' / ')"))
    }
    private fun click(s: ActivityScenario<Garment3dActivity>,tag: String) { s.onActivity { it.window.decorView.findViewWithTag<View>(tag).performClick() } }
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val image=instrumentation.uiAutomation.takeScreenshot()
        try {
            var blue=0
            for(y in 0 until image.height step 4)for(x in 0 until image.width step 4){val c=image.getPixel(x,y)
                if(android.graphics.Color.blue(c)>android.graphics.Color.red(c)+8 && android.graphics.Color.green(c)>android.graphics.Color.red(c)+3)blue++
            }
            assertTrue("Expected 3D garment pixels over the CG input, found $blue",blue>400)
            if(InstrumentationRegistry.getArguments().getString("garment3d_evidence")=="true"){
                val file=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence/garment3d-live-$name.png")
                file.parentFile!!.mkdirs();file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        }finally{image.recycle()}
    }
    @Test fun continuousInputRendersMirrorsAndClearsOnStopAndBackgroundWithoutAutoRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("garment3d_live")=="true")
        ActivityScenario.launch(Garment3dActivity::class.java).use { s->
            await(s,"document.querySelector('#viewport').dataset.sceneState==='ready'")
            s.onActivity { assertEquals("sample",it.liveMode) }
            click(s,"garment3d.replay")
            await(s,"document.querySelector('#viewport').dataset.driverMode==='replay' && document.querySelector('#viewport').dataset.tracked==='true' && +document.querySelector('#viewport').dataset.liveSequence>3")
            val arm=js(s,"document.querySelector('#viewport').dataset.leftArm")
            SystemClock.sleep(900)
            assertNotEquals("A continuous input must change the actual rig",arm,js(s,"document.querySelector('#viewport').dataset.leftArm"))
            screenshot("mirror-on")
            click(s,"garment3d.mirror")
            await(s,"document.querySelector('#viewport').dataset.mirrored==='false'")
            screenshot("mirror-off")
            click(s,"garment3d.keypoints")
            await(s,"document.querySelector('#viewport').dataset.keypoints==='false'")
            s.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await(s,"innerHeight>innerWidth && document.querySelector('#viewport').dataset.tracked==='true'")
            assertEquals("true",js(s,"document.documentElement.scrollWidth<=innerWidth && Math.abs(document.querySelector('#viewport').clientHeight-innerHeight)<2"))
            screenshot("portrait")
            click(s,"garment3d.sample")
            await(s,"document.querySelector('#viewport').dataset.driverMode==='sample' && document.querySelector('.live-background').childElementCount===0")
            click(s,"garment3d.replay")
            await(s,"document.querySelector('#viewport').dataset.tracked==='true'")
            s.moveToState(Lifecycle.State.CREATED)
            await(s,"document.querySelector('#viewport').dataset.driverMode==='sample' && document.querySelector('.live-background').childElementCount===0")
            s.moveToState(Lifecycle.State.RESUMED)
            s.onActivity { assertEquals("sample",it.liveMode) }
            SystemClock.sleep(400)
            assertEquals("true",js(s,"document.querySelector('#viewport').dataset.driverMode==='sample' && document.querySelector('.live-background').childElementCount===0"))
        }
    }
    @Test fun lowConfidenceHidesGarmentAndStreamTimeoutErasesTheLastImage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("garment3d_live")=="true")
        ActivityScenario.launch(Garment3dActivity::class.java).use { s->
            await(s,"document.querySelector('#viewport').dataset.sceneState==='ready'")
            click(s,"garment3d.replay")
            await(s,"document.querySelector('#viewport').dataset.tracked==='true'")
            // Retain only a CG JPEG for deterministic invalid-pose/timeout checks.
            js(s,"(()=>{const c=document.createElement('canvas');c.width=640;c.height=480;c.getContext('2d').drawImage(document.querySelector('.live-background img'),0,0);window.cgJpeg=c.toDataURL('image/jpeg').split(',')[1];return true})()")
            click(s,"garment3d.sample")
            assertEquals("true",js(s,"window.garment3dLive.begin({session:'invalid-pose',source:'replay'})"))
            assertEquals("true",js(s,"window.garment3dLive.push({version:1,session:'invalid-pose',source:'replay',sequence:1,width:640,height:480,mirrored:true,points:{},jpeg:window.cgJpeg})"))
            await(s,"document.querySelector('#viewport').dataset.tracked==='false' && document.querySelector('#viewport').dataset.liveSequence==='1'")
            assertEquals("true",js(s,"document.querySelector('#load-status').textContent.includes('入镜')"))
            await(s,"document.querySelector('.live-background').childElementCount===0 && document.querySelector('#load-status').textContent.includes('中断')")
            assertEquals("false",js(s,"window.garment3dLive.push({version:1,session:'old',source:'replay',sequence:2,width:640,height:480,mirrored:true,points:{},jpeg:window.cgJpeg})"))
            js(s,"delete window.cgJpeg;window.garment3dLive.end();true")
        }
    }
}
