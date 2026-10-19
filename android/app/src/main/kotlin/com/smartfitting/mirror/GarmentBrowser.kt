package com.smartfitting.mirror

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.*
import kotlinx.coroutines.*

/** Adapts upstream ListScreen/DetailScreen/AddClothes to existing Views and HTTP contracts.
 * Source and course reuse: third_party/SmartMirror/NOTICE.md. No second navigation/data stack.
 */
internal class GarmentBrowser(private val context: Context, private val controller: FittingSession,
    private val scope: CoroutineScope, private val assets: List<Garment>,
    query: String, private val rememberQuery: (String) -> Unit) {
    private val dialog = Dialog(context)
    private val root = column()
    private val cards = column()
    private val summary = text("")
    private val search = EditText(context).apply { hint = "搜索名称、品牌、材质或标签"; tag = "catalog.search"; setSingleLine(); setText(query) }
    private val filters = Spinner(context).apply { tag = "catalog.filter"; contentDescription = "按服装标签筛选" }
    private var tags = listOf("全部")
    private var selectedTag = "全部"
    private var state = controller.state.value
    private val thumbnails = mutableListOf<Thumbnail>()
    private var details: AlertDialog? = null
    private var detailImage: Thumbnail? = null
    private var editor: AlertDialog? = null
    private val add = Button(context).apply { text = "新增衣服"; tag = "catalog.add"; setOnClickListener { edit(null) } }
    init {
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.setBackgroundColor(Color.rgb(242,246,249))
        root.addView(text("我的临时衣柜", 22f)); root.addView(text("结束会话后恢复初始目录。图片取自现有素材库。"))
        root.addView(search); root.addView(filters); root.addView(summary)
        root.addView(ScrollView(context).apply { addView(cards) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Button(context).apply { text = "刷新衣柜"; setOnClickListener { controller.refreshCatalog() } }
        val back = Button(context).apply { text = "返回试衣"; setOnClickListener { dismiss() } }
        for (action in listOf(add,refresh,back)) actions.addView(action,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(actions)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { rememberQuery(s.toString()); renderCards() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filters.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTag = tags.getOrElse(position) { "全部" }; renderCards()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { thumbnails.forEach { it.close() }; thumbnails.clear(); details?.dismiss(); editor?.dismiss() }
    }
    fun show() { dialog.show(); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); render(state) }
    fun dismiss() { dialog.dismiss() }
    fun render(value: FittingState) {
        val changed = state.garments != value.garments
        state = value
        add.isEnabled = value.canSelect && assets.isNotEmpty()
        if (!value.connected) { dismiss(); return }
        val nextTags = listOf("全部") + value.garments.flatMap { it.tags }.distinct().sorted()
        if (tags != nextTags || filters.adapter == null) {
            tags = nextTags
            filters.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, tags)
            filters.setSelection(tags.indexOf(selectedTag).coerceAtLeast(0))
        }
        if (changed || cards.childCount == 0) renderCards()
        summary.text = if (!value.canSelect) value.message else "${value.garments.size} 件 · 点击查看详情"
    }
    private fun renderCards() {
        thumbnails.forEach { it.close() }; thumbnails.clear(); cards.removeAllViews()
        // Upstream fuzzyMatch's searchable product fields, adapted to our optional metadata.
        val items = state.garments.filter { GarmentSearch.matches(it,search.text.toString(),selectedTag.takeIf { it != "全部" }) }
            .sortedByDescending { "photo" in it.tags }
        if (items.isEmpty()) {
            cards.addView(text(if (state.garments.isEmpty()) "衣柜暂无服装，可从现有素材新增。" else "没有匹配的服装，请修改搜索或筛选。"))
            cards.addView(Button(context).apply { text = "清除筛选"; setOnClickListener { search.setText(""); selectedTag = "全部"; this@GarmentBrowser.filters.setSelection(0); renderCards() } })
        }
        for (pair in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in pair) {
                val card = column().apply {
                    setPadding(dp(8),dp(8),dp(8),dp(8)); setBackgroundColor(Color.WHITE)
                    isClickable = true; isFocusable = true; contentDescription = "查看${item.name}详情"
                    setOnClickListener { if (state.canSelect) detail(item) }
                }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = item.name }
                card.addView(image, LinearLayout.LayoutParams(-1,dp(120)))
                thumbnails += Thumbnail(image, item)
                card.addView(text(item.name,16f)); card.addView(text(item.tags.joinToString(" · ")))
                if (state.selectedId == item.garmentId) card.addView(text("正在试穿"))
                row.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }
            if (pair.size == 1) row.addView(View(context),LinearLayout.LayoutParams(0,1,1f))
            cards.addView(row)
        }
    }
    private fun detail(item: Garment) {
        details?.dismiss()
        val content = column().apply { setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(image,LinearLayout.LayoutParams(-1,dp(220)))
        detailImage = Thumbnail(image,item)
        val d = item.details
        val info = buildList {
            add(item.tags.joinToString(" · ")); if (d.brand.isNotBlank()) add("品牌：${d.brand}")
            if (d.material.isNotBlank()) add("材质：${d.material}")
            if (d.sizes.isNotEmpty()) add("尺寸：${d.sizes.joinToString(" / ")}")
            if (d.colors.isNotEmpty()) add("颜色：${d.colors.joinToString(" / ")}")
            if (d.season.isNotBlank()) add("季节：${d.season}")
            d.priceCents?.let { add("参考价格：¥${it / 100}.${(it % 100).toString().padStart(2,'0')}") }
            if (d.description.isNotBlank()) add(d.description)
            if (d.careInstructions.isNotBlank()) add("护理：${d.careInstructions}")
            add(if (item.generationReferenceUrl == null) "仅实时预览" else "支持生成请求；实际结果类型由服务标明")
        }
        content.addView(text(info.joinToString("\n")))
        content.addView(Button(context).apply { text = "编辑资料"; setOnClickListener { details?.dismiss(); edit(item) } })
        content.addView(Button(context).apply { text = "从本次衣柜移除"; setOnClickListener {
            AlertDialog.Builder(context).setMessage("从本次衣柜移除“${item.name}”？")
                .setNegativeButton("保留",null).setPositiveButton("移除") { _,_ -> controller.deleteGarment(item.garmentId); details?.dismiss() }.show()
        } })
        details = AlertDialog.Builder(context).setTitle(item.name).setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("返回",null).setPositiveButton("试穿这件") { _,_ -> controller.select(item.garmentId); dismiss() }.create()
        details!!.setOnDismissListener { detailImage?.close(); detailImage = null }
        details!!.show()
    }
    private fun edit(item: Garment?) {
        val content = column().apply { setPadding(dp(16),dp(8),dp(16),dp(8)) }
        fun input(label: String, value: String) = EditText(context).apply { hint = label; setText(value); contentDescription = label; content.addView(this) }
        val d = item?.details ?: GarmentDetails()
        val name = input("服装名称（必填）",item?.name ?: "")
        val brand = input("品牌",d.brand); val material = input("材质",d.material)
        val description = input("描述",d.description); val tagsInput = input("标签，以逗号分隔", item?.tags?.joinToString(",") ?: "")
        val sizes = input("尺寸，以逗号分隔",d.sizes.joinToString(",")); val colors = input("颜色，以逗号分隔",d.colors.joinToString(","))
        val season = input("季节",d.season); val care = input("护理说明",d.careInstructions)
        val price = input("参考价格（元，可留空）",d.priceCents?.let { java.math.BigDecimal(it).movePointLeft(2).toPlainString() } ?: "")
        price.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        content.addView(text("选择现有图片素材"))
        val choices = assets.distinctBy { it.previewImage.url }
        val picker = Spinner(context).apply { adapter = ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,choices.map { it.name }); setSelection(choices.indexOfFirst { it.previewImage.url == item?.previewImage?.url }.coerceAtLeast(0)) }
        content.addView(picker)
        editor = AlertDialog.Builder(context).setTitle(if (item == null) "新增临时服装" else "编辑服装资料")
            .setView(ScrollView(context).apply { addView(content) }).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        editor!!.setOnShowListener {
            editor!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    require(name.text.toString().trim().isNotEmpty()) { "请填写服装名称" }
                    val cents = price.text.toString().takeIf { it.isNotBlank() }?.let { java.math.BigDecimal(it).movePointRight(2).intValueExact() }
                    require(cents == null || cents in 0..100_000_000) { "价格超出范围" }
                    fun values(edit: EditText) = edit.text.toString().split(",","，").map { it.trim() }.filter { it.isNotEmpty() }
                    val asset = choices[picker.selectedItemPosition]
                    val id = item?.garmentId ?: "wardrobe_${java.util.UUID.randomUUID().toString().replace("-","")}"
                    controller.saveGarment(GarmentWrite(id,name.text.toString().trim(),tags=values(tagsInput),
                        assetId=Uri.parse(asset.previewImage.url).lastPathSegment!!.removeSuffix(".png"),
                        details=GarmentDetails(brand.text.toString(),material.text.toString(),description.text.toString(),
                            values(sizes),values(colors),season.text.toString(),care.text.toString(),cents)),item != null)
                    editor!!.dismiss()
                } catch (failure: Exception) { name.error = failure.message ?: "输入格式有误" }
            }
        }
        editor!!.show()
    }
    private inner class Thumbnail(private val view: ImageView, item: Garment) : AutoCloseable {
        private var bitmap: Bitmap? = null
        private val work = scope.launch {
            var bytes: ByteArray? = null; var decoded: Bitmap? = null
            try {
                bytes = controller.thumbnail(item)
                withContext(Dispatchers.Default) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes!!,0,bytes!!.size,bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong()*bounds.outHeight <= 16_000_000)
                    val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/640) }
                    decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes!!,0,bytes!!.size,options))
                }
                ensureActive(); bitmap = decoded; view.setImageBitmap(bitmap); decoded = null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                view.contentDescription = "${item.name}：图片加载失败"
            } finally { bytes?.fill(0); decoded?.recycle() }
        }
        override fun close() { work.cancel(); view.setImageDrawable(null); bitmap?.recycle(); bitmap = null }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 13f) = TextView(context).apply { text = value; textSize = size; setTextColor(Color.rgb(26,43,55)); setPadding(0,dp(4),0,dp(4)) }
}
