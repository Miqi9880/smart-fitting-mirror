package com.smartfitting.mirror

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.StatFs
import android.widget.TextView
import android.widget.ScrollView

/** Read-only discovery. Listing a device never claims SDK, depth or preview support. */
internal object HardwareInventory {
    @Suppress("DEPRECATION")
    fun collect(context: Context): String = buildString {
        appendLine("智能穿衣镜设备核对")
        appendLine("品牌/型号：${Build.MANUFACTURER} / ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("硬件标识：${Build.HARDWARE}；板卡：${Build.BOARD}")
        if (Build.VERSION.SDK_INT >= 31) appendLine("系统报告 SoC：${Build.SOC_MANUFACTURER} / ${Build.SOC_MODEL}")
        appendLine("支持 ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        appendLine("内存：${memory.totalMem/1024/1024} MiB；当前可用 ${memory.availMem/1024/1024} MiB")
        val storage = StatFs(context.filesDir.absolutePath)
        appendLine("应用所在存储：${storage.totalBytes/1024/1024} MiB；可用 ${storage.availableBytes/1024/1024} MiB")
        val metrics = context.resources.displayMetrics
        appendLine("当前应用显示：${metrics.widthPixels}×${metrics.heightPixels}；density=${metrics.density}")
        appendLine("触摸类型配置：${context.resources.configuration.touchscreen}")
        appendLine("\nCamera2 枚举（不等同实际预览成功）：")
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (manager.cameraIdList.isEmpty()) appendLine("系统未枚举摄像头")
            for (id in manager.cameraIdList) {
                val info = manager.getCameraCharacteristics(id)
                appendLine("ID=$id；朝向=${info.get(CameraCharacteristics.LENS_FACING)}；传感器旋转=${info.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
                val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.take(8)
                appendLine("  YUV 尺寸：${sizes?.joinToString { "${it.width}×${it.height}" } ?: "未知"}")
            }
        } catch (_: Exception) { appendLine("无法读取 Camera2 信息，请结合权限和实际预览核对") }
        appendLine("\nUSB 枚举（不申请访问、不读取序列号）：")
        val devices = (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
        if (devices.isEmpty()) appendLine("未枚举 USB 设备；请核对数据线、主机口和转接方式")
        for (device in devices) {
            appendLine("VID=%04X PID=%04X；%s / %s".format(device.vendorId,device.productId,device.manufacturerName ?: "未知",device.productName ?: "未知"))
            appendLine("  接口类：${(0 until device.interfaceCount).joinToString { device.getInterface(it).interfaceClass.toString() }}（14 通常表示视频接口，仍需验证驱动）")
        }
        appendLine("\n本报告不证明摄像头已出图、深度对齐、帧率或厂家 SDK 可用；请另做实际验证。")
    }
    fun show(context: Context): AlertDialog {
        val report = collect(context)
        val content = TextView(context).apply { text = report; textSize = 14f; setPadding(24,16,24,16); setTextIsSelectable(true) }
        return AlertDialog.Builder(context).setTitle("设备信息")
            .setView(ScrollView(context).apply { addView(content) }).setNegativeButton("关闭",null)
            .setPositiveButton("复制核对信息") { _,_ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("智能镜设备核对",report))
            }.show()
    }
}
