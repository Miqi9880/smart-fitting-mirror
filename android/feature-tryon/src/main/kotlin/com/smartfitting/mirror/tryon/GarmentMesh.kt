package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.*
import kotlin.math.*

/** Adapts SmartMirror GarmentDriver OBJ parsing and torso/arm region driving.
 * 2D landmarks drive an orthographic projection, never inferred metric body depth.
 * Source: J0KER-N/SmartMirror@11b667b; third_party/SmartMirror/NOTICE.md.
 */
data class MeshPoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(p: MeshPoint) = MeshPoint(x+p.x,y+p.y,z+p.z)
    operator fun minus(p: MeshPoint) = MeshPoint(x-p.x,y-p.y,z-p.z)
    operator fun times(s: Float) = MeshPoint(x*s,y*s,z*s)
    fun length() = sqrt(x*x+y*y+z*z)
    fun cross(p: MeshPoint) = MeshPoint(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x)
}
data class GarmentMesh(val vertices: List<MeshPoint>, val triangles: List<IntArray>) {
    companion object {
        fun readObj(lines: Sequence<String>): GarmentMesh {
            val vertices = mutableListOf<MeshPoint>(); val faces = mutableListOf<IntArray>()
            for (raw in lines) {
                require(raw.length <= 4096) { "OBJ line too long" }
                val fields = raw.substringBefore('#').trim().split(Regex("\\s+"))
                when (fields.firstOrNull()) {
                    "v" -> {
                        require(fields.size >= 4 && vertices.size < 100_000)
                        val p = fields.drop(1).take(3).map { it.toFloat().also { v -> require(v.isFinite()) } }
                        vertices += MeshPoint(p[0],p[1],p[2])
                    }
                    "f" -> {
                        require(fields.size >= 4 && fields.size <= 128)
                        val indices = fields.drop(1).map {
                            val index = it.substringBefore('/').toInt()
                            val resolved = if (index > 0) index-1 else vertices.size+index
                            require(index != 0 && resolved in vertices.indices); resolved
                        }
                        for (i in 1 until indices.lastIndex) {
                            require(faces.size < 200_000)
                            faces += intArrayOf(indices[0],indices[i],indices[i+1])
                        }
                    }
                }
            }
            require(vertices.isNotEmpty() && faces.isNotEmpty()) { "OBJ has no drawable mesh" }
            return GarmentMesh(vertices,faces)
        }
    }
}
data class DrivenMesh(val vertices: List<MeshPoint>, val triangles: List<IntArray>)

class GarmentMeshDriver(private val mesh: GarmentMesh) {
    fun bind(frame: VisionFrame): DrivenMesh? {
        if (frame.trackingState != TrackingState.TRACKING) return null
        fun pixel(p: Landmark?) = p?.takeIf { it.confidence >= .5f }?.let {
            MeshPoint(.5f+it.x*(frame.image.width-1),.5f+it.y*(frame.image.height-1),0f)
        }
        val ls = pixel(frame.keypoints.leftShoulder) ?: return null
        val rs = pixel(frame.keypoints.rightShoulder) ?: return null
        val lh = pixel(frame.keypoints.leftHip) ?: return null
        val rh = pixel(frame.keypoints.rightHip) ?: return null
        val torsoWidth = (ls-rs).length()
        if (torsoWidth < 2f || ((lh+rh)*.5f-(ls+rs)*.5f).length() < 2f) return null
        val leftElbow = pixel(frame.keypoints.leftElbow)
        val rightElbow = pixel(frame.keypoints.rightElbow)
        fun torso(p: MeshPoint): MeshPoint {
            val u = (p.x+.15f)/.3f; val v = (p.y-.10f)/.29f
            val top = rs*(1-u)+ls*u; val bottom = rh*(1-u)+lh*u
            return (top*(1-v)+bottom*v)+MeshPoint(0f,0f,p.z*torsoWidth/.3f)
        }
        val visible = BooleanArray(mesh.vertices.size) { true }
        val vertices = mesh.vertices.mapIndexed { index,p ->
            // The supplied OBJ's sleeve vertices lie outside the +/-0.15 m torso.
            // Positive image X is the wearer's left; upstream naming is adapted.
            if (abs(p.x) > .16f) {
                val shoulder = if (p.x > 0) ls else rs
                val elbow = if (p.x > 0) leftElbow else rightElbow
                if (elbow == null || (elbow-shoulder).length() < 2f) {
                    visible[index] = false; torso(p)
                } else {
                    val arm = elbow-shoulder
                    val length = arm.length()
                    val along = (abs(p.x)-.15f)/.4f
                    val normal = MeshPoint(-arm.y/length,arm.x/length,0f)
                    shoulder+arm*(along*.7f)+normal*((p.y-(.10f+along*.07f))*torsoWidth/.3f)+
                        MeshPoint(0f,0f,p.z*torsoWidth/.3f)
                }
            } else torso(p)
        }
        return DrivenMesh(vertices,mesh.triangles.filter { face -> face.all { visible[it] } })
    }
}
