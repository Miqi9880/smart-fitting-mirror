package com.smartfitting.mirror

import android.content.Context
import android.view.View
import android.widget.*
import com.smartfitting.mirror.contracts.TrackingState
import com.smartfitting.mirror.vision.*

/** Development controls; scenarios do not change the public VisionFrame contract. */
class SyntheticControls(context: Context, private val source: SyntheticVisionSource,
    onSettingsChanged: (SyntheticSettings) -> Unit) : LinearLayout(context) {
    private val scenario = Spinner(context).apply {
        tag = "synthetic.scenario"
        contentDescription = "合成人体场景"
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, scenarioLabels)
        setSelection(source.settings.scenario.ordinal)
    }
    private val viewport = Spinner(context).apply {
        tag = "synthetic.viewport"
        contentDescription = "合成图像画幅"
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("竖图 480×640", "横图 640×480"))
        setSelection(source.settings.viewport.ordinal)
    }
    private val summary = TextView(context).apply { tag = "synthetic.summary"; textSize = 12f }
    private var selected = true
    private var running = false
    private var tracking: TrackingState? = null
    private val play = Button(context).apply {
        tag = "synthetic.play"
        setOnClickListener { source.setPlaying(!source.playing); refresh() }
    }
    private val step = Button(context).apply {
        tag = "synthetic.step"; text = "单步"
        setOnClickListener { source.step(); refresh() }
    }
    private val reset = Button(context).apply {
        tag = "synthetic.reset"; text = "从头重放"
        setOnClickListener { source.seekTo(0); refresh() }
    }
    init {
        orientation = VERTICAL
        addView(horizontal(scenario, viewport))
        addView(horizontal(play, step, reset))
        addView(summary)
        scenario.onItemSelectedListener = selectionListener {
            val next = source.settings.copy(scenario = SyntheticScenario.entries[scenario.selectedItemPosition])
            if (next != source.settings) onSettingsChanged(next)
        }
        viewport.onItemSelectedListener = selectionListener {
            val next = source.settings.copy(viewport = SyntheticViewport.entries[viewport.selectedItemPosition])
            if (next != source.settings) onSettingsChanged(next)
        }
        refresh()
    }
    fun render(selected: Boolean, running: Boolean, tracking: TrackingState? = null) {
        this.selected = selected; this.running = running; this.tracking = tracking
        refresh()
    }
    private fun refresh() {
        scenario.isEnabled = selected; viewport.isEnabled = selected
        play.isEnabled = selected && running
        play.text = if (source.playing) "暂停" else "播放"
        step.isEnabled = selected && running && !source.playing
        reset.isEnabled = selected && running
        summary.text = when {
            !selected -> "合成场景控制仅用于合成样例。"
            !running -> "合成源已停止；可选择场景后重新开始。"
            else -> "${if (source.playing) "播放中" else "已暂停"} · 样本 ${source.lastSampleStep ?: 0} · ${tracking ?: "等待帧"}\n" +
                expectations.getValue(source.settings.scenario)
        }
    }
    private fun horizontal(vararg children: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        children.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun selectionListener(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private companion object {
        val scenarioLabels = listOf("静止人体", "左右移动", "人物大小变化", "缺少左肩", "人物丢失", "丢失与恢复",
            "手臂挡胸", "抬臂跟随")
        val expectations = mapOf(
            SyntheticScenario.STILL to "预期：TRACKING，四个躯干锚点稳定。",
            SyntheticScenario.TRANSLATE to "预期：整体平移，人体大小不变。",
            SyntheticScenario.SCALE to "预期：围绕中心缩放；不是距离测量。",
            SyntheticScenario.PARTIAL to "预期：PARTIAL；渲染方应隐藏衣服。",
            SyntheticScenario.LOST to "预期：LOST；全部关键点为空。",
            SyntheticScenario.RECOVERY to "每 20 样本：TRACKING → PARTIAL → LOST → TRACKING。",
            SyntheticScenario.ARM_CROSS to "用短袖查看前臂是否显示在上衣前方。",
            SyntheticScenario.ARM_RAISE to "用衣柜里的 T 恤或卫衣查看袖口随抬臂移动。",
        )
    }
}
