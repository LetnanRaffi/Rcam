package com.hinnka.mycamera.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.timestamp.TimestampBurnIn
import com.hinnka.mycamera.timestamp.TimestampGridComposer
import com.hinnka.mycamera.timestamp.TimestampGridPreview
import com.hinnka.mycamera.timestamp.TimestampLocationPhase
import com.hinnka.mycamera.timestamp.TimestampLocationState
import com.hinnka.mycamera.timestamp.TimestampPlacement
import com.hinnka.mycamera.timestamp.TimestampSettings
import com.hinnka.mycamera.timestamp.TimestampTextSize
import com.hinnka.mycamera.timestamp.timestampLines
import com.hinnka.mycamera.ui.components.CameraGlassStyle
import com.hinnka.mycamera.ui.components.GlassCapsule
import com.hinnka.mycamera.ui.components.GlassToggle
import com.hinnka.mycamera.ui.components.LiquidGlassBackdrop
import com.hinnka.mycamera.ui.components.LiquidGlassSurface
import com.hinnka.mycamera.ui.components.StrongGlassStyle
import com.hinnka.mycamera.ui.icons.AppIcons
import com.hinnka.mycamera.ui.theme.AccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun TimestampPreviewOverlay(
    settings: TimestampSettings,
    latitude: Double?,
    longitude: Double?,
    locationName: String? = null,
    headingDegrees: Float? = null,
    accuracyMeters: Float? = null,
    modifier: Modifier = Modifier,
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val lines = timestampLines(
        settings, currentTime, locationName, latitude, longitude, headingDegrees, accuracyMeters,
    )
    if (lines.isEmpty()) return
    val horizontalAlignment = when (settings.placement) {
        TimestampPlacement.TOP_LEFT, TimestampPlacement.BOTTOM_LEFT -> Alignment.Start
        TimestampPlacement.TOP_RIGHT, TimestampPlacement.BOTTOM_RIGHT -> Alignment.End
    }
    Column(
        modifier = modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (settings.showBackgroundPlate) {
                    Modifier.background(Color.Black.copy(alpha = 0.44f))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White,
                fontSize = when (settings.textSize) {
                    TimestampTextSize.COMPACT -> 10.sp
                    TimestampTextSize.STANDARD -> 12.sp
                    TimestampTextSize.LARGE -> 14.sp
                },
                lineHeight = when (settings.textSize) {
                    TimestampTextSize.COMPACT -> 13.sp
                    TimestampTextSize.STANDARD -> 16.sp
                    TimestampTextSize.LARGE -> 18.sp
                },
                fontWeight = FontWeight.Medium,
                textAlign = if (horizontalAlignment == Alignment.End) TextAlign.End else TextAlign.Start,
                style = androidx.compose.ui.text.TextStyle(shadow = ViewfinderTextShadow),
            )
        }
    }
}

@Composable
internal fun TimestampCompassOverlay(
    headingDegrees: Float?,
    modifier: Modifier = Modifier,
) {
    val heading = headingDegrees ?: return
    Canvas(modifier.size(82.dp)) {
        val stroke = size.minDimension * 0.13f
        drawCircle(
            color = Color(0xA62C2D2F),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(stroke),
        )
        val compassCenter = center
        val needle = Path().apply {
            moveTo(compassCenter.x, compassCenter.y - size.minDimension * 0.31f)
            lineTo(compassCenter.x - size.minDimension * 0.065f, compassCenter.y + size.minDimension * 0.23f)
            lineTo(compassCenter.x + size.minDimension * 0.065f, compassCenter.y + size.minDimension * 0.23f)
            close()
        }
        rotate(heading) { drawPath(needle, Color(0xFF00BEDC)) }
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = size.minDimension * 0.13f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawText("N", compassCenter.x, stroke * 1.15f, paint)
            drawText("S", compassCenter.x, size.height - stroke * 0.35f, paint)
            drawText("W", stroke * 0.65f, compassCenter.y - (paint.ascent() + paint.descent()) / 2f, paint)
            drawText("E", size.width - stroke * 0.65f, compassCenter.y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }
}

@Composable
internal fun TimestampLocationStatusPill(
    state: TimestampLocationState,
    backdrop: LiquidGlassBackdrop,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (state.phase) {
        TimestampLocationPhase.IDLE -> return
        TimestampLocationPhase.PERMISSION_REQUIRED -> stringResource(R.string.timestamp_location_permission)
        TimestampLocationPhase.LOCATING -> stringResource(R.string.timestamp_location_loading)
        TimestampLocationPhase.READY -> state.fix?.accuracyMeters?.takeIf(Float::isFinite)?.let {
            stringResource(R.string.timestamp_location_ready_accuracy, it.roundToInt())
        } ?: stringResource(R.string.timestamp_location_ready)
        TimestampLocationPhase.UNAVAILABLE -> stringResource(R.string.timestamp_location_unavailable)
    }
    GlassCapsule(
        backdrop = backdrop,
        modifier = modifier.height(34.dp).clickable(
            enabled = state.phase != TimestampLocationPhase.LOCATING,
            onClick = onRetry,
        ),
        cornerRadius = 17.dp,
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
internal fun TimestampQuickControls(
    backdrop: LiquidGlassBackdrop,
    fourGridEnabled: Boolean,
    onFourGridChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCapsule(
            backdrop = backdrop,
            modifier = Modifier
                .height(44.dp)
                .clickable(onClick = onSettingsClick),
            cornerRadius = 20.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(AppIcons.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                Text(
                    stringResource(R.string.timestamp_settings),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        GlassCapsule(
            backdrop = backdrop,
            modifier = Modifier.height(44.dp),
            cornerRadius = 20.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 13.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.timestamp_four_grid),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                GlassToggle(checked = fourGridEnabled, onCheckedChange = onFourGridChanged)
            }
        }
    }
}

@Composable
internal fun TimestampGridProgress(
    photoIds: List<String?>,
    activeIndex: Int,
    isCapturing: Boolean,
    onSlotClick: (Int) -> Unit,
    onCancel: () -> Unit,
    backdrop: LiquidGlassBackdrop,
    modifier: Modifier = Modifier,
) {
    GlassCapsule(
        backdrop = backdrop,
        modifier = modifier.widthIn(max = 184.dp),
        cornerRadius = 22.dp,
        style = CameraGlassStyle.copy(tint = Color.Black.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.timestamp_grid_progress,
                        photoIds.count { it != null },
                        photoIds.size,
                    ),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel), tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { column ->
                            val index = row * 2 + column
                            TimestampGridSlot(
                                photoId = photoIds[index],
                                number = index + 1,
                                active = index == activeIndex,
                                enabled = !isCapturing,
                                onClick = { onSlotClick(index) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimestampGridSlot(
    photoId: String?,
    number: Int,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, photoId) {
        value = photoId?.let { id ->
            withContext(Dispatchers.IO) {
                val source = GalleryManager.loadBitmap(context, id, maxEdge = 320) ?: return@withContext null
                val metadata = GalleryManager.loadMetadata(context, id)
                val square = TimestampGridComposer.cropToSquare(source)
                if (square !== source) source.recycle()
                if (metadata == null) {
                    square
                } else {
                    TimestampBurnIn.apply(square, metadata).also {
                        if (it !== square) square.recycle()
                    }
                }
            }
        }
    }
    DisposableEffect(bitmap) {
        val ownedBitmap = bitmap
        onDispose { ownedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (active) 0.2f else 0.08f))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) AccentColor else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = number.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun TimestampConfigurationSheet(
    visible: Boolean,
    settings: TimestampSettings,
    hasLocation: Boolean,
    fourGridEnabled: Boolean,
    backdrop: LiquidGlassBackdrop,
    onFourGridChanged: (Boolean) -> Unit,
    onSettingsChanged: (TimestampSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(settings, visible) { mutableStateOf(settings) }
    val openingLabel = stringResource(R.string.timestamp_label_opening)
    val closingLabel = stringResource(R.string.timestamp_label_closing)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
            LiquidGlassSurface(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clickable(enabled = false) {},
                cornerRadius = 30.dp,
                openBottomEdge = true,
                style = StrongGlassStyle,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 42.dp, height = 4.dp)
                            .background(Color.White.copy(alpha = 0.34f), RoundedCornerShape(2.dp))
                            .align(Alignment.CenterHorizontally),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.timestamp_settings),
                            modifier = Modifier.weight(1f),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                        }
                    }
                    TimestampPreviewOverlay(
                        settings = draft,
                        latitude = null,
                        longitude = null,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    TimestampOptionRow(stringResource(R.string.timestamp_show_date), draft.showDate) {
                        draft = draft.copy(showDate = it)
                    }
                    TimestampOptionRow(stringResource(R.string.timestamp_show_time), draft.showTime) {
                        draft = draft.copy(showTime = it)
                    }
                    TimestampOptionRow(
                        stringResource(R.string.timestamp_show_location),
                        draft.showLocation,
                        enabled = hasLocation,
                    ) { draft = draft.copy(showLocation = it) }
                    TimestampOptionRow(
                        stringResource(R.string.timestamp_show_coordinates),
                        draft.showCoordinates,
                        enabled = hasLocation,
                    ) { draft = draft.copy(showCoordinates = it) }
                    TimestampOptionRow(stringResource(R.string.timestamp_show_heading), draft.showHeading) {
                        draft = draft.copy(showHeading = it)
                    }
                    TimestampOptionRow(stringResource(R.string.timestamp_show_accuracy), draft.showAccuracy) {
                        draft = draft.copy(showAccuracy = it)
                    }
                    TimestampOptionRow(
                        stringResource(R.string.timestamp_four_grid),
                        fourGridEnabled,
                        onCheckedChange = onFourGridChanged,
                    )
                    TimestampOptionRow(stringResource(R.string.timestamp_background_plate), draft.showBackgroundPlate) {
                        draft = draft.copy(showBackgroundPlate = it)
                    }
                    OutlinedTextField(
                        value = draft.customText,
                        onValueChange = { draft = draft.copy(customText = it.take(80)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.timestamp_custom_text)) },
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.timestamp_size),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimestampTextSize.entries.forEach { textSize ->
                            TextButton(
                                onClick = { draft = draft.copy(textSize = textSize) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (draft.textSize == textSize) Color.White.copy(alpha = 0.2f)
                                    else Color.Transparent,
                                ),
                            ) {
                                val label = when (textSize) {
                                    TimestampTextSize.COMPACT -> R.string.timestamp_size_compact
                                    TimestampTextSize.STANDARD -> R.string.timestamp_size_standard
                                    TimestampTextSize.LARGE -> R.string.timestamp_size_large
                                }
                                Text(stringResource(label), color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { draft = draft.copy(customText = openingLabel) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.timestamp_preset_opening), color = Color.White) }
                        TextButton(
                            onClick = { draft = draft.copy(customText = closingLabel) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.timestamp_preset_closing), color = Color.White) }
                    }
                    Text(
                        stringResource(R.string.timestamp_placement),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimestampPlacement.entries.forEach { placement ->
                            val label = when (placement) {
                                TimestampPlacement.TOP_LEFT -> R.string.timestamp_position_top_left
                                TimestampPlacement.TOP_RIGHT -> R.string.timestamp_position_top_right
                                TimestampPlacement.BOTTOM_LEFT -> R.string.timestamp_position_bottom_left
                                TimestampPlacement.BOTTOM_RIGHT -> R.string.timestamp_position_bottom_right
                            }
                            TextButton(
                                onClick = { draft = draft.copy(placement = placement) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (draft.placement == placement) {
                                        Color.White.copy(alpha = 0.2f)
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                            ) {
                                Text(stringResource(label), color = Color.White, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onSettingsChanged(draft)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimestampOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Color.White.copy(alpha = if (enabled) 0.92f else 0.38f),
            fontSize = 13.sp,
        )
        GlassToggle(checked, onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun TimestampGridResultSheet(
    preview: TimestampGridPreview?,
    isSaving: Boolean,
    backdrop: LiquidGlassBackdrop,
    onSave: () -> Unit,
    onRetakeSlot: (Int) -> Unit,
    onCancel: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = preview != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        val result = preview
        if (result == null) return@AnimatedVisibility
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            LiquidGlassSurface(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                cornerRadius = 30.dp,
                openBottomEdge = true,
                style = StrongGlassStyle,
            ) {
                Column(
                    Modifier.padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.timestamp_grid_complete),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Image(
                        bitmap = result.collage.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        stringResource(R.string.timestamp_grid_retake_hint),
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        result.thumbnails.forEachIndexed { index, bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.timestamp_retake_slot, index + 1),
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !isSaving) { onRetakeSlot(index) },
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCancel, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cancel), color = Color.White)
                        }
                        TextButton(onClick = onGallery, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.gallery), color = Color.White)
                        }
                        Button(
                            onClick = onSave,
                            enabled = !isSaving,
                            modifier = Modifier.weight(1.35f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) {
                            Text(if (isSaving) stringResource(R.string.saving) else stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

internal fun TimestampGridPreview.recycle() {
    if (!collage.isRecycled) collage.recycle()
    thumbnails.forEach { if (!it.isRecycled) it.recycle() }
}
