package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.vision.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

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

    @Test fun nearbyFrontDepthPreservesForearmEdgesAndFingerGapsWithoutCuttingTheTorso() {
        val size = 100
        val depth = DepthMetadata("arm-depth", size, size, size * 2)
        val bytes = ByteArray(size * size * 2)
        fun setDepth(x: Int, y: Int, mm: Int) {
            val index = (y * size + x) * 2
            bytes[index] = mm.toByte()
            bytes[index + 1] = (mm shr 8).toByte()
        }
        for (row in 0 until size) for (column in 0 until size) setDepth(column, row, 2000)
        for (column in 25..55) setDepth(column, 45, 1850)
        setDepth(35, 45, 1975)
        setDepth(51, 45, 0)
        setDepth(54, 45, 1995)
        setDepth(61, 45, 0)
        setDepth(60, 45, 1850)
        setDepth(62, 45, 1850)
        fun point(x: Int, y: Int) = Landmark(x / 99f, y / 99f, .9f)
        val keypoints = Keypoints(leftShoulder = point(75, 22), rightShoulder = point(25, 22),
            leftHip = point(70, 76), rightHip = point(30, 76),
            rightElbow = point(25, 45), rightWrist = point(42, 45))
        val frame = VisionFrame(sessionId = "arm-depth", frameId = 1, timestampMs = 1,
            image = ImageMetadata("rgb", size, size), trackingState = keypoints.state(),
            keypoints = keypoints, depth = depth)
        val plane = AlignedDepthPixels(depth, bytes)
        val mask = IntArray(size * size)
        DepthOcclusion.fillMask(plane, 2000, mask)
        assertEquals(-1, mask[45 * size + 35])
        assertEquals(-1, mask[45 * size + 51])
        val fingers = HandLandmarks(emptyList(), listOf(point(55, 45)))
        DepthOcclusion.refineArms(frame, plane, fingers, 2000, mask)
        assertEquals(0, mask[45 * size + 35])
        assertEquals(0, mask[45 * size + 51])
        assertEquals(-1, mask[45 * size + 54])
        assertEquals(-1, mask[45 * size + 61])
    }

    @Test fun sparseFingerDepthDoesNotFlipAnOpenHandBehindTheShirt() {
        // Reported in #97: with the forearm crossed on the chest and the fingers
        // spread, the finger outlines still ended up under the shirt. The finger
        // pixels themselves are shallower than the torso, but depth sensors
        // return 0 (unknown) along thin structures and at the gaps between
        // fingers. fillMask treats unknown as "not occluded", so the shirt is
        // drawn over those pixels unless refineArms turns them into foreground.
        // refineArms used to require measured foreground nearby, so a
        // finger made of unknown samples stayed occluded.
        val size = 100
        val depth = DepthMetadata("finger-depth", size, size, size * 2)
        val bytes = ByteArray(size * size * 2)
        fun setDepth(x: Int, y: Int, mm: Int) {
            val index = (y * size + x) * 2
            bytes[index] = mm.toByte()
            bytes[index + 1] = (mm shr 8).toByte()
        }
        for (row in 0 until size) for (column in 0 until size) setDepth(column, row, 2000)
        // Forearm band across the chest.
        for (column in 20..56) setDepth(column, 45, 1850)
        // Four spread fingers fanning out from the wrist at (42,45) to tips at
        // x = 64, y = 38..47. Each finger is three pixels thick. The base of a
        // finger returns a real distance, but its tip returns no sample at all:
        // that is what a sensor does on a thin structure, and it leaves the last
        // stretch of every finger as an unbroken run of zeros. The tips stay
        // within 0.55 * shoulderSpan of the wrist, the limit refineArms enforces.
        val tips = intArrayOf(38, 41, 44, 47)
        val tipsX = 64
        val unknownFrom = 56
        val fingers = mutableListOf<Pair<Int, Int>>()
        for (tipY in tips) {
            fingers += tipsX to tipY
            for (column in 50..tipsX) {
                val t = (column - 42).toFloat() / (tipsX - 42)
                val lineY = (45f + (tipY - 45) * t).roundToInt()
                for (dy in -1..1) setDepth(column, lineY + dy,
                    if (column >= unknownFrom) 0 else 1850)
            }
        }
        fun point(x: Int, y: Int) = Landmark(x / 99f, y / 99f, .9f)
        val keypoints = Keypoints(leftShoulder = point(75, 22), rightShoulder = point(25, 22),
            leftHip = point(70, 76), rightHip = point(30, 76),
            rightElbow = point(25, 45), rightWrist = point(42, 45))
        val frame = VisionFrame(sessionId = "finger-depth", frameId = 1, timestampMs = 1,
            image = ImageMetadata("rgb", size, size), trackingState = keypoints.state(),
            keypoints = keypoints, depth = depth)
        val plane = AlignedDepthPixels(depth, bytes)
        val mask = IntArray(size * size)
        DepthOcclusion.fillMask(plane, 2000, mask)
        DepthOcclusion.refineArms(frame, plane,
            HandLandmarks(emptyList(), fingers.map { (x, y) -> point(x, y) }), 2000, mask)
        // Every pixel of the open hand must sit in front of the shirt, including
        // the fingertip samples the sensor dropped.
        var fingerPixels = 0
        for (tipY in tips) {
            for (column in unknownFrom..tipsX) {
                val t = (column - 42).toFloat() / (tipsX - 42)
                val lineY = (45f + (tipY - 45) * t).roundToInt()
                for (dy in -1..1) {
                    val pixel = (lineY + dy) * size + column
                    assertEquals("dropped fingertip sample ($column,${lineY + dy}) must stay in front",
                        0, mask[pixel])
                    fingerPixels++
                }
            }
        }
        assertTrue("the fixture must actually contain dropped finger samples", fingerPixels > 20)
        // A large depth hole on the forearm has no measured foreground support.
        for (row in 42..48) for (column in 27..35) setDepth(column, row, 0)
        // A measured torso pixel on a projected finger overrides geometry.
        setDepth(64, 41, 2000)
        DepthOcclusion.fillMask(plane, 2000, mask)
        DepthOcclusion.refineArms(frame, plane,
            HandLandmarks(emptyList(), fingers.map { (x, y) -> point(x, y) }), 2000, mask)
        assertEquals(-1, mask[45 * size + 31])
        assertEquals(-1, mask[41 * size + 64])
        // The shirt away from the hand is still fully drawn.
        assertEquals(-1, mask[20 * size + 30])
        assertEquals(-1, mask[60 * size + 30])
        assertEquals(-1, mask[30 * size + 50])
    }
}
