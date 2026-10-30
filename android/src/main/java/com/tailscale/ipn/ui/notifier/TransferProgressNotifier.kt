// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Live progress notification for Taildrop transfers.
 *
 * On Android 16 an ongoing notification carrying [Notification.ProgressStyle] and requested as a
 * promoted ongoing notification is surfaced as a live update: a progress chip in the status bar
 * that ColorOS shows in its Fluid Cloud area. Below API 36 this degrades to a regular ongoing
 * progress notification.
 *
 * Progress needs no new plumbing: outgoing files report Sent/DeclaredSize and incoming files
 * report Received/DeclaredSize through the IPN bus, refreshed at most once per second by the Go
 * taildrop code, so both directions animate while the bytes move.
 */
object TransferProgressNotifier {
  private const val TAG = "TransferProgressNotifier"
  // A single transfer is shown at a time, so one fixed id is enough.
  private const val NOTIFICATION_ID = 0x7461
  // A live update that vanishes the instant it hits 100% looks like a failed transfer, so the
  // finished state sits on screen for a moment before it is withdrawn.
  private const val HOLD_AFTER_COMPLETE_MS = 5_000L

  private enum class Direction {
    SEND,
    RECEIVE,
  }

  private data class Transfer(
      val key: String,
      val name: String,
      val direction: Direction,
      val transferred: Long,
      val declaredSize: Long,
      val done: Boolean,
      val failed: Boolean = false,
  )

  private var appContext: Context? = null
  private var scope: CoroutineScope? = null
  private var collectJob: Job? = null
  private var holdJob: Job? = null
  private val activeKeys = mutableSetOf<String>()
  private val completedKeys = mutableSetOf<String>()

  /** Starts observing file transfers. Safe to call more than once. */
  @Synchronized
  fun start(context: Context, appScope: CoroutineScope) {
    if (collectJob != null) {
      return
    }
    appContext = context.applicationContext
    scope = appScope
    collectJob =
        appScope.launch {
          combine(Notifier.outgoingFiles, Notifier.incomingFiles) { outgoing, incoming ->
                pickOutgoing(outgoing.orEmpty()) ?: pickIncoming(incoming.orEmpty())
              }
              .distinctUntilChanged()
              .collect { transfer -> render(transfer) }
        }
    TSLog.d(TAG, "watching transfers for live progress notifications")
  }

  /**
   * Called when the user flips the setting. Turning the feature off withdraws whatever is on
   * screen right now instead of waiting for the next transfer to finish.
   */
  fun onPreferenceChanged() {
    if (App.get().isLiveTransferNotificationEnabled()) {
      return
    }
    holdJob?.cancel()
    holdJob = null
    cancel()
  }

  fun cancel() {
    val context = appContext ?: return
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  /**
   * The most recent upload. Unfinished work wins; otherwise a file that we watched being sent and
   * has since finished, so its completion is reported.
   */
  private fun pickOutgoing(files: List<Ipn.OutgoingFile>): Transfer? {
    val candidates = files.filterNot { InlineShare.matches(it.Name) }
    val active = candidates.lastOrNull { !it.Finished }
    if (active != null) {
      activeKeys += active.key()
      return active.toTransfer(Direction.SEND, done = false)
    }
    val finished = candidates.lastOrNull { it.Finished && it.key() in activeKeys }
    return finished?.toTransfer(Direction.SEND, done = true)
  }

  /** The most recent download, using the same active-then-finished rule as [pickOutgoing]. */
  private fun pickIncoming(files: List<Ipn.PartialFile>): Transfer? {
    val candidates = files.filterNot { InlineShare.matches(it.Name) }
    val active = candidates.lastOrNull { it.Done != true }
    if (active != null) {
      activeKeys += active.key()
      return active.toTransfer(Direction.RECEIVE, done = false)
    }
    val finished = candidates.lastOrNull { it.Done == true && it.key() in activeKeys }
    return finished?.toTransfer(Direction.RECEIVE, done = true)
  }

  private fun Ipn.OutgoingFile.key(): String = "out:${ID.ifEmpty { Name }}"

  private fun Ipn.PartialFile.key(): String = "in:${Name}:${Started}"

  private fun Ipn.OutgoingFile.toTransfer(direction: Direction, done: Boolean) =
      Transfer(key(), Name, direction, Sent, DeclaredSize, done, failed = done && !Succeeded)

  private fun Ipn.PartialFile.toTransfer(direction: Direction, done: Boolean) =
      Transfer(key(), Name, direction, Received, DeclaredSize, done)

  private fun render(transfer: Transfer?) {
    if (transfer == null) {
      // The lists going empty (a completed receive is dropped immediately) must not withdraw the
      // notification: the hold job below owns the dismissal.
      return
    }
    if (!transfer.done) {
      completedKeys -= transfer.key
      holdJob?.cancel()
      holdJob = null
      post(transfer, complete = false)
      return
    }
    if (!completedKeys.add(transfer.key)) {
      return
    }
    post(transfer, complete = true)
    holdJob?.cancel()
    holdJob =
        scope?.launch {
          delay(HOLD_AFTER_COMPLETE_MS)
          cancel()
          activeKeys -= transfer.key
          completedKeys -= transfer.key
        }
  }

  private fun post(transfer: Transfer, complete: Boolean) {
    val context = appContext ?: return
    if (!App.get().isLiveTransferNotificationEnabled()) {
      cancel()
      return
    }
    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      TSLog.d(TAG, "POST_NOTIFICATIONS not granted; skipping transfer notification")
      return
    }

    val percent = percentOf(transfer)
    val title =
        context.getString(
            when (transfer.direction) {
              Direction.SEND -> R.string.transfer_sending
              Direction.RECEIVE -> R.string.transfer_receiving
            }
        )
    val text =
        when {
          transfer.failed -> context.getString(R.string.transfer_failed)
          complete -> context.getString(R.string.transfer_complete)
          percent >= 0 ->
              context.getString(
                  R.string.transfer_progress,
                  percent,
                  formatBytes(transfer.transferred),
                  formatBytes(transfer.declaredSize),
              )
          else -> context.getString(R.string.transfer_in_progress)
        }

    val notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
          buildLiveUpdate(context, title, text, percent)
        } else {
          buildCompatProgress(context, title, text, percent)
        }
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun buildLiveUpdate(
      context: Context,
      title: String,
      text: String,
      percent: Int,
  ): Notification {
    val style =
        Notification.ProgressStyle()
            // Let the declared size drive the bar; there are no separate segments or points.
            .setStyledByProgress(false)
            .setProgressIndeterminate(percent < 0)
            .setProgress(percent.coerceAtLeast(0))
    val builder =
        Notification.Builder(context, App.FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setContentIntent(contentIntent(context))
    if (percent >= 0) {
      builder.setShortCriticalText("$percent%")
    }
    // Promoted ongoing notifications are what the system (and ColorOS' Fluid Cloud) picks up as a
    // live update. The setter only exists on API 36.1+, so it is invoked reflectively rather than
    // compiled in: on plain API 36 the notification still posts as an ongoing progress card.
    runCatching {
      Notification.Builder::class
          .java
          .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
          .invoke(builder, true)
    }
    return builder.build()
  }

  private fun buildCompatProgress(
      context: Context,
      title: String,
      text: String,
      percent: Int,
  ): Notification {
    return NotificationCompat.Builder(context, App.FILE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setShowWhen(false)
        .setContentIntent(contentIntent(context))
        .build()
  }

  private fun contentIntent(context: Context): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Percentage 0..100, or -1 when the sender did not declare a size. */
  private fun percentOf(transfer: Transfer): Int {
    if (transfer.done && !transfer.failed && transfer.declaredSize > 0) {
      return 100
    }
    if (transfer.declaredSize <= 0) {
      return -1
    }
    return ((transfer.transferred * 100) / transfer.declaredSize).toInt().coerceIn(0, 100)
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) {
      return "$bytes B"
    }
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
      value /= 1024
      unit++
    }
    return if (value >= 100) {
      String.format("%.0f %s", value, units[unit])
    } else {
      String.format("%.1f %s", value, units[unit])
    }
  }
}
