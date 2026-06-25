package com.fraykiee.droidboot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7EE787),
    secondary = Color(0xFF58A6FF),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2DA44E),
    secondary = Color(0xFF0969DA),
)

@Composable
fun DroidBootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
