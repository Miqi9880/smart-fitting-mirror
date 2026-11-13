package com.smartfitting.mirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.orbbec.obsensor.AlignFilter
import com.orbbec.obsensor.Config
import com.orbbec.obsensor.Device
import com.orbbec.obsensor.DeviceChangedCallback
import com.orbbec.obsensor.DeviceList
import com.orbbec.obsensor.FrameSet
import com.orbbec.obsensor.OBContext
import com.orbbec.obsensor.Pipeline
import com.orbbec.obsensor.types.Format
import com.orbbec.obsensor.types.FrameAggregateOutputMode
import com.orbbec.obsensor.types.FrameType
import com.orbbec.obsensor.types.SensorType
import com.orbbec.obsensor.types.StreamType
import com.smartfitting.mirror.contracts.VisionSource
import com.smartfitting.mirror.vision.BitmapPixels
import com.smartfitting.mirror.vision.DepthGeometry
import com.smartfitting.mirror.vision.RgbdReader
import com.smartfitting.mirror.vision.RgbdSample
import com.smartfitting.mirror.vision.RgbdVisionSource
import java.math.BigInteger

internal fun geminiDepthSource(context: Context): VisionSource<BitmapPixels>? =
    RgbdVisionSource(context, GeminiReader(context.applicationContext))

/** Android 14+ requires an export flag for the SDK's private USB permission broadcast. */
private class OrbbecContext(context: Context) : ContextWrapper(context) {
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        if (filter.countActions() == 1 && filter.getAction(0).startsWith("orbbec.action.REQUIRE_USB_PERMISSION#")) {
            ContextCompat.registerReceiver(baseContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else super.registerReceiver(receiver, filter)
}

private class GeminiReader(private val context: Context) : RgbdReader {
    override fun stream(active: () -> Boolean, onSample: (RgbdSample) -> Unit) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        check(usb.deviceList.values.any { it.vendorId == VID && it.productId == PID }) { "Gemini 2 USB device missing" }
        val callbacks = object : DeviceChangedCallback {
            override fun onDeviceAttach(list: DeviceList) = list.close()
            override fun onDeviceDetach(list: DeviceList) = list.close()
        }
        OBContext(OrbbecContext(context), callbacks).use { sdk ->
            val device = awaitDevice(sdk, active) ?: return
            device.use {
                Pipeline(it).use { pipeline ->
                    Config().use { config ->
                        pipeline.getStreamProfileList(SensorType.COLOR).use { profiles ->
                            val preferred = runCatching { profiles.getVideoStreamProfile(640, 480, Format.RGB, 30) }
                                .getOrNull()
                            val rgb = preferred ?: profiles.getVideoStreamProfile(0, 0, Format.RGB, 0)
                            checkNotNull(rgb) { "No RGB profile" }.use(config::enableStream)
                        }
                        config.enableVideoStream(StreamType.DEPTH, 0, 0, 0, Format.Y16)
                        config.setFrameAggregateOutputMode(FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_ALL_TYPE_FRAME_REQUIRE)
                        pipeline.start(config)
                    }
                    try {
                        pipeline.enableFrameSync()
                        AlignFilter(StreamType.COLOR).use { align ->
                            var id = 0L
                            while (active()) {
                                val sample = pipeline.waitForFrameSet(100)?.use rawFrame@{ raw ->
                                    val aligned = align.process(raw) ?: return@rawFrame null
                                    // SDK cast shares the original handle. The typed FrameSet owns it here.
                                    val frames = aligned.`as`<FrameSet>(FrameType.FRAME_SET)
                                    frames.use { decode(it, ++id) }
                                }
                                if (sample != null) onSample(sample)
                            }
                        }
                    } finally { pipeline.stop() }
                }
            }
        }
    }

    private fun awaitDevice(sdk: OBContext, active: () -> Boolean): Device? {
        while (active()) {
            sdk.queryDevices().use { devices ->
                val index = (0 until devices.deviceCount).firstOrNull {
                    devices.getVid(it) == VID && devices.getPid(it) == PID
                }
                if (index != null) {
                    val device = runCatching { devices.getDevice(index) }.getOrNull()
                    if (device != null) return device
                }
            }
            Thread.sleep(120)
        }
        return null
    }

    private fun decode(frames: FrameSet, id: Long): RgbdSample? {
        val color = frames.colorFrame ?: return null
        color.use { rgb ->
            val depth = frames.depthFrame ?: return null
            depth.use { z ->
                val width = rgb.width
                val height = rgb.height
                if (width !in 1..1920 || height !in 1..1080 || z.width != width || z.height != height ||
                    rgb.format != Format.RGB || z.format != Format.Y16) return null
                if (rgb.timeStampUs.subtract(z.timeStampUs).abs() > MAX_PAIR_DELTA_US) return null
                val count = width * height
                if (rgb.dataSize != count * 3 || z.dataSize != count * 2) return null
                val colors = ByteArray(count * 3)
                val depths = ByteArray(count * 2)
                if (rgb.getData(colors) != colors.size || z.getData(depths) != depths.size) return null
                val millimeters = DepthGeometry.millimeterPlane("gemini_depth_$id", width, height, depths, z.valueScale, 0)
                val pixels = IntArray(count) { pixel ->
                    val offset = pixel * 3
                    (0xff shl 24) or ((colors[offset].toInt() and 255) shl 16) or
                        ((colors[offset + 1].toInt() and 255) shl 8) or (colors[offset + 2].toInt() and 255)
                }
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                return RgbdSample(bitmap, millimeters, SystemClock.elapsedRealtime())
            }
        }
    }

    private companion object {
        const val VID = 0x2bc5
        const val PID = 0x0670
        val MAX_PAIR_DELTA_US: BigInteger = BigInteger.valueOf(50_000)
    }
}
