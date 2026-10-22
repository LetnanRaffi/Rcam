package com.hinnka.mycamera.timestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.content.Context
import com.hinnka.mycamera.gallery.MediaMetadata
import java.text.SimpleDateFormat
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

enum class TimestampTextSize { COMPACT, STANDARD, LARGE }

data class TimestampSettings(
    val showDate: Boolean = true,
    val showTime: Boolean = true,
    val showLocation: Boolean = true,
    val showCoordinates: Boolean = false,
    val showHeading: Boolean = true,
    val showAccuracy: Boolean = false,
    val customText: String = "",
    val placement: TimestampPlacement = TimestampPlacement.BOTTOM_RIGHT,
    val showBackgroundPlate: Boolean = false,
    val textSize: TimestampTextSize = TimestampTextSize.LARGE,
)

data class TimestampLocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
    val provider: String,
    val fixTimeMillis: Long,
    val addressLines: List<String>,
) {
    val displayAddress: String get() = addressLines.filter(String::isNotBlank).joinToString("\n")

    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - fixTimeMillis in 0L..FRESH_LOCATION_MAX_AGE_MILLIS
}

enum class TimestampLocationPhase { IDLE, PERMISSION_REQUIRED, LOCATING, READY, UNAVAILABLE }

data class TimestampLocationState(
    val phase: TimestampLocationPhase = TimestampLocationPhase.IDLE,
    val fix: TimestampLocationFix? = null,
)

data class TimestampCaptureContext(
    val location: TimestampLocationFix?,
    val headingDegrees: Float?,
)

data class TimestampGridPreview(
    val collage: Bitmap,
    val thumbnails: List<Bitmap>,
)

object TimestampSettingsStore {
    private const val FILE_NAME = "rcam_timestamp"

    fun load(context: Context): TimestampSettings {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val defaults = TimestampSettings()
        return TimestampSettings(
            showDate = prefs.getBoolean("showDate", defaults.showDate),
            showTime = prefs.getBoolean("showTime", defaults.showTime),
            showLocation = prefs.getBoolean("showLocation", defaults.showLocation),
            showCoordinates = prefs.getBoolean("showCoordinates", defaults.showCoordinates),
            showHeading = prefs.getBoolean("showHeading", defaults.showHeading),
            showAccuracy = prefs.getBoolean("showAccuracy", defaults.showAccuracy),
            customText = prefs.getString("customText", defaults.customText).orEmpty(),
            placement = runCatching {
                TimestampPlacement.valueOf(prefs.getString("placement", defaults.placement.name).orEmpty())
            }.getOrDefault(defaults.placement),
            showBackgroundPlate = prefs.getBoolean("showBackgroundPlate", defaults.showBackgroundPlate),
            textSize = runCatching {
                TimestampTextSize.valueOf(prefs.getString("textSize", defaults.textSize.name).orEmpty())
            }.getOrDefault(defaults.textSize),
        )
    }

    fun save(context: Context, settings: TimestampSettings) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("showDate", settings.showDate)
            .putBoolean("showTime", settings.showTime)
            .putBoolean("showLocation", settings.showLocation)
            .putBoolean("showCoordinates", settings.showCoordinates)
            .putBoolean("showHeading", settings.showHeading)
            .putBoolean("showAccuracy", settings.showAccuracy)
            .putString("customText", settings.customText)
            .putString("placement", settings.placement.name)
            .putBoolean("showBackgroundPlate", settings.showBackgroundPlate)
            .putString("textSize", settings.textSize.name)
            .apply()
    }
}

private const val PREFIX = "rcam.timestamp."
private const val ENABLED_KEY = "${PREFIX}enabled"
private const val DATE_KEY = "${PREFIX}date"
private const val TIME_KEY = "${PREFIX}time"
private const val LOCATION_KEY = "${PREFIX}location"
private const val COORDINATES_KEY = "${PREFIX}coordinates"
private const val HEADING_VISIBLE_KEY = "${PREFIX}headingVisible"
private const val HEADING_DEGREES_KEY = "${PREFIX}headingDegrees"
private const val ACCURACY_KEY = "${PREFIX}accuracyMeters"
private const val ACCURACY_VISIBLE_KEY = "${PREFIX}accuracyVisible"
private const val PROVIDER_KEY = "${PREFIX}provider"
private const val FIX_TIME_KEY = "${PREFIX}fixTime"
private const val CUSTOM_TEXT_KEY = "${PREFIX}customText"
private const val PLACEMENT_KEY = "${PREFIX}placement"
private const val PLATE_KEY = "${PREFIX}plate"
private const val TEXT_SIZE_KEY = "${PREFIX}textSize"
private const val FRESH_LOCATION_MAX_AGE_MILLIS = 2 * 60 * 1000L

fun TimestampSettings.toMetadataProperties(): Map<String, String> = mapOf(
    ENABLED_KEY to "true",
    DATE_KEY to showDate.toString(),
    TIME_KEY to showTime.toString(),
    LOCATION_KEY to showLocation.toString(),
    COORDINATES_KEY to showCoordinates.toString(),
    HEADING_VISIBLE_KEY to showHeading.toString(),
    ACCURACY_VISIBLE_KEY to showAccuracy.toString(),
    CUSTOM_TEXT_KEY to customText.trim(),
    PLACEMENT_KEY to placement.name,
    PLATE_KEY to showBackgroundPlate.toString(),
    TEXT_SIZE_KEY to textSize.name,
)

fun TimestampCaptureContext?.toMetadataProperties(): Map<String, String> = buildMap {
    this@toMetadataProperties?.headingDegrees?.let { put(HEADING_DEGREES_KEY, it.toString()) }
    this@toMetadataProperties?.location?.let { fix ->
        put(ACCURACY_KEY, fix.accuracyMeters.toString())
        put(PROVIDER_KEY, fix.provider)
        put(FIX_TIME_KEY, fix.fixTimeMillis.toString())
    }
}

fun MediaMetadata.timestampSettings(): TimestampSettings? {
    if (customProperties[ENABLED_KEY] != "true") return null
    return TimestampSettings(
        showDate = customProperties[DATE_KEY]?.toBooleanStrictOrNull() ?: true,
        showTime = customProperties[TIME_KEY]?.toBooleanStrictOrNull() ?: true,
        showLocation = customProperties[LOCATION_KEY]?.toBooleanStrictOrNull() ?: true,
        showCoordinates = customProperties[COORDINATES_KEY]?.toBooleanStrictOrNull() ?: false,
        showHeading = customProperties[HEADING_VISIBLE_KEY]?.toBooleanStrictOrNull() ?: true,
        showAccuracy = customProperties[ACCURACY_VISIBLE_KEY]?.toBooleanStrictOrNull() ?: false,
        customText = customProperties[CUSTOM_TEXT_KEY].orEmpty(),
        placement = runCatching {
            TimestampPlacement.valueOf(customProperties[PLACEMENT_KEY].orEmpty())
        }.getOrDefault(TimestampPlacement.BOTTOM_RIGHT),
        showBackgroundPlate = customProperties[PLATE_KEY]?.toBooleanStrictOrNull() ?: false,
        textSize = runCatching {
            TimestampTextSize.valueOf(customProperties[TEXT_SIZE_KEY].orEmpty())
        }.getOrDefault(TimestampTextSize.LARGE),
    )
}

fun MediaMetadata.timestampHeadingDegrees(): Float? =
    customProperties[HEADING_DEGREES_KEY]?.toFloatOrNull()?.let(::normalizeHeading)

fun MediaMetadata.timestampAccuracyMeters(): Float? =
    customProperties[ACCURACY_KEY]?.toFloatOrNull()?.takeIf(Float::isFinite)

fun timestampLines(
    settings: TimestampSettings,
    captureTimeMillis: Long,
    locationName: String?,
    latitude: Double?,
    longitude: Double?,
    headingDegrees: Float? = null,
    accuracyMeters: Float? = null,
    locale: Locale = Locale.getDefault(),
): List<String> = buildList {
    settings.customText.trim().takeIf(String::isNotEmpty)?.let(::add)
    val date = Date(captureTimeMillis)
    val dateTime = buildList {
        val pattern = when {
            settings.showDate && settings.showTime -> "dd MMM yyyy HH.mm.ss"
            settings.showDate -> "dd MMM yyyy"
            else -> "HH.mm.ss"
        }
        if (settings.showDate || settings.showTime) add(SimpleDateFormat(pattern, locale).format(date))
    }.joinToString(" ")
    dateTime.takeIf(String::isNotEmpty)?.let(::add)
    if (settings.showHeading && headingDegrees != null) {
        add("${normalizeHeading(headingDegrees).roundToInt()}° ${headingCardinal(headingDegrees)}")
    }
    if (settings.showLocation) {
        locationName?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.forEach(::add)
    }
    if (settings.showCoordinates && latitude != null && longitude != null) {
        add(String.format(Locale.US, "%.5f, %.5f", latitude, longitude))
    }
    if (settings.showAccuracy && accuracyMeters != null && accuracyMeters.isFinite()) {
        add("±${accuracyMeters.roundToInt()} m")
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
            headingDegrees = metadata.timestampHeadingDegrees(),
            accuracyMeters = metadata.timestampAccuracyMeters(),
        )
        if (lines.isEmpty()) return bitmap

        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
            }
        val canvas = Canvas(output)
        val shortestEdge = min(output.width, output.height).toFloat()
        val sizeFactor = when (settings.textSize) {
            TimestampTextSize.COMPACT -> 0.022f
            TimestampTextSize.STANDARD -> 0.028f
            TimestampTextSize.LARGE -> 0.034f
        }
        val textSize = (shortestEdge * sizeFactor).coerceIn(22f, 132f)
        val padding = textSize * 0.58f
        val margin = max(18f, shortestEdge * 0.025f)
        val lineGap = textSize * 0.28f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.NORMAL,
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
        if (settings.showHeading && metadata.timestampHeadingDegrees() != null) {
            drawCompass(
                canvas = canvas,
                headingDegrees = metadata.timestampHeadingDegrees()!!,
                size = shortestEdge * 0.18f,
                margin = margin,
            )
        }
        return output
    }

    private fun drawCompass(canvas: Canvas, headingDegrees: Float, size: Float, margin: Float) {
        val radius = size / 2f
        val cx = margin + radius
        val cy = margin + radius
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 44, 45, 47)
            style = Paint.Style.STROKE
            strokeWidth = size * 0.14f
        }
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.13f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        val labelRadius = radius * 0.76f
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, bearing) ->
            val radians = Math.toRadians((bearing - headingDegrees - 90f).toDouble())
            val x = cx + kotlin.math.cos(radians).toFloat() * labelRadius
            val y = cy + kotlin.math.sin(radians).toFloat() * labelRadius - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(label, x, y, labelPaint)
        }
        val needle = Path().apply {
            moveTo(cx, cy - radius * 0.66f)
            lineTo(cx - radius * 0.12f, cy + radius * 0.47f)
            lineTo(cx + radius * 0.12f, cy + radius * 0.47f)
            close()
        }
        canvas.drawPath(needle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 190, 220) })
    }
}

fun normalizeHeading(value: Float): Float = ((value % 360f) + 360f) % 360f

fun headingCardinal(value: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[((normalizeHeading(value) + 22.5f) / 45f).toInt() % points.size]
}

object TimestampGridComposer {
    fun cropToSquare(image: Bitmap): Bitmap {
        val side = min(image.width, image.height)
        if (side == image.width && side == image.height) return image
        val left = (image.width - side) / 2
        val top = (image.height - side) / 2
        return Bitmap.createBitmap(image, left, top, side, side)
    }

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
