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
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.RecentsVisibility
import com.tailscale.ipn.util.TSLog
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ShareActivity is the entry point for Taildrop share intents. It is only the dimmed backdrop: the
// card itself lives in a floating window of its own (ShareSheetDialog), which is what lets Android
// blur behind the card alone. The sheet steps out of the way as soon as a device is picked: the
// transfer itself is reported by the live transfer notification.
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())
  private val scrimVisible = MutableStateFlow(true)
  private var sheet: ShareSheetDialog? = null

  private val glass: Boolean by lazy { App.get().isShareSheetGlassEnabled() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // No window transition: the card slides its own window in, and the system's own slide would
    // carry the whole window (status bar included) sideways around it.
    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
      overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
    // Translucent window: everything here is the scrim behind the card.
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
        ShareSheetScrim(visible = scrimVisible, glass = glass, onTap = { closeSheet() })
      }
    }

    showSheet()
  }

  override fun onStart() {
    super.onStart()
    // Ensure our app instance is initialized
    App.get()
    // The sheet lives in a task of its own: it is never a place to come back to from recents,
    // whatever the app-wide preference says.
    RecentsVisibility.apply(this, true)
    lifecycleScope.launch { withContext(Dispatchers.IO) { loadFiles() } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    loadFiles()
  }

  override fun onDestroy() {
    sheet?.dismiss()
    sheet = null
    super.onDestroy()
  }

  private fun showSheet() {
    val dialog =
        ShareSheetDialog(
            host = this,
            requestedTransfers = requestedTransfers,
            applicationScope = (application as App).applicationScope,
            glass = glass,
            onShareStarted = {
              // With the sheet gone, a disabled live notification would leave the tap without any
              // visible answer at all.
              if (!App.get().isLiveTransferNotificationEnabled()) {
                Toast.makeText(this, sendingMessage(), Toast.LENGTH_SHORT).show()
              }
              closeSheet()
            },
        ) {
          finish()
        }
    sheet = dialog
    dialog.show()
  }

  /** Dismisses the card and the scrim together; the activity goes away once the card has slid out. */
  private fun closeSheet() {
    scrimVisible.value = false
    val dialog = sheet
    sheet = null
    if (dialog == null) {
      finish()
    } else {
      dialog.requestClose()
    }
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

private const val SCRIM_ANIMATION_MS = 200

/**
 * Everything ShareActivity itself draws: a dim over the app the user shared from. Tapping it closes
 * the sheet, exactly like the card's Cancel button. The card sits above it in its own window.
 */
@Composable
private fun ShareSheetScrim(visible: StateFlow<Boolean>, glass: Boolean, onTap: () -> Unit) {
  val shown by visible.collectAsState()
  // The frosted card carries its own contrast, so the backdrop can stay lighter and let more of the
  // app behind it show through.
  val target = if (glass) 0.32f else 0.45f
  // The dim fades in with the card rather than flashing up at full strength for the first frame.
  var entered by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { entered = true }
  val alpha by
      animateFloatAsState(
          targetValue = if (shown && entered) target else 0f,
          animationSpec = tween(SCRIM_ANIMATION_MS),
          label = "shareScrim",
      )

  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(Color.Black.copy(alpha = alpha))
              .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
              ) {
                if (shown) onTap()
              }
  )
}
