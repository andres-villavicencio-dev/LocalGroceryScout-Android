package com.localscout.app.ui.screens.search

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * M3 Expressive loading state (tactic #5 — fluid, natural motion):
 *
 * A shape-morph sequence (square → squircle → circle → back) driven by spring
 * physics, with a slow breathing scale. Replaces the static hourglass: the
 * morph reads as "the app is working" without a spinny cliché.
 *
 * The 3-shape cycle uses Compose's GenericShape so we interpolate between
 * corner radii rather than swapping composables — the morph is continuous.
 */
@Composable
fun ShapeMorphLoader(
    modifier: Modifier = Modifier,
    sizeDp: Int = 44,
) {
    val transition = rememberInfiniteTransition(label = "morph")

    // Phase driver: 0 → 1 → 2 → 0 every 2.4s
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            phase = (phase + 1) % 3
        }
    }

    // Corner radius morphs: 4dp (square-ish) → 14dp (squircle) → 22dp (circle).
    // Spring physics: slight overshoot makes the morph feel alive.
    val targetRadius = when (phase) {
        0 -> 4f; 1 -> 14f; else -> 22f
    }
    val corner by animateFloatAsState(
        targetValue = targetRadius,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "corner",
    )

    // Breathing scale
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    // Gentle rotation while morphing adds life without dizziness
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
        ),
        label = "rotate",
    )

    Box(
        modifier = modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .scale(scale)
                .graphicsLayer { rotationZ = rotation }
                .clip(RoundedCornerShape(corner.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        // Small satellite dot orbiting for extra life
        Box(
            modifier = Modifier
                .size((sizeDp + 18).dp)
                .graphicsLayer { rotationZ = -rotation * 1.5f }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)),
            )
        }
    }
}

/**
 * Staggered spring entrance for result cards (tactic #5 applied to list
 * entry): each card slides up with overshoot, delayed by its index.
 * Wrap each LazyColumn item content with this.
 */
@Composable
fun SpringEntrance(
    index: Int,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 60ms stagger; cap so a 67-result list doesn't take 4s to appear
        delay((index.coerceAtMost(12) * 60L))
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "entrance",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 80f
        },
    ) {
        content()
    }
}