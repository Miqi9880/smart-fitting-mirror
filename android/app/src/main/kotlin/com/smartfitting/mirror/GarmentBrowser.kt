package com.smartfitting.mirror

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.smartfitting.mirror.contracts.*
import com.smartfitting.mirror.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Adapts upstream ListScreen/DetailScreen/AddClothes to existing Views and HTTP contracts.
 * Source and course reuse: third_party/SmartMirror/NOTICE.md. No second navigation/data stack.
 */
internal class GarmentBrowser(private val context: Context, private val controller: FittingSession,
    private val scope: CoroutineScope, private val assets: List<Garment>,
    query: String, private val rememberQuery: (String) -> Unit,
    private val tryOnGarment: (String) -> Unit,
    private val selectedAccessories: () -> Map<AccessoryCategory, OutfitAccessory>,
    private val selectAccessory: (AccessoryCategory, OutfitAccessory?) -> Unit) {
    private enum class Tab(val label: String, val garments: Set<GarmentCategory>) {
        ALL("全部", GarmentCategory.entries.toSet()),
        TOPS("上衣", setOf(GarmentCategory.UPPER_BODY)),
        BOTTOMS("下装", setOf(GarmentCategory.LOWER_BODY, GarmentCategory.SHORTS, GarmentCategory.SKIRT)),
        DRESSES("连衣裙", setOf(GarmentCategory.DRESS)),
        HATS("帽子", setOf(GarmentCategory.HEADWEAR)),
        ACCESSORIES("配饰", emptySet()),
    }

    private val dialog = Dialog(context)
    private val root = column()
    private val cards = column()
    private val cardScroll = ScrollView(context).apply { addView(cards) }
    private val summary = text("")
    private val search = EditText(context).apply { hint = "搜索衣柜"; tag = "catalog.search"; setSingleLine(); setText(query) }
    private val filters = Spinner(context).apply { tag = "catalog.filter"; contentDescription = "按服装标签筛选" }
    private val categories = listOf(GarmentCategory.UPPER_BODY, GarmentCategory.LOWER_BODY,
        GarmentCategory.SHORTS, GarmentCategory.SKIRT, GarmentCategory.DRESS, GarmentCategory.HEADWEAR)
    private var selectedTab = Tab.ALL
    private val tabButtons = Tab.entries.associateWith { tab ->
        Button(context).apply {
            text = tab.label
            tag = "catalog.tab.${tab.name.lowercase()}"
            minimumWidth = dp(76)
            minimumHeight = dp(48)
            setOnClickListener {
                if (selectedTab != tab) {
                    selectedTab = tab
                    renderTabs()
                    renderCards()
                    cardScroll.scrollTo(0, 0)
                }
            }
        }
    }
    private var tags = listOf("全部")
    private var selectedTag = "全部"
    private var state = controller.state.value
    private val thumbnails = mutableListOf<Thumbnail>()
    private val thumbnailSlots = Semaphore(3)
    private var details: AlertDialog? = null
    private var detailImage: Thumbnail? = null
    private var editor: AlertDialog? = null
    private val add = Button(context).apply { text = "新增衣服"; tag = "catalog.add"; setOnClickListener { edit(null) } }
    init {
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.setBackgroundColor(Color.rgb(242,246,249))
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("浏览衣柜", 22f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "×"
                textSize = 26f
                tag = "catalog.close"
                contentDescription = "关闭衣柜，返回试衣"
                setPadding(0, 0, 0, 0)
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tabButtons.values.forEach { addView(it, LinearLayout.LayoutParams(-2, -2)) }
            })
        })
        root.addView(search); root.addView(filters); root.addView(summary)
        root.addView(cardScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = Button(context).apply { text = "刷新衣柜"; setOnClickListener { controller.refreshCatalog() } }
        for (action in listOf(add,refresh)) actions.addView(action,LinearLayout.LayoutParams(0,-2,1f))
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
    fun show() {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        render(state)
    }
    fun dismiss() { dialog.dismiss() }
    fun render(value: FittingState) {
        val changed = state.garments != value.garments || state.accessories != value.accessories ||
            state.outfitAvailable != value.outfitAvailable || state.selectedId != value.selectedId ||
            state.canSelect != value.canSelect
        state = value
        if (!dialog.isShowing) return
        if (!value.connected) { dismiss(); return }
        if (!value.outfitAvailable && selectedTab == Tab.ACCESSORIES) selectedTab = Tab.ALL
        renderTabs()
        val nextTags = listOf("全部") + value.garments.flatMap { it.tags }.distinct().sorted()
        if (tags != nextTags || filters.adapter == null) {
            tags = nextTags
            if (selectedTag !in tags) selectedTag = "全部"
            filters.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, tags)
            filters.setSelection(tags.indexOf(selectedTag))
        }
        if (changed || cards.childCount == 0) renderCards()
        if (!value.canSelect) summary.text = value.message
    }

    private fun renderTabs() {
        for ((tab, button) in tabButtons) {
            button.visibility = if (tab != Tab.ACCESSORIES || state.outfitAvailable) View.VISIBLE else View.GONE
            button.isSelected = tab == selectedTab
            button.backgroundTintList = ColorStateList.valueOf(
                if (button.isSelected) Color.rgb(28, 81, 125) else Color.rgb(225, 233, 240)
            )
            button.setTextColor(if (button.isSelected) Color.WHITE else Color.rgb(26, 43, 55))
        }
        filters.visibility = if (selectedTab == Tab.ACCESSORIES) View.GONE else View.VISIBLE
        add.visibility = filters.visibility
        add.isEnabled = state.canSelect && assets.isNotEmpty()
    }

    private fun renderCards() {
        thumbnails.forEach { it.close() }; thumbnails.clear(); cards.removeAllViews()
        val query = search.text.toString().trim()
        // Upstream fuzzyMatch's searchable product fields, adapted to our optional metadata.
        val garments = state.garments.filter { it.category in selectedTab.garments &&
            GarmentSearch.matches(it, query, selectedTag.takeIf { tag -> tag != "全部" }) }
            .sortedByDescending { "photo" in it.tags }
        val showAccessories = state.outfitAvailable &&
            (selectedTab == Tab.ACCESSORIES || (selectedTab == Tab.ALL && selectedTag == "全部"))
        val accessories = if (showAccessories) state.accessories.filter {
            "${it.name} ${it.category.label}".contains(query, ignoreCase = true)
        } else emptyList()
        summary.text = if (state.canSelect) "${garments.size + accessories.size} 件" else state.message
        if (garments.isEmpty() && accessories.isEmpty()) {
            cards.addView(text("当前分类没有匹配的素材。"))
            if (query.isNotEmpty() || (selectedTab != Tab.ACCESSORIES && selectedTag != "全部")) {
                cards.addView(Button(context).apply {
                    text = "清除筛选"
                    setOnClickListener {
                        if (selectedTab != Tab.ACCESSORIES) {
                            selectedTag = "全部"
                            this@GarmentBrowser.filters.setSelection(0)
                        }
                        if (search.text.isNotEmpty()) search.setText("") else renderCards()
                    }
                })
            }
        }
        for (category in categories) {
            val group = garments.filter { it.category == category }
            if (group.isEmpty()) continue
            cards.addView(text(category.label, 18f))
            addCards(group)
        }
        if (accessories.isNotEmpty()) addAccessories(accessories)
    }
    private fun addCards(items: List<Garment>) = addRows(items) { item ->
        column().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true; contentDescription = "查看${item.name}详情"
            setOnClickListener { if (state.canSelect) detail(item) }
            addView(thumbnail(item.name) { controller.thumbnail(item) }, LinearLayout.LayoutParams(-1, dp(120)))
            addView(text(item.name, 16f))
            addView(text(item.tags.joinToString(" · ")))
            if (state.selectedId == item.garmentId) addView(text("正在试穿"))
        }
    }

    private fun addAccessories(items: List<OutfitAccessory>) {
        cards.addView(text("配饰用于照片搭配生成，每类可选一件。图片为 AI 设计参考。"))
        for (category in AccessoryCategory.entries) {
            val group = items.filter { it.category == category }
            if (group.isEmpty()) continue
            cards.addView(text(category.label, 18f))
            val buttons = mutableListOf<Pair<OutfitAccessory, Button>>()
            fun updateSelection() {
                val selected = selectedAccessories()[category]?.accessoryId
                for ((item, button) in buttons) {
                    button.isSelected = item.accessoryId == selected
                    button.text = if (button.isSelected) "${item.name}（已选，点按移除）" else item.name
                }
            }
            addRows(group) { item ->
                column().apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setBackgroundColor(Color.WHITE)
                    addView(thumbnail(item.name) { controller.accessoryThumbnail(item) },
                        LinearLayout.LayoutParams(-1, dp(72)))
                    val pick = Button(context).apply {
                        tag = "fitting.accessory.${item.accessoryId}"
                        isEnabled = state.canSelect
                        setOnClickListener {
                            if (state.canSelect) {
                                selectAccessory(category, item.takeUnless { isSelected })
                                updateSelection()
                            }
                        }
                    }
                    buttons += item to pick
                    addView(pick)
                }
            }
            updateSelection()
        }
    }

    private fun <T> addRows(items: List<T>, card: (T) -> View) {
        for (pair in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in pair) {
                row.addView(card(item), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
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
        detailImage = Thumbnail(image, item.name) { controller.thumbnail(item) }
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
            add(when {
                item.generationReferenceUrl != null -> "支持单件和搭配生成"
                state.outfitAvailable -> "可通过当前搭配生成"
                else -> "实时预览素材"
            })
        }
        content.addView(text(info.joinToString("\n")))
        content.addView(Button(context).apply { text = "编辑资料"; setOnClickListener { details?.dismiss(); edit(item) } })
        content.addView(Button(context).apply { text = "从本次衣柜移除"; setOnClickListener {
            AlertDialog.Builder(context).setMessage("从本次衣柜移除“${item.name}”？")
                .setNegativeButton("保留",null).setPositiveButton("移除") { _,_ -> controller.deleteGarment(item.garmentId); details?.dismiss() }.show()
        } })
        details = AlertDialog.Builder(context).setTitle(item.name).setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("返回",null).setPositiveButton("试穿这件") { _,_ -> tryOnGarment(item.garmentId) }.create()
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
                    controller.saveGarment(GarmentWrite(id,name.text.toString().trim(),category=asset.category,tags=values(tagsInput),
                        assetId=Uri.parse(asset.previewImage.url).lastPathSegment!!.removeSuffix(".png"),
                        details=GarmentDetails(brand.text.toString(),material.text.toString(),description.text.toString(),
                            values(sizes),values(colors),season.text.toString(),care.text.toString(),cents)),item != null)
                    editor!!.dismiss()
                } catch (failure: Exception) { name.error = failure.message ?: "输入格式有误" }
            }
        }
        editor!!.show()
    }
    private fun thumbnail(name: String, load: suspend () -> ByteArray) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = name
        thumbnails += Thumbnail(this, name, load)
    }

    private inner class Thumbnail(private val view: ImageView, name: String, load: suspend () -> ByteArray) : AutoCloseable {
        private var bitmap: Bitmap? = null
        private val work = scope.launch { thumbnailSlots.withPermit {
            var bytes: ByteArray? = null; var decoded: Bitmap? = null
            try {
                bytes = load()
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
                view.contentDescription = "${name}：图片加载失败"
            } finally { bytes?.fill(0); decoded?.recycle() }
        } }
        override fun close() { work.cancel(); view.setImageDrawable(null); bitmap?.recycle(); bitmap = null }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 13f) = TextView(context).apply { text = value; textSize = size; setTextColor(Color.rgb(26,43,55)); setPadding(0,dp(4),0,dp(4)) }
}
