package com.ahmedmahmoud.cinemonitor.camera

import android.graphics.RectF

object TouchAfCoordinateMapper {

    data class CanonPoint(val x: Int, val y: Int)

    fun mapScreenToCanon(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        videoAspectRatio: Float = 16f / 9f,
        evfWidth: Int = 6000,
        evfHeight: Int = 3375
    ): CanonPoint? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null

        val viewAspectRatio = viewWidth / viewHeight
        val activeVideoRect = RectF()

        if (viewAspectRatio > videoAspectRatio) {
            val actualVideoWidth = viewHeight * videoAspectRatio
            val leftOffset = (viewWidth - actualVideoWidth) / 2f
            activeVideoRect.set(leftOffset, 0f, leftOffset + actualVideoWidth, viewHeight)
        } else {
            val actualVideoHeight = viewWidth / videoAspectRatio
            val topOffset = (viewHeight - actualVideoHeight) / 2f
            activeVideoRect.set(0f, topOffset, viewWidth, topOffset + actualVideoHeight)
        }

        if (!activeVideoRect.contains(touchX, touchY)) return null

        val normalizedX = ((touchX - activeVideoRect.left) / activeVideoRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((touchY - activeVideoRect.top) / activeVideoRect.height()).coerceIn(0f, 1f)

        val canonX = (normalizedX * evfWidth).toInt().coerceIn(0, evfWidth)
        val canonY = (normalizedY * evfHeight).toInt().coerceIn(0, evfHeight)

        return CanonPoint(canonX, canonY)
    }
}
