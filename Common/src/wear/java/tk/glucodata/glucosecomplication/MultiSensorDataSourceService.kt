package tk.glucodata.glucosecomplication

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.TrendArrowAngle
import tk.glucodata.ui.WearSensorSelection
import java.time.Instant

internal data class MultiSensorValue(
    val sensorId: String,
    val reading: GlucoseComplicationData.Reading?,
    val colorArgb: Int,
) {
    val text: String get() = reading?.text ?: "—"
}

/** Keep every selected slot, even when stale, so a peer never impersonates the primary. */
internal fun multiSensorValues(
    selected: List<String>,
    colors: Map<String, Int>,
    now: Long,
    timeout: Long,
    resolve: (String) -> GlucoseComplicationData.Reading?,
): List<MultiSensorValue> = selected.map { sensor ->
    val reading = resolve(sensor)?.takeIf {
        it.timeMillis in 1..now && now - it.timeMillis < timeout &&
            it.value.isFinite() && it.value > 0f && it.text.isNotBlank() &&
            SensorIdentity.matches(it.sensorId, sensor)
    }
    MultiSensorValue(sensor, reading, colors[sensor] ?: 0xFFE3E2DE.toInt())
}

internal fun multiSensorText(values: List<MultiSensorValue>, arrows: Boolean): String =
    values.joinToString(" · ") { value ->
        val rate = value.reading?.rate
        val rotation = rate?.let { -TrendArrowAngle.rotationDegrees(it) } ?: 0f
        val arrow = if (!arrows || rate == null || !rate.isFinite()) "" else when {
            rate > 2f -> "↑↑"
            rotation >= 67.5f -> "↑"
            rotation >= 22.5f -> "↗"
            rate < -2f -> "↓↓"
            rotation <= -67.5f -> "↓"
            rotation <= -22.5f -> "↘"
            else -> "→"
        }
        value.text + arrow
    }

abstract class MultiSensorComplicationBase : SuspendingComplicationDataSourceService() {
    protected abstract val showArrows: Boolean

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, preview = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, preview = false)

    private fun build(type: ComplicationType, preview: Boolean): ComplicationData? {
        val now = System.currentTimeMillis()
        val selected = WearSensorSelection.selected()
        val live = multiSensorValues(selected, WearSensorSelection.colors(), now, Notify.glucosetimeout) {
            GlucoseComplicationData.currentReading(it)
        }
        // A useful two-sensor picker example even before selection/history arrives.
        val values = if (preview && live.size < 2) previewValues(now) else live
        val text = multiSensorText(values, showArrows).ifBlank { Applic.app.getString(R.string.novalue) }
        val description = PlainComplicationText.Builder(
            values.joinToString("; ") { "${it.sensorId}: ${it.text}" }.ifBlank { text },
        ).build()
        val tap = GlucoseComplicationData.tapAction()
        val expires = values.mapNotNull { it.reading?.timeMillis }.minOrNull()?.plus(Notify.glucosetimeout)
        val range = if (preview || expires == null) TimeRange.ALWAYS else TimeRange.before(Instant.ofEpochMilli(expires))
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(text).build(), description,
            ).setTapAction(tap).setValidTimeRange(range).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithBitmap(multiSensorBitmap(values, showArrows)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tap).setValidTimeRange(range).build()
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                Icon.createWithBitmap(multiSensorBitmap(values, showArrows)), description,
            ).setTapAction(tap).setValidTimeRange(range).build()
            else -> null
        }
    }

    private fun previewValues(now: Long): List<MultiSensorValue> {
        val mmol = ComplicationRenderer.isMmol()
        return listOf(101f to 0.4f, 126f to -1.2f).mapIndexed { index, (mgdl, rate) ->
            val value = if (mmol) mgdl / 18.0182f else mgdl
            MultiSensorValue(
                "${index + 1}",
                GlucoseComplicationData.Reading(value, tk.glucodata.ui.util.GlucoseFormatter.format(value, mmol), mmol, now, rate, 0),
                if (index == 0) 0xFFE3E2DE.toInt() else 0xFF4285F4.toInt(),
            )
        }
    }
}

/** All content fits inside a circle's inscribed square, including the first/last sensor. */
internal fun multiSensorBitmap(values: List<MultiSensorValue>, arrows: Boolean): Bitmap {
    val size = 320
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    val inset = size * 0.16f
    val width = size - 2 * inset
    val count = values.size.coerceAtLeast(1)
    val rowHeight = minOf(width / count, size * 0.25f)
    val top = (size - rowHeight * count) / 2f
    val rows = values.ifEmpty { listOf(MultiSensorValue("", null, 0xFFE3E2DE.toInt())) }
    rows.forEachIndexed { index, value ->
        val centerY = top + (index + 0.5f) * rowHeight
        paint.color = value.colorArgb
        canvas.drawCircle(inset + rowHeight * 0.08f, centerY, rowHeight * 0.05f, paint)
        paint.color = value.reading?.let { ComplicationRenderer.valueColor(it.value, it.isMmol) } ?: 0xFFE3E2DE.toInt()
        paint.textSize = rowHeight * 0.72f
        val textLeft = inset + rowHeight * 0.2f
        val arrowWidth = if (arrows) rowHeight * 0.5f else 0f
        val available = width - rowHeight * 0.2f - arrowWidth
        val measured = paint.measureText(value.text)
        if (measured > available) paint.textSize *= available / measured
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(value.text, textLeft, baseline, paint)
        value.reading?.rate?.takeIf { arrows && it.isFinite() }?.let { rate ->
            ComplicationRenderer.drawArrow(canvas, arrowWidth, arrowWidth, rate, paint.color,
                size - inset - arrowWidth, centerY - arrowWidth / 2f)
        }
    }
    return bitmap
}

class MultiSensorDataSourceService : MultiSensorComplicationBase() {
    override val showArrows = false

    companion object {
        @JvmStatic
        fun update() {
            listOf(MultiSensorDataSourceService::class.java, MultiSensorArrowDataSourceService::class.java).forEach {
                ComplicationDataSourceUpdateRequester.create(Applic.app, ComponentName(Applic.app, it)).requestUpdateAll()
            }
        }
    }
}

class MultiSensorArrowDataSourceService : MultiSensorComplicationBase() {
    override val showArrows = true
}
