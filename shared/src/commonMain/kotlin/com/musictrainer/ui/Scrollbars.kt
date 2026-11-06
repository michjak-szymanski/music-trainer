package com.musictrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.composed
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Visible, draggable scrollbar for a lazy list, placed at the right edge of the enclosing Box.
 * Shown only when the list doesn't fit.
 */
@Composable
fun BoxScope.ListScrollbar(state: LazyListState) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return
    val avgSize = visible.sumOf { it.size }.toFloat() / visible.size
    val contentPx = avgSize * total
    val viewPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (contentPx <= viewPx + 1f) return
    val offsetPx = state.firstVisibleItemIndex * avgSize + state.firstVisibleItemScrollOffset
    val scope = rememberCoroutineScope()
    ScrollbarTrack(fraction = (offsetPx / (contentPx - viewPx)).coerceIn(0f, 1f), visibleRatio = viewPx / contentPx) { newFraction ->
        val target = newFraction * (contentPx - viewPx)
        val index = (target / avgSize).toInt().coerceIn(0, total - 1)
        scope.launch { state.scrollToItem(index, (target - index * avgSize).toInt()) }
    }
}

/** Visible, draggable scrollbar for a verticalScroll column. */
@Composable
fun BoxScope.ColumnScrollbar(state: ScrollState) {
    if (state.maxValue <= 0) return
    val scope = rememberCoroutineScope()
    val viewPx = state.viewportSize.toFloat()
    ScrollbarTrack(state.value.toFloat() / state.maxValue, viewPx / (viewPx + state.maxValue)) { f ->
        scope.launch { state.scrollTo((f * state.maxValue).toInt()) }
    }
}

@Composable
private fun BoxScope.ScrollbarTrack(fraction: Float, visibleRatio: Float, onScrollTo: (Float) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    var current = fraction
    Canvas(
        Modifier.align(Alignment.TopEnd).width(14.dp).fillMaxHeight()
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val thumb = (size.height * visibleRatio).coerceAtLeast(32.dp.toPx())
                    current = (current + dy / (size.height - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onScrollTo(current)
                }
            }
    ) {
        val w = 6.dp.toPx()
        val thumbH = (size.height * visibleRatio.coerceIn(0f, 1f)).coerceAtLeast(32.dp.toPx())
        val y = fraction * (size.height - thumbH)
        val x = size.width - w - 3.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.12f), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        drawRoundRect(color.copy(alpha = 0.55f), Offset(x, y), Size(w, thumbH), CornerRadius(w / 2))
    }
}

/**
 * Scroll by dragging with a finger or the mouse. Desktop lists only follow the wheel, and Windows delivers
 * touchscreen drags as mouse input; on Android the list's own touch scrolling consumes the drag first.
 * [reverse] is for states whose delta means "content moved" rather than "scroll forward".
 */
fun Modifier.dragToScroll(state: ScrollableState, reverse: Boolean = false): Modifier = composed {
    val fling = ScrollableDefaults.flingBehavior()
    val drag = rememberDraggableState { delta -> state.dispatchRawDelta(if (reverse) delta else -delta) }
    draggable(
        drag, Orientation.Vertical,
        // Keep gliding after a quick swipe, slowing down naturally.
        onDragStopped = { velocity -> state.scroll { with(fling) { performFling(if (reverse) velocity else -velocity) } } },
    )
}

/** A system on-screen keyboard the app opens itself (Windows touch keyboard). */
interface TouchKeyboard {
    /** The device has a touchscreen and the keyboard can be opened. */
    val available: Boolean
    /** Open it automatically when a text field gets focus (tablet posture). */
    val autoShow: Boolean
    fun toggle()
}
