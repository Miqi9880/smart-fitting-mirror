package com.smartfitting.mirror.vision

import org.junit.Assert.*
import org.junit.Test

class ImagePoseMapperTest {
    @Test fun keepsScreenDepthSeparateFromMetricDepthAndNormalizesItByWidth() {
        val p = requireNotNull(ImagePoseMapper.point(320f,120f,-64f,.9f,640,480))
        assertEquals(.5f,p.x,0f); assertEquals(.25f,p.y,0f); assertEquals(-.1f,p.z,0f)
    }
    @Test fun excludesNonFiniteAndOutOfFrameHintsRatherThanMovingThemToTheEdge() {
        assertNull(ImagePoseMapper.point(-1f,20f,0f,1f,640,480))
        assertNull(ImagePoseMapper.point(1f,20f,Float.NaN,1f,640,480))
        assertNull(ImagePoseMapper.point(1f,20f,0f,2f,640,480))
    }
}
