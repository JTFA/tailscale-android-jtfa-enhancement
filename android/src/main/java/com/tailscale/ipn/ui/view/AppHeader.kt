// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.minTextSize
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.theme.surfaceContainerListItem
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AutoResizingText
import com.tailscale.ipn.ui.viewModel.MainViewModel

/**
 * The top bar shared by every top-level tab: the VPN master switch on the left, the account and
 * connection state in the middle, and the settings entry point on the right.
 *
 * Keeping this in one place guarantees that all tabs show and drive the same VPN state as the
 * Quick Settings tile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToHealth: () -> Unit,
) {
  val isOn by viewModel.vpnToggleState.collectAsState()
  val state by viewModel.ipnState.collectAsState()
  val user by viewModel.loggedInUser.collectAsState()
  val stateVal by viewModel.stateRes.collectAsState(initial = R.string.placeholder)
  val stateStr = stringResource(id = stateVal)
  val healthIcon by viewModel.healthIcon.collectAsState()
  val disableToggle by MDMSettings.forceEnabled.flow.collectAsState()
  val isToggleInProgress by viewModel.isToggleInProgress.collectAsState()

  val hideHeader = (isAndroidTV() && state == Ipn.State.NeedsLogin)
  ListItem(
      colors = MaterialTheme.colorScheme.surfaceContainerListItem,
      leadingContent = {
        if (!hideHeader) {
          TintedSwitch(
              checked = isOn,
              enabled = !disableToggle.value && !isToggleInProgress,
              onCheckedChange = { desiredState -> viewModel.toggleVpn(desiredState) },
          )
        }
      },
      headlineContent = {
        user?.NetworkProfile?.tailnetNameForDisplay()?.let { domain ->
          AutoResizingText(
              text = domain,
              style = MaterialTheme.typography.titleMedium.short,
              minFontSize = MaterialTheme.typography.minTextSize,
              overflow = TextOverflow.Ellipsis,
          )
        }
      },
      supportingContent = {
        if (!hideHeader) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stateStr, style = MaterialTheme.typography.bodyMedium.short)
            healthIcon?.let {
              Spacer(modifier = Modifier.size(4.dp))
              IconButton(
                  onClick = { onNavigateToHealth() },
                  modifier = Modifier.size(16.dp),
              ) {
                Icon(
                    painterResource(id = it),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      },
      trailingContent = {
        Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterEnd) {
          when (user) {
            null -> SettingsButton { onNavigateToSettings() }
            else -> {
              Avatar(
                  profile = user,
                  size = 36,
                  { onNavigateToSettings() },
                  isFocusable = true,
              )
            }
          }
        }
      },
  )
}

@Composable
internal fun SettingsButton(action: () -> Unit) {
  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        contentDescription = "Open settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
