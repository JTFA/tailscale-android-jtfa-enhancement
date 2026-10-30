// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier

/** Handles the Close button on a kept "Received" live card. */
class LiveTransferActionReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CLOSE = "com.tailscale.ipn.LIVE_TRANSFER_CLOSE"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_CLOSE) {
      TransferProgressNotifier.onCloseAction()
    }
  }
}
