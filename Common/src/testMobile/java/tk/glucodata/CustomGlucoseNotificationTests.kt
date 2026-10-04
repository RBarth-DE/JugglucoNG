package tk.glucodata

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.RotateDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CustomGlucoseNotificationTests {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun values(expanded: Boolean = true, rate: Float = 0f,
        peers: List<NotificationChartDrawer.ValueItem> = listOf(NotificationChartDrawer.ValueItem("5,5", 0xff81a9f6.toInt(), 0f)),
        scale: Float = 1f, enabled: Boolean = true) =
        CustomGlucoseNotification.values(app, expanded, "5,7", 0xffeeeeee.toInt(), 0xffcccccc.toInt(),
            0xffaaaaaa.toInt(), peers, rate, 0xffff0000.toInt(), true, scale, 400, false, enabled, 1f, "", true)

    @Test fun nativeReadoutSurvivesParcelAndRestrictedHostWithoutBitmapValues() {
        val parcel = Parcel.obtain()
        try {
            values().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            val restricted = object : android.content.ContextWrapper(app) { override fun isRestricted() = true }
            val root = restored.apply(restricted, null)
            assertEquals("5,7", root.findViewById<TextView>(R.id.notification_glucose).text.toString())
            assertEquals("5,5", root.findViewById<TextView>(R.id.notification_peer_value).text.toString())
            val arrow = root.findViewById<ImageView>(R.id.notification_arrow)
            assertTrue(arrow.drawable is RotateDrawable)
            assertTrue((arrow.drawable as RotateDrawable).drawable is VectorDrawable)
            assertEquals("Steady trend", arrow.contentDescription.toString())
            assertEquals(View.GONE, root.findViewById<TextView>(R.id.notification_status).visibility)
        } finally { parcel.recycle() }
    }

    @Test fun unknownPrimaryDoesNotSuppressPeerTrendAndReapplyRemovesOldPeers() {
        val root = values(rate = Float.NaN).apply(app, null)
        assertEquals(View.GONE, root.findViewById<ImageView>(R.id.notification_arrow).visibility)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.notification_peer_arrow).visibility)
        values(peers = emptyList()).reapply(app, root)
        assertEquals(0, root.findViewById<ViewGroup>(R.id.notification_peers).childCount)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.notification_arrow).visibility)
    }

    @Test fun directionAndDoubleHeadThresholdUseTheSharedRateConvention() {
        for ((rate, level) in listOf(-3f to 10000, -1f to 7500, 0.5f to 5000, 1f to 2500, 3f to 0)) {
            val root = values(rate = rate).apply(app, null)
            val arrow = root.findViewById<ImageView>(R.id.notification_arrow)
            assertEquals(level, arrow.drawable.level)
            val description = arrow.contentDescription.toString()
            assertEquals(kotlin.math.abs(rate) > 2f, description.startsWith("Rapidly"))
        }
        val hidden = values(enabled = false).apply(app, null)
        assertEquals(View.GONE, hidden.findViewById<ImageView>(R.id.notification_arrow).visibility)
        assertEquals(View.GONE, hidden.findViewById<ImageView>(R.id.notification_peer_arrow).visibility)
    }

    @Test fun compactPlotUsesItsHeightInsteadOfFittingAScreenWidthRaster() {
        val views = values(expanded = false)
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(1344, 144, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        val density = app.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((300*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val chart = root.findViewById<ImageView>(R.id.notification_chart)
        assertEquals(ImageView.ScaleType.FIT_XY, chart.scaleType)
        assertEquals((48*density).toInt(), chart.height)
        assertTrue(chart.width > 0)
    }

    @Test fun expandedChartFitsEntireImageAndHidesCleanly() {
        val views = values()
        CustomGlucoseNotification.chart(views, Bitmap.createBitmap(400, 256, Bitmap.Config.ARGB_8888))
        val root = views.apply(app, null)
        assertEquals(ImageView.ScaleType.FIT_CENTER, root.findViewById<ImageView>(R.id.notification_chart).scaleType)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.chart_container).visibility)
        CustomGlucoseNotification.chart(views, null)
        views.reapply(app, root)
        assertEquals(View.GONE, root.findViewById<View>(R.id.chart_container).visibility)
    }

    @Test fun preferenceSizeScalesNativeTextAndCompactFitsHeightBudget() {
        val ordinary = values().apply(app, null).findViewById<TextView>(R.id.notification_glucose)
        val larger = values(scale = 1.5f).apply(app, null).findViewById<TextView>(R.id.notification_glucose)
        assertTrue(larger.textSize > ordinary.textSize)
        val compact = values(expanded = false, scale = 1.5f).apply(app, null)
        val density = app.resources.displayMetrics.density
        compact.measure(View.MeasureSpec.makeMeasureSpec((300*density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(compact.measuredHeight <= 48*density+1)
    }

    @Test fun notificationKeepsCustomContentAndActualTimestampWithUnitlessFallback() {
        val builder = Notification.Builder(app, "glucose").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(123456L).setShowWhen(true).setOnlyAlertOnce(true)
        CustomGlucoseNotification.apply(builder, "5,7", listOf(NotificationChartDrawer.ValueItem("5,5", 0, 0f)),
            "", values(expanded = false), values())
        val notification = builder.build()
        assertNotNull(notification.contentView)
        assertNotNull(notification.bigContentView)
        assertEquals("5,7 · 5,5", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(123456L, notification.`when`)
    }
}
