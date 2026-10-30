// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    // File managers shipped by the big Chinese ROMs, plus the AOSP one every device has.
    private val OPPO_FILE_MANAGERS = listOf("com.coloros.filemanager", "com.oplus.filemanager")
    private val AOSP_FILE_MANAGERS =
        listOf("com.android.documentsui", "com.google.android.documentsui")
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

  /**
   * In order: the type the sender meant, a wildcard so the system offers whatever might read it, an
   * install intent for an APK, and finally a chooser, which at least shows what is available. A
   * received file never answers a tap with nothing at all.
   */
  private fun fileIntents(name: String?): List<Intent> {
    val uri = name?.let { TransferProgressNotifier.openUriFor(it) } ?: return emptyList()
    val mime = mimeFor(uri, name)
    val out = mutableListOf<Intent>()
    // ACTION_VIEW with the archive type is what any file manager hands to the installer; the
    // dedicated install action stays as a second chance for ROMs that want it.
    out += view(uri, mime)
    if (isApk(name, mime)) {
      out +=
          Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setData(uri)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
          }
    }
    if (mime != "*/*") {
      out += view(uri, "*/*")
    }
    out += chooser(view(uri, "*/*"))
    return out
  }

  /**
   * The folder the file was received into. The phone maker's own file manager is asked first, then
   * the AOSP one, and each is given the folder as a document inside the tree and as a real path,
   * since they disagree about which one they understand.
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
    val uris = listOfNotNull(dirDocument, tree, documentPath(tree)?.let { Uri.fromFile(File(it)) })
    for (fileManager in fileManagerOrder()) {
      for (uri in uris) {
        out += view(uri, DocumentsContract.Document.MIME_TYPE_DIR).setPackage(fileManager)
      }
    }
    // Nothing answered yet: fall back to whoever wants it, chooser and all.
    for (uri in uris) {
      out += view(uri, DocumentsContract.Document.MIME_TYPE_DIR)
    }
    return out
  }

  /** The file manager this phone is likely to have first, then the AOSP one as the safety net. */
  private fun fileManagerOrder(): List<String> {
    val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
    val preferred =
        when {
          listOf("oppo", "oplus", "realme", "oneplus").any { it in brand } -> OPPO_FILE_MANAGERS
          listOf("xiaomi", "redmi", "poco").any { it in brand } ->
              listOf("com.mi.android.globalFileexplorer")
          listOf("huawei", "honor").any { it in brand } -> listOf("com.huawei.hidisk")
          "samsung" in brand -> listOf("com.sec.android.app.myfiles")
          listOf("vivo", "iqoo").any { it in brand } -> listOf("com.vivo.filemanager")
          else -> emptyList()
        }
    return preferred + AOSP_FILE_MANAGERS
  }

  private fun view(uri: Uri, mime: String): Intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK)
      }

  /** Lets the user pick an app, even when none claims the file's own type. */
  private fun chooser(target: Intent): Intent =
      Intent.createChooser(target, getString(R.string.transfer_open_with)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

  private fun isApk(name: String?, mime: String): Boolean =
      mime == "application/vnd.android.package-archive" ||
          name?.endsWith(".apk", ignoreCase = true) == true

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
        ?: "*/*"
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
