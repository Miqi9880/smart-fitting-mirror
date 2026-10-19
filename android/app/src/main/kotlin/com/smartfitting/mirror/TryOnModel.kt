package com.smartfitting.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartfitting.mirror.network.*
import com.smartfitting.mirror.vision.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Retains remote task identity across rotation. Camera and bitmaps belong to the Activity. */
class TryOnModel : ViewModel() {
    private val mutable = MutableStateFlow(FittingState())
    val state = mutable.asStateFlow()
    var controller: FittingSession? = null
        private set
    private var bridge: Job? = null
    var baseUrl = "http://10.0.2.2:8000"
    var rgb = true
    var running = false
    var mirrored = true
    var synthetic = SyntheticSettings()
    var playing = true
    var showLandmarks = false
    var meshMode = false
    var catalogQuery = ""
    var catalogAssets = emptyList<com.smartfitting.mirror.contracts.Garment>()

    fun connect(url: String) {
        val api = HttpFittingApi(url.trim()) // Validate before replacing an existing controller.
        bridge?.cancel()
        controller?.close()
        baseUrl = url.trim()
        val next = FittingSession(api, viewModelScope)
        controller = next
        bridge = viewModelScope.launch { next.state.collect { mutable.value = it } }
        next.connect()
    }
    override fun onCleared() { controller?.close(); super.onCleared() }
}
