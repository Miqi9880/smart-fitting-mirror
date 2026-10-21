package com.smartfitting.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.tryon.TryOnView
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtendedOutfitTest {
    @Test fun dressRendersAContinuousHemHidesWithoutLegsAndReleasesOnReplacement() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view=TryOnView(instrumentation.targetContext).apply { layout(0,0,480,640); mirrored=false }
            val anchors=GarmentAnchors(Anchor(.7f,.12f),Anchor(.3f,.12f),Anchor(.68f,.45f),Anchor(.32f,.45f))
            val controls=mapOf("right_hip" to anchors.rightHip,"left_hip" to anchors.leftHip,
                "right_hem" to Anchor(.3f,.94f),"left_hem" to Anchor(.7f,.94f))
            val dress=Garment("dress","dress",GarmentCategory.DRESS,emptyList(),"http://localhost/dress.png",
                PreviewImage("http://localhost/dress.png",100,140,anchors,controls,1.7f),null)
            val pixels=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val background=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val output=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
            var sequence=0L
            fun draw(points: Keypoints) {
                val frame=FrameLease(VisionFrame(sessionId="extended",frameId=++sequence,timestampMs=sequence,
                    image=ImageMetadata("synthetic",480,640),trackingState=points.state(),keypoints=points),BitmapPixels(background)) {}
                view.submit(frame,dress); frame.release();view.draw(Canvas(output))
            }
            view.setOutfit(listOf(dress));view.setLayerAsset(dress,pixels)
            val pose=SyntheticScene.sample(SyntheticScenario.STILL,0)
            draw(pose)
            // A skirt remains filled through the centre below the knees.
            for (x in 225..255) assertEquals(Color.GREEN,output.getPixel(x,500))
            assertEquals(Color.BLACK,background.getPixel(240,500))
            draw(pose.copy(leftAnkle=null));assertEquals(Color.BLACK,output.getPixel(240,500))
            draw(pose);assertEquals(Color.GREEN,output.getPixel(240,500))
            view.mirrored=true;draw(pose);assertEquals(Color.GREEN,output.getPixel(239,500))
            val shirt=dress.copy(garmentId="shirt",category=GarmentCategory.UPPER_BODY)
            view.setOutfit(listOf(shirt));assertTrue(pixels.isRecycled)
            val late=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888)
            view.setLayerAsset(dress,late);assertTrue(late.isRecycled)
            view.clear();assertFalse(background.isRecycled);background.recycle();output.recycle()
        }
    }
}
