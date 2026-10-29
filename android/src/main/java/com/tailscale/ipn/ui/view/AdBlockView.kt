// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.AdBlock
import com.tailscale.ipn.AdBlockStats
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.delay

/**
 * AdBlock tab. Like the Tailscale tab, the header carries the VPN master switch; this page only
 * owns the filtering switch. While the tunnel is down the page shows the not-connected state.
 */
@Composable
fun AdBlockView(
    viewModel: MainViewModel,
    navigation: MainViewNavigation,
    onNavigateToApps: () -> Unit = {},
) {
  val state by viewModel.ipnState.collectAsState()
  var enabled by remember { mutableStateOf(App.get().isAdBlockEnabled()) }
  var stats by remember { mutableStateOf(AdBlockStats()) }

  LaunchedEffect(state) {
    while (true) {
      stats = AdBlock.stats()
      delay(1000)
    }
  }

  Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
    AppHeader(
        viewModel = viewModel,
        onNavigateToSettings = navigation.onNavigateToSettings,
        onNavigateToHealth = navigation.onNavigateToHealth,
    )
    when (state) {
      Ipn.State.Running ->
          AdBlockContent(
              enabled = enabled,
              stats = stats,
              onToggle = {
                enabled = it
                App.get().setAdBlockEnabled(it)
                App.get().syncAdBlockSettings()
              },
              onNavigateToApps = onNavigateToApps,
          )
      else ->
          TabNotConnected(message = stringResource(R.string.not_connected_adblock)) {
            viewModel.toggleVpn(desiredState = true)
          }
    }
  }
}

@Composable
private fun AdBlockContent(
    enabled: Boolean,
    stats: AdBlockStats,
    onToggle: (Boolean) -> Unit,
    onNavigateToApps: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.adblock_ad_blocking),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.adblock_active_while_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = enabled, onCheckedChange = onToggle)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row {
          StatCell(
              value = stats.todayBlocked.toString(),
              label = stringResource(R.string.adblock_today),
              modifier = Modifier.weight(1f),
          )
          StatCell(
              value = stats.totalBlocked.toString(),
              label = stringResource(R.string.adblock_total),
              modifier = Modifier.weight(1f),
          )
          StatCell(
              value = stats.apps.toString(),
              label = stringResource(R.string.adblock_apps),
              modifier = Modifier.weight(1f),
          )
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      ListItem(
          headlineContent = { Text(stringResource(R.string.adblock_app_filtering)) },
          supportingContent = {
            Text(
                text = stringResource(R.string.adblock_app_filtering_desc),
                style = MaterialTheme.typography.bodySmall,
            )
          },
          trailingContent = { Text("\u203A", style = MaterialTheme.typography.titleMedium) },
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
          modifier = Modifier.clickable { onNavigateToApps() },
      )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text(
            text = stringResource(R.string.adblock_dns_note_title),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.adblock_dns_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.adblock_rules_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Spacer(modifier = Modifier.size(8.dp))
  }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(text = value, style = MaterialTheme.typography.titleMedium)
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
