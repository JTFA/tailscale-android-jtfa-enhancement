// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.viewModel.AdBlockAppsViewModel

/**
 * Per-app filtering list. Apps outside the tunnel cannot be filtered, so they get an action to add
 * them to the tunnel instead of a switch.
 */
@Composable
fun AdBlockAppsView(
    onBack: () -> Unit,
    model: AdBlockAppsViewModel = viewModel(),
) {
  val apps by model.installedApps.collectAsState()
  val disabled by model.disabled.collectAsState()
  val membershipVersion by model.membershipVersion.collectAsState()

  Scaffold(topBar = { Header(titleRes = R.string.adblock_app_filtering, onBack = onBack) }) {
      padding ->
    LazyColumn(modifier = Modifier.padding(padding)) {
      item {
        Text(
            text = stringResource(R.string.adblock_app_filtering_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
      items(apps, key = { it.packageName }) { app ->
        AppRow(
            app = app,
            filtered = app.packageName !in disabled,
            membershipVersion = membershipVersion,
            onToggle = { model.setFiltered(app.packageName, it) },
            onAddToTunnel = { model.addToTunnel(app.packageName) },
        )
      }
    }
  }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    filtered: Boolean,
    membershipVersion: Int,
    onToggle: (Boolean) -> Unit,
    onAddToTunnel: () -> Unit,
) {
  val inTunnel =
      remember(app.packageName, membershipVersion) { App.get().isPackageInTunnel(app.packageName) }
  ListItem(
      colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
      headlineContent = { Text(app.name) },
      supportingContent = {
        Text(
            text =
                if (inTunnel) app.packageName
                else stringResource(R.string.adblock_app_outside_tunnel),
            style = MaterialTheme.typography.bodySmall,
        )
      },
      trailingContent = {
        if (inTunnel) {
          Switch(checked = filtered, onCheckedChange = onToggle)
        } else {
          TextButton(onClick = onAddToTunnel) {
            Text(stringResource(R.string.adblock_add_to_tunnel), maxLines = 1)
          }
        }
      },
      modifier = Modifier.padding(horizontal = 4.dp),
  )
  androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
