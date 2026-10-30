// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.Lists

/**
 * Settings → Theme. Two looks for the share sheet: the plain opaque card, or the ColorOS17 frosted
 * card with a translucent blur and a bright edge. The choice is stored right away, so the next share
 * already shows it.
 */
@Composable
fun ThemeSettingsView(backToSettings: BackNavigation) {
  var glass by remember { mutableStateOf(App.get().isShareSheetGlassEnabled()) }

  fun choose(enabled: Boolean) {
    glass = enabled
    App.get().setShareSheetGlassEnabled(enabled)
  }

  Scaffold(topBar = { Header(titleRes = R.string.settings_theme, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item { Lists.SectionDivider() }
      item {
        ThemeOption(
            titleRes = R.string.theme_default,
            subtitleRes = R.string.theme_default_subtitle,
            selected = !glass,
            onClick = { choose(false) },
        )
      }
      item { Lists.ItemDivider() }
      item {
        ThemeOption(
            titleRes = R.string.theme_coloros17,
            subtitleRes = R.string.theme_coloros17_subtitle,
            selected = glass,
            onClick = { choose(true) },
        )
      }
      item { Lists.SectionDivider() }
      item { ShareSheetSample(glass = glass) }
    }
  }
}

@Composable
private fun ThemeOption(
    titleRes: Int,
    subtitleRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
  ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
      },
      supportingContent = {
        Text(
            stringResource(subtitleRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      trailingContent = {
        if (selected) {
          Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp),
          )
        }
      },
  )
}

/** A small sample of the card over a colourful backdrop, so the difference is visible at a glance. */
@Composable
private fun ShareSheetSample(glass: Boolean) {
  val night = isSystemInDarkTheme()
  val cardShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
  val cardColor =
      if (glass) Color.White.copy(alpha = if (night) 0.18f else 0.40f)
      else if (night) MaterialTheme.colorScheme.surface
      else MaterialTheme.colorScheme.surface
  val rim =
      if (glass)
          Brush.linearGradient(
              listOf(
                  Color.White.copy(alpha = 0.85f),
                  Color.White.copy(alpha = 0.10f),
                  Color.White.copy(alpha = 0.40f),
              )
          )
      else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))

  Box(
      modifier =
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
              .fillMaxWidth()
              .height(140.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(
                  Brush.linearGradient(
                      listOf(Color(0xFF5A82DC), Color(0xFF9B6BD6), Color(0xFFE0748C))
                  )
              ),
      contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 28.dp)
                .clip(cardShape)
                .background(cardColor)
                .then(if (glass) Modifier.border(1.dp, rim, cardShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
          text = stringResource(R.string.share),
          style = MaterialTheme.typography.titleSmall,
          color = if (glass && !night) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.onSurface,
      )
      Box(
          modifier =
              Modifier.fillMaxWidth()
                  .height(10.dp)
                  .clip(RoundedCornerShape(5.dp))
                  .background(
                      if (glass) Color.Black.copy(alpha = 0.12f)
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                  )
      )
      Box(
          modifier =
              Modifier.fillMaxWidth(0.6f)
                  .height(10.dp)
                  .clip(RoundedCornerShape(5.dp))
                  .background(
                      if (glass) Color.Black.copy(alpha = 0.12f)
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                  )
      )
    }
  }
}
