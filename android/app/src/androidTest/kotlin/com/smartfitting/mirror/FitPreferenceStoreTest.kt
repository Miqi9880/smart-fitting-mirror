package com.smartfitting.mirror

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** New on-device persistence behaviour for the opt-in "记住本机贴合设置". No server, assets or camera. */
@RunWith(AndroidJUnit4::class)
class FitPreferenceStoreTest {
    private lateinit var context: Context
    private val name = "fitting_fit_overrides_test"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        raw().edit().clear().commit()
    }

    private fun store() = FitPreferenceStore(context, name)
    private fun raw() = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun disabledByDefaultAndSavesNothing() {
        val store = store()
        assertFalse("记住开关默认关闭", store.enabled)
        assertTrue("默认无任何覆盖", store.load().isEmpty())
        store.save(mapOf(GarmentCategory.SHORTS to OutfitFit(1.6f, 1.06f, -.18f)))
        assertTrue("关闭状态下不落盘", store.load().isEmpty())
        assertFalse(raw().contains("fit.SHORTS.width"))
    }

    @Test fun enabledRoundTripsNonDefaultOverrides() {
        val store = store()
        store.enabled = true
        val fit = OutfitFit(1.6f, 1.2f, -.1f)
        store.save(mapOf(GarmentCategory.SHORTS to fit))
        assertEquals("开启后重开恢复覆盖", fit, store().load()[GarmentCategory.SHORTS])
    }

    @Test fun disableClearsOverrides() {
        val store = store()
        store.enabled = true
        store.save(mapOf(GarmentCategory.SHORTS to OutfitFit(1.6f, 1.06f, -.18f)))
        store.enabled = false
        store.clear()
        assertFalse("关闭后开关为关", store().enabled)
        assertTrue("关闭后重开回默认", store().load().isEmpty())
    }

    @Test fun resetOneCategoryKeepsOthers() {
        val store = store()
        store.enabled = true
        val shorts = OutfitFit(1.6f, 1.06f, -.18f)
        val headwear = OutfitFit(1.5f, 1.4f, -.4f)
        store.save(mapOf(GarmentCategory.SHORTS to shorts, GarmentCategory.HEADWEAR to headwear))
        store.save(mapOf(GarmentCategory.SHORTS to OutfitFit.defaultFor(GarmentCategory.SHORTS), GarmentCategory.HEADWEAR to headwear))
        val reloaded = store().load()
        assertFalse("恢复本类默认应清除该类覆盖", reloaded.containsKey(GarmentCategory.SHORTS))
        assertFalse("默认值不写盘", raw().contains("fit.SHORTS.width"))
        assertEquals("其他品类覆盖保留", headwear, reloaded[GarmentCategory.HEADWEAR])
    }

    @Test fun invalidValuesFallBackToDefault() {
        raw().edit()
            .putFloat("fit.SHORTS.width", Float.NaN)
            .putFloat("fit.SHORTS.length", 1.06f)
            .putFloat("fit.SHORTS.offset", -.18f)
            .putFloat("fit.HEADWEAR.width", 3.0f)
            .putFloat("fit.HEADWEAR.length", 1.35f)
            .putFloat("fit.HEADWEAR.offset", -.38f)
            .putFloat("fit.DRESS.width", Float.POSITIVE_INFINITY)
            .putFloat("fit.DRESS.length", 1.05f)
            .putFloat("fit.DRESS.offset", 0f)
            .putString("fit.SKIRT.width", "corrupt")
            .putString("fit.SKIRT.length", "corrupt")
            .putString("fit.SKIRT.offset", "corrupt")
            .putBoolean(FitPreferenceStore.KEY_ENABLED, true)
            .commit()
        assertTrue("损坏/越界/NaN/无穷都应回退", store().load().isEmpty())
    }

    @Test fun missingFieldAndUnknownCategoryAreIgnored() {
        raw().edit()
            .putFloat("fit.SHORTS.width", 1.6f)
            .putFloat("fit.UNKNOWN_BODY.width", 2f)
            .putFloat("fit.UNKNOWN_BODY.length", 1f)
            .putFloat("fit.UNKNOWN_BODY.offset", 0f)
            .putBoolean(FitPreferenceStore.KEY_ENABLED, true)
            .commit()
        assertTrue("缺项回退", store().load().isEmpty())
        val store = store()
        store.enabled = true
        store.save(mapOf(GarmentCategory.SHORTS to OutfitFit(1.6f, 1.06f, -.18f)))
        assertEquals("仅已知品类的有效覆盖被读取", 1, store().load().size)
    }
}
