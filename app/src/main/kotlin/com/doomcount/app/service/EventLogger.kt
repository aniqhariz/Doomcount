package com.doomcount.app.service

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.Collections

/**
 * EventLogger — prototype-only, structured safe event recorder.
 *
 * Records strictly structural and motion metadata:
 * - Event type (integer + name)
 * - Package name
 * - Class name (to investigate feed vs non-feed view classes)
 * - Timestamps (system wallclock and system event uptime)
 * - Content-change type bitmask and human labels (when available)
 * - Scroll metadata (deltas, coordinates, item index range, scrollable flag)
 *
 * PRIVACY RULES (CRITICAL):
 * - NEVER reads or logs event text, node text, or user input.
 * - NEVER reads or logs content descriptions or view text.
 * - NEVER captures screenshots, video contents, messages, or typed text.
 */
object EventLogger {
    private const val TAG = "DoomEvent"
    private const val MAX_LOG_SIZE = 200

    private val _sessionLog = Collections.synchronizedList(mutableListOf<LoggedEvent>())
    val sessionLog: List<LoggedEvent> get() = synchronized(_sessionLog) { _sessionLog.toList() }

    /**
     * Logs the event with structured metadata. Safe: does not read text or hierarchy.
     */
    fun log(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: "unknown"
        val cls = event.className?.toString() ?: ""

        val changeTypes = if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.contentChangeTypes
        } else {
            0
        }
        val changeTypesDesc = if (changeTypes != 0) describeContentChangeTypes(changeTypes) else ""

        val (deltaX, deltaY) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            Pair(event.scrollDeltaX, event.scrollDeltaY)
        } else {
            Pair(null, null)
        }

        val entry = LoggedEvent(
            timestampMs = now,
            eventTime = event.eventTime,
            eventType = eventType,
            eventTypeName = AccessibilityEvent.eventTypeToString(eventType),
            packageName = pkg,
            className = cls,
            contentChangeTypes = changeTypes,
            contentChangeTypesDesc = changeTypesDesc,
            scrollDeltaX = deltaX,
            scrollDeltaY = deltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            isScrollable = event.isScrollable
        )

        synchronized(_sessionLog) {
            if (_sessionLog.size >= MAX_LOG_SIZE) {
                _sessionLog.removeAt(0)
            }
            _sessionLog.add(entry)
        }

        // Structured logcat output for ADB inspection
        Log.d(TAG, buildStructuredLogMessage(entry))
    }

    private fun buildStructuredLogMessage(entry: LoggedEvent): String {
        return buildString {
            append("[${entry.eventTypeName}] ")
            append("pkg=${entry.packageName} ")
            if (entry.className.isNotEmpty()) {
                append("class=${entry.className} ")
            }
            if (entry.contentChangeTypes != 0) {
                append("contentChanges=[${entry.contentChangeTypesDesc}] ")
            }
            if (entry.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                append("scroll=[")
                if (entry.scrollDeltaX != null || entry.scrollDeltaY != null) {
                    append("dx=${entry.scrollDeltaX}, dy=${entry.scrollDeltaY}, ")
                }
                append("y=${entry.scrollY}/${entry.maxScrollY}, ")
                append("from=${entry.fromIndex}, to=${entry.toIndex}, count=${entry.itemCount}")
                append("] ")
            }
            append("t=${entry.timestampMs}")
        }
    }

    private fun describeContentChangeTypes(types: Int): String {
        if (types == 0) return "NONE"
        val parts = mutableListOf<String>()
        if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) parts.add("SUBTREE")
        if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) parts.add("TEXT")
        if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) parts.add("CONTENT_DESC")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0) parts.add("PANE_TITLE")
            if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0) parts.add("PANE_APPEARED")
            if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0) parts.add("PANE_DISAPPEARED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0) parts.add("STATE_DESC")
        }
        return if (parts.isEmpty()) "0x${Integer.toHexString(types)}" else parts.joinToString("|")
    }

    fun clearSession() {
        synchronized(_sessionLog) {
            _sessionLog.clear()
        }
    }
}

/**
 * Immutable record of an observed accessibility event containing only structural metadata.
 */
data class LoggedEvent(
    val timestampMs: Long,
    val eventTime: Long,
    val eventType: Int,
    val eventTypeName: String,
    val packageName: String,
    val className: String,
    val contentChangeTypes: Int = 0,
    val contentChangeTypesDesc: String = "",
    val scrollDeltaX: Int? = null,
    val scrollDeltaY: Int? = null,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val maxScrollX: Int = 0,
    val maxScrollY: Int = 0,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
    val isScrollable: Boolean = false
)
