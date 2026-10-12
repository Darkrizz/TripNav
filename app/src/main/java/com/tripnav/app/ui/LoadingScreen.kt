package com.tripnav.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tripnav.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Cold-start loading screen. It takes over from the system splash with the logo in the same place: the wheel
 * breathes in, the arrow pulses forward, the wordmark settles underneath, and the whole screen fades away once the app is
 * [ready] (and the intro has played).
 */
@Composable
fun LoadingScreen(ready: Boolean, onFinished: () -> Unit) {
    val spin = remember { Animatable(0f) }
    val arrow = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val isReady by rememberUpdatedState(ready)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { spin.animateTo(1f, tween(1_150, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
            launch { delay(200); arrow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            launch { delay(520); word.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
            launch { delay(760); line.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        }
        snapshotFlow { isReady }.first { it }
        delay(150)
        exit.animateTo(1f, tween(380, easing = LinearEasing))
        finished()
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(TN.Splash),
        contentAlignment = Alignment.Center,
    ) {
        // Same size and position as the system splash icon, so the hand-over is seamless.
        Box(Modifier.size(240.dp).graphicsLayer { scaleX = 1f + exit.value * 0.06f; scaleY = scaleX }) {
            Image(
                painterResource(R.drawable.logo_tyre), null,
                // Starts exactly as the system splash left it, dips slightly and settles: a quiet "breath" in.
                Modifier.fillMaxSize().graphicsLayer {
                    val dip = kotlin.math.sin(Math.PI * spin.value).toFloat()
                    scaleX = 1f - 0.06f * dip; scaleY = scaleX
                },
            )
            Image(
                painterResource(R.drawable.logo_arrow), null,
                Modifier.fillMaxSize().graphicsLayer {
                    // The system splash already showed the arrow, so it stays put and just pulses forward once.
                    val pulse = kotlin.math.sin(Math.PI * arrow.value).toFloat()
                    scaleX = 1f + 0.12f * pulse; scaleY = scaleX
                    translationY = -10.dp.toPx() * pulse
                },
            )
        }
        Column(
            Modifier.align(Alignment.Center).graphicsLayer {
                translationY = 150.dp.toPx() + 16.dp.toPx() * (1f - word.value)
                alpha = word.value
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "TRIPNAV",
                style = TextStyle(
                    color = TN.Ivory, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold,
                    fontSize = 38.sp, letterSpacing = (14 - 6 * word.value).sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.width(140.dp * line.value).height(1.5.dp)
                    .background(Brush.horizontalGradient(listOf(TN.Ember.copy(alpha = 0f), TN.Ember, TN.Ember.copy(alpha = 0f)))),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "MADE FOR THE OPEN ROAD",
                style = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 3.sp),
                color = TN.TextMid.copy(alpha = line.value),
            )
        }
    }
}
