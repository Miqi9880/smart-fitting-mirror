package com.smartfitting.mirror.tryon

import com.smartfitting.mirror.contracts.Garment
import com.smartfitting.mirror.contracts.GarmentCategory

/** Physical coverage rules, shared by selection and rendering validation. */
object OutfitSelection {
    private val bottoms = setOf(GarmentCategory.LOWER_BODY, GarmentCategory.SKIRT, GarmentCategory.SHORTS)
    fun add(current: Map<GarmentCategory, Garment>, item: Garment): Map<GarmentCategory, Garment> {
        val remove = when (item.category) {
            GarmentCategory.DRESS -> bottoms + GarmentCategory.UPPER_BODY + GarmentCategory.DRESS
            GarmentCategory.UPPER_BODY -> setOf(GarmentCategory.UPPER_BODY, GarmentCategory.DRESS)
            in bottoms -> bottoms + GarmentCategory.DRESS
            else -> setOf(item.category)
        }
        return current.filterKeys { it !in remove } + (item.category to item)
    }
    fun compatible(items: List<Garment>): Boolean {
        val categories = items.map { it.category }
        return categories.distinct().size == items.size &&
            categories.count { it in bottoms } <= 1 &&
            (GarmentCategory.DRESS !in categories || categories.none { it in bottoms || it == GarmentCategory.UPPER_BODY })
    }
}
