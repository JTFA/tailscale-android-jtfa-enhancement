// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier
import com.tailscale.ipn.util.TSLog

/** Handles the Open and Close buttons on a kept "Received" live card. */
class LiveTransferActionReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CLOSE = "com.tailscale.ipn.LIVE_TRANSFER_CLOSE"
    const val ACTION_OPEN = "com.tailscale.ipn.LIVE_TRANSFER_OPEN"
    const val EXTRA_NAME = "name"
  }

  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      ACTION_CLOSE -> TransferProgressNotifier.onCloseAction()
      ACTION_OPEN -> open(context, intent.getStringExtra(EXTRA_NAME) ?: return)
    }
  }

  // Resolved here rather than when the notification was built: the card can appear before the
  // rename that gives the file its final URI, and the tap must still find the file.
  private fun open(context: Context, name: String) {
    val uri = TransferProgressNotifier.openUriFor(name)
    if (uri == null) {
      TSLog.w("LiveTransferActionReceiver", "received file $name is gone")
      return
    }
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val view =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, mime)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(view) }
        .onSuccess { TransferProgressNotifier.onOpenAction() }
        .onFailure { TSLog.w("LiveTransferActionReceiver", "no app opened $name: $it") }
  }
}
