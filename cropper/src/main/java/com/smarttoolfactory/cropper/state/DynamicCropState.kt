package com.smarttoolfactory.cropper.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.IntSize
import com.smarttoolfactory.cropper.TouchRegion
import com.smarttoolfactory.cropper.model.AspectRatio
import com.smarttoolfactory.cropper.util.getDistanceToEdgeFromTouch
import com.smarttoolfactory.cropper.util.getTouchRegion
import com.smarttoolfactory.cropper.util.updateOverlayRect
import kotlinx.coroutines.coroutineScope

/**
 * State for cropper with dynamic overlay. Overlay of this state can be moved or resized
 * using handles or touching inner position of overlay. When overlay overflow out of image bounds
 * or moves out of bounds it animates back to valid size and position
 *
 * @param handleSize size of the handle to control, move or scale dynamic overlay
 * @param minOverlaySize minimum overlay size that can be shrunk to by moving handles
 * @param imageSize size of the **Bitmap**
 * @param containerSize size of the Composable that draws **Bitmap**
 * @param maxZoom maximum zoom value
 * @param fling when set to true dragging pointer builds up velocity. When last
 * pointer leaves Composable a movement invoked against friction till velocity drops below
 * to threshold
 * @param zoomable when set to true zoom is enabled
 * @param pannable when set to true pan is enabled
 * @param rotatable when set to true rotation is enabled
 * @param limitPan limits pan to bounds of parent Composable. Using this flag prevents creating
 * empty space on sides or edges of parent
 */
class DynamicCropState internal constructor(
    private val handleSize: Float,
    private val minOverlaySize: Float,
    imageSize: IntSize,
    containerSize: IntSize,
    aspectRatio: AspectRatio,
    maxZoom: Float = 5f,
    fling: Boolean = false,
    zoomable: Boolean = true,
    pannable: Boolean = true,
    rotatable: Boolean = false,
    limitPan: Boolean = false
) : CropState(
    imageSize = imageSize,
    containerSize = containerSize,
    aspectRatio = aspectRatio,
    maxZoom = maxZoom,
    fling = fling,
    moveToBounds = true,
    zoomable = zoomable,
    pannable = pannable,
    rotatable = rotatable,
    limitPan = limitPan
) {

    // Rectangle that covers Image composable
    private val rectBounds = Rect(
        offset = Offset.Zero,
        size = Size(containerSize.width.toFloat(), containerSize.height.toFloat())
    )

    // This rectangle is needed to set bounds set at first touch position while
    // moving to constraint current bounds to temp one from first down
    // When pointer is up
    private var rectTemp = Rect.Zero

    // Region of touch inside, corners of or outside of overlay rectangle
    private var touchRegion = TouchRegion.None

    // Touch position for edge of the rectangle, used for not jumping to edge of rectangle
    // when user moves a handle. We set positionActual as position of selected handle
    // and using this distance as offset to not have a jump from touch position
    private var distanceToEdgeFromTouch = Offset.Zero

    private var doubleTapped = false

    override suspend fun onDown(change: PointerInputChange) {

        rectTemp = overlayRect.copy()
        val position = change.position
        val touchPositionScreenX = position.x
        val touchPositionScreenY = position.y

        val touchPositionOnScreen = Offset(touchPositionScreenX, touchPositionScreenY)

        // Get whether user touched outside, handles of rectangle or inner region or overlay
        // rectangle. Depending on where is touched we can move or scale overlay
        touchRegion = getTouchRegion(
            position = touchPositionOnScreen,
            rect = overlayRect,
            threshold = handleSize
        )

        // This is the difference between touch position and edge
        // This is required for not moving edge of draw rect to touch position on move
        distanceToEdgeFromTouch =
            getDistanceToEdgeFromTouch(touchRegion, rectTemp, touchPositionOnScreen)
    }

    override suspend fun onMove(change: PointerInputChange) {

        // update overlay rectangle based on where its touched and touch position to corners
        // This function moves and/or scales overlay rectangle
        val newRect = updateOverlayRect(
            distanceToEdgeFromTouch = distanceToEdgeFromTouch,
            touchRegion = touchRegion,
            minDimension = minOverlaySize,
            rectTemp = rectTemp,
            overlayRect = overlayRect,
            change = change
        )

        val bounds = getBounds()
        val positionChange = change.positionChangeIgnoreConsumed()

        // When zoom is bigger than 100% and dynamic overlay is not at any edge of
        // image we can pan in the same direction motion goes towards when touch region
        // of rectangle is not one of the handles but region inside
        val isPanRequired = touchRegion == TouchRegion.Inside && zoom > 1f

        // Overlay moving right
        if (isPanRequired && -pan.x < bounds.x && newRect.right >= containerSize.width) {
            snapOverlayRectTo(newRect.translate(-positionChange.x, 0f))
            snapPanXto(pan.x - positionChange.x * zoom)
            // Overlay moving left
        } else if (isPanRequired && pan.x < bounds.x && newRect.left <= 0f) {
            snapOverlayRectTo(newRect.translate(-positionChange.x, 0f))
            snapPanXto(pan.x - positionChange.x * zoom)
        } else if (isPanRequired && pan.y < bounds.y && newRect.top <= 0f) {
            // Overlay moving top
            snapOverlayRectTo(newRect.translate(0f, -positionChange.y))
            snapPanYto(pan.y - positionChange.y * zoom)
        } else if (isPanRequired && -pan.y < bounds.y && newRect.bottom >= containerSize.height) {
            // Overlay moving bottom
            snapOverlayRectTo(newRect.translate(0f, -positionChange.y))
            snapPanYto(pan.y - positionChange.y * zoom)
        } else {
            snapOverlayRectTo(newRect)
        }
        if (touchRegion != TouchRegion.None) {
            change.consume()
        }
    }

    override suspend fun onUp(change: PointerInputChange) = coroutineScope {
        touchRegion = TouchRegion.None
        rectTemp = moveOverlayRectToBounds(rectBounds, overlayRect)
        animateOverlayRectTo(rectTemp)
    }

    override suspend fun onGesture(
        centroid: Offset,
        pan: Offset,
        zoom: Float,
        rotation: Float,
        mainPointer: PointerInputChange,
        changes: List<PointerInputChange>
    ) {
        if (touchRegion == TouchRegion.None) {
            updateTransformState(
                centroid = centroid,
                zoomChange = zoom,
                panChange = pan,
                rotationChange = 0f
            )
        }
    }

    override suspend fun onGestureStart() = Unit
    override suspend fun onGestureEnd(onBoundsCalculated: () -> Unit) = Unit

    override suspend fun onDoubleTap(
        pan: Offset,
        zoom: Float,
        rotation: Float,
        onAnimationEnd: () -> Unit
    ) {
        doubleTapped = true

        if (fling) {
            resetTracking()
        }
        resetWithAnimation(pan = pan, zoom = zoom, rotation = rotation)
        onAnimationEnd()
    }

    /**
     * When pointer is up calculate valid position and size overlay can be updated to
     */
    private fun moveOverlayRectToBounds(rectBounds: Rect, rectCurrent: Rect): Rect {
        var width = rectCurrent.width
        var height = rectCurrent.height


        if (width > rectBounds.width) {
            width = rectBounds.width
        }

        if (height > rectBounds.height) {
            height = rectBounds.height
        }

        var rect = Rect(offset = rectCurrent.topLeft, size = Size(width, height))

        if (rect.left < rectBounds.left) {
            rect = rect.translate(rectBounds.left - rect.left, 0f)
        }

        if (rect.top < rectBounds.top) {
            rect = rect.translate(0f, rectBounds.top - rect.top)
        }

        if (rect.right > rectBounds.right) {
            rect = rect.translate(rectBounds.right - rect.right, 0f)
        }

        if (rect.bottom > rectBounds.bottom) {
            rect = rect.translate(0f, rectBounds.bottom - rect.bottom)
        }

        return rect
    }
}