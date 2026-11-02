package com.smartfitting.mirror

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import kotlin.math.roundToInt

/** Local per-category adjustments. Changing these never uploads a camera image. */
internal class FitControls(context: Context, private val changed: (GarmentCategory, OutfitFit) -> Unit,
    private val targetChanged: (GarmentCategory) -> Unit) : LinearLayout(context) {
    private val toggle = Button(context).apply { tag = "fitting.fit.toggle" }
    private val panel = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val target = Spinner(context).apply {
        tag = "fitting.fit.target"; minimumHeight = (48*resources.displayMetrics.density).toInt()
    }
    private val summary = TextView(context).apply { tag = "fitting.fit.summary" }
    private var categories = emptyList<GarmentCategory>()
    private var category: GarmentCategory? = null
    private var fit = OutfitFit()
    private var updating = false
    private fun slider(id: String, maxValue: Int, read: (Int) -> OutfitFit) = SeekBar(context).apply {
        tag = id; max = maxValue
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                val current = category ?: return
                fit = read(value); updateSummary(); changed(current,fit)
            }
        })
    }
    private val width = slider("fitting.fit.width",180) { fit.copy(width = (it+60)/100f) }
    private val length = slider("fitting.fit.length",100) { fit.copy(length = (it+60)/100f) }
    private val offset = slider("fitting.fit.offset",150) { fit.copy(offset = (it-75)/100f) }
    init {
        orientation = VERTICAL
        toggle.setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        addView(toggle); addView(panel)
        target.id = View.generateViewId()
        panel.addView(TextView(context).apply { text = "正在调整的品类"; labelFor = target.id })
        target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = categories.getOrNull(position) ?: return
                if (!updating && selected != category) targetChanged(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        panel.addView(target)
        panel.addView(summary)
        for ((label,bar) in listOf("宽度：左窄／右宽" to width,"纵向尺寸：左短／右长" to length,
            "位置：左上移／右下移" to offset)) {
            panel.addView(TextView(context).apply { text = label })
            bar.contentDescription = label; panel.addView(bar)
        }
        panel.addView(Button(context).apply {
            text = "恢复本类默认贴合"; tag = "fitting.fit.reset"
            setOnClickListener {
                category?.let { current ->
                    fit = OutfitFit.defaultFor(current); syncSliders(); changed(current,fit)
                }
            }
        })
        panel.addView(TextView(context).apply { text = "只影响当前品类的二维预览，不代表真实尺码；帽子需正面双耳，裙裤需相应腿部点。" })
        render(emptyList(),null,OutfitFit())
    }
    fun render(available: List<GarmentCategory>, value: GarmentCategory?, calibration: OutfitFit) {
        updating = true
        categories = available
        if (target.adapter == null || (0 until target.adapter.count).any { target.adapter.getItem(it) != available.getOrNull(it)?.label } ||
            target.adapter.count != available.size) {
            target.adapter = object : ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,available.map { it.label }) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position,convertView,parent).apply {
                        minimumHeight = (48*resources.displayMetrics.density).roundToInt()
                    }
            }.apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        category = value; fit = calibration
        target.isEnabled = value != null
        target.contentDescription = value?.let { "正在调整${it.label}贴合；点击切换当前搭配内的品类" } ?: "搭配为空，无法选择调整目标"
        val position = available.indexOf(value)
        if (position >= 0 && target.selectedItemPosition != position) target.setSelection(position,false)
        toggle.isEnabled = value != null
        toggle.text = value?.let { "调整${it.label}贴合（大小／位置）" } ?: "选中单品后可调整贴合"
        if (value == null) panel.visibility = View.GONE
        syncSliders()
    }
    private fun syncSliders() {
        updating = true
        width.progress = (fit.width*100).roundToInt()-60
        length.progress = (fit.length*100).roundToInt()-60
        offset.progress = (fit.offset*100).roundToInt()+75
        updating = false; updateSummary()
    }
    private fun updateSummary() {
        summary.text = "${category?.label ?: "单品"}：宽 ${(fit.width*100).roundToInt()}% · 纵向 ${(fit.length*100).roundToInt()}% · 位置 ${(fit.offset*100).roundToInt()}"
    }
}
