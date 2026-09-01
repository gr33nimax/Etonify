package io.hydrabox.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn here rather than pulled from a bundle.
 *
 * One language: 24 dp box, 2 dp stroke, round caps, no fills. Material's icon bundle is
 * not available to Compose Multiplatform without an extra artifact, and half of the icons
 * a VPN needs — a lane, a shield with a slash — are not in it anyway.
 */
object HydraIcons {
    val Shield = stroked("shield") {
        moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19.5f, 11.5f)
        curveTo(19.5f, 16f, 16.2f, 19.5f, 12f, 21f)
        curveTo(7.8f, 19.5f, 4.5f, 16f, 4.5f, 11.5f)
        lineTo(4.5f, 6f); close()
    }

    val ShieldCheck = stroked("shield-check") {
        moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19.5f, 11.5f)
        curveTo(19.5f, 16f, 16.2f, 19.5f, 12f, 21f)
        curveTo(7.8f, 19.5f, 4.5f, 16f, 4.5f, 11.5f)
        lineTo(4.5f, 6f); close()
        moveTo(8.8f, 11.6f); lineTo(11.2f, 14f); lineTo(15.4f, 9.4f)
    }

    val Globe = stroked("globe") {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        close()
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(8.7f, 6.4f, 8.7f, 17.6f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(15.3f, 6.4f, 15.3f, 17.6f, 12f, 21f)
    }

    val Sliders = stroked("sliders") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 7f); arcTo(2f, 2f, 0f, true, true, 8.99f, 7f); close()
        moveTo(16f, 12f); arcTo(2f, 2f, 0f, true, true, 15.99f, 12f); close()
        moveTo(11f, 17f); arcTo(2f, 2f, 0f, true, true, 10.99f, 17f); close()
    }

    val Back = stroked("back") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }

    val Add = stroked("add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Refresh = stroked("refresh") {
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, false, 6.3f, 6.3f)
        moveTo(4f, 4f); lineTo(4f, 8.5f); lineTo(8.5f, 8.5f)
    }

    val Search = stroked("search") {
        moveTo(11f, 4f); arcTo(6.5f, 6.5f, 0f, true, true, 10.99f, 4f); close()
        moveTo(15.6f, 15.6f); lineTo(20f, 20f)
    }

    val Check = stroked("check") {
        moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7f)
    }

    val Warning = stroked("warning") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 9.5f); lineTo(12f, 14f)
        moveTo(12f, 16.6f); lineTo(12f, 17f)
    }

    val Info = stroked("info") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f); close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.6f); lineTo(12f, 8f)
    }

    val Apps = stroked("apps") {
        moveTo(4.5f, 4.5f); lineTo(10f, 4.5f); lineTo(10f, 10f); lineTo(4.5f, 10f); close()
        moveTo(14f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 10f); lineTo(14f, 10f); close()
        moveTo(4.5f, 14f); lineTo(10f, 14f); lineTo(10f, 19.5f); lineTo(4.5f, 19.5f); close()
        moveTo(14f, 14f); lineTo(19.5f, 14f); lineTo(19.5f, 19.5f); lineTo(14f, 19.5f); close()
    }

    val Chevron = stroked("chevron") {
        moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
    }

    val Close = stroked("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Bolt = stroked("bolt") {
        moveTo(13f, 3f); lineTo(6f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21f)
        lineTo(18f, 10.5f); lineTo(12.5f, 10.5f); close()
    }

    val Power = stroked("power") {
        moveTo(12f, 3f); lineTo(12f, 11f)
        moveTo(7.5f, 6.5f)
        arcTo(6.8f, 6.8f, 0f, false, false, 12f, 20.5f)
        arcTo(6.8f, 6.8f, 0f, false, false, 16.5f, 6.5f)
    }

    val Download = stroked("download") {
        moveTo(12f, 4f); lineTo(12f, 15.5f)
        moveTo(7f, 11f); lineTo(12f, 16f); lineTo(17f, 11f)
        moveTo(5f, 20f); lineTo(19f, 20f)
    }

    val Upload = stroked("upload") {
        moveTo(12f, 20f); lineTo(12f, 8.5f)
        moveTo(7f, 13f); lineTo(12f, 8f); lineTo(17f, 13f)
        moveTo(5f, 4f); lineTo(19f, 4f)
    }

    val Link = stroked("link") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(9.5f, 7.5f); lineTo(12f, 5f)
        arcTo(3.5f, 3.5f, 0f, false, true, 17f, 10f); lineTo(15f, 12f)
        moveTo(14.5f, 16.5f); lineTo(12f, 19f)
        arcTo(3.5f, 3.5f, 0f, false, true, 7f, 14f); lineTo(9f, 12f)
    }

    val Delete = stroked("delete") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 7f)
    }

    val Edit = stroked("edit") {
        moveTo(4.5f, 19.5f); lineTo(8f, 18.5f); lineTo(19f, 7.5f); lineTo(16f, 4.5f)
        lineTo(5f, 15.5f); close()
    }

    val Logs = stroked("logs") {
        moveTo(5f, 6f); lineTo(19f, 6f)
        moveTo(5f, 11f); lineTo(19f, 11f)
        moveTo(5f, 16f); lineTo(14f, 16f)
    }

    val Palette = stroked("palette") {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, false, 12f, 21f)
        curveTo(13.6f, 21f, 13.6f, 18.5f, 12.8f, 17.6f)
        curveTo(12f, 16.6f, 12.6f, 15f, 14f, 15f)
        lineTo(17f, 15f)
        curveTo(19.2f, 15f, 21f, 13.2f, 21f, 11f)
        curveTo(21f, 6.6f, 17f, 3f, 12f, 3f)
        close()
    }

    private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
