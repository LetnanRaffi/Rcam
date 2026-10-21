package com.hinnka.mycamera.timestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hinnka.mycamera.gallery.MediaMetadata
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class TimestampPlacement {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

data class TimestampSettings(
    val showDate: Boolean = true,
    val showTime: Boolean = true,
    val showLocation: Boolean = false,
    val showCoordinates: Boolean = false,
    val customText: String = "",
    val placement: TimestampPlacement = TimestampPlacement.BOTTOM_RIGHT,
    val showBackgroundPlate: Boolean = true,
)

data class TimestampGridPreview(
    val collage: Bitmap,
    val thumbnails: List<Bitmap>,
)

private const val PREFIX = "rcam.timestamp."
private const val ENABLED_KEY = "${PREFIX}enabled"
private const val DATE_KEY = "${PREFIX}date"
private const val TIME_KEY = "${PREFIX}time"
private const val LOCATION_KEY = "${PREFIX}location"
private const val COORDINATES_KEY = "${PREFIX}coordinates"
private const val CUSTOM_TEXT_KEY = "${PREFIX}customText"
private const val PLACEMENT_KEY = "${PREFIX}placement"
private const val PLATE_KEY = "${PREFIX}plate"

fun TimestampSettings.toMetadataProperties(): Map<String, String> = mapOf(
    ENABLED_KEY to "true",
    DATE_KEY to showDate.toString(),
    TIME_KEY to showTime.toString(),
    LOCATION_KEY to showLocation.toString(),
    COORDINATES_KEY to showCoordinates.toString(),
    CUSTOM_TEXT_KEY to customText.trim(),
    PLACEMENT_KEY to placement.name,
    PLATE_KEY to showBackgroundPlate.toString(),
)

fun MediaMetadata.timestampSettings(): TimestampSettings? {
    if (customProperties[ENABLED_KEY] != "true") return null
    return TimestampSettings(
        showDate = customProperties[DATE_KEY]?.toBooleanStrictOrNull() ?: true,
        showTime = customProperties[TIME_KEY]?.toBooleanStrictOrNull() ?: true,
        showLocation = customProperties[LOCATION_KEY]?.toBooleanStrictOrNull() ?: false,
        showCoordinates = customProperties[COORDINATES_KEY]?.toBooleanStrictOrNull() ?: false,
        customText = customProperties[CUSTOM_TEXT_KEY].orEmpty(),
        placement = runCatching {
            TimestampPlacement.valueOf(customProperties[PLACEMENT_KEY].orEmpty())
        }.getOrDefault(TimestampPlacement.BOTTOM_RIGHT),
        showBackgroundPlate = customProperties[PLATE_KEY]?.toBooleanStrictOrNull() ?: true,
    )
}

fun timestampLines(
    settings: TimestampSettings,
    captureTimeMillis: Long,
    locationName: String?,
    latitude: Double?,
    longitude: Double?,
    locale: Locale = Locale.getDefault(),
): List<String> = buildList {
    settings.customText.trim().takeIf(String::isNotEmpty)?.let(::add)
    val date = Date(captureTimeMillis)
    val dateTime = buildList {
        if (settings.showDate) {
            add(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date))
        }
        if (settings.showTime) {
            add(DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(date))
        }
    }.joinToString("  ")
    dateTime.takeIf(String::isNotEmpty)?.let(::add)
    if (settings.showLocation) {
        locationName?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    }
    if (settings.showCoordinates && latitude != null && longitude != null) {
        add(String.format(Locale.US, "%.5f, %.5f", latitude, longitude))
    }
}

object TimestampBurnIn {
    fun apply(bitmap: Bitmap, metadata: MediaMetadata): Bitmap {
        val settings = metadata.timestampSettings() ?: return bitmap
        val lines = timestampLines(
            settings = settings,
            captureTimeMillis = metadata.dateTaken ?: System.currentTimeMillis(),
            locationName = metadata.location,
            latitude = metadata.latitude,
            longitude = metadata.longitude,
        )
        if (lines.isEmpty()) return bitmap

        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
            }
        val canvas = Canvas(output)
        val shortestEdge = min(output.width, output.height).toFloat()
        val textSize = (shortestEdge * 0.027f).coerceIn(22f, 58f)
        val padding = textSize * 0.58f
        val margin = max(18f, shortestEdge * 0.025f)
        val lineGap = textSize * 0.28f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
            setShadowLayer(max(2f, textSize * 0.09f), 0f, textSize * 0.05f, Color.BLACK)
        }
        val maxTextWidth = lines.maxOf(textPaint::measureText)
        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val contentHeight = lineHeight * lines.size + lineGap * (lines.size - 1).coerceAtLeast(0)
        val plateWidth = maxTextWidth + padding * 2f
        val plateHeight = contentHeight + padding * 1.7f
        val left = when (settings.placement) {
            TimestampPlacement.TOP_LEFT, TimestampPlacement.BOTTOM_LEFT -> margin
            TimestampPlacement.TOP_RIGHT, TimestampPlacement.BOTTOM_RIGHT ->
                output.width - margin - plateWidth
        }.coerceIn(margin, max(margin, output.width - margin - plateWidth))
        val top = when (settings.placement) {
            TimestampPlacement.TOP_LEFT, TimestampPlacement.TOP_RIGHT -> margin
            TimestampPlacement.BOTTOM_LEFT, TimestampPlacement.BOTTOM_RIGHT ->
                output.height - margin - plateHeight
        }.coerceIn(margin, max(margin, output.height - margin - plateHeight))

        if (settings.showBackgroundPlate) {
            val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(142, 8, 10, 12) }
            val radius = textSize * 0.42f
            canvas.drawRoundRect(
                RectF(left, top, left + plateWidth, top + plateHeight),
                radius,
                radius,
                platePaint,
            )
        }
        var baseline = top + padding - metrics.ascent
        lines.forEach { line ->
            val x = if (
                settings.placement == TimestampPlacement.TOP_RIGHT ||
                settings.placement == TimestampPlacement.BOTTOM_RIGHT
            ) {
                left + plateWidth - padding - textPaint.measureText(line)
            } else {
                left + padding
            }
            canvas.drawText(line, x, baseline, textPaint)
            baseline += lineHeight + lineGap
        }
        return output
    }
}

object TimestampGridComposer {
    fun compose(images: List<Bitmap>, maxEdge: Int = 2400): Bitmap? {
        if (images.size != 4 || images.any { it.isRecycled || it.width <= 0 || it.height <= 0 }) {
            return null
        }
        val outputSize = maxEdge.coerceAtLeast(800)
        val gap = (outputSize * 0.0125f).roundToInt().coerceAtLeast(8)
        val cellSize = (outputSize - gap * 3) / 2
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(10, 11, 13))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        images.forEachIndexed { index, image ->
            val column = index % 2
            val row = index / 2
            val left = gap + column * (cellSize + gap)
            val top = gap + row * (cellSize + gap)
            val sourceSide = min(image.width, image.height)
            val sourceLeft = (image.width - sourceSide) / 2
            val sourceTop = (image.height - sourceSide) / 2
            canvas.drawBitmap(
                image,
                android.graphics.Rect(sourceLeft, sourceTop, sourceLeft + sourceSide, sourceTop + sourceSide),
                android.graphics.Rect(left, top, left + cellSize, top + cellSize),
                paint,
            )
        }
        return output
    }
}
