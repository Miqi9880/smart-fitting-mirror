package com.smartfitting.mirror.tryon

import kotlin.math.max
import kotlin.math.min

/** Display-only transform, after fit-center and mirroring. Offsets are view fractions. */
data class PreviewViewport(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    init { require(zoom.isFinite() && zoom in 1f..3f && offsetX.isFinite() && offsetY.isFinite()) }

    fun zoomBy(factor: Float, focusX: Float = .5f, focusY: Float = .5f): PreviewViewport {
        require(factor.isFinite() && factor > 0 && focusX.isFinite() && focusY.isFinite())
        val next = (zoom * factor).coerceIn(1f, 3f)
        if (next == 1f) return PreviewViewport()
        val ratio = next / zoom
        return PreviewViewport(next, ratio * offsetX + (1 - ratio) * (focusX - .5f),
            ratio * offsetY + (1 - ratio) * (focusY - .5f))
    }

    fun pan(dx: Float, dy: Float) = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** Never drag the image completely out of view or expose additional empty edges. */
    fun bounded(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): PreviewViewport {
        if (zoom == 1f) return PreviewViewport()
        if (viewWidth <= 0 || viewHeight <= 0) return copy(offsetX = 0f, offsetY = 0f)
        val fit = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val maxX = max(0f, (imageWidth * fit * zoom - viewWidth) / (2 * viewWidth))
        val maxY = max(0f, (imageHeight * fit * zoom - viewHeight) / (2 * viewHeight))
        return copy(offsetX = offsetX.coerceIn(-maxX, maxX), offsetY = offsetY.coerceIn(-maxY, maxY))
    }
}
