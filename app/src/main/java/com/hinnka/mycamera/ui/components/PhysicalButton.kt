package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

object PhysicalButtonDefaults {
    val BackgroundColor = Color(0xB51A1D22)
}

/**
 * 普通半透明黑色按钮表面。
 */
@Composable
fun PhysicalButton(
    modifier: Modifier = Modifier.size(48.dp),
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    backgroundColor: Color = PhysicalButtonDefaults.BackgroundColor,
    contentAlignment: Alignment = Alignment.Center,
    contentShape: Shape = shape,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(if (pressed) .94f else 1f, label = "buttonPress")
    val haptic = LocalHapticFeedback.current
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .scale(buttonScale)
            .alpha(if (enabled) 1f else .38f)
            .clip(shape)
            .background(backgroundColor)
            .border(.5.dp, Color.White.copy(alpha = .16f), shape)
            .then(clickModifier),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(contentShape),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}
