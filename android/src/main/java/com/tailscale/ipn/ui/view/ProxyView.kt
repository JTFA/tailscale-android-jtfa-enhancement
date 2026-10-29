// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.os.SystemClock
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.App
import com.tailscale.ipn.ProxyManager
import com.tailscale.ipn.ProxyStatus
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Every Proxy tab. The header carries the VPN master switch; this page owns the proxy switch, the
 * per protocol switches and the ports. The proxy only runs while the tunnel is up.
 */
@Composable
fun ProxyView(viewModel: MainViewModel, navigation: MainViewNavigation) {
  val state by viewModel.ipnState.collectAsState()
  var enabled by remember { mutableStateOf(App.get().isProxyEnabled()) }
  var httpOn by remember { mutableStateOf(App.get().proxyHttpEnabled()) }
  var socksOn by remember { mutableStateOf(App.get().proxySocksEnabled()) }
  var pacOn by remember { mutableStateOf(App.get().proxyPacEnabled()) }
  var httpPort by remember { mutableStateOf(App.get().proxyHttpPort()) }
  var socksPort by remember { mutableStateOf(App.get().proxySocksPort()) }
  var pacPort by remember { mutableStateOf(App.get().proxyPacPort()) }
  var status by remember { mutableStateOf(ProxyManager.status()) }
  var traffic by remember { mutableStateOf(ProxyTraffic()) }
  val tracker = remember { TrafficTracker() }
  var editingProtocol by remember { mutableStateOf<ProtocolKind?>(null) }
  var showingEgress by remember { mutableStateOf(false) }

  LaunchedEffect(state) {
    while (true) {
      status = ProxyManager.status()
      traffic = tracker.update(status.bytesUp, status.bytesDown, status.running)
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
              traffic = traffic,
              httpOn = httpOn,
              socksOn = socksOn,
              pacOn = pacOn,
              httpPort = httpPort,
              socksPort = socksPort,
              pacPort = pacPort,
              onToggleMaster = {
                enabled = it
                App.get().setProxyEnabled(it)
                ProxyManager.apply()
              },
              onToggleProtocol = { protocol, on ->
                when (protocol) {
                  ProtocolKind.HTTP -> httpOn = on
                  ProtocolKind.SOCKS -> socksOn = on
                  ProtocolKind.PAC -> pacOn = on
                }
                App.get().setProxyProtocolEnabled(protocol.key, on)
                ProxyManager.apply()
              },
              onEditPort = { editingProtocol = it },
              onShowEgress = { showingEgress = true },
          )
      else ->
          TabNotConnected(message = stringResource(R.string.not_connected_proxy)) {
            viewModel.toggleVpn(desiredState = true)
          }
    }
  }

  if (showingEgress) {
    EgressDialog(onDismiss = { showingEgress = false })
  }

  editingProtocol?.let { protocol ->
    PortDialog(
        protocol = protocol,
        current =
            when (protocol) {
              ProtocolKind.HTTP -> httpPort
              ProtocolKind.SOCKS -> socksPort
              ProtocolKind.PAC -> pacPort
            },
        onDismiss = { editingProtocol = null },
        onSave = { port ->
          when (protocol) {
            ProtocolKind.HTTP -> httpPort = port
            ProtocolKind.SOCKS -> socksPort = port
            ProtocolKind.PAC -> pacPort = port
          }
          App.get().setProxyPorts(httpPort, socksPort, pacPort)
          editingProtocol = null
          ProxyManager.apply()
        },
    )
  }
}

private enum class ProtocolKind(val key: String, val letter: String) {
  HTTP("http", "H"),
  SOCKS("socks", "S"),
  PAC("pac", "P"),
}

@Composable
private fun ProxyContent(
    enabled: Boolean,
    status: ProxyStatus,
    traffic: ProxyTraffic,
    httpOn: Boolean,
    socksOn: Boolean,
    pacOn: Boolean,
    httpPort: Int,
    socksPort: Int,
    pacPort: Int,
    onToggleMaster: (Boolean) -> Unit,
    onToggleProtocol: (ProtocolKind, Boolean) -> Unit,
    onEditPort: (ProtocolKind) -> Unit,
    onShowEgress: () -> Unit,
) {
  val anyProtocol = httpOn || socksOn || pacOn
  val protocolNames =
      buildList {
            if (httpOn) add("HTTP")
            if (socksOn) add("SOCKS5")
            if (pacOn) add("PAC")
          }
          .joinToString(" · ")
  val running = enabled && anyProtocol && status.running
  val expose = App.get().proxyExposeOnTailnet()
  val tailnetIP = Notifier.netmap.value?.SelfNode?.primaryIPv4Address
  val addresses =
      if (!expose) "127.0.0.1"
      else
          (listOfNotNull(tailnetIP) + status.hosts)
              .filter { it.isNotBlank() }
              .distinct()
              .joinToString(", ")
              .ifEmpty { "0.0.0.0" }

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
          StatusDot(active = running)
          Spacer(modifier = Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    stringResource(
                        when {
                          !enabled -> R.string.proxy_status_off
                          status.running -> R.string.proxy_status_running
                          else -> R.string.proxy_status_stopped
                        }
                    ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    when {
                      !enabled -> stringResource(R.string.proxy_off_desc)
                      !anyProtocol -> stringResource(R.string.proxy_select_protocol)
                      else ->
                          stringResource(
                              R.string.proxy_subtitle,
                              protocolNames,
                              status.currentClients,
                          )
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = enabled, onCheckedChange = onToggleMaster)
        }
        Spacer(modifier = Modifier.size(10.dp))
        InfoRow(
            label = stringResource(R.string.proxy_hosts_label),
            value = addresses,
        )
        InfoRow(
            label = stringResource(R.string.proxy_port_label),
            value =
                buildList {
                      if (httpOn) add(httpPort.toString())
                      if (socksOn) add(socksPort.toString())
                      if (pacOn) add(pacPort.toString())
                    }
                    .joinToString(", ")
                    .ifEmpty { "—" },
        )
        InfoRow(
            label = stringResource(R.string.proxy_auth_label),
            value =
                App.get().proxyUsername().ifEmpty { stringResource(R.string.proxy_auth_none) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          TrafficDonut(received = status.bytesUp, sent = status.bytesDown)
          Spacer(modifier = Modifier.width(18.dp))
          MetricColumn(
              title = stringResource(R.string.proxy_bytes_up),
              rows =
                  listOf(
                      stringResource(R.string.metric_max) to formatRate(traffic.maxUp),
                      stringResource(R.string.metric_current) to formatRate(traffic.upBps),
                      stringResource(R.string.metric_total) to formatBytes(status.bytesUp),
                  ),
              modifier = Modifier.weight(1f),
          )
          MetricColumn(
              title = stringResource(R.string.proxy_bytes_down),
              rows =
                  listOf(
                      stringResource(R.string.metric_max) to formatRate(traffic.maxDown),
                      stringResource(R.string.metric_current) to formatRate(traffic.downBps),
                      stringResource(R.string.metric_total) to formatBytes(status.bytesDown),
                  ),
              modifier = Modifier.weight(1f),
          )
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column {
        ProtocolRow(
            letter = ProtocolKind.HTTP.letter,
            title = stringResource(R.string.proxy_http),
            subtitle = httpPort.toString(),
            checked = httpOn,
            onCheckedChange = { onToggleProtocol(ProtocolKind.HTTP, it) },
            onClick = { onEditPort(ProtocolKind.HTTP) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        ProtocolRow(
            letter = ProtocolKind.SOCKS.letter,
            title = stringResource(R.string.proxy_socks),
            subtitle = socksPort.toString(),
            checked = socksOn,
            onCheckedChange = { onToggleProtocol(ProtocolKind.SOCKS, it) },
            onClick = { onEditPort(ProtocolKind.SOCKS) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        ProtocolRow(
            letter = ProtocolKind.PAC.letter,
            title = stringResource(R.string.proxy_pac),
            subtitle = pacPort.toString(),
            checked = pacOn,
            onCheckedChange = { onToggleProtocol(ProtocolKind.PAC, it) },
            onClick = { onEditPort(ProtocolKind.PAC) },
        )
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.proxy_egress)) },
            supportingContent = {
              Text(
                  text =
                      if (App.get().proxyEgress() == "direct") stringResource(R.string.proxy_direct)
                      else stringResource(R.string.proxy_via_tailscale),
                  style = MaterialTheme.typography.bodySmall,
              )
            },
            trailingContent = { Text("\u203A", style = MaterialTheme.typography.titleMedium) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onShowEgress() },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        ExposureRow(addresses = addresses)
      }
    }

    AuthCard()
  }
}

@Composable
private fun ExposureRow(addresses: String) {
  var expose by remember { mutableStateOf(App.get().proxyExposeOnTailnet()) }

  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
          text = stringResource(R.string.proxy_expose_tailnet),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          text = addresses,
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
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
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
private fun EgressDialog(onDismiss: () -> Unit) {
  var egress by remember { mutableStateOf(App.get().proxyEgress()) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.proxy_egress)) },
      text = {
        Column {
          EgressOption(
              label = stringResource(R.string.proxy_via_tailscale),
              selected = egress == "tailscale",
          ) {
            egress = "tailscale"
            App.get().setProxyEgress("tailscale")
            ProxyManager.restart()
            onDismiss()
          }
          EgressOption(
              label = stringResource(R.string.proxy_direct),
              selected = egress == "direct",
          ) {
            egress = "direct"
            App.get().setProxyEgress("direct")
            ProxyManager.restart()
            onDismiss()
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
      },
  )
}

@Composable
private fun EgressOption(label: String, selected: Boolean, onClick: () -> Unit) {
  ListItem(
      headlineContent = { Text(label) },
      trailingContent = {
        if (selected) {
          Text("\u2713", color = MaterialTheme.colorScheme.primary)
        }
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      modifier = Modifier.clickable(onClick = onClick),
  )
}

@Composable
private fun PortDialog(
    protocol: ProtocolKind,
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
  var value by remember { mutableStateOf(current.toString()) }
  val port = value.toIntOrNull()
  val valid = port != null && port in 1..65535

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.proxy_port_title, protocolTitle(protocol))) },
      text = {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.proxy_port_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      },
      confirmButton = {
        TextButton(
            onClick = { if (valid) onSave(port!!) },
            enabled = valid,
        ) {
          Text(stringResource(android.R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
      },
  )
}

@Composable
private fun protocolTitle(protocol: ProtocolKind): String =
    when (protocol) {
      ProtocolKind.HTTP -> stringResource(R.string.proxy_http)
      ProtocolKind.SOCKS -> stringResource(R.string.proxy_socks)
      ProtocolKind.PAC -> stringResource(R.string.proxy_pac)
    }

/** Rates derived from the byte counters the proxy reports. */
data class ProxyTraffic(
    val upBps: Long = 0,
    val downBps: Long = 0,
    val maxUp: Long = 0,
    val maxDown: Long = 0,
)

private class TrafficTracker {
  private var lastUp = -1L
  private var lastDown = -1L
  private var lastAt = 0L
  private var snapshot = ProxyTraffic()

  fun update(up: Long, down: Long, running: Boolean): ProxyTraffic {
    if (!running) {
      lastUp = -1
      lastDown = -1
      lastAt = 0
      snapshot = ProxyTraffic()
      return snapshot
    }
    val now = SystemClock.elapsedRealtime()
    if (lastUp >= 0 && now > lastAt) {
      val seconds = (now - lastAt) / 1000.0
      val upRate = ((up - lastUp).coerceAtLeast(0) / seconds).toLong()
      val downRate = ((down - lastDown).coerceAtLeast(0) / seconds).toLong()
      snapshot =
          ProxyTraffic(
              upBps = upRate,
              downBps = downRate,
              maxUp = max(snapshot.maxUp, upRate),
              maxDown = max(snapshot.maxDown, downRate),
          )
    }
    lastUp = up
    lastDown = down
    lastAt = now
    return snapshot
  }
}
