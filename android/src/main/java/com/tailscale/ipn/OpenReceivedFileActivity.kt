// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier
import com.tailscale.ipn.util.TSLog

/**
 * Launches a received file from its live card and takes the card down as it leaves.
 *
 * The notification cannot point straight at the viewer: the card has to be withdrawn once the file
 * is open, and only code of ours knows when that happened. A broadcast receiver is not an option,
 * because starting an activity from one is silently blocked as a background launch. An activity
 * opened by the notification is a user-initiated launch, so it may start the viewer.
 *
 * It draws nothing and stays only until the viewer is in front of it: going to the background is
 * the moment the file is open, and that is when the card is withdrawn, so the system's transition
 * into the viewer is never cut short by a card disappearing underneath it.
 */
class OpenReceivedFileActivity : Activity() {
  private var name: String? = null
  private var launched = false
  private var closed = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    name = intent.getStringExtra(LiveTransferActionReceiver.EXTRA_NAME)
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
      launched = true
      // Nothing else covers us if the viewer never came up, so do not wait for it forever.
      Handler(Looper.getMainLooper()).postDelayed({ leave() }, GIVE_UP_MS)
    } catch (e: Exception) {
      TSLog.w("OpenReceivedFileActivity", "no app opened $name: $e")
      finish()
    }
  }

  override fun onStop() {
    super.onStop()
    if (launched) {
      leave()
    }
  }

  private fun leave() {
    if (closed) {
      return
    }
    closed = true
    TransferProgressNotifier.onOpenAction(name)
    finish()
    overridePendingTransition(0, 0)
  }

  private companion object {
    const val GIVE_UP_MS = 3_000L
  }
}
