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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.AdBlock
import com.tailscale.ipn.AdBlockStats
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.delay

/**
 * AdBlock tab. The header carries the VPN master switch; this page owns the filtering switch. The
 * rules themselves are completely user supplied, so nothing is blocked until a list is added.
 */
@Composable
fun AdBlockView(
    viewModel: MainViewModel,
    navigation: MainViewNavigation,
    onNavigateToRules: () -> Unit = {},
    onNavigateToApps: () -> Unit = {},
) {
  val state by viewModel.ipnState.collectAsState()
  var enabled by remember { mutableStateOf(App.get().isAdBlockEnabled()) }
  var stats by remember { mutableStateOf(AdBlockStats()) }
  var ruleCount by remember { mutableStateOf(App.get().adblockRuleCount()) }
  var excludedApps by remember { mutableStateOf(App.get().adblockDisabledPackages().size) }

  LaunchedEffect(state) {
    while (true) {
      stats = AdBlock.stats()
      ruleCount = App.get().adblockRuleCount()
      excludedApps = App.get().adblockDisabledPackages().size
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
              ruleCount = ruleCount,
              excludedApps = excludedApps,
              onToggle = {
                enabled = it
                App.get().setAdBlockEnabled(it)
                App.get().syncAdBlockSettings()
              },
              onNavigateToRules = onNavigateToRules,
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
    ruleCount: Int,
    excludedApps: Int,
    onToggle: (Boolean) -> Unit,
    onNavigateToRules: () -> Unit,
    onNavigateToApps: () -> Unit,
) {
  val filtering = enabled && ruleCount > 0
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusDot(active = filtering)
          Spacer(modifier = Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    stringResource(
                        when {
                          !enabled -> R.string.adblock_status_paused
                          ruleCount == 0 -> R.string.adblock_status_no_rules
                          else -> R.string.adblock_status_running
                        }
                    ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.adblock_subtitle, ruleCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(modifier = Modifier.size(10.dp))
        InfoRow(label = stringResource(R.string.adblock_info_rules), value = ruleCount.toString())
        InfoRow(
            label = stringResource(R.string.adblock_info_apps),
            value =
                if (excludedApps == 0) stringResource(R.string.adblock_apps_all)
                else stringResource(R.string.adblock_apps_excluded, excludedApps),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          MetricColumn(
              title = stringResource(R.string.adblock_blocked_title),
              rows =
                  listOf(
                      stringResource(R.string.metric_today) to stats.todayBlocked.toString(),
                      stringResource(R.string.metric_total) to stats.totalBlocked.toString(),
                  ),
              modifier = Modifier.weight(1f),
          )
          MetricColumn(
              title = stringResource(R.string.adblock_queries_title),
              rows =
                  listOf(
                      stringResource(R.string.metric_today) to stats.todayQueries.toString(),
                      stringResource(R.string.metric_total) to stats.totalQueries.toString(),
                  ),
              modifier = Modifier.weight(1f),
          )
        }
        if (enabled && ruleCount > 0) {
          Spacer(modifier = Modifier.size(10.dp))
          Text(
              text =
                  when {
                    stats.lastBlocked.isNotEmpty() ->
                        stringResource(R.string.adblock_last_blocked, stats.lastBlocked)
                    stats.lastQuery.isNotEmpty() ->
                        stringResource(R.string.adblock_last_query, stats.lastQuery)
                    else -> stringResource(R.string.adblock_waiting_for_dns)
                  },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (ruleCount == 0) {
          Spacer(modifier = Modifier.size(12.dp))
          Button(onClick = onNavigateToRules, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.adblock_add_rules))
          }
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.adblock_custom_rules)) },
            supportingContent = {
              Text(
                  text =
                      if (ruleCount == 0) stringResource(R.string.adblock_custom_rules_empty)
                      else stringResource(R.string.adblock_custom_rules_count, ruleCount),
                  style = MaterialTheme.typography.bodySmall,
              )
            },
            trailingContent = { Text("\u203A", style = MaterialTheme.typography.titleMedium) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onNavigateToRules() },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.adblock_app_filtering)) },
            supportingContent = {
              Text(
                  text =
                      if (excludedApps == 0) stringResource(R.string.adblock_apps_all)
                      else stringResource(R.string.adblock_apps_excluded, excludedApps),
                  style = MaterialTheme.typography.bodySmall,
              )
            },
            trailingContent = { Text("\u203A", style = MaterialTheme.typography.titleMedium) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onNavigateToApps() },
        )
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text(
            text = stringResource(R.string.adblock_dns_note_title),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.adblock_dns_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Spacer(modifier = Modifier.size(8.dp))
  }
}
