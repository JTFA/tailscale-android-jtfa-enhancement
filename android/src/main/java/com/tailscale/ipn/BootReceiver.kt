// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tailscale.ipn.util.TSLog

/**
 * Starts the VPN after the device boots, provided the user enabled "Start on boot" and the VPN was
 * connected the last time the app was running.
 *
 * This is independent from Android's own always-on VPN: it only kicks in when the user opted in via
 * Settings.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
      else -> return
    }

    val app = UninitializedApp.get()
    if (!app.isStartOnBootEnabled()) {
      TSLog.d(TAG, "Start on boot is disabled, not starting the VPN")
      return
    }
    if (!app.wasVpnRequested()) {
      TSLog.d(TAG, "VPN was not running before shutdown, not starting it")
      return
    }
    // Without a previous VPN authorization the service would have to ask for consent,
    // which Android does not allow from the background.
    if (!app.isAbleToStartVPN()) {
      TSLog.d(TAG, "VPN was never authorized, not starting it from boot")
      return
    }

    try {
      ContextCompat.startForegroundService(
          context,
          Intent(context, IPNService::class.java).apply { action = IPNService.ACTION_START_VPN },
      )
      TSLog.d(TAG, "Started the VPN after boot")
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start the VPN after boot: $e")
    }
  }

  companion object {
    private const val TAG = "BootReceiver"
  }
}
