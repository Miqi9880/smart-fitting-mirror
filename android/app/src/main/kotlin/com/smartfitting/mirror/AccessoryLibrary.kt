package com.smartfitting.mirror

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.smartfitting.mirror.contracts.AccessoryCategory
import com.smartfitting.mirror.contracts.OutfitAccessory
import com.smartfitting.mirror.network.FittingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun accessoryLibraryDialog(
    context: Context, scope: CoroutineScope, controller: FittingSession,
    available: List<OutfitAccessory>, selected: Map<AccessoryCategory, OutfitAccessory>,
    select: (AccessoryCategory, OutfitAccessory?) -> Unit,
): AlertDialog {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(12))
    }
    content.addView(TextView(context).apply { text = "配饰用于照片搭配生成。每类可选择一件。图为 AI 设计参考。" })
    val jobs = mutableListOf<Job>()
    val images = mutableListOf<OwnedImage>()
    val dialog = AlertDialog.Builder(context).setTitle("配饰素材库")
        .setView(ScrollView(context).apply { addView(content) }).setNegativeButton("关闭", null).create()
    for (category in AccessoryCategory.entries) {
        val items = available.filter { it.category == category }
        if (items.isEmpty()) continue
        content.addView(TextView(context).apply { text = category.label; textSize = 19f })
        if (category in selected) content.addView(Button(context).apply {
            text = "移除${category.label}"
            setOnClickListener { select(category, null); dialog.dismiss() }
        })
        for (item in items) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(preview, LinearLayout.LayoutParams(dp(96), dp(78)))
            val pick = Button(context).apply {
                text = if (selected[category] == item) "${item.name}（已选）" else item.name
                tag = "fitting.accessory.${item.accessoryId}"
                setOnClickListener { select(category, item); dialog.dismiss() }
            }
            row.addView(pick, LinearLayout.LayoutParams(0, dp(56), 1f))
            content.addView(row)
            val owned = OwnedImage(preview, scope) {}
            images += owned
            jobs += scope.launch {
                try {
                    val bytes = controller.accessoryThumbnail(item)
                    try { ensureActive(); owned.update(bytes) } finally { bytes.fill(0) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    preview.contentDescription = "素材缩略图加载失败"
                }
            }
        }
    }
    dialog.setOnDismissListener { jobs.forEach { it.cancel() }; images.forEach { it.clear() } }
    return dialog
}
