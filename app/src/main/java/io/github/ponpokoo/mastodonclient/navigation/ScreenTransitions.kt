package io.github.ponpokoo.mastodonclient.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import kotlin.math.roundToInt

private const val ScreenEnterDurationMillis = 240
private const val ScreenEnterFadeDurationMillis = 180
private const val ScreenEnterSlideFraction = 0.12f
private const val ScreenPopDurationMillis = 240
private const val ScreenSlideFraction = 0.35f
private const val ScreenPopFadeDelayMillis = 20
private const val ScreenPopFadeDurationMillis = 190
private const val ScreenPredictiveFadeStartFraction = 0.20f
private const val ScreenFadeEndFraction = 0.88f
private val ScreenPredictiveFadeDelayMillis =
    (ScreenPopDurationMillis * ScreenPredictiveFadeStartFraction).roundToInt()
private val ScreenPredictiveFadeDurationMillis =
    (ScreenPopDurationMillis * ScreenFadeEndFraction).roundToInt() - ScreenPredictiveFadeDelayMillis

private val ScreenTransitionEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val ScreenPopFadeEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 1f)

@Composable
internal fun ScreenNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val graph = remember(navController, startDestination, builder) {
        // Named routes and serializable route objects use different createGraph overloads.
        if (startDestination is String) {
            navController.createGraph(startDestination = startDestination, builder = builder)
        } else {
            navController.createGraph(startDestination = startDestination, builder = builder)
        }
    }
    NavHost(
        navController = navController,
        graph = graph,
        modifier = modifier,
        enterTransition = { screenEnterTransition() },
        exitTransition = { fadeOut(tween(ScreenEnterDurationMillis, easing = ScreenTransitionEaseOut)) },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { screenPopExitTransition() },
        predictivePopEnterTransition = { EnterTransition.None },
        // Navigation seeks this transition proportionally with gesture progress and restores it on cancellation.
        predictivePopExitTransition = { screenPredictivePopExitTransition() },
    )
}

private fun screenEnterTransition(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(ScreenEnterDurationMillis, easing = ScreenTransitionEaseOut),
        initialOffsetX = { width -> (width * ScreenEnterSlideFraction).roundToInt() },
    ) + fadeIn(tween(ScreenEnterFadeDurationMillis, easing = ScreenTransitionEaseOut))

private fun screenPopExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(ScreenPopDurationMillis, easing = ScreenTransitionEaseOut),
        targetOffsetX = { width -> (width * ScreenSlideFraction).roundToInt() },
    ) + fadeOut(tween(
        durationMillis = ScreenPopFadeDurationMillis,
        delayMillis = ScreenPopFadeDelayMillis,
        easing = ScreenPopFadeEasing,
    ))

private fun screenPredictivePopExitTransition(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(ScreenPopDurationMillis, easing = LinearEasing),
        targetOffsetX = { width -> (width * ScreenSlideFraction).roundToInt() },
    ) + fadeOut(tween(
        durationMillis = ScreenPredictiveFadeDurationMillis,
        delayMillis = ScreenPredictiveFadeDelayMillis,
        easing = LinearEasing,
    ))

internal inline fun <reified T : Any> NavGraphBuilder.animatedScreen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T>(content = content)
}

internal fun NavGraphBuilder.animatedScreen(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, content = content)
}
