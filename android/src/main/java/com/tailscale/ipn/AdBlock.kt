// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import com.tailscale.ipn.util.TSLog
import org.json.JSONObject

/** Counters reported by the native DNS filtering engine. */
data class AdBlockStats(
    val todayBlocked: Long = 0,
    val totalBlocked: Long = 0,
    val todayQueries: Long = 0,
    val totalQueries: Long = 0,
    val apps: Int = 0,
    val ruleCount: Int = 0,
)

object AdBlock {
  private const val TAG = "AdBlock"

  /**
   * Reads the current counters. The engine lives in Go and is only reachable once libtailscale has
   * started; before that this returns zeros.
   */
  fun stats(): AdBlockStats {
    return try {
      val json = JSONObject(App.get().getLibtailscaleApp().adBlockStatsJSON())
      AdBlockStats(
          todayBlocked = json.optLong("todayBlocked"),
          totalBlocked = json.optLong("blocked"),
          todayQueries = json.optLong("today"),
          totalQueries = json.optLong("total"),
          apps = json.optJSONObject("apps")?.length() ?: 0,
      )
    } catch (e: Exception) {
      TSLog.w(TAG, "reading stats failed: ${e.localizedMessage}")
      AdBlockStats()
    }
  }
}
