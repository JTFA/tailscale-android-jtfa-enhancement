// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Patterns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.RecentsVisibility
import com.tailscale.ipn.util.TSLog
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ShareActivity is the entry point for Taildrop share intents. It shows the device picker as a
// floating sheet over the app that started the share, and steps out of the way as soon as a device
// is picked: the transfer itself is reported by the live transfer notification.
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Translucent window: everything on screen is the sheet and its scrim.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    @Suppress("DEPRECATION") window.statusBarColor = AndroidColor.TRANSPARENT
    @Suppress("DEPRECATION") window.navigationBarColor = AndroidColor.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    // The scrim dims everything behind the sheet, so the system bar icons have to be the light ones
    // whatever the app theme is.
    WindowInsetsControllerCompat(window, window.decorView).apply {
      isAppearanceLightStatusBars = false
      isAppearanceLightNavigationBars = false
    }

    setContent {
      AppTheme(setSystemBars = false) {
        ShareSheet(
            requestedTransfers = requestedTransfers,
            applicationScope = (application as App).applicationScope,
            onShareStarted = {
              // With the sheet gone, a disabled live notification would leave the tap without any
              // visible answer at all.
              if (!App.get().isLiveTransferNotificationEnabled()) {
                Toast.makeText(this, sendingMessage(), Toast.LENGTH_SHORT).show()
              }
            },
            onDismiss = { finish() },
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // Ensure our app instance is initialized
    App.get()
    RecentsVisibility.apply(this, App.get().isHideFromRecentsEnabled())
    lifecycleScope.launch { withContext(Dispatchers.IO) { loadFiles() } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    loadFiles()
  }

  /** What the sheet is about to send, for the toast that covers a disabled live notification. */
  private fun sendingMessage(): String {
    val files = requestedTransfers.value
    val what =
        if (files.size == 1) files[0].Name
        else getString(R.string.file_count, files.size.toString())
    return getString(R.string.share_sending, what)
  }

  // Loads the files from the intent.
  fun loadFiles() {
    if (intent == null) {
      TSLog.e(TAG, "Share failure - No intent found")
      return
    }

    val act = intent.action

    val uris: List<Uri?>? =
        when (act) {
          Intent.ACTION_SEND -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            } else {
              @Suppress("DEPRECATION")
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
          }
          Intent.ACTION_SEND_MULTIPLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
              @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)
            }
          }
          else -> {
            TSLog.e(TAG, "No extras found in intent - nothing to share")
            null
          }
        }

    val pendingFiles: MutableList<Ipn.OutgoingFile> =
        uris
            ?.filterNotNull()
            ?.mapNotNull { uri ->
              contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (cursor.moveToFirst()) {
                  val name: String = cursor.getString(nameCol) ?: generateFallbackName(uri)
                  val size: Long = cursor.getLong(sizeCol)
                  Ipn.OutgoingFile(Name = name, DeclaredSize = size).apply { this.uri = uri }
                } else {
                  TSLog.e(TAG, "Cursor is empty for URI: $uri")
                  null
                }
              }
            }
            ?.toMutableList() ?: mutableListOf()

    if (pendingFiles.isEmpty() && act == Intent.ACTION_SEND) {
      inlineShareFromIntent(intent)?.let { pendingFiles.add(it) }
    }

    if (pendingFiles.isEmpty()) {
      TSLog.e(TAG, "Share failure - no files extracted from intent")
    }

    requestedTransfers.set(pendingFiles)
  }

  private fun inlineShareFromIntent(intent: Intent): Ipn.OutgoingFile? {
    val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val text = stripChromeHighlightedTextShare(raw)
    val kind = classifyKind(text)

    return try {
      val file = InlineShare.writeToCache(applicationContext, kind, text)
      Ipn.OutgoingFile(Name = file.name, DeclaredSize = file.length()).apply {
        this.uri = Uri.fromFile(file)
        this.inlineShare = InlineShare(kind = kind, content = text)
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to write inline share: $e")
      null
    }
  }

  private fun classifyKind(text: String): InlineShare.Kind {
    // Patterns.WEB_URL covers http(s)://, ftp://, www., bare hostnames. Use .matches()
    // so a URL embedded in a sentence stays TEXT.
    if (Patterns.WEB_URL.matcher(text).matches()) {
      val scheme = runCatching { Uri.parse(text) }.getOrNull()?.scheme
      if (scheme != null && scheme != "file") return InlineShare.Kind.URL
    }
    // Opaque schemes (tel:, sms:, mailto:, ssh:, magnet:, geo:, tailscale:, …) have
    // no `//` so Patterns.WEB_URL misses them. Accept anything that's whitespace-free
    // and parses to a non-file scheme with a non-empty body. The whitespace gate keeps
    // "see: this thing" out without us having to maintain a scheme allowlist.
    if (text.none { it.isWhitespace() }) {
      val parsed = runCatching { Uri.parse(text) }.getOrNull()
      val scheme = parsed?.scheme?.lowercase()
      if (scheme != null && scheme != "file" && !parsed.schemeSpecificPart.isNullOrBlank()) {
        return InlineShare.Kind.URL
      }
    }
    return InlineShare.Kind.TEXT
  }

  // Chromium highlighted-text shares come through as `"<snippet>"\n <url>#:~:text=…`.
  // Receivers paste the blob verbatim; drop the URL tail and outer quotes.
  private fun stripChromeHighlightedTextShare(text: String): String {
    val urlLine = Regex("""(?m)^[ \t]*https?://\S*#:~:text=\S*[ \t]*$""").find(text) ?: return text
    val before = text.substring(0, urlLine.range.first).trimEnd()
    val unquoted = before.removeSurrounding("\"")
    return if (unquoted.isNotEmpty()) unquoted else text
  }

  private fun generateFallbackName(uri: Uri): String {
    val randomId = Random.nextLong()
    val mimeType = contentResolver?.getType(uri)
    val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (extension != null) "$randomId.$extension" else randomId.toString()
  }
}

private const val SHEET_ANIMATION_MS = 200

/**
 * The share sheet: a scrim over whatever the user was in, and a card that slides up from the bottom.
 * Tapping the scrim, the close button, dragging the card down or pressing back all dismiss it.
 */
@Composable
private fun ShareSheet(
    requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    applicationScope: CoroutineScope,
    onShareStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
  var shown by remember { mutableStateOf(false) }
  var closing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // One way out: the scrim and a device tap can both land, and the exit must be played once.
  val close: () -> Unit = {
    if (!closing) {
      closing = true
      shown = false
      scope.launch {
        delay(SHEET_ANIMATION_MS.toLong())
        onDismiss()
      }
    }
  }

  LaunchedEffect(Unit) { shown = true }
  BackHandler(onBack = close)

  Box(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(SHEET_ANIMATION_MS)),
        exit = fadeOut(tween(SHEET_ANIMATION_MS)),
    ) {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.45f))
                  .clickable(
                      interactionSource = remember { MutableInteractionSource() },
                      indication = null,
                      onClick = close,
                  )
      )
    }

    AnimatedVisibility(
        visible = shown,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(SHEET_ANIMATION_MS)) { height -> height },
        exit = slideOutVertically(tween(SHEET_ANIMATION_MS)) { height -> height },
    ) {
      ShareSheetCard(
          requestedTransfers = requestedTransfers,
          applicationScope = applicationScope,
          onShareStarted = {
            onShareStarted()
            close()
          },
          onClose = close,
      )
    }
  }
}

@Composable
private fun ShareSheetCard(
    requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    applicationScope: CoroutineScope,
    onShareStarted: () -> Unit,
    onClose: () -> Unit,
) {
  val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
  val dragOffset = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current

  Surface(
      modifier =
          Modifier.fillMaxWidth()
              .offset { IntOffset(0, dragOffset.value.roundToInt()) }
              .draggable(
                  orientation = Orientation.Vertical,
                  state =
                      rememberDraggableState { delta ->
                        scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                      },
                  onDragStopped = { velocity ->
                    val fling = with(density) { 96.dp.toPx() }
                    if (dragOffset.value > fling || velocity > 1_200f) {
                      onClose()
                    } else {
                      dragOffset.animateTo(0f, tween(SHEET_ANIMATION_MS))
                    }
                  },
              ),
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      color = MaterialTheme.colorScheme.surface,
  ) {
    Column(modifier = Modifier.heightIn(max = maxHeight).navigationBarsPadding()) {
      // The grab handle says the card can be pulled down, which is what it does.
      Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier.width(36.dp)
                    .height(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(2.dp),
                    )
        )
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = stringResource(R.string.share),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
          Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = stringResource(R.string.cancel),
          )
        }
      }

      TaildropView(
          requestedTransfers = requestedTransfers,
          applicationScope = applicationScope,
          onShareStarted = onShareStarted,
      )
    }
  }
}
