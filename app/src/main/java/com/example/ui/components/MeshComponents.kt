package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MeshEmerald
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val elevatedColor = MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = accentColor.copy(alpha = 0.08f), spotColor = accentColor.copy(alpha = 0.12f))
            .animateContentSize(),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    accentColor.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            surfaceColor.copy(alpha = 0.96f),
                            elevatedColor.copy(alpha = 0.98f)
                        )
                    )
                )
                .padding(16.dp)
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(260)) + slideInVertically(
                    initialOffsetY = { it / 18 },
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                )
            ) {
                Column(content = content)
            }
        }
    }
}

@Composable
fun PulseStatusDot(
    active: Boolean,
    activeColor: Color = MeshEmerald,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 10.dp
) {
    val scale = if (active) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val animatedScale by transition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.28f,
            animationSpec = infiniteRepeatable(
                animation = tween(1250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
        animatedScale
    } else 1f
    val color = if (active) activeColor else inactiveColor.copy(alpha = 0.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size * 2f)
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(size * scale * 1.4f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f))
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun MeshPillBadge(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .padding(bottom = 7.dp)
                    .size(width = 34.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                        )
                    )
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun QrCodePreview(
    content: String,
    sizeDp: Dp = 210.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(content) { generateQrBitmap(content, 512) }
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Node Identity QR Code",
                modifier = Modifier.size(sizeDp - 24.dp)
            )
        }
    }
}

private fun generateQrBitmap(content: String, pixels: Int): Bitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels)
        val bmp = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        for (x in 0 until pixels) {
            for (y in 0 until pixels) {
                bmp.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bmp
    }.getOrNull()
}
