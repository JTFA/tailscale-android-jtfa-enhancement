// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.App
import com.tailscale.ipn.ProxyManager
import com.tailscale.ipn.ProxyStatus
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.delay

/**
 * Every Proxy tab. The header carries the VPN master switch; this page owns the proxy switch. The
 * proxy itself only runs while the tunnel is up.
 */
@Composable
fun ProxyView(viewModel: MainViewModel, navigation: MainViewNavigation) {
  val state by viewModel.ipnState.collectAsState()
  var enabled by remember { mutableStateOf(App.get().isProxyEnabled()) }
  var status by remember { mutableStateOf(ProxyManager.status()) }

  LaunchedEffect(state) {
    while (true) {
      status = ProxyManager.status()
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
          ProxyContent(
              enabled = enabled,
              status = status,
              onToggle = {
                enabled = it
                App.get().setProxyEnabled(it)
                ProxyManager.apply()
              },
          )
      else ->
          TabNotConnected(message = stringResource(R.string.not_connected_proxy)) {
            viewModel.toggleVpn(desiredState = true)
          }
    }
  }
}

@Composable
private fun ProxyContent(enabled: Boolean, status: ProxyStatus, onToggle: (Boolean) -> Unit) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    StatusCard(enabled = enabled, status = status, onToggle = onToggle)
    PortsCard()
    ExposureCard()
    EgressCard()
    AuthCard()
    Spacer(modifier = Modifier.size(8.dp))
  }
}

@Composable
private fun StatusCard(enabled: Boolean, status: ProxyStatus, onToggle: (Boolean) -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
              text = stringResource(R.string.proxy_server),
              style = MaterialTheme.typography.titleSmall,
          )
          Text(
              text =
                  stringResource(
                      when {
                        !enabled -> R.string.proxy_status_off
                        status.running -> R.string.proxy_status_running
                        else -> R.string.proxy_status_stopped
                      }
                  ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
      }
      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
      Row {
        StatCell(
            value = status.currentClients.toString(),
            label = stringResource(R.string.proxy_clients_current),
            modifier = Modifier.weight(1f),
        )
        StatCell(
            value = status.maxClients.toString(),
            label = stringResource(R.string.proxy_clients_max),
            modifier = Modifier.weight(1f),
        )
        StatCell(
            value = status.totalClients.toString(),
            label = stringResource(R.string.proxy_clients_total),
            modifier = Modifier.weight(1f),
        )
      }
      Spacer(modifier = Modifier.size(10.dp))
      Row {
        StatCell(
            value = formatBytes(status.bytesUp),
            label = stringResource(R.string.proxy_bytes_up),
            modifier = Modifier.weight(1f),
        )
        StatCell(
            value = formatBytes(status.bytesDown),
            label = stringResource(R.string.proxy_bytes_down),
            modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun PortsCard() {
  var http by remember { mutableStateOf(App.get().proxyHttpPort().toString()) }
  var socks by remember { mutableStateOf(App.get().proxySocksPort().toString()) }
  var pac by remember { mutableStateOf(App.get().proxyPacPort().toString()) }

  val httpVal = http.toIntOrNull()
  val socksVal = socks.toIntOrNull()
  val pacVal = pac.toIntOrNull()
  val valid =
      httpVal != null && httpVal in 1..65535 &&
          socksVal != null && socksVal in 1..65535 &&
          pacVal != null && pacVal in 1..65535
  val changed =
      httpVal != App.get().proxyHttpPort() ||
          socksVal != App.get().proxySocksPort() ||
          pacVal != App.get().proxyPacPort()

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(text = stringResource(R.string.proxy_ports_title), style = MaterialTheme.typography.titleSmall)
      Spacer(modifier = Modifier.size(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PortField(
            label = stringResource(R.string.proxy_http),
            value = http,
            onValueChange = { http = it },
            modifier = Modifier.weight(1f),
        )
        PortField(
            label = stringResource(R.string.proxy_socks),
            value = socks,
            onValueChange = { socks = it },
            modifier = Modifier.weight(1f),
        )
        PortField(
            label = stringResource(R.string.proxy_pac),
            value = pac,
            onValueChange = { pac = it },
            modifier = Modifier.weight(1f),
        )
      }
      Spacer(modifier = Modifier.size(8.dp))
      Button(
          onClick = {
            if (valid) {
              App.get().setProxyPorts(httpVal!!, socksVal!!, pacVal!!)
              ProxyManager.restart()
            }
          },
          enabled = valid && changed,
      ) {
        Text(stringResource(R.string.proxy_apply))
      }
    }
  }
}

@Composable
private fun PortField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  OutlinedTextField(
      value = value,
      onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(5)) },
      label = { Text(label, maxLines = 1) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = modifier,
  )
}

@Composable
private fun ExposureCard() {
  var expose by remember { mutableStateOf(App.get().proxyExposeOnTailnet()) }
  val status = ProxyManager.status()

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
              text = stringResource(R.string.proxy_expose_tailnet),
              style = MaterialTheme.typography.titleSmall,
          )
          Text(
              text = stringResource(R.string.proxy_expose_tailnet_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
            checked = expose,
            onCheckedChange = {
              expose = it
              App.get().setProxyExposeOnTailnet(it)
              ProxyManager.restart()
            },
        )
      }
      if (expose && status.hosts.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Text(
            text = stringResource(R.string.proxy_hosts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status.hosts.forEach { host ->
          Text(text = host, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

@Composable
private fun EgressCard() {
  var egress by remember { mutableStateOf(App.get().proxyEgress()) }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          text = stringResource(R.string.proxy_egress),
          style = MaterialTheme.typography.titleSmall,
      )
      Spacer(modifier = Modifier.size(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = egress == "tailscale",
            onClick = {
              egress = "tailscale"
              App.get().setProxyEgress("tailscale")
              ProxyManager.restart()
            },
            label = { Text(stringResource(R.string.proxy_via_tailscale)) },
        )
        FilterChip(
            selected = egress == "direct",
            onClick = {
              egress = "direct"
              App.get().setProxyEgress("direct")
              ProxyManager.restart()
            },
            label = { Text(stringResource(R.string.proxy_direct)) },
        )
      }
    }
  }
}

@Composable
private fun AuthCard() {
  var username by remember { mutableStateOf(App.get().proxyUsername()) }
  var password by remember { mutableStateOf(App.get().proxyPassword()) }
  val changed = username != App.get().proxyUsername() || password != App.get().proxyPassword()

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          text = stringResource(R.string.proxy_auth_title),
          style = MaterialTheme.typography.titleSmall,
      )
      Text(
          text = stringResource(R.string.proxy_auth_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.size(8.dp))
      OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text(stringResource(R.string.proxy_auth_username)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.size(8.dp))
      OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.proxy_auth_password)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.size(8.dp))
      Button(
          onClick = {
            App.get().setProxyCredentials(username, password)
            ProxyManager.restart()
          },
          enabled = changed,
      ) {
        Text(stringResource(R.string.proxy_apply))
      }
    }
  }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(text = value, style = MaterialTheme.typography.titleSmall)
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) {
    value /= 1024
    unit++
  }
  return String.format("%.1f %s", value, units[unit])
}
