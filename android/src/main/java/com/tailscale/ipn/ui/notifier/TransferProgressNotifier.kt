// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
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

  // Request promotion into a live update. API 36.1 added Notification.Builder#requestPromotedOngoing,
  // but the request itself is just an extra, which compiles against API 36 and is honored on any
  // release that understands it.
  private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

  private var appContext: Context? = null
  private var scope: CoroutineScope? = null
  private var collectJob: Job? = null
  private var holdJob: Job? = null
  private var loggedKey: String? = null
  // Writer-driven receive state; see onReceiveStreamOpened.
  private var writerName: String? = null
  private var writerTransfer: Transfer? = null
  // Names whose receive card the writer stream already finished, so a later bus message about the
  // same transfer does not post the completion twice.
  private val completedReceiveNames = mutableSetOf<String>()
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
    val active = candidates.lastOrNull { it.Done != true && it.Name !in completedReceiveNames }
    if (active != null) {
      return active.toTransfer(Direction.RECEIVE, done = false)
    }
    // A finished entry is not required to have been seen active first: a fast transfer (or a slow
    // bus) can deliver only the final notification, and skipping it used to leave the user with no
    // receiving notification at all. Go drops the entry right after this notify, so anything with
    // Done=true here is a transfer that just ended. Names that the writer stream already completed
    // are skipped: that card is on screen and must not be posted a second time.
    val finished = candidates.lastOrNull { it.Done == true && it.Name !in completedReceiveNames }
    return finished?.toTransfer(Direction.RECEIVE, done = true)
  }

  /**
   * Called by ShareFileHelper when Go starts writing a received file. The bus notification that
   * carries IncomingFiles can be missed entirely (a short transfer can start and finish before the
   * watcher or the flow catches up), but this callback is the stream Go writes into, so it always
   * fires. Post the card now; the bus refines it with the declared size and progress, and
   * [onReceiveStreamClosed] turns it into the completion.
   */
  fun onReceiveStreamOpened(name: String) {
    if (InlineShare.matches(name)) {
      return
    }
    val busFile = Notifier.incomingFiles.value?.firstOrNull { it.Name == name }
    writerName = name
    completedReceiveNames -= name
    val transfer =
        Transfer(
            "in-stream:$name",
            name,
            Direction.RECEIVE,
            busFile?.Received ?: 0L,
            busFile?.DeclaredSize ?: -1L,
            done = false,
        )
    writerTransfer = transfer
    completedKeys -= transfer.key
    holdJob?.cancel()
    holdJob = null
    post(transfer, complete = false)
  }

  /**
   * The receive stream closed, which happens right before Go reports the completion. Show the end
   * state on the same card instead of withdrawing it and letting the bus post a second one: a fast
   * transfer otherwise flashes through a cancel and re-post.
   */
  fun onReceiveStreamClosed(name: String) {
    if (writerName != name) {
      return
    }
    writerName = null
    val placeholder = writerTransfer ?: return
    writerTransfer = null
    completedReceiveNames += name
    if (completedReceiveNames.size > 64) {
      completedReceiveNames.clear()
    }
    val done =
        placeholder.copy(
            transferred = if (placeholder.declaredSize > 0) placeholder.declaredSize
                          else placeholder.transferred,
            done = true,
        )
    if (!completedKeys.add(done.key)) {
      return
    }
    post(done, complete = true)
    scheduleHold(done.key)
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
    scheduleHold(transfer.key)
  }

  private fun scheduleHold(key: String) {
    holdJob?.cancel()
    holdJob =
        scope?.launch {
          delay(HOLD_AFTER_COMPLETE_MS)
          cancel()
          activeKeys -= key
          // The key stays in completedKeys: a repeat of the same finished entry (the bus can emit it
          // again) must not resurrect the card. Keys are per transfer, so this only grows slowly.
          if (completedKeys.size > 64) {
            completedKeys.clear()
          }
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
    logPromotionState(context, notification, transfer.key)
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
    builder.addExtras(
        android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) }
    )
    val notification = builder.build()
    return notification
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

  private fun logPromotionState(context: Context, notification: Notification, key: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA || loggedKey == key) {
      return
    }
    loggedKey = key
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      val manager = context.getSystemService(NotificationManager::class.java)
      TSLog.d(
          TAG,
          "live update: promotable=${notification.hasPromotableCharacteristics()} " +
              "allowed=${manager?.canPostPromotedNotifications()}")
    }
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
