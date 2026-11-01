package app.orchard.tv.ui.wallpaper

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import kotlinx.coroutines.CancellationException

/** Keep only the latest descendant's request active in this scrolling sheet.
 * Must be placed after verticalScroll so it intercepts requests before the scroll responder.
 */
internal fun Modifier.latestBringIntoView(scroll: ScrollState): Modifier =
    this then LatestBringIntoViewElement(scroll)

private data class LatestBringIntoViewElement(val scroll: ScrollState) : ModifierNodeElement<LatestBringIntoViewNode>() {
    override fun create() = LatestBringIntoViewNode(scroll)
    override fun update(node: LatestBringIntoViewNode) { node.scroll = scroll }
    override fun InspectorInfo.inspectableProperties() { name = "latestBringIntoView" }
}

private class LatestBringIntoViewNode(var scroll: ScrollState) : Modifier.Node(), BringIntoViewModifierNode, LayoutAwareModifierNode {
    private val requests = MutatorMutex()
    private var pendingRequests = 0
    private var hasBeenPlaced = false

    override fun onPlaced(coordinates: LayoutCoordinates) { hasBeenPlaced = true }
    override fun onDetach() { hasBeenPlaced = false }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // Focus gain starts a request, but focus loss does not cancel it. Group's note expansion
        // makes consecutive row requests nested: Compose can scroll towards the larger request
        // while waiting for the smaller one to reach its TV pivot, leaving animation running.
        val replacing = pendingRequests > 0
        pendingRequests++
        try {
            requests.mutate {
                // Removing a request does not stop the scroll responder's independent animation.
                // Stop its cached next-frame delta before checking the replacement's target,
                // which may already be at its pivot and otherwise return without scrolling.
                if (replacing) scroll.stopScroll()
                bringIntoView {
                    // The public helper treats a null rectangle as this entire node's bounds.
                    // A removed request must not turn into a request for the whole sheet.
                    if (!isAttached || !hasBeenPlaced || !childCoordinates.isAttached) {
                        throw CancellationException("Bring-into-view target is no longer placed")
                    }
                    val coordinates = requireLayoutCoordinates()
                    val childRect = boundsProvider()
                        ?: throw CancellationException("Bring-into-view target no longer has bounds")
                    // Forward the original bounds (including Group's note), changing only their
                    // coordinate space, exactly as BringIntoViewResponder does.
                    childRect.translate(
                        coordinates.localBoundingBoxOf(childCoordinates, clipBounds = false).topLeft,
                    )
                }
            }
        } finally {
            pendingRequests--
        }
    }
}
