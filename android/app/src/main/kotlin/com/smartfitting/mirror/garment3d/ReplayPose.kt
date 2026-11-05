package com.smartfitting.mirror.garment3d

import com.smartfitting.mirror.contracts.KeypointName
import com.smartfitting.mirror.vision.ImagePosePoint
import kotlin.math.*

/** Analytic, asymmetric CG fixture. Not recorded human motion or an inference result. */
internal object ReplayPose {
    fun points(seconds: Double): Map<KeypointName,ImagePosePoint> {
        val result = mutableMapOf<KeypointName,ImagePosePoint>()
        val shift = .06*sin(seconds*.6); val scale = 1+.08*sin(seconds*.4)
        fun p(x: Double,y: Double,z: Double=0.0) = ImagePosePoint(
            (.5+shift+x*230*scale/640).toFloat(),(.70-y*230*scale/480).toFloat(),(-z*230*scale/640).toFloat(),.99f)
        result[KeypointName.LEFT_HIP]=p(.15,0.0);result[KeypointName.RIGHT_HIP]=p(-.15,0.0)
        for ((side,sign) in listOf("LEFT" to 1,"RIGHT" to -1)) {
            val phase = .5+.5*sin(seconds*(if(sign==1).9 else .67)+(if(sign==1)0.0 else 1.7))
            val rz = sign*phase*1.15; val rx = -.65*(.5+.5*sin(seconds*.47+sign))
            fun rotate(x: Double,y: Double,z: Double): DoubleArray {
                val cy=y*cos(rx)-z*sin(rx);val cz=y*sin(rx)+z*cos(rx)
                return doubleArrayOf(x*cos(rz)-cy*sin(rz),x*sin(rz)+cy*cos(rz),cz)
            }
            val shoulder=doubleArrayOf(sign*.205,.575,0.0)
            val upper=rotate(sign*.12,-.285,0.0)
            val elbow=DoubleArray(3) { shoulder[it]+upper[it] }
            val lower=rotate(sign*.035,-.23,.06+.08*phase)
            val wrist=DoubleArray(3) { elbow[it]+lower[it] }
            result[KeypointName.valueOf("${side}_SHOULDER")]=p(shoulder[0],shoulder[1],shoulder[2])
            result[KeypointName.valueOf("${side}_ELBOW")]=p(elbow[0],elbow[1],elbow[2])
            result[KeypointName.valueOf("${side}_WRIST")]=p(wrist[0],wrist[1],wrist[2])
        }
        return result
    }
}
