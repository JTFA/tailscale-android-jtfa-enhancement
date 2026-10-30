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
 * Launches a received file from its live card and takes the card down straight away.
 *
 * The notification cannot point straight at the viewer: the card has to be withdrawn once the file
 * is opened, and only code of ours knows when that happened. A broadcast receiver is not an option,
 * because starting an activity from one is silently blocked as a background launch. An activity
 * opened by the notification is a user-initiated launch, so it may start the viewer.
 *
 * The card is cancelled as this activity starts, before the viewer is launched: the system has
 * already resolved the tap into this activity at that point, so its transition does not depend on
 * the notification still being there.
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
    TransferProgressNotifier.onOpenAction(name)
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val view =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, mime)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
      startActivity(view)
    } catch (e: Exception) {
      TSLog.w("OpenReceivedFileActivity", "no app opened $name: $e")
    }
    finish()
    overridePendingTransition(0, 0)
  }
}
