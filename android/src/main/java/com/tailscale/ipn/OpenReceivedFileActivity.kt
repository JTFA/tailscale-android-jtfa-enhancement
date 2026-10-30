// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier
import com.tailscale.ipn.util.TSLog

/**
 * Launches a received file from its live card and then withdraws the card.
 *
 * The notification cannot point straight at the viewer: the card has to be taken down once the file
 * is open, and only code of ours knows when that happened. A broadcast receiver is not an option,
 * because starting an activity from one is silently blocked as a background launch. An activity
 * opened by the notification is a user-initiated launch, so it may start the viewer, and it stays
 * out of sight because it never draws anything.
 */
class OpenReceivedFileActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val name = intent.getStringExtra(LiveTransferActionReceiver.EXTRA_NAME)
    val uri = name?.let { TransferProgressNotifier.openUriFor(it) }
    if (uri == null) {
      Toast.makeText(this, R.string.transfer_file_missing, Toast.LENGTH_SHORT).show()
      finish()
      return
    }
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val view =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, mime)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
      startActivity(view)
      // The card follows once the system has animated the viewer into place.
      TransferProgressNotifier.onOpenAction()
    } catch (e: Exception) {
      TSLog.w("OpenReceivedFileActivity", "no app opened $name: $e")
    }
    finish()
    overridePendingTransition(0, 0)
  }
}
