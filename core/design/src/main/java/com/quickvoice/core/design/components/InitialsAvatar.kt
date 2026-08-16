package com.quickvoice.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AvatarPalette = listOf(
    Color(0xFF00696E),
    Color(0xFF00639B),
    Color(0xFF1B873B),
    Color(0xFF7A5900),
    Color(0xFFBA1A1A),
    Color(0xFF6A2A5E),
    Color(0xFF415A00),
    Color(0xFF7A4F00),
)

private fun initialsFor(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> (parts[0].firstOrNull()?.toString() ?: "") + (parts[1].firstOrNull()?.toString() ?: "")
        else -> trimmed.take(2).uppercase()
    }.uppercase()
}

private fun colorFor(name: String): Color {
    val hash = name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    return AvatarPalette[hash % AvatarPalette.size]
}

@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val initials = remember(name) { initialsFor(name) }
    val bg = remember(name) { colorFor(name) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
