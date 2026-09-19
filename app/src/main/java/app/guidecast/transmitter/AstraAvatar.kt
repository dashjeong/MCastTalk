package app.guidecast.transmitter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Lightweight local vector avatar; no remote image, animation loop or background network. */
@Composable
internal fun AstraAvatar() {
    val body = MaterialTheme.colorScheme.primary
    val halo = MaterialTheme.colorScheme.primaryContainer
    val face = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(64.dp).semantics { contentDescription = "아스트라 미니미 · MCastTalk 비서 아바타" }) {
        drawCircle(halo, radius = size.minDimension / 2)
        drawCircle(body, radius = size.minDimension * .38f)
        drawCircle(face, radius = size.minDimension * .05f, center = Offset(size.width * .37f, size.height * .46f))
        drawCircle(face, radius = size.minDimension * .05f, center = Offset(size.width * .63f, size.height * .46f))
        drawLine(face, Offset(size.width * .43f, size.height * .64f), Offset(size.width * .57f, size.height * .64f), strokeWidth = 3.dp.toPx())
    }
}
