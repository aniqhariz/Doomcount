package com.doomcount.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doomcount.app.service.SessionStats
import com.doomcount.app.ui.theme.DoomAmber
import com.doomcount.app.ui.theme.DoomBackground
import com.doomcount.app.ui.theme.DoomGreen
import com.doomcount.app.ui.theme.DoomRed
import com.doomcount.app.ui.theme.DoomSubtle
import com.doomcount.app.ui.theme.DoomSurface

/**
 * MainScreen — technical feasibility prototype screen for DOOMCOUNT.
 *
 * Clearly separates:
 * 1. Raw Accessibility Event counts (uninterpreted events from Android).
 * 2. Experimental Transition Estimate (temporal scroll-burst clustering).
 * 3. Validation procedure for 10, 20, and 50 swipe tests.
 *
 * No polished consumer UI, dashboard, or monetization features.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val stats by viewModel.sessionStats.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────────
            Column {
                Text(
                    text = "DOOMCOUNT",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Default,
                    color = DoomRed,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Scroll-Detection Feasibility Experiment",
                    style = MaterialTheme.typography.titleMedium,
                    color = DoomSubtle
                )
            }

            // ── Empirical Finding Note ──────────────────────────────────────────
            SectionCard(borderColor = DoomAmber.copy(alpha = 0.4f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🔬 ", fontSize = 16.sp)
                    Text(
                        text = "Real-Device Finding (10-Swipe Test)",
                        style = MaterialTheme.typography.titleMedium,
                        color = DoomAmber,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "In manual device testing, 10 video swipes generated only 2 Window State Changes, 30 Scroll Events (~3/swipe), and 155 Content Changes. Transitions are estimated below by clustering scroll events into gestures (500ms window), not from window changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DoomSubtle
                )
            }

            // ── Service Status Card ──────────────────────────────────────────────
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) DoomGreen else DoomRed)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isRunning) "Accessibility Service: ENABLED" else "Accessibility Service: DISABLED",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isRunning) DoomGreen else DoomRed,
                        fontWeight = FontWeight.Bold
                    )
                }

                AnimatedVisibility(
                    visible = !isRunning,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = DoomSubtle.copy(alpha = 0.3f))
                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "How to enable for testing",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "DOOMCOUNT requires Accessibility Service to observe scroll and navigation events in TikTok. It does not read screen text, video contents, messages, or passwords.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DoomSubtle
                        )
                        Spacer(Modifier.height(10.dp))

                        listOf(
                            "1" to "Tap \"Enable in Settings\" below.",
                            "2" to "Select \"DOOMCOUNT — Video Transition Detector\".",
                            "3" to "Toggle service ON and confirm system dialog.",
                            "4" to "Return to DOOMCOUNT."
                        ).forEach { (step, text) ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "$step.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DoomRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DoomRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Enable in Settings", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Primary Metric: Confirmed Transition Estimate ──────────────────
            SectionCard(borderColor = DoomGreen.copy(alpha = 0.5f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONFIRMED TRANSITIONS",
                        style = MaterialTheme.typography.titleMedium,
                        color = DoomGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(DoomGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "SETTLED & CONFIRMED",
                            fontSize = 10.sp,
                            color = DoomGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Confirmed Video Transitions (Feed Advanced):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DoomSubtle
                )

                Spacer(Modifier.height(8.dp))
                // Large primary counter
                Text(
                    text = "${stats.estimatedTransitions}",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = DoomGreen,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "confirmed transitions",
                    style = MaterialTheme.typography.labelSmall,
                    color = DoomSubtle,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DoomSubtle.copy(alpha = 0.2f))
                Spacer(Modifier.height(10.dp))

                // Breakdown of candidates, confirmed, and canceled
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(
                        label = "Candidate gestures initiated",
                        value = "${stats.candidateTransitionsCount}"
                    )
                    StatRow(
                        label = "Canceled / aborted swipes prevented",
                        value = "${stats.canceledGesturesCount}",
                        valueColor = if (stats.canceledGesturesCount > 0) DoomAmber else DoomSubtle
                    )
                    StatRow(
                        label = "Scroll events absorbed (continuation)",
                        value = "${stats.absorbedScrollEvents}"
                    )
                    StatRow(
                        label = "Bursts rejected (horizontal / non-feed)",
                        value = "${stats.rejectedBurstsCount}",
                        valueColor = if (stats.rejectedBurstsCount > 0) DoomAmber else DoomSubtle
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = DoomSubtle.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Latest Decision:",
                    style = MaterialTheme.typography.labelSmall,
                    color = DoomSubtle,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stats.lastDecisionSummary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Burst Motion Diagnostics:",
                    style = MaterialTheme.typography.labelSmall,
                    color = DoomSubtle,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stats.lastBurstSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DoomSubtle
                )
            }

            // ── Raw Accessibility Events (Unfiltered) ────────────────────────────
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Raw Event Counts (TikTok)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "unfiltered",
                        style = MaterialTheme.typography.labelSmall,
                        color = DoomSubtle
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Raw signals emitted by TikTok. These reflect OS event volume, not 1:1 user swipes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DoomSubtle
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow(
                        label = "TYPE_VIEW_SCROLLED",
                        value = "${stats.scrollEvents}"
                    )
                    StatRow(
                        label = "TYPE_WINDOW_CONTENT_CHANGED",
                        value = "${stats.contentChangedEvents}"
                    )
                    StatRow(
                        label = "TYPE_WINDOW_STATE_CHANGED",
                        value = "${stats.windowStateChanges}"
                    )
                    StatRow(
                        label = "TYPE_VIEW_CLICKED",
                        value = "${stats.clickEvents}"
                    )
                }
            }

            // ── Manual Validation Checklist (Tests A through E) ──────────────────
            SectionCard {
                Text(
                    text = "📋 Manual Validation Procedure",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Follow these tests on a physical device with TikTok to validate canceled-swipe prevention:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DoomSubtle
                )
                Spacer(Modifier.height(10.dp))

                ValidationProtocolItem(
                    target = "Test A — Normal Swipes (10, 20, 50)",
                    description = "Perform intentional vertical swipes. Expected: exactly 1 confirmed transition per completed feed advance."
                )
                Spacer(Modifier.height(8.dp))
                ValidationProtocolItem(
                    target = "Test B — Canceled Swipes (20 Gestures)",
                    description = "Perform 20 deliberately incomplete/canceled swipes where TikTok returns to original video. Expected: 0 additional transitions, 20 canceled gestures detected."
                )
                Spacer(Modifier.height(8.dp))
                ValidationProtocolItem(
                    target = "Test C — Mixed Gestures",
                    description = "Mix completed vertical swipes, canceled swipes, short drags, and horizontal swipes. Expected: only actual feed advancements increment counter."
                )
                Spacer(Modifier.height(8.dp))
                ValidationProtocolItem(
                    target = "Test D — Idle (30 Seconds)",
                    description = "Remain on one video for 30s without touching screen. Expected: no new transitions or bursts."
                )
                Spacer(Modifier.height(8.dp))
                ValidationProtocolItem(
                    target = "Test E — Target App Isolation",
                    description = "Scroll TikTok, switch to Instagram Reels, scroll there, return to TikTok. Expected: Reels scrolling produces 0 TikTok counts."
                )
            }

            // ── Privacy Assurance ────────────────────────────────────────────────
            SectionCard {
                Text(
                    text = "🔒 Privacy & Data Collection Notice",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "DOOMCOUNT only inspects event type, package, class names, and scroll/change metadata. canRetrieveWindowContent is false. No video contents, screen text, messages, or typed text are captured or logged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DoomSubtle
                )
            }

            // ── Session Reset Control ────────────────────────────────────────────
            OutlinedButton(
                onClick = { viewModel.clearSession() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Reset Counters & Clear Session", color = DoomRed)
            }

            // ── Build Information ────────────────────────────────────────────────
            Text(
                text = "v0.1.0-prototype · Milestone 1 · Scroll-Detection Feasibility Spike",
                style = MaterialTheme.typography.labelSmall,
                color = DoomSubtle.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ValidationProtocolItem(
    target: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DoomBackground.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Text(
            text = target,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = DoomSubtle
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DoomSubtle,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SectionCard(
    borderColor: Color = DoomSubtle.copy(alpha = 0.2f),
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DoomSurface)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        content()
    }
}
