package com.smartfitting.mirror

import android.content.Context
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run write/read/disabled in separate instrumentations, force-stopping the app between phases. */
class FitPreferenceProcessTest {
    @Test fun explicitProcessBoundary() {
        val phase=InstrumentationRegistry.getArguments().getString("preference_phase")
        assumeTrue(phase!=null); require(phase in listOf("write","read","disabled"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val raw=context.getSharedPreferences("fitting_fit_overrides",Context.MODE_PRIVATE)
        if (phase=="write") raw.edit().clear().commit()
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model=ViewModelProvider(it)[TryOnModel::class.java]
                val toggle=it.window.decorView.findViewWithTag<Switch>("fitting.fit.remember")
                when (phase) {
                    "write" -> {
                        assertFalse(toggle.isChecked)
                        model.setFit(GarmentCategory.SHORTS,OutfitFit(1.6f,1.2f,-.1f))
                        toggle.performClick()
                    }
                    "read" -> {
                        assertTrue(toggle.isChecked)
                        assertEquals(OutfitFit(1.6f,1.2f,-.1f),model.outfitFits[GarmentCategory.SHORTS])
                        toggle.performClick()
                        assertEquals(1.6f,model.outfitFits.getValue(GarmentCategory.SHORTS).width,0f)
                    }
                    "disabled" -> {
                        assertFalse(toggle.isChecked); assertTrue(model.outfitFits.isEmpty())
                        assertFalse(raw.contains("fit.SHORTS.width"))
                    }
                }
                assertFalse("Persistence must never open a camera",model.running)
            }
        }
        if (phase=="disabled") raw.edit().clear().commit()
    }
}
