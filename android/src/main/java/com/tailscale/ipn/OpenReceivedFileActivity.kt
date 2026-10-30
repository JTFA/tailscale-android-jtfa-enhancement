// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier
import com.tailscale.ipn.util.TSLog
import java.io.File

/**
 * Launches a received file, or the folder it landed in, from its live card, and takes the card down
 * straight away.
 *
 * The notification cannot point straight at the viewer: the card has to be withdrawn once the file
 * is opened, and only code of ours knows when that happened. A broadcast receiver is not an option,
 * because starting an activity from one is silently blocked as a background launch. An activity
 * opened by the notification is a user-initiated launch, so it may start the viewer.
 *
 * The card is cancelled as this activity starts, before the launch: the system has already resolved
 * the tap into this activity at that point, so its transition does not depend on the notification.
 */
class OpenReceivedFileActivity : Activity() {
  companion object {
    const val EXTRA_FOLDER = "folder"

    // AOSP Files (DocumentsUI), then the file managers shipped by the big Chinese ROMs.
    private val FILE_MANAGERS =
        listOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.coloros.filemanager",
            "com.oplus.filemanager",
            "com.android.fileexplorer",
            "com.mi.android.globalFileexplorer",
        )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val name = intent.getStringExtra(LiveTransferActionReceiver.EXTRA_NAME)
    val toFolder = intent.getBooleanExtra(EXTRA_FOLDER, false)
    // Candidates are tried in order: the first one a file manager accepts wins. Querying first
    // would be wrong, since package visibility hides handlers of other apps.
    val targets = if (toFolder) folderIntents() else fileIntents(name)
    for (target in targets) {
      try {
        launch(target)
        if (!toFolder) {
          TransferProgressNotifier.onOpenAction(name)
        }
        finish()
        overridePendingTransition(0, 0)
        return
      } catch (e: ActivityNotFoundException) {
        TSLog.d("OpenReceivedFileActivity", "nothing handles ${target.data}; trying next")
      } catch (e: Exception) {
        TSLog.w("OpenReceivedFileActivity", "could not open ${target.data}: $e")
      }
    }
    val message = if (toFolder) R.string.transfer_no_file_manager else R.string.transfer_no_app
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    finish()
  }

  private fun fileIntents(name: String?): List<Intent> {
    val uri = name?.let { TransferProgressNotifier.openUriFor(it) } ?: return emptyList()
    return listOf(view(uri, mimeFor(uri, name)))
  }

  /**
   * The folder the file was received into. File managers are addressed by package, one at a time:
   * an implicit intent for a folder is understood by several apps on this ROM, so the system answers
   * with a chooser instead of opening anything. The AOSP Files app (DocumentsUI) can open a folder
   * document, which is the closest thing to "show me this folder" Android offers.
   */
  private fun folderIntents(): List<Intent> {
    val tree = TaildropDirectoryStore.loadSavedDir() ?: return emptyList()
    val out = mutableListOf<Intent>()
    val dirDocument =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }
            .getOrNull()
            ?.let { id ->
              runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
            }
    val uris = listOfNotNull(dirDocument, tree)
    for (fileManager in FILE_MANAGERS) {
      for (uri in uris) {
        out += view(uri, DocumentsContract.Document.MIME_TYPE_DIR).setPackage(fileManager)
      }
    }
    // Nothing answered yet: fall back to whoever wants it, chooser and all, then to a real path.
    for (uri in uris) {
      out += view(uri, DocumentsContract.Document.MIME_TYPE_DIR)
    }
    documentPath(tree)?.let {
      out += view(Uri.fromFile(File(it)), DocumentsContract.Document.MIME_TYPE_DIR)
    }
    return out
  }

  private fun view(uri: Uri, mime: String): Intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK)
      }

  /** file:// is refused for apps targeting a recent API, but a file manager that needs a path needs it. */
  private fun launch(target: Intent) {
    if (target.data?.scheme != "file") {
      startActivity(target)
      return
    }
    val previous = StrictMode.getVmPolicy()
    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    try {
      startActivity(target)
    } finally {
      StrictMode.setVmPolicy(previous)
    }
  }

  /**
   * Go writes every received file as application/octet-stream, so the provider cannot say what an
   * APK or a text file is and the system offers a chooser, or nothing at all. Fall back to the
   * extension, which is what the sender meant.
   */
  private fun mimeFor(uri: Uri, name: String?): String {
    val declared = runCatching { contentResolver.getType(uri) }.getOrNull()
    if (declared != null && declared != "application/octet-stream") {
      return declared
    }
    val fileName =
        name ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty()
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: declared
        ?: "application/octet-stream"
  }

  /** The real path behind a shared-storage tree, when it has one. */
  private fun documentPath(tree: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
    val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return null
    val volume = id.substringBefore(':', "")
    val relative = id.substringAfter(':', "")
    if (relative.isEmpty()) {
      return null
    }
    val base = if (volume == "primary" || volume == "home") root else "/storage/$volume"
    return "$base/$relative"
  }
}
