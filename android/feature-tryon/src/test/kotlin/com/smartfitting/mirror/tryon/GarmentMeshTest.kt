package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test

class GarmentMeshTest {
    private fun mesh() = javaClass.classLoader!!.getResourceAsStream("reference/tshirt.obj")!!.bufferedReader().use {
        GarmentMesh.readObj(it.lineSequence())
    }
    private fun frame(points: Keypoints = SyntheticScene.sample(SyntheticScenario.STILL,0)) = VisionFrame(
        sessionId="mesh",frameId=1,timestampMs=1,image=ImageMetadata("mesh",480,640),trackingState=points.state(),keypoints=points)

    @Test fun suppliedModelLoadsAndMovesWithTheCurrentBody() {
        val m = mesh(); assertEquals(60,m.vertices.size); assertTrue(m.triangles.size > 100)
        val driver = GarmentMeshDriver(m)
        val first = driver.bind(frame())!!
        val shifted = driver.bind(frame(SyntheticScene.sample(SyntheticScenario.TRANSLATE,20)))!!
        for (i in first.vertices.indices) {
            assertEquals(47.9f,shifted.vertices[i].x-first.vertices[i].x,.0001f)
            assertEquals(first.vertices[i].y,shifted.vertices[i].y,.0001f)
        }
    }
    @Test fun currentArmMovesSleevesAndMissingArmHidesItsFaces() {
        val driver = GarmentMeshDriver(mesh())
        val base = SyntheticScene.sample(SyntheticScenario.STILL,0)
        val first = driver.bind(frame(base))!!
        val raised = driver.bind(frame(base.copy(leftElbow=Landmark(.8f,.15f,.9f))))!!
        assertTrue(raised.vertices.zip(first.vertices).any { (a,b) -> kotlin.math.abs(a.y-b.y)>5 })
        val missing = driver.bind(frame(base.copy(leftElbow=null)))!!
        assertTrue(missing.triangles.size < first.triangles.size)
        assertNull(driver.bind(frame(Keypoints())))
        assertNull(driver.bind(frame(base.copy(leftShoulder=null))))
    }
    @Test fun objQuadsAndNegativeIndicesAreTriangulatedAndMalformedInputIsRejected() {
        val quad = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf -4/1 -3/2 -2/3 -1/4"
        val parsed = GarmentMesh.readObj(quad.lineSequence())
        assertEquals(2,parsed.triangles.size); assertArrayEquals(intArrayOf(0,2,3),parsed.triangles[1])
        for (text in listOf("", "v NaN 1 2", "v 0 0 0\nf 1 2 3", "v 0 0 0\nf 0 0 0"))
            assertThrows(IllegalArgumentException::class.java) { GarmentMesh.readObj(text.lineSequence()) }
    }
    @Test fun metricDepthComparisonHandlesPaddingUnknownAndTolerance() {
        val depth = byteArrayOf(0,0, 0x84.toByte(),3, 77,77, 0xDA.toByte(),3, 0x4C,4, 77,77)
        // 0,900 mm, 986 mm,1100 mm; padded rows. 20 mm tolerance at 1000 mm.
        assertArrayEquals(byteArrayOf(-1,0,-1,-1),DepthOcclusion.alpha(depth,2,2,6,1000))
        assertThrows(IllegalArgumentException::class.java) { DepthOcclusion.alpha(depth,2,2,3,1000) }
        assertThrows(IllegalArgumentException::class.java) { DepthOcclusion.alpha(depth,2,3,6,1000) }
        assertThrows(IllegalArgumentException::class.java) { DepthOcclusion.alpha(depth,2,2,6,0) }
    }
}
