package com.hinnka.mycamera.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.ui.theme.AccentColor

val CameraGlassStyle = LiquidGlassStyle(
    blurRadius = 12.dp,
    refractionHeight = 7.dp,
    refractionAmount = 5.dp,
    saturation = 1.2f,
    tint = Color.Black.copy(alpha = 0.24f),
    highlightAlpha = 0.42f,
    fallbackColor = Color(0xCC171A1F),
)

val StrongGlassStyle = CameraGlassStyle.copy(
    blurRadius = 18.dp,
    tint = Color.Black.copy(alpha = 0.38f),
    fallbackColor = Color(0xE6191C21),
)

@Composable
fun GlassCapsule(
    backdrop: LiquidGlassBackdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    style: LiquidGlassStyle = CameraGlassStyle,
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        cornerRadius = cornerRadius,
        style = style,
        content = content,
    )
}

@Composable
fun GlassIconButton(
    backdrop: LiquidGlassBackdrop,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    GlassCapsule(
        backdrop = backdrop,
        modifier = modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        cornerRadius = 24.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(21.dp).align(Alignment.Center),
        )
    }
}

@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val activeColor = AccentColor
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 520f),
        label = "glassToggleOffset",
    )
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 30.dp)
            .clickable(enabled = enabled, indication = null, interactionSource = remember {
                MutableInteractionSource()
            }) { onCheckedChange(!checked) },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = if (checked) activeColor.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.18f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 3.dp)
                .size(24.dp),
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawCircle(if (checked) Color.White else Color(0xFFE1E3E7))
                drawCircle(
                    Color.White.copy(alpha = 0.75f),
                    radius = size.minDimension * 0.43f,
                    center = center.copy(y = center.y - size.height * 0.05f),
                )
            }
        }
    }
}

@Composable
fun GlassSegmentedRow(
    backdrop: LiquidGlassBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    GlassCapsule(backdrop = backdrop, modifier = modifier, cornerRadius = 22.dp) {
        Row(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier.width(220.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = AccentColor,
            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
        ),
    )
}
