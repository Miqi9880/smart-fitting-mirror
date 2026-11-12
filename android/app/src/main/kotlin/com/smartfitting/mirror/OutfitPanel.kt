package com.smartfitting.mirror

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.smartfitting.mirror.contracts.Garment
import com.smartfitting.mirror.contracts.OutfitAccessory

internal class OutfitPanel(
    context: Context,
    private val removeGarment: (Garment) -> Unit,
    private val removeAccessory: (OutfitAccessory) -> Unit,
    clearOutfit: () -> Unit,
) : LinearLayout(context) {
    private val heading = TextView(context).apply {
        text = "当前搭配为空"
        textSize = 14f
        setTextColor(Color.rgb(26, 43, 55))
        setPadding(0, dp(4), 0, dp(4))
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val items = GridLayout(context)
    private val clear = Button(context).apply {
        text = "清空搭配"
        tag = "fitting.outfit.clear"
        visibility = GONE
        setOnClickListener { clearOutfit() }
    }
    private val removeButtons = mutableListOf<Button>()
    private var shownGarments = emptyList<Garment>()
    private var shownAccessories = emptyList<OutfitAccessory>()
    private var editable = false

    init {
        tag = "fitting.outfit"
        orientation = VERTICAL
        addView(heading)
        addView(items, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(garments: List<Garment>, accessories: List<OutfitAccessory>) {
        if (shownGarments == garments && shownAccessories == accessories) return
        shownGarments = garments
        shownAccessories = accessories
        items.removeAllViews()
        removeButtons.clear()
        val count = garments.size + accessories.size
        heading.text = if (count == 0) "当前搭配为空" else "当前搭配"
        clear.visibility = if (count == 0) GONE else VISIBLE
        clear.isEnabled = editable
        if (count == 0) return
        val screenWidth = resources.configuration.screenWidthDp
        val columns = minOf(count, (screenWidth / 240).coerceIn(1, 3))
        val labelWidth = dp((screenWidth / columns - 80).coerceIn(80, 180))
        items.columnCount = columns
        garments.forEach { garment ->
            addItem(garment.name, "garment.${garment.garmentId}", columns, labelWidth) { removeGarment(garment) }
        }
        accessories.forEach { accessory ->
            addItem(accessory.name, "accessory.${accessory.accessoryId}", columns, labelWidth) { removeAccessory(accessory) }
        }
    }

    fun setEditingEnabled(enabled: Boolean) {
        if (editable == enabled) return
        editable = enabled
        clear.isEnabled = enabled
        removeButtons.forEach { it.isEnabled = enabled }
    }

    private fun addItem(name: String, id: String, columns: Int, labelWidth: Int, remove: () -> Unit) {
        val index = removeButtons.size
        val chip = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(207, 220, 227))
            }
            addView(TextView(context).apply {
                text = name
                textSize = 14f
                maxWidth = labelWidth
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.rgb(26, 43, 55))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            val action = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "×"
                textSize = 22f
                tag = "fitting.outfit.remove.$id"
                contentDescription = "移除$name"
                minimumWidth = 0
                minWidth = 0
                setPadding(0, 0, 0, 0)
                isEnabled = editable
                setOnClickListener { remove() }
            }
            removeButtons += action
            addView(action, LayoutParams(dp(48), dp(48)))
        }
        items.addView(chip, GridLayout.LayoutParams(
            GridLayout.spec(index / columns), GridLayout.spec(index % columns)
        ).apply {
            width = LayoutParams.WRAP_CONTENT
            height = LayoutParams.WRAP_CONTENT
            setMargins(0, dp(2), dp(6), dp(4))
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
