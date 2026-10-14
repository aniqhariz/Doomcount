package com.doomcount.app.detectors

/**
 * Platform-independent abstraction of an accessibility/motion event.
 *
 * Allows [TikTokDetector] to be tested with deterministic synthetic sequences
 * on the JVM without requiring an Android device, Robolectric, or framework mocks.
 */
data class FeedEvent(
    val eventType: Int,
    val packageName: String?,
    val className: String?,
    val eventTime: Long = System.currentTimeMillis(),
    val scrollDeltaX: Int = 0,
    val scrollDeltaY: Int = 0,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
    val contentChangeTypes: Int = 0
) {
    companion object {
        const val TYPE_VIEW_SCROLLED = 0x00001000
        const val TYPE_WINDOW_STATE_CHANGED = 0x00000020
        const val TYPE_WINDOW_CONTENT_CHANGED = 0x00000800
        const val TYPE_VIEW_CLICKED = 0x00000001

        const val CONTENT_CHANGE_TYPE_SUBTREE = 1
        const val CONTENT_CHANGE_TYPE_TEXT = 2
        const val CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION = 4
    }
}

