package com.doomcount.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doomcount.app.service.DoomAccessibilityService
import com.doomcount.app.service.EventLogger
import com.doomcount.app.service.SessionStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * MainViewModel — bridges the DoomAccessibilityService's companion StateFlows
 * into Compose-friendly state for MainScreen.
 *
 * Because the service exposes its state via static StateFlows on the companion
 * object (the standard pattern for accessibility services that can't be bound
 * normally), we simply collect those flows here rather than injecting a repository.
 *
 * The ViewModel's viewModelScope keeps these flows active across recomposition
 * while the Activity is alive.
 */
class MainViewModel : ViewModel() {

    /**
     * Whether the DoomAccessibilityService is currently connected and running.
     * Updated by the service itself on [onServiceConnected] / [onUnbind] / [onDestroy].
     */
    val isServiceRunning: StateFlow<Boolean> = DoomAccessibilityService.isRunning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /**
     * Latest session-level event counts from the service.
     * Resets to zero only when [clearSession] is called or the process restarts.
     */
    val sessionStats: StateFlow<SessionStats> = DoomAccessibilityService.sessionStats
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionStats()
        )

    /**
     * Clears the in-memory session log and resets the session event counters.
     * This is the "Clear session" action shown in the UI.
     */
    fun clearSession() {
        EventLogger.clearSession()
        DoomAccessibilityService.resetStats()
    }
}
