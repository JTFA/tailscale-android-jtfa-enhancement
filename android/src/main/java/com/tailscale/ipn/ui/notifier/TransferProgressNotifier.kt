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
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.LiveTransferActionReceiver
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.ShareFileHelper
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
 * taildrop code, so both directions animate while the bytes move. A receive additionally gets a
 * start-of-transfer card from the stream Go writes the file into, because a short transfer can
 * finish before the bus reports anything at all.
 *
 * Tapping the card does nothing while the bytes move: the file does not exist yet, and a send has
 * nothing local to open. A finished receive opens the received file. When the user asks to keep
 * the finished card, it stays as a "Received" card with Open and Close actions.
 */
object TransferProgressNotifier {
  private const val TAG = "TransferProgressNotifier"
  // A single transfer is shown at a time, so one fixed id is enough.
  private const val NOTIFICATION_ID = 0x7461
  // A live update that vanishes the instant it hits 100% looks like a failed transfer, so the
  // finished state sits on screen for a moment before it is withdrawn.
  private const val HOLD_AFTER_COMPLETE_MS = 5_000L
  // Long enough for the system's expand/app-open transition to play out before the card is
  // withdrawn, short enough that the card is gone by the time the viewer is usable.
  private const val OPEN_CLOSE_DELAY_MS = 900L
  // Request promotion into a live update. API 36.1 added Notification.Builder#requestPromotedOngoing,
  // but the request itself is just an extra, which compiles against API 36 and is honored on any
  // release that understands it.
  private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
  // A Taildrop sender's node id, as Go appends it to a partial file name: "n12345CNTRL".
  private val NODE_ID = Regex("^n[0-9A-Za-z]{1,32}$")

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

  /** An action button on the card. */
  private class CardAction(val titleRes: Int, val intent: PendingIntent)

  /** Everything that decides how one posting of the card looks. */
  private class Card(
      val title: String,
      val text: String,
      val percent: Int,
      // Null means tapping the card does nothing, which is what the user asked for while a transfer
      // is running and for every send.
      val tap: PendingIntent?,
      val actions: List<CardAction>,
      val chipText: String?,
  )

  private var appContext: Context? = null
  private var scope: CoroutineScope? = null
  private var collectJob: Job? = null
  private var holdJob: Job? = null
  private var loggedKey: String? = null
  // Writer-driven receive state; see onReceiveStreamOpened.
  private var writerName: String? = null
  private var writerTransfer: Transfer? = null
  private var lastCompletedReceive: Transfer? = null
  // Final URI per received file, filled in by the rename callback.
  private val receivedUris = mutableMapOf<String, String>()
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
   * Called when the user flips a setting. Turning live notifications off withdraws whatever is on
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

  /** The user pressed Close on a kept "Received" card. */
  /** The user pressed Close on a kept "Received" card. */
  fun onCloseAction() {
    holdJob?.cancel()
    holdJob = null
    lastCompletedReceive = null
    cancel()
  }

  /**
   * The user pressed Open. The card is withdrawn only after the system has finished animating the
   * app it launched into place; cancelling immediately pulls the card out from under that
   * transition.
   */
  fun onOpenAction() {
    val appScope = scope
    if (appScope == null) {
      onCloseAction()
      return
    }
    appScope.launch {
      delay(OPEN_CLOSE_DELAY_MS)
      onCloseAction()
    }
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
    val baseName = baseNameOf(name)
    if (InlineShare.matches(baseName)) {
      return
    }
    val busFile = Notifier.incomingFiles.value?.lastOrNull { it.Name == baseName }
    writerName = name
    completedReceiveNames -= baseName
    val transfer =
        Transfer(
            "in-stream:$name",
            baseName,
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
    // Keyed by the visible name: bus entries and Rename both use it, the writer name does not.
    completedReceiveNames += placeholder.name
    if (completedReceiveNames.size > 64) {
      completedReceiveNames.clear()
    }
    val done =
        placeholder.copy(
            transferred =
                if (placeholder.declaredSize > 0) placeholder.declaredSize
                else placeholder.transferred,
            done = true,
        )
    lastCompletedReceive = done
    if (!completedKeys.add(done.key)) {
      return
    }
    if (!post(done, complete = true)) {
      scheduleHold(done.key)
    }
  }

  /**
   * Go renamed the received file into place, so its final URI is known. Whatever is on screen is
   * refreshed with it; the caller can ask [handlesReceivedNotification] whether the standard
   * "file received" notification is redundant.
   */
  fun onReceiveCompleted(name: String, uri: Uri) {
    receivedUris[name] = uri.toString()
    if (receivedUris.size > 64) {
      receivedUris.clear()
    }
    val last = lastCompletedReceive
    if (last == null || !sameReceive(last.name, name)) {
      TSLog.d(TAG, "finished receive $name has no live card")
      return
    }
    // The rename knows the real name, which beats whatever the writer name suggested.
    lastCompletedReceive = last.copy(name = name)
    post(lastCompletedReceive!!, complete = true)
  }

  /**
   * Whether the live card speaks for a finished receive, in which case the standard "file received"
   * notification would only say the same thing twice. Answered from the setting rather than from
   * whatever happens to be on screen: a transfer that finishes before the watcher catches up still
   * gets its card, and the two notifications must not race for the same file.
   */
  fun handlesReceivedNotification(): Boolean {
    val context = appContext ?: return false
    return App.get().isKeepReceivedTransferNotificationEnabled() && canPost(context)
  }

  /** The file a kept card's Open action or tap should launch. */
  fun openUriFor(name: String): Uri? {
    receivedUris[name]?.let {
      return Uri.parse(it)
    }
    // The rename callback may not have arrived (or the app may have been restarted since), so ask
    // the SAF directory for the file by name as a fallback.
    return runCatching { Uri.parse(ShareFileHelper.getFileURI(name)) }.getOrNull()
  }

  /** True when [a] and [b] name the same received file, allowing for the writer's partial suffix. */
  private fun sameReceive(a: String, b: String): Boolean =
      a == b || a.startsWith("$b.") || b.startsWith("$a.")

  /**
   * Go names the writer after the partial file it writes into ("name.partial", or
   * "name.<clientID>.partial" when the sender has a node id) and only renames it to the bare name at
   * the very end. Everything the bus and the rename report uses the bare name, so map back before
   * comparing: the writer name never equals the finished file's name.
   */
  private fun baseNameOf(writerName: String): String {
    if (!writerName.endsWith(".partial")) {
      return writerName
    }
    Notifier.incomingFiles
        .value
        ?.map { it.Name }
        ?.lastOrNull { writerName.startsWith("$it.") }
        ?.let {
          return it
        }
    // No bus entry to go on (a zero-byte or very short transfer never reports one): drop the
    // suffix and the node id that precedes it, when what is left looks like a node id.
    val stem = writerName.removeSuffix(".partial")
    val dot = stem.lastIndexOf('.')
    if (dot > 0 && NODE_ID.matches(stem.substring(dot + 1))) {
      return stem.substring(0, dot)
    }
    return stem
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
    if (!post(transfer, complete = true)) {
      scheduleHold(transfer.key)
    }
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

  private fun post(transfer: Transfer, complete: Boolean): Boolean {
    val context = appContext ?: return false
    if (!canPost(context)) {
      return false
    }

    // A finished receive stays on screen as a "Received" card when the user asked for that. It is
    // decided here rather than by the rename callback: whichever path reports the completion (the
    // write stream or the bus) has to end up with the same card.
    val keep =
        complete &&
            !transfer.failed &&
            transfer.direction == Direction.RECEIVE &&
            App.get().isKeepReceivedTransferNotificationEnabled()
    val percent =
        when {
          // A finished, unfailed transfer is 100% even when the sender never declared a size.
          complete && !transfer.failed -> 100
          else -> percentOf(transfer)
        }
    val title =
        when {
          keep -> context.getString(R.string.transfer_received)
          transfer.direction == Direction.SEND -> context.getString(R.string.transfer_sending)
          else -> context.getString(R.string.transfer_receiving)
        }
    val text =
        when {
          keep -> transfer.name
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
    // Open and tap both go through the receiver, which resolves the file when pressed and only
    // withdraws the card once the app it launched has its window.
    val openAction = openByNameIntent(context, transfer.name)
    val actions =
        if (keep) {
          listOf(
              CardAction(R.string.transfer_open, openAction),
              CardAction(R.string.transfer_close, closeIntent(context)),
          )
        } else {
          emptyList()
        }
    val card =
        Card(
            title = title,
            text = text,
            percent = percent,
            // Nothing opens while bytes move, and a send never has a local file to open. A finished
            // receive opens the file, however late its URI became known.
            tap = if (complete && !transfer.failed && transfer.direction == Direction.RECEIVE) openAction else null,
            actions = actions,
            chipText =
                when {
                  // The chip is the whole card while it is collapsed, and "100%" there reads like a
                  // transfer that never finished.
                  keep -> context.getString(R.string.transfer_received)
                  percent >= 0 -> "$percent%"
                  else -> null
                },
        )

    val notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
          buildLiveUpdate(context, card)
        } else {
          buildCompatProgress(context, card)
        }
    logPromotionState(context, notification, transfer.key)
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    if (keep) {
      holdJob?.cancel()
      holdJob = null
    }
    return keep
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun buildLiveUpdate(context: Context, card: Card): Notification {
    val style =
        Notification.ProgressStyle()
            // Let the declared size drive the bar; there are no separate segments or points.
            .setStyledByProgress(false)
            .setProgressIndeterminate(card.percent < 0)
            .setProgress(card.percent.coerceAtLeast(0))
    val builder =
        Notification.Builder(context, App.FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
    card.tap?.let { builder.setContentIntent(it) }
    card.chipText?.let { builder.setShortCriticalText(it) }
    for (action in card.actions) {
      builder.addAction(
          Notification.Action.Builder(
                  null as Icon?,
                  context.getString(action.titleRes),
                  action.intent,
              )
              .build()
      )
    }
    builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
    return builder.build()
  }

  private fun buildCompatProgress(context: Context, card: Card): Notification {
    val builder =
        NotificationCompat.Builder(context, App.FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setProgress(100, card.percent.coerceAtLeast(0), card.percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
    card.tap?.let { builder.setContentIntent(it) }
    for (action in card.actions) {
      builder.addAction(NotificationCompat.Action(0, context.getString(action.titleRes), action.intent))
    }
    return builder.build()
  }

  private fun canPost(context: Context): Boolean {
    if (!App.get().isLiveTransferNotificationEnabled()) {
      cancel()
      return false
    }
    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      TSLog.d(TAG, "POST_NOTIFICATIONS not granted; skipping transfer notification")
      return false
    }
    return true
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

  /**
   * Opens a received file by name, resolved at press time: the card can appear before the rename
   * reports the final URI, and the button has to work whenever it is pressed.
   */
  private fun openByNameIntent(context: Context, name: String): PendingIntent {
    val intent =
        Intent(context, LiveTransferActionReceiver::class.java)
            .setAction(LiveTransferActionReceiver.ACTION_OPEN)
            .putExtra(LiveTransferActionReceiver.EXTRA_NAME, name)
    return PendingIntent.getBroadcast(
        context,
        NOTIFICATION_ID + 1,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Removes a kept card, see [onCloseAction]. */
  private fun closeIntent(context: Context): PendingIntent {    val intent =
        Intent(context, LiveTransferActionReceiver::class.java)
            .setAction(LiveTransferActionReceiver.ACTION_CLOSE)
    return PendingIntent.getBroadcast(
        context,
        NOTIFICATION_ID,
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
