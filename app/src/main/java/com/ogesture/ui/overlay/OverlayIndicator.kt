package com.ogesture.ui.overlay

/** A cosmetic gesture indicator living in its own non-touchable overlay window. */
interface OverlayIndicator {
    fun attach()
    fun detach()

    /**
     * Hide (alpha 0) or restore the indicator window. While a consumed touch is being
     * replayed to the UI underneath, every overlay window of ours must be invisible to
     * input dispatch: a non-touchable overlay window counts as 0.8 "obscuring opacity",
     * and two of them stacked (zone + indicator) exceed Android's 0.8 limit, so the
     * system would silently drop the replayed touch as untrusted. Windows with alpha 0
     * are exempt from that occlusion check.
     */
    fun setWindowHidden(hidden: Boolean)
}
