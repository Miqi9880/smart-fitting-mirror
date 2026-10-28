package com.smartfitting.mirror

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartfitting.mirror.network.*
import com.smartfitting.mirror.vision.*
import com.smartfitting.mirror.contracts.Garment
import com.smartfitting.mirror.contracts.GarmentCategory
import com.smartfitting.mirror.tryon.OutfitFit
import com.smartfitting.mirror.tryon.PreviewViewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Retains remote task identity across rotation. Camera and bitmaps belong to the Activity.
 *  The opt-in per-category fit overrides persist on-device via [FitPreferenceStore]. */
class TryOnModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(FittingState())
    val state = mutable.asStateFlow()
    var controller: FittingSession? = null
        private set
    private var bridge: Job? = null
    var baseUrl = "http://10.0.2.2:8000"
    var rgb = true
    var cameraId: String? = null
    var running = false
    var visionFailure: String? = null
    var mirrored = true
    var synthetic = SyntheticSettings()
    var playing = true
    var showLandmarks = true
    var protectHead = true
    var previewFullscreen = false
    var previewViewport = PreviewViewport()
    internal var resultComparisonMode = ResultComparisonMode.SIDE_BY_SIDE
    val fitPreferences = FitPreferenceStore(application)
    var outfitFits = if (fitPreferences.enabled) fitPreferences.load() else emptyMap<GarmentCategory, OutfitFit>()
    var meshMode = false
    var catalogQuery = ""
    var catalogAssets = emptyList<com.smartfitting.mirror.contracts.Garment>()
    var outfit = emptyMap<GarmentCategory,Garment>()
    var lastOutfitSelection: Garment? = null

    fun connect(url: String) {
        val api = HttpFittingApi(url.trim()) // Validate before replacing an existing controller.
        bridge?.cancel()
        controller?.close()
        outfit = emptyMap(); lastOutfitSelection = null
        resultComparisonMode = ResultComparisonMode.SIDE_BY_SIDE
        baseUrl = url.trim()
        val next = FittingSession(api, viewModelScope)
        controller = next
        bridge = viewModelScope.launch { next.state.collect { mutable.value = it } }
        next.connect()
    }

    /** Apply a slider/reset change and, when the opt-in is on, persist non-default overrides. */
    fun setFit(category: GarmentCategory, fit: OutfitFit) {
        outfitFits = outfitFits + (category to fit)
        if (fitPreferences.enabled) fitPreferences.save(outfitFits)
    }

    /** Toggle the opt-in. Disabling deletes saved values but keeps this session's in-memory values. */
    fun setFitSaveEnabled(value: Boolean) {
        fitPreferences.enabled = value
        if (value) fitPreferences.save(outfitFits) else fitPreferences.clear()
    }

    override fun onCleared() { controller?.close(); super.onCleared() }
}
