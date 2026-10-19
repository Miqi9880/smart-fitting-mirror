package com.smartfitting.mirror.contracts

/** SmartMirror ListScreen fuzzyMatch adaptation: product fields and loose Chinese
 * character matching; whitespace-separated terms must all match. */
object GarmentSearch {
    fun matches(item: Garment, query: String, tag: String? = null): Boolean {
        if (tag != null && tag !in item.tags) return false
        val d = item.details
        val haystack = "${item.name} ${item.tags.joinToString(" ")} ${d.brand} ${d.material} ${d.description} ${d.season}".lowercase()
        return query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { word ->
            word in haystack || (word.length > 1 && word.all { it in '\u4e00'..'\u9fff' } && word.all { it in haystack })
        }
    }
}
