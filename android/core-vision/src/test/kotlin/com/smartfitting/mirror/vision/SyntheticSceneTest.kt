package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.*
import org.junit.Assert.*
import org.junit.Test

class SyntheticSceneTest {
    @Test fun recoveryHasFourExplicitPhasesAndRepeats() {
        val expected = listOf(TrackingState.TRACKING, TrackingState.PARTIAL, TrackingState.LOST, TrackingState.TRACKING)
        for (step in 0L..159L) {
            val points = SyntheticScene.sample(SyntheticScenario.RECOVERY, step)
            assertEquals(expected[(step % 80 / 20).toInt()], points.state())
            if (points.state() == TrackingState.LOST) assertTrue(points.asMap().values.all { it == null })
            if (points.state() == TrackingState.PARTIAL) assertNull(points.leftShoulder)
        }
    }
    @Test fun translationKeepsDimensionsAndAnatomicalSides() {
        val middle = SyntheticScene.sample(SyntheticScenario.TRANSLATE, 0)
        val right = SyntheticScene.sample(SyntheticScenario.TRANSLATE, 20)
        val left = SyntheticScene.sample(SyntheticScenario.TRANSLATE, 60)
        for ((name, initial) in middle.asMap()) {
            assertEquals(initial!!.x + .1f, right.asMap().getValue(name)!!.x, .00001f)
            assertEquals(initial.x - .1f, left.asMap().getValue(name)!!.x, .00001f)
            assertEquals(initial.y, right.asMap().getValue(name)!!.y, 0f)
        }
        assertTrue(left.leftShoulder!!.x > left.rightShoulder!!.x)
    }
    @Test fun scaleKeepsCenterAndChangesTorsoSize() {
        val small = SyntheticScene.sample(SyntheticScenario.SCALE, 0)
        val large = SyntheticScene.sample(SyntheticScenario.SCALE, 40)
        fun width(p: Keypoints) = p.leftShoulder!!.x - p.rightShoulder!!.x
        fun height(p: Keypoints) = p.leftHip!!.y - p.leftShoulder!!.y
        assertEquals(1.2f / .7f, width(large) / width(small), .00001f)
        assertEquals(width(large) / width(small), height(large) / height(small), .00001f)
        for (p in listOf(small, large)) assertEquals(.5f, (p.leftShoulder!!.x + p.rightShoulder!!.x) / 2, .00001f)
    }
    @Test fun allScenariosAreRepeatableAndHaveValidCoordinates() {
        for (scenario in SyntheticScenario.entries) for (step in 0L..159L) {
            val points = SyntheticScene.sample(scenario, step)
            assertEquals(points, SyntheticScene.sample(scenario, step + 80))
            points.asMap().values.filterNotNull().forEach {
                assertTrue(it.x in 0f..1f && it.y in 0f..1f && it.confidence >= .5f)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SyntheticScene.sample(SyntheticScenario.STILL, -1) }
    }
    @Test fun fixedScenariosDoNotMoveWithTime() {
        for (scenario in listOf(SyntheticScenario.STILL, SyntheticScenario.PARTIAL, SyntheticScenario.LOST))
            for (step in 1L..80L) assertEquals(SyntheticScene.sample(scenario, 0), SyntheticScene.sample(scenario, step))
        assertEquals(TrackingState.PARTIAL, SyntheticScene.sample(SyntheticScenario.PARTIAL, 0).state())
        assertEquals(TrackingState.LOST, SyntheticScene.sample(SyntheticScenario.LOST, 0).state())
    }
}
