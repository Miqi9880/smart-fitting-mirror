package com.smartfitting.mirror

import android.content.Context
import android.content.SharedPreferences
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit

/**
 * On-device store for the opt-in "记住本机贴合设置". Persists only per-category
 * numeric overrides (width/length/offset); no portraits, keypoints, sessions, tokens,
 * jobs or upload results. Missing, corrupt, out-of-range, non-finite or unknown-category
 * values are dropped so callers fall back to [OutfitFit.defaultFor]. Default values are
 * never written to disk, so an old "长裤 100%" is not resurrected as an override.
 */
class FitPreferenceStore(context: Context, private val name: String = "fitting_fit_overrides") {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Valid non-default overrides only; anything unreadable is silently dropped. */
    fun load(): Map<GarmentCategory, OutfitFit> {
        if (!enabled) return emptyMap()
        val result = mutableMapOf<GarmentCategory, OutfitFit>()
        for (category in GarmentCategory.entries) {
            val fit = readFit(category) ?: continue
            if (fit != OutfitFit.defaultFor(category)) result[category] = fit
        }
        return result
    }

    /** Replace every stored override. Entries equal to the category default are omitted. */
    fun save(fits: Map<GarmentCategory, OutfitFit>) {
        if (!enabled) return
        val editor = prefs.edit()
        for (category in GarmentCategory.entries) removeKeys(editor, category)
        for ((category, fit) in fits) {
            if (fit == OutfitFit.defaultFor(category)) continue
            editor.putFloat(key(category, "width"), fit.width)
            editor.putFloat(key(category, "length"), fit.length)
            editor.putFloat(key(category, "offset"), fit.offset)
        }
        editor.apply()
    }

    /** Remove all stored overrides; the enabled flag is left untouched. */
    fun clear() {
        val editor = prefs.edit()
        for (category in GarmentCategory.entries) removeKeys(editor, category)
        editor.apply()
    }

    private fun readFit(category: GarmentCategory): OutfitFit? = try {
        OutfitFit(
            prefs.getFloat(key(category, "width"), Float.NaN),
            prefs.getFloat(key(category, "length"), Float.NaN),
            prefs.getFloat(key(category, "offset"), Float.NaN),
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun removeKeys(editor: SharedPreferences.Editor, category: GarmentCategory) {
        editor.remove(key(category, "width"))
        editor.remove(key(category, "length"))
        editor.remove(key(category, "offset"))
    }

    private fun key(category: GarmentCategory, field: String) = "fit.${category.name}.$field"

    companion object {
        const val KEY_ENABLED = "fit.remember_enabled"
    }
}
