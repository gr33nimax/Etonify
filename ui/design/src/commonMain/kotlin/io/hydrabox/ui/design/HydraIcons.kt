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

    val Server = stroked("server") {
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 10f); lineTo(5f, 10f); close()
        moveTo(5f, 14f); lineTo(19f, 14f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(8f, 7f); lineTo(8.4f, 7f)
        moveTo(8f, 17f); lineTo(8.4f, 17f)
        moveTo(12f, 7f); lineTo(16f, 7f)
        moveTo(12f, 17f); lineTo(16f, 17f)
    }

    val Subscription = stroked("subscription") {
        moveTo(6f, 7f); lineTo(20f, 7f); lineTo(20f, 19f); lineTo(6f, 19f); close()
        moveTo(4f, 5f); lineTo(18f, 5f)
        moveTo(9f, 11f); lineTo(17f, 11f)
        moveTo(9f, 15f); lineTo(14f, 15f)
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

    /**
     * Three quarter-circles and a head. One `arcTo` over half a circle drew a shape that read
     * as two unrelated strokes on the subscriptions card; quarters are unambiguous.
     */
    val Refresh = stroked("refresh") {
        moveTo(12f, 4.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 19.5f, 12f)
        arcTo(7.5f, 7.5f, 0f, false, true, 12f, 19.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 4.5f, 12f)
        moveTo(2.3f, 14.2f); lineTo(4.5f, 12f); lineTo(6.7f, 14.2f)
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

    val Document = stroked("document") {
        moveTo(6f, 3f); lineTo(15f, 3f); lineTo(19f, 7f); lineTo(19f, 21f); lineTo(6f, 21f); close()
        moveTo(15f, 3f); lineTo(15f, 7f); lineTo(19f, 7f)
        moveTo(9f, 12f); lineTo(16f, 12f)
        moveTo(9f, 16f); lineTo(16f, 16f)
    }

    val Lock = stroked("lock") {
        moveTo(7f, 10f); lineTo(7f, 7.5f)
        arcTo(5f, 5f, 0f, false, true, 17f, 7.5f)
        lineTo(17f, 10f)
        moveTo(5f, 10f); lineTo(19f, 10f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(12f, 14f); lineTo(12f, 17f)
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

    /** Both directions at once, for a figure that is the sum of them. */
    val Exchange = stroked("exchange") {
        moveTo(4f, 9f); lineTo(20f, 9f)
        moveTo(16.5f, 5.5f); lineTo(20f, 9f); lineTo(16.5f, 12.5f)
        moveTo(20f, 15f); lineTo(4f, 15f)
        moveTo(7.5f, 11.5f); lineTo(4f, 15f); lineTo(7.5f, 18.5f)
    }

    val Traffic = stroked("traffic") {
        moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9.5f, 7f)
        lineTo(13f, 17f); lineTo(15.5f, 12f); lineTo(21f, 12f)
    }

    val Connections = stroked("connections") {
        moveTo(8.2f, 8.2f); lineTo(10.7f, 15.2f)
        moveTo(15.8f, 8.2f); lineTo(13.3f, 15.2f)
        moveTo(8.5f, 6f); lineTo(15.5f, 6f)
        moveTo(6f, 3.5f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 3.5f); close()
        moveTo(18f, 3.5f); arcTo(2.5f, 2.5f, 0f, true, true, 17.99f, 3.5f); close()
        moveTo(12f, 15.5f); arcTo(2.5f, 2.5f, 0f, true, true, 11.99f, 15.5f); close()
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

    val Export = stroked("export") {
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
        moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(18f, 13f); lineTo(18f, 20f); lineTo(4f, 20f); lineTo(4f, 6f); lineTo(11f, 6f)
    }

    val Share = stroked("share") {
        moveTo(8.2f, 10.2f); lineTo(15.8f, 6.3f)
        moveTo(8.2f, 13.8f); lineTo(15.8f, 17.7f)
        moveTo(6f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 9.5f); close()
        moveTo(18f, 3.5f); arcTo(2.5f, 2.5f, 0f, true, true, 17.99f, 3.5f); close()
        moveTo(18f, 15.5f); arcTo(2.5f, 2.5f, 0f, true, true, 17.99f, 15.5f); close()
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
