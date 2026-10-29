// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Status and counters reported by the native proxy server. */
data class ProxyStatus(
    val running: Boolean = false,
    val httpPort: Int = 0,
    val socksPort: Int = 0,
    val pacPort: Int = 0,
    val egress: String = "",
    val auth: Boolean = false,
    val hosts: List<String> = emptyList(),
    val currentClients: Long = 0,
    val maxClients: Long = 0,
    val totalClients: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
)

/**
 * Owns the lifecycle of the native proxy. The proxy only runs while the VPN master switch is on and
 * the Every Proxy feature switch is enabled; both being true is what [apply] checks.
 */
object ProxyManager {
  private const val TAG = "Proxy"

  @Volatile private var running = false
  @Volatile private var observed = false

  fun observe(scope: CoroutineScope) {
    if (observed) return
    observed = true
    scope.launch(Dispatchers.IO) {
      Notifier.state.collect { apply() }
    }
  }

  /**
   * Starts, restarts or stops the proxy so that it matches the current preferences.
   *
   * Uses [UninitializedApp] for the preference checks so that it can be called while the app is
   * still starting up: calling `App.get()` there would re-enter initialization.
   */
  fun apply() {
    val app = UninitializedApp.get()
    val anyProtocol = app.proxyHttpEnabled() || app.proxySocksEnabled() || app.proxyPacEnabled()
    if (!app.isProxyEnabled() || !anyProtocol || Notifier.state.value != Ipn.State.Running) {
      stop()
      return
    }
    try {
      val json = configJson(app)
      val status = JSONObject(App.get().getLibtailscaleApp().startProxy(json))
      running = status.optBoolean("running")
      TSLog.d(TAG, "proxy running: $status")
    } catch (e: Exception) {
      running = false
      TSLog.e(TAG, "starting proxy failed", e)
    }
  }

  fun restart() {
    stop()
    apply()
  }

  fun stop() {
    if (!running) return
    try {
      App.get().getLibtailscaleApp().stopProxy()
    } catch (e: Exception) {
      TSLog.e(TAG, "stopping proxy failed", e)
    }
    running = false
  }

  fun isRunning(): Boolean = running

  fun status(): ProxyStatus {
    return try {
      val json = JSONObject(App.get().getLibtailscaleApp().proxyStatusJSON())
      val hosts = mutableListOf<String>()
      json.optJSONArray("hosts")?.let { arr ->
        for (i in 0 until arr.length()) hosts.add(arr.optString(i))
      }
      ProxyStatus(
          running = json.optBoolean("running"),
          httpPort = json.optInt("httpPort"),
          socksPort = json.optInt("socksPort"),
          pacPort = json.optInt("pacPort"),
          egress = json.optString("egress"),
          auth = json.optBoolean("auth"),
          hosts = hosts,
          currentClients = json.optLong("current"),
          maxClients = json.optLong("max"),
          totalClients = json.optLong("total"),
          bytesUp = json.optLong("bytesUp"),
          bytesDown = json.optLong("bytesDown"),
      )
    } catch (e: Exception) {
      ProxyStatus()
    }
  }

  private fun configJson(app: UninitializedApp): String {
    val expose = app.proxyExposeOnTailnet()
    return JSONObject()
        .put("bindAddr", if (expose) "0.0.0.0" else "127.0.0.1")
        .put("httpPort", app.proxyHttpPort())
        .put("socks4Port", app.proxySocksPort())
        .put("socks5Port", app.proxySocksPort())
        .put("pacPort", app.proxyPacPort())
        .put("username", app.proxyUsername())
        .put("password", app.proxyPassword())
        .put("egress", app.proxyEgress())
        .put("enableHttp", app.proxyHttpEnabled())
        .put("enableSocks", app.proxySocksEnabled())
        .put("enablePac", app.proxyPacEnabled())
        .toString()
  }
}
