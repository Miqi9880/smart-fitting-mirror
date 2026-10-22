package com.smartfitting.mirror.tryon

import org.junit.Assert.*
import org.junit.Test

class PreviewViewportTest {
    @Test fun zoomRetainsTheChosenFocusAndResetRemovesPanning() {
        val start = PreviewViewport(1.5f,.12f,-.1f)
        val end = start.zoomBy(1.2f,.35f,.7f)
        fun position(view: PreviewViewport, raw: Float, horizontal: Boolean) = .5f +
            view.zoom * (raw-.5f) + if (horizontal) view.offsetX else view.offsetY
        val focusRawX = .5f + (.35f-.5f-start.offsetX)/start.zoom
        val focusRawY = .5f + (.7f-.5f-start.offsetY)/start.zoom
        assertEquals(.35f,position(end,focusRawX,true),.0001f)
        assertEquals(.7f,position(end,focusRawY,false),.0001f)
        assertEquals(PreviewViewport(),end.zoomBy(.01f))
        assertEquals(3f,end.zoomBy(100f).zoom,0f)
    }
    @Test fun wideWindowKeepsLetterboxedAxisCentredAndClampsTheOtherAxis() {
        val zoom = PreviewViewport(2f,10f,-10f).bounded(1920,1080,640,480)
        assertEquals(.25f,zoom.offsetX,.0001f) // 1440 * 2 image fits a 1920 wide window.
        assertEquals(-.5f,zoom.offsetY,.0001f)
        val portrait = PreviewViewport(2f,10f,-10f).bounded(1080,1920,640,480)
        assertEquals(.5f,portrait.offsetX,.0001f)
        assertEquals(0f,portrait.offsetY,.0001f) // Height still letterboxed at 2x.
        assertEquals(PreviewViewport(),PreviewViewport(1f,100f,-100f).bounded(1920,1080,640,480))
    }
    @Test fun resizeAndPanCannotLoseTheImage() {
        val start = PreviewViewport(3f).pan(100f,-100f)
        val landscape = start.bounded(1920,1080,480,640)
        assertEquals(.1328125f,landscape.offsetX,.0001f)
        assertEquals(-1f,landscape.offsetY,0f)
        val portrait = landscape.bounded(1080,1920,640,480)
        assertEquals(-.1328125f,portrait.offsetY,.0001f)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesInvalidZoom() { PreviewViewport(Float.NaN) }
}
