package com.smartfitting.mirror

import android.app.Application
import android.content.Context
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Camera/server-free integration; the emulator's test-run preferences are cleared. */
class FitPreferenceIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun raw() = context.getSharedPreferences("fitting_fit_overrides",Context.MODE_PRIVATE)
    @Before fun prepare() { raw().edit().clear().commit() }
    @After fun clean() { raw().edit().clear().commit() }

    @Test fun corruptEnabledFlagFallsBackAndDoesNotCrashActivityStartup() {
        raw().edit().putString(FitPreferenceStore.KEY_ENABLED,"corrupt").commit()
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertFalse(it.window.decorView.findViewWithTag<Switch>("fitting.fit.remember").isChecked)
                val model=ViewModelProvider(it)[TryOnModel::class.java]
                assertTrue(model.outfitFits.isEmpty()); assertFalse(model.running)
            }
        }
    }

    @Test fun uiToggleRotationNewModelResetAndDisablePreserveTheRequiredBoundaries() {
        val shorts=OutfitFit(1.6f,1.2f,-.1f); val hat=OutfitFit(1.5f,1.4f,-.4f)
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model=ViewModelProvider(it)[TryOnModel::class.java]
                model.setFit(GarmentCategory.SHORTS,shorts)
                assertTrue(FitPreferenceStore(context).load().isEmpty())
                it.window.decorView.findViewWithTag<Switch>("fitting.fit.remember").performClick()
                model.setFit(GarmentCategory.HEADWEAR,hat)
                assertEquals(shorts,FitPreferenceStore(context).load()[GarmentCategory.SHORTS])
                assertFalse(model.running)
            }
            scenario.recreate()
            scenario.onActivity {
                val model=ViewModelProvider(it)[TryOnModel::class.java]
                assertTrue(it.window.decorView.findViewWithTag<Switch>("fitting.fit.remember").isChecked)
                val fresh=TryOnModel(context.applicationContext as Application)
                assertEquals(shorts,fresh.outfitFits[GarmentCategory.SHORTS])
                model.setFit(GarmentCategory.SHORTS,OutfitFit.defaultFor(GarmentCategory.SHORTS))
                assertFalse(FitPreferenceStore(context).load().containsKey(GarmentCategory.SHORTS))
                assertEquals(hat,FitPreferenceStore(context).load()[GarmentCategory.HEADWEAR])
                it.window.decorView.findViewWithTag<Switch>("fitting.fit.remember").performClick()
                assertEquals(hat,model.outfitFits[GarmentCategory.HEADWEAR])
                assertTrue(TryOnModel(context.applicationContext as Application).outfitFits.isEmpty())
                assertFalse(raw().contains("fit.HEADWEAR.width")); assertFalse(model.running)
            }
        }
    }
}
