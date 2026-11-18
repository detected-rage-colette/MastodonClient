package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope as LayoutScrollScope
import kotlin.math.roundToInt

private val TopReturnDecelerateEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
// Quadratic ease-out (initial derivative = 2.0) matches the incoming linear approach velocity.
private val TopSettleContinuationEasing = Easing { fraction ->
    val remaining = 1f - fraction
    1f - remaining * remaining
}

/** Return a list, optionally followed by its shared profile header, as one scroll animation. */
internal suspend fun LazyListState.animateToTimelineTop(headerListState: LazyListState? = null) {
    require(headerListState !== this)
    scroll {
        val posts = TopScrollTarget(this@animateToTimelineTop, LazyLayoutScrollScope(this@animateToTimelineTop, this))
        if (headerListState == null) {
            animateToTop(listOf(posts))
        } else {
            // Hold both mutations: a gesture on either list cancels the entire return.
            headerListState.scroll {
                val header = TopScrollTarget(headerListState, LazyLayoutScrollScope(headerListState, this))
                animateToTop(listOf(posts, header))
            }
        }
    }
}

private class TopScrollTarget(val state: LazyListState, val layout: LayoutScrollScope) {
    fun snapNearTop(maxDistance: Int) {
        val originalIndex = state.firstVisibleItemIndex
        val originalOffset = state.firstVisibleItemScrollOffset
        if (originalIndex == 0 && originalOffset <= maxDistance) return
        val estimatedDistance = -layout.calculateDistanceTo(0)
        if (estimatedDistance in 1..maxDistance) return
        layout.snapToItem(0, maxDistance)
        // Keep the closer position even when a tall first row normalizes the offset.
        if (state.firstVisibleItemIndex > originalIndex ||
            (state.firstVisibleItemIndex == originalIndex && state.firstVisibleItemScrollOffset > originalOffset)) {
            layout.snapToItem(originalIndex, originalOffset)
        }
    }
}

private suspend fun animateToTop(targets: List<TopScrollTarget>) {
    val viewport = targets.last().state.layoutInfo
    val viewportHeight = (viewport.viewportEndOffset - viewport.viewportStartOffset).coerceAtLeast(1)
    val maxAnimatedDistance = viewportHeight * 2
    // Reserve the header's distance first so the combined return is at most two viewports.
    // If the header alone fills this budget, posts reach their top before it is revealed.
    var remainingDistance = maxAnimatedDistance
    for (target in targets.asReversed()) {
        target.snapNearTop(remainingDistance)
        remainingDistance = if (target.state.firstVisibleItemIndex == 0) {
            (remainingDistance - target.state.firstVisibleItemScrollOffset).coerceAtLeast(0)
        } else 0
    }
    fun scrollBy(delta: Float): Float {
        var remaining = delta
        for (target in targets) {
            remaining -= target.layout.scrollBy(remaining)
        }
        return delta - remaining
    }
    fun firstItemsVisible() = targets.all { it.state.firstVisibleItemIndex == 0 }

    // When a single new post is anchored right below index 0, stepping 1px across the boundary
    // composes index 0 immediately so its exact height can be animated with a single curve.
    if (targets.size == 1 &&
        targets[0].state.firstVisibleItemIndex == 1 &&
        targets[0].state.firstVisibleItemScrollOffset == 0
    ) {
        scrollBy(-1f)
    }

    val chunkDistance = maxAnimatedDistance.toFloat()
    val nearTop = targets.all { it.state.firstVisibleItemIndex <= 3 }
    val chunkDurationMillis = if (nearTop) 300 else 250
    val approachVelocityPxPerMs = chunkDistance / chunkDurationMillis
    var animation = AnimationState(0f)
    var chunks = 0
    var reachedBoundary = false
    while (!firstItemsVisible() && targets.any { it.state.canScrollBackward } && !reachedBoundary) {
        animation = animation.copy(value = 0f)
        var previous = 0f
        animation.animateTo(-chunkDistance, tween(chunkDurationMillis, easing = LinearEasing), sequentialAnimation = chunks > 0) {
            val delta = value - previous
            val consumed = scrollBy(delta)
            previous = value
            if (delta < -1f && consumed == 0f) reachedBoundary = true
            if (firstItemsVisible() || reachedBoundary) cancelAnimation()
        }
        chunks++
    }
    if (firstItemsVisible()) {
        val finalDistance = targets.sumOf { it.state.firstVisibleItemScrollOffset }
        if (finalDistance > 0) {
            var previous = 0f
            val durationMillis = if (chunks == 0) {
                (180 + (finalDistance.toFloat() / viewportHeight * 60f).roundToInt()).coerceIn(180, 240)
            } else {
                ((2f * finalDistance) / approachVelocityPxPerMs).roundToInt().coerceIn(90, 220)
            }
            val easing = if (chunks == 0) TopReturnDecelerateEasing else TopSettleContinuationEasing
            animation = animation.copy(value = 0f)
            animation.animateTo(
                targetValue = -finalDistance.toFloat(),
                animationSpec = tween(durationMillis, easing = easing),
                sequentialAnimation = chunks > 0,
            ) {
                scrollBy(value - previous)
                previous = value
            }
        }
    }
    // Gesture/context cancellation exits both scroll mutations before reaching this.
    targets.forEach { it.layout.snapToItem(0) }
}
