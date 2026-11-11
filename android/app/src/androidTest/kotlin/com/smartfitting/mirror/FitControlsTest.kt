package com.smartfitting.mirror

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FitControlsTest {
    @Test fun independentFitSettingsSurviveRotationAndReset() {
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]
            fun await(check: (TryOnActivity)->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+30_000; var ready=false
                while (!ready && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { ready=check(it) }; if (!ready) SystemClock.sleep(100)
                }
                assertTrue("Expected fit UI state",ready)
            }
            fun select(id: String) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).state.value.selectedId==id &&
                    it.window.decorView.findViewWithTag<TextView>("fitting.fit.summary").text.contains(model(it).state.value.selected!!.category.label) }
            }
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                root.findViewWithTag<EditText>("fitting.address").setText(host)
                root.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await { model(it).state.value.canSelect }
            select("studio_chino_shorts_32")
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                root.findViewWithTag<Button>("fitting.fit.toggle").performClick()
                val args=Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100f) }
                assertTrue(root.findViewWithTag<SeekBar>("fitting.fit.width").performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                assertEquals(1.6f,model(activity).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
            }
            select("studio_black_beanie")
            scenario.onActivity {
                assertEquals(1.35f,OutfitFit.defaultFor(GarmentCategory.HEADWEAR).width,.001f)
                assertFalse(model(it).outfitFits.containsKey(GarmentCategory.HEADWEAR))
                assertEquals(1.6f,model(it).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
            }
            scenario.recreate()
            await { model(it).state.value.canSelect }
            scenario.onActivity {
                assertEquals(1.6f,model(it).outfitFits.getValue(GarmentCategory.SHORTS).width,.001f)
            }
            select("studio_chino_shorts_32")
            scenario.onActivity {
                val root=it.window.decorView
                root.findViewWithTag<Button>("fitting.fit.toggle").performClick()
                root.findViewWithTag<Button>("fitting.fit.reset").performClick()
                assertEquals(OutfitFit.defaultFor(GarmentCategory.SHORTS),model(it).outfitFits[GarmentCategory.SHORTS])
                assertFalse("Connecting and calibration must not start a camera",model(it).running)
                root.findViewWithTag<Button>("fitting.end").performClick()
            }
        }
    }

    @Test fun multiGarmentTargetAdjustsOnlyChosenCategoryAndSurvivesGuestLifecycle() {
        val host=InstrumentationRegistry.getArguments().getString("studio_test_host")
        assumeTrue(host!=null)
        require(host in listOf("http://10.0.2.2:8010","http://127.0.0.1:8010"))
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            fun model(a: TryOnActivity)=ViewModelProvider(a)[TryOnModel::class.java]
            fun await(check: (TryOnActivity)->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+30_000; var ready=false
                while (!ready && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { ready=check(it) }; if (!ready) SystemClock.sleep(100)
                }
                assertTrue("Expected target selection state",ready)
            }
            fun select(id: String) {
                scenario.onActivity { model(it).controller!!.select(id) }
                await { model(it).state.value.selectedId==id && model(it).outfit.values.any { item -> item.garmentId==id } }
            }
            fun choose(category: GarmentCategory) {
                scenario.onActivity {
                    val target=it.window.decorView.findViewWithTag<Spinner>("fitting.fit.target")
                    val position=(0 until target.adapter.count).indexOfFirst { index -> target.adapter.getItem(index)==category.label }
                    assertTrue("Only a worn category can be a target",position>=0)
                    target.setSelection(position)
                }
                await {
                    model(it).fitTarget==category && it.window.decorView.findViewWithTag<TextView>("fitting.fit.summary")
                        .text.startsWith("${category.label}：")
                }
            }
            scenario.onActivity {
                it.window.decorView.findViewWithTag<EditText>("fitting.address").setText(host)
                it.window.decorView.findViewWithTag<Button>("fitting.connect").performClick()
            }
            await { model(it).state.value.canSelect }
            for (id in listOf("studio_black_beanie","studio_blue_tee","studio_black_pants")) select(id)
            val hat=OutfitFit(1.5f,1.4f,-.4f); val pants=OutfitFit(1.2f,1.1f,.2f)
            scenario.onActivity {
                model(it).setFit(GarmentCategory.HEADWEAR,hat); model(it).setFit(GarmentCategory.LOWER_BODY,pants)
                it.window.decorView.findViewWithTag<Button>("fitting.fit.toggle").performClick()
            }
            choose(GarmentCategory.UPPER_BODY)
            scenario.onActivity {
                val current=model(it)
                val outfit=current.outfit.values.map { item -> item.garmentId }
                val args=Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,100f) }
                assertTrue(it.window.decorView.findViewWithTag<SeekBar>("fitting.fit.width").performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                assertEquals(1.6f,current.outfitFits.getValue(GarmentCategory.UPPER_BODY).width,.001f)
                assertEquals(hat,current.outfitFits[GarmentCategory.HEADWEAR])
                assertEquals(pants,current.outfitFits[GarmentCategory.LOWER_BODY])
                assertEquals("studio_black_pants",current.state.value.selectedId)
                assertEquals(outfit,current.outfit.values.map { item -> item.garmentId })
                assertNull(current.state.value.job)
                assertFalse(current.running)
            }
            select("studio_black_beanie")
            await { model(it).fitTarget==GarmentCategory.UPPER_BODY }
            select("studio_black_pants")
            await { model(it).fitTarget==GarmentCategory.UPPER_BODY }
            choose(GarmentCategory.LOWER_BODY)
            scenario.onActivity {
                val root=it.window.decorView
                assertEquals(60,root.findViewWithTag<SeekBar>("fitting.fit.width").progress)
                assertEquals(50,root.findViewWithTag<SeekBar>("fitting.fit.length").progress)
                assertEquals(95,root.findViewWithTag<SeekBar>("fitting.fit.offset").progress)
                root.findViewWithTag<Button>("fitting.fit.reset").performClick()
                assertEquals(OutfitFit.defaultFor(GarmentCategory.LOWER_BODY),model(it).outfitFits[GarmentCategory.LOWER_BODY])
                assertEquals(1.6f,model(it).outfitFits.getValue(GarmentCategory.UPPER_BODY).width,.001f)
                assertEquals(hat,model(it).outfitFits[GarmentCategory.HEADWEAR])
            }
            choose(GarmentCategory.UPPER_BODY)
            scenario.recreate()
            await {
                model(it).state.value.canSelect && model(it).fitTarget==GarmentCategory.UPPER_BODY &&
                    it.window.decorView.findViewWithTag<TextView>("fitting.fit.summary").text.startsWith("上衣：")
            }
            scenario.onActivity {
                assertEquals("studio_black_pants",model(it).state.value.selectedId)
                assertTrue(it.window.decorView.findViewWithTag<TextView>("fitting.fit.summary").text.startsWith("上衣："))
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await { model(it).fitTarget==GarmentCategory.UPPER_BODY }
            select("studio_cotton_t_dress_09")
            await { model(it).fitTarget==GarmentCategory.DRESS }
            scenario.onActivity {
                assertEquals(setOf(GarmentCategory.DRESS,GarmentCategory.HEADWEAR),model(it).outfit.keys)
                val target=it.window.decorView.findViewWithTag<Spinner>("fitting.fit.target")
                assertEquals(2,target.adapter.count)
            }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.outfit.remove").performClick() }
            await { model(it).fitTarget==GarmentCategory.HEADWEAR }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("fitting.outfit.clear").performClick() }
            scenario.onActivity {
                val root=it.window.decorView
                assertNull(model(it).fitTarget)
                assertFalse(root.findViewWithTag<Spinner>("fitting.fit.target").isEnabled)
                assertFalse(root.findViewWithTag<Button>("fitting.fit.toggle").isEnabled)
                model(it).fitTarget=GarmentCategory.HEADWEAR
                model(it).connect(host!!)
                assertNull("Reconnect must clear the guest's target",model(it).fitTarget)
            }
            await { model(it).state.value.canSelect }
            select("studio_black_beanie")
            scenario.onActivity {
                model(it).fitTarget=GarmentCategory.HEADWEAR
                it.window.decorView.findViewWithTag<Button>("fitting.end").performClick()
                assertNull("Ending must clear the guest's target",model(it).fitTarget)
            }
        }
    }

    @Test fun largeFontTargetHasOnlyWornReadableAccessibleChoices() {
        ActivityScenario.launch(TryOnActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (scale in listOf(1.3f,1.5f)) {
                    val context=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale=scale })
                    val controls=FitControls(context,{ _, _ -> },{})
                    val categories=listOf(GarmentCategory.HEADWEAR,GarmentCategory.UPPER_BODY,GarmentCategory.LOWER_BODY)
                    controls.render(categories,GarmentCategory.UPPER_BODY,OutfitFit())
                    controls.findViewWithTag<Button>("fitting.fit.toggle").performClick()
                    for (widthDp in listOf(320,700)) {
                        val width=(widthDp*context.resources.displayMetrics.density).toInt()
                        controls.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        controls.layout(0,0,width,controls.measuredHeight)
                        val target=controls.findViewWithTag<Spinner>("fitting.fit.target")
                        assertEquals(categories.map { it.label },(0 until target.adapter.count).map { target.adapter.getItem(it) })
                        assertTrue(target.isEnabled); assertTrue(target.isFocusable)
                        assertTrue(target.contentDescription.contains("正在调整上衣贴合"))
                        assertTrue(target.height>=48*context.resources.displayMetrics.density)
                        val selected=target.selectedView as TextView
                        assertEquals("上衣",selected.text)
                        assertTrue("Target text must not clip at font=$scale width=$widthDp",
                            selected.layout.height<=selected.height-selected.compoundPaddingTop-selected.compoundPaddingBottom)
                        for (index in categories.indices) {
                            val choice=target.adapter.getDropDownView(index,null,target) as TextView
                            choice.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                            choice.layout(0,0,width,choice.measuredHeight)
                            assertTrue("Each target choice needs a 48dp touch area",choice.height>=48*context.resources.displayMetrics.density)
                            assertEquals(categories[index].label,choice.text)
                            assertTrue("Dropdown labels must not clip",choice.layout.height<=
                                choice.height-choice.compoundPaddingTop-choice.compoundPaddingBottom)
                        }
                    }
                }
            }
        }
    }
}
