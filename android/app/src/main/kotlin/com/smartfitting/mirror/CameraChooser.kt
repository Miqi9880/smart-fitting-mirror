package com.smartfitting.mirror

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Enumerating channels does not open a camera or identify its actual RGB/depth role. */
internal object CameraChooser {
    data class Choice(val id: String?, val label: String)
    fun label(id: String?) = id?.let { "相机通道：ID $it" } ?: "相机通道：自动（优先前置）"

    fun choices(context: Context, selected: String?): List<Choice> {
        val result = mutableListOf(Choice(null, "自动（优先前置）"))
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in manager.cameraIdList) {
                val facing = when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前向"
                    CameraCharacteristics.LENS_FACING_BACK -> "背向"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                    else -> "朝向未知"
                }
                result += Choice(id, "ID $id · $facing（彩色能力需实测）")
            }
        } catch (_: Exception) { /* Keep automatic and the explicit selection available. */ }
        if (selected != null && result.none { it.id == selected })
            result += Choice(selected, "ID $selected（当前未枚举，请核对连接）")
        return result
    }

    fun show(context: Context, selected: String?, options: List<Choice> = choices(context,selected),
             changed: (String?) -> Unit): AlertDialog {
        var index = options.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        return AlertDialog.Builder(context).setTitle("选择相机通道；USB设备可能含红外／深度通道")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), index) { _, value -> index = value }
            .setNegativeButton("取消",null)
            .setPositiveButton("使用本通道") { _,_ -> changed(options[index].id) }
            .show()
    }
}
