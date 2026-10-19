package com.smartfitting.mirror.vision

import com.smartfitting.mirror.contracts.Keypoints
import com.smartfitting.mirror.contracts.Landmark
import kotlin.math.PI
import kotlin.math.sin

enum class SyntheticScenario { DEMO, STILL, TRANSLATE, SCALE, PARTIAL, LOST, RECOVERY }
enum class SyntheticViewport(val width: Int, val height: Int) { PORTRAIT(480, 640), LANDSCAPE(640, 480) }
data class SyntheticSettings(
    val scenario: SyntheticScenario = SyntheticScenario.DEMO,
    val viewport: SyntheticViewport = SyntheticViewport.PORTRAIT,
)

/** Geometry depends only on scenario and sample index, never wall time or camera availability. */
object SyntheticScene {
    const val CYCLE_STEPS = 80L
    const val STEP_MS = 100L

    fun sample(scenario: SyntheticScenario, step: Long,
               viewport: SyntheticViewport = SyntheticViewport.PORTRAIT): Keypoints {
        require(step >= 0)
        val phase = step % CYCLE_STEPS
        if (scenario == SyntheticScenario.LOST ||
            (scenario == SyntheticScenario.DEMO && phase in 60L..69L) ||
            (scenario == SyntheticScenario.RECOVERY && phase in 40L..59L)) return Keypoints()
        val shift = if (scenario == SyntheticScenario.TRANSLATE || scenario == SyntheticScenario.DEMO)
            (.10 * sin(2 * PI * phase / CYCLE_STEPS)).toFloat() else 0f
        // Vary apparent body size only; this is not a depth or distance measurement.
        val scale = if (scenario == SyntheticScenario.SCALE)
            .7f + .5f * (if (phase <= 40) phase else 80 - phase) / 40f else 1f
        fun point(x: Float, y: Float) = Landmark(.5f + (x - .5f) * scale + shift,
            .45f + (y - .45f) * scale, .95f)
        val points = Keypoints(point(.65f, .25f), point(.35f, .25f),
            point(.7f, .43f), point(.3f, .43f), point(.72f, .6f), point(.28f, .6f),
            point(.6f, .65f), point(.4f, .65f))
        val current = if (scenario == SyntheticScenario.PARTIAL ||
            (scenario == SyntheticScenario.RECOVERY && phase in 20L..39L)) points.copy(leftShoulder = null)
        else points
        // Fit the same pixel-space body into each canvas before normalizing. V07:
        // equal normalized x/y on different aspect ratios would stretch the person.
        val scaleToFit = minOf((viewport.width - 1) / 479f, (viewport.height - 1) / 639f)
        return Keypoints.fromMap(current.asMap().mapValues { (_, p) -> p?.let {
            Landmark(.5f + (it.x - .5f) * 479f * scaleToFit / (viewport.width - 1),
                .5f + (it.y - .5f) * 639f * scaleToFit / (viewport.height - 1), it.confidence)
        } })
    }
}
