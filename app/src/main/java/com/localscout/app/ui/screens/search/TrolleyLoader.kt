package com.localscout.app.ui.screens.search

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The trolley loader (M3 Expressive tactic #5 — fluid, natural motion):
 *
 * A shopping trolley that rocks side-to-side like it's being pushed down the
 * aisle while grocery "items" drop into it. On-brand for a price-scouting
 * app: the user is literally watching the trolley fill while the agent works.
 *
 * Motion inventory:
 *  - rock: ±14° rotation over 1.6s with a keyframe wobble (cart-wheel feel)
 *  - hop: small vertical bounce synced to the rock (trolley bumping over tiles)
 *  - items: 3 dots drop from above into the basket, staggered, fading as they
 *    land — reads as "collecting prices"
 */
@Composable
fun TrolleyLoader(
    modifier: Modifier = Modifier,
    sizeDp: Int = 64,
) {
    val transition = rememberInfiniteTransition(label = "trolley")

    // Rock: swing -14° ↔ +14° with easing wobble
    val rock by transition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                0f at 0 using FastOutSlowInEasing
                14f at 400
                -8f at 800 using FastOutSlowInEasing
                14f at 1200
                -14f at 1600
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "rock",
    )

    // Hop: two small vertical bumps per cycle
    val hop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                0f at 0
                -8f at 200 using FastOutSlowInEasing
                0f at 400
                -6f at 1000 using FastOutSlowInEasing
                0f at 1200
            },
        ),
        label = "hop",
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        // Items dropping into the basket (3 staggered dots)
        repeat(3) { i ->
            val startMs = i * 500
            val landMs = i * 400 + 500
            val drop by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 2400
                        0f at startMs
                        1f at landMs using FastOutSlowInEasing
                        1f at 2400
                    },
                ),
                label = "drop-$i",
            )
            if (drop > 0.01f && drop < 0.99f) {
                Box(
                    modifier = Modifier
                        .offset(y = (drop * 36).dp)
                        .size(8.dp)
                        .alpha(1f - drop)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
        // The trolley itself
        Icon(
            imageVector = Icons.Default.ShoppingCart,
            contentDescription = "Trolley loading",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(sizeDp.dp)
                .rotate(rock)
                .scale(1f + hop / 100f),
        )
    }
}