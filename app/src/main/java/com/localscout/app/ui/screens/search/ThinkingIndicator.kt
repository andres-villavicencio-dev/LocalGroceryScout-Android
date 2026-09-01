package com.localscout.app.ui.screens.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localscout.app.R

/**
 * Thinking indicator shown while we're waiting on ollama.
 *
 * Three pieces:
 *  - Pulsing sparkle icon (visual heartbeat, alpha 0.4 → 1.0 over 1.2s)
 *  - Rotating hourglass (rotation 0° → 360° over 3s — fun but not distracting)
 *  - Status text that swaps based on [phase]:
 *      phase 0          → "Asking <model>…"  (with the model name)
 *      phase 1..5 (5s+)  → "Combing the aisles…" / "Checking the shelves…" / etc.
 *  - Elapsed seconds counter so the user can see the call is alive.
 *
 * Material 3 layout — no hardcoded colors so it adapts to dynamic color.
 */
@Composable
fun ThinkingIndicator(
    modelName: String?,
    elapsedSeconds: Int,
    phase: Int,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = stringResource(R.string.thinking_a11y)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp)
            .semantics { contentDescription = a11yLabel },
    ) {
        // Animated icon stack: rotating hourglass + pulsing sparkle behind it.
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            val transition = rememberInfiniteTransition(label = "thinking-pulse")

            // Pulse alpha on the background sparkle (1.2s loop).
            val sparkleAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sparkle-alpha",
            )
            // Rotate the hourglass (3s loop).
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3000, easing = LinearEasing),
                ),
                label = "hourglass-rotation",
            )
            // Subtle scale pulse on the whole stack (synced with sparkle alpha).
            val scale by transition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "stack-scale",
            )

            // Background sparkle (faded accent color).
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .scale(scale)
                    .alpha(sparkleAlpha * 0.55f),
            )
            // Rotating hourglass on top.
            Icon(
                imageVector = Icons.Outlined.HourglassBottom,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(40.dp)
                    .rotate(rotation),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status text: first phase shows model name; later phases cycle jokes.
        val statusText = when {
            elapsedSeconds < 5 && modelName != null ->
                stringResource(R.string.thinking_initial, modelName)
            phase == 1 || (phase == 0 && modelName == null) ->
                stringResource(R.string.thinking_rotating)
            phase == 2 -> stringResource(R.string.thinking_rotating_2)
            phase == 3 -> stringResource(R.string.thinking_rotating_3)
            phase == 4 -> stringResource(R.string.thinking_rotating_4)
            else -> stringResource(R.string.thinking_rotating_5)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Elapsed-time line so users can see the call is alive.
        Text(
            text = stringResource(R.string.thinking_elapsed, elapsedSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Witty status rotation, shared between the classic hourglass loader above
 * and the M3 Expressive ShapeMorphLoader so both feel cohesive.
 */
object ThinkingPhrases {
    fun statusText(phase: Int): String = when {
        phase == 1 -> "Combing the aisles…"
        phase == 2 -> "Checking the shelves…"
        phase == 3 -> "Asking the locals…"
        phase == 4 -> "Comparing the receipts…"
        else -> "Walking to the back of the store…"
    }
}

/**
 * Tiny inline progress dot row — used inside cards to give a visual heartbeat
 * while a result card is loading. Kept here so the thinking indicator stays
 * cohesive in look-and-feel.
 */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, dotColor: Color? = null) {
    val color = dotColor ?: MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 900,
                        delayMillis = i * 150,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$i-alpha",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}
