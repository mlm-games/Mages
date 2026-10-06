package org.mlm.mages.ui.components.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

const val ViewerMinScale = 1f
const val ViewerMaxScale = 5f

private const val DoubleTapLadderLow = 1.75f
private const val DoubleTapLadderHigh = 2.75f
private const val DismissThreshold = 0.12f
private const val DismissFlingVelocityDp = 900f

@Stable
class ImageZoomState(private val maxScale: Float = ViewerMaxScale) {
    var scale by mutableFloatStateOf(ViewerMinScale)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    private var containerSize by mutableStateOf(IntSize.Zero)
    private var intrinsicSize by mutableStateOf(IntSize.Zero)

    val isZoomed: Boolean get() = scale > ViewerMinScale * 1.01f

    fun onContainerSizeChanged(size: IntSize) {
        if (containerSize == size) return
        containerSize = size
        constrain()
    }

    fun onIntrinsicSizeChanged(size: IntSize) {
        if (size.width <= 0 || size.height <= 0 || intrinsicSize == size) return
        intrinsicSize = size
        constrain()
    }

    fun canPan(pan: Offset): Boolean {
        if (!isZoomed) return false
        if (pan.x == 0f) return true
        val limit = maxOffsetX(scale)
        val blocked = (pan.x > 0f && offset.x >= limit - 0.5f) ||
            (pan.x < 0f && offset.x <= -limit + 0.5f)
        return !blocked
    }

    fun transform(centroid: Offset, zoomChange: Float, panChange: Offset) {
        val nextScale = (scale * zoomChange).coerceIn(ViewerMinScale, maxScale)
        if (nextScale != scale) {
            val pivot = if (centroid == Offset.Unspecified) center() else centroid
            offset += (pivot - center() - offset) * (1f - nextScale / scale)
        }
        scale = nextScale
        offset += panChange
        constrain()
    }

    fun doubleTapTarget(tap: Offset): Pair<Float, Offset> {
        val target = when {
            scale < DoubleTapLadderLow -> 2f
            scale < DoubleTapLadderHigh -> 3f
            else -> ViewerMinScale
        }.coerceAtMost(maxScale)
        if (target <= ViewerMinScale) return ViewerMinScale to Offset.Zero
        return target to clamp(offset + (tap - center() - offset) * (1f - target), target)
    }

    fun panStep(): Offset = Offset(containerSize.width * 0.25f, containerSize.height * 0.25f)

    suspend fun stepZoom(delta: Float) {
        val target = (scale + delta).coerceIn(ViewerMinScale, maxScale)
        val reset = target <= ViewerMinScale
        animateTo(target, if (reset) Offset.Zero else offset)
    }

    suspend fun toggleZoom() {
        if (isZoomed) animateTo(ViewerMinScale, Offset.Zero)
        else animateTo(min(2f, maxScale), Offset.Zero)
    }

    suspend fun panBy(delta: Offset): Boolean {
        val next = clamp(offset + delta, scale)
        if (next == offset) return false
        animateTo(scale, next)
        return true
    }

    suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        val toScale = targetScale.coerceIn(ViewerMinScale, maxScale)
        val toOffset = clamp(targetOffset, toScale)
        animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { progress, _ ->
            scale = fromScale + (toScale - fromScale) * progress
            offset = Offset(
                fromOffset.x + (toOffset.x - fromOffset.x) * progress,
                fromOffset.y + (toOffset.y - fromOffset.y) * progress,
            )
            constrain()
        }
    }

    private fun constrain() {
        offset = clamp(offset, scale)
    }

    private fun clamp(value: Offset, atScale: Float) = Offset(
        value.x.coerceIn(-maxOffsetX(atScale), maxOffsetX(atScale)),
        value.y.coerceIn(-maxOffsetY(atScale), maxOffsetY(atScale)),
    )

    private fun center() = Offset(containerSize.width / 2f, containerSize.height / 2f)

    private fun maxOffsetX(atScale: Float = scale) =
        ((fittedWidth() * atScale - containerSize.width) / 2f).coerceAtLeast(0f)

    private fun maxOffsetY(atScale: Float = scale) =
        ((fittedHeight() * atScale - containerSize.height) / 2f).coerceAtLeast(0f)

    private fun fittedWidth(): Float {
        val width = intrinsicSize.width.toFloat()
        if (width <= 0f || containerSize.width <= 0) return containerSize.width.toFloat()
        return width * fitScale()
    }

    private fun fittedHeight(): Float {
        val height = intrinsicSize.height.toFloat()
        if (height <= 0f || containerSize.height <= 0) return containerSize.height.toFloat()
        return height * fitScale()
    }

    private fun fitScale(): Float {
        val width = intrinsicSize.width.toFloat()
        val height = intrinsicSize.height.toFloat()
        if (width <= 0f || height <= 0f) return 1f
        return min(containerSize.width / width, containerSize.height / height)
    }
}

@Composable
internal fun ZoomableImage(
    model: Any?,
    zoom: ImageZoomState,
    contentDescription: String?,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onDraggingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dismissOffset = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val request = remember(model) {
        ImageRequest.Builder(context).data(model).crossfade(true).build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissOffset.floatValue
                val fraction = (abs(dismissOffset.floatValue) / size.height.coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                scaleX = 1f - fraction * 0.2f
                scaleY = scaleX
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val (targetScale, targetOffset) = zoom.doubleTapTarget(tap)
                        scope.launch { zoom.animateTo(targetScale, targetOffset) }
                    },
                )
            }
            .pointerInput(zoom, dismissOffset, scope) {
                detectDismissDrag(zoom, dismissOffset, scope, onDraggingChange, onDismiss)
            },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black))

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged(zoom::onContainerSizeChanged)
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                    clip = true
                }
                .transformable(
                    state = rememberTransformableState { centroid, zoomChange, panChange, _ ->
                        zoom.transform(centroid, zoomChange, panChange)
                    },
                    canPan = zoom::canPan,
                    lockRotationOnZoomPan = true,
                ),
        ) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    val image = state.result.image
                    zoom.onIntrinsicSizeChanged(IntSize(image.width, image.height))
                },
            )
        }
    }
}

private suspend fun PointerInputScope.detectDismissDrag(
    zoom: ImageZoomState,
    dismissOffset: MutableFloatState,
    scope: CoroutineScope,
    onDraggingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val flingVelocity = DismissFlingVelocityDp * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (zoom.isZoomed) return@awaitEachGesture

        val velocityTracker = VelocityTracker()
        var dragging = false
        var multiTouch = false
        var totalX = 0f
        var totalY = 0f

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.size > 1) {
                multiTouch = true
                break
            }
            if (event.changes.any { it.isConsumed }) break
            val change = event.changes.first { it.id == down.id }
            val dx = change.positionChange().x
            val dy = change.positionChange().y
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (dragging) {
                totalX += dx
                totalY += dy
            } else if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                dragging = true
                onDraggingChange(true)
                totalY += dy
            } else {
                continue
            }
            change.consume()
            dismissOffset.floatValue += dy
        }

        if (!dragging) return@awaitEachGesture
        onDraggingChange(false)

        if (multiTouch) {
            scope.launch { animateDismiss(dismissOffset, 0f) }
            return@awaitEachGesture
        }

        val velocityY = velocityTracker.calculateVelocity().y
        val flung = abs(velocityY) > flingVelocity && sign(velocityY) == sign(totalY)
        if (abs(totalY) > size.height * DismissThreshold || flung) {
            scope.launch {
                animateDismiss(dismissOffset, sign(totalY) * size.height, onDismiss)
            }
        } else {
            scope.launch { animateDismiss(dismissOffset, 0f) }
        }
    }
}

private suspend fun animateDismiss(
    dismissOffset: MutableFloatState,
    target: Float,
    onDismiss: () -> Unit = {},
) {
    val from = dismissOffset.floatValue
    val progress = Animatable(0f)
    progress.animateTo(1f, animationSpec = tween(200)) {
        dismissOffset.floatValue = from + (target - from) * value
    }
    onDismiss()
}