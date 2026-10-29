// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.os.Build
import java.net.InetSocketAddress
import libtailscale.UIDLookup

/**
 * Resolves the owner of a DNS socket so the native engine can apply the per-app filtering list.
 *
 * Attribution is only possible on Android 10+ and only while the VPN service is running; when it is
 * unavailable the engine falls back to filtering the query (the app list can only narrow the set).
 */
class AdBlockUidLookup(private val app: App) : UIDLookup {
  override fun lookupUID(
      proto: Int,
      srcIP: String,
      srcPort: Int,
      dstIP: String,
      dstPort: Int,
  ): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return -1
    }
    if (IPNService.instance == null) return -1
    return try {
      val cm =
          app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
              as android.net.ConnectivityManager
      cm.getConnectionOwnerUid(
          proto,
          InetSocketAddress(srcIP, srcPort),
          InetSocketAddress(dstIP, dstPort),
      )
    } catch (e: Exception) {
      -1
    }
  }

  override fun packageForUID(uid: Int): String {
    if (uid < 0) return ""
    return try {
      // Shared UIDs report "package:process"; keep only the package.
      app.packageManager.getNameForUid(uid)?.substringBefore(':') ?: ""
    } catch (e: Exception) {
      ""
    }
  }
}
