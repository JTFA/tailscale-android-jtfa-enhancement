// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One remote filter list the user can enable. */
@Serializable
data class AdBlockSource(
    val id: String,
    val title: String,
    val url: String,
    val builtin: Boolean = false,
    val enabled: Boolean = false,
)

/** Raised when a downloaded list cannot be used. The message is user facing. */
class AdBlockSourceException(message: String) : Exception(message)

/**
 * Persistence for the user's rule sources and for the last rule text that was applied. The applied
 * text is cached so that starting the app never requires the network.
 */
object AdBlockSources {
  private const val PREFS = "adblock_sources"
  private const val SOURCES_KEY = "sources"
  private const val CACHE_DIR = "adblock"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Mainstream lists. GitHub hosted lists use jsDelivr because raw.githubusercontent.com is often
   * unreachable on mobile networks. None of them is enabled by default: nothing is blocked until
   * the user chooses.
   */
  fun defaults(): List<AdBlockSource> =
      listOf(
          AdBlockSource(
              id = "adguard-dns",
              title = "AdGuard DNS filter",
              url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
              builtin = true,
          ),
          AdBlockSource(
              id = "adguard-base",
              title = "AdGuard Base filter",
              url = "https://filters.adtidy.org/extension/ublock/filters/2.txt",
              builtin = true,
          ),
          AdBlockSource(
              id = "adaway",
              title = "AdAway hosts",
              url = "https://adaway.org/hosts.txt",
              builtin = true,
          ),
          AdBlockSource(
              id = "stevenblack",
              title = "StevenBlack hosts",
              url = "https://fastly.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
              builtin = true,
          ),
          AdBlockSource(
              id = "1hosts-lite",
              title = "1Hosts Lite",
              url = "https://fastly.jsdelivr.net/gh/badmojr/1Hosts@master/Lite/hosts.txt",
              builtin = true,
          ),
          AdBlockSource(
              id = "oisd-small",
              title = "OISD small",
              url = "https://small.oisd.nl/",
              builtin = true,
          ),
      )

  fun load(context: Context): List<AdBlockSource> {
    val raw = prefs(context).getString(SOURCES_KEY, null) ?: return defaults()
    return try {
      json.decodeFromString<List<AdBlockSource>>(raw)
    } catch (e: Exception) {
      defaults()
    }
  }

  fun save(context: Context, sources: List<AdBlockSource>) {
    prefs(context).edit().putString(SOURCES_KEY, json.encodeToString(sources)).apply()
  }

  /** Looks up a valid http(s) URL and returns it normalized, or null. */
  fun normalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)) {
      return null
    }
    return try {
      val uri = URL(trimmed).toURI()
      if (uri.host.isNullOrBlank()) null else trimmed
    } catch (e: Exception) {
      null
    }
  }

  fun titleFromUrl(url: String): String =
      try {
        URL(url).host ?: url
      } catch (e: Exception) {
        url
      }

  /**
   * Alternative URLs to try when the direct download fails. GitHub raw content and the default
   * jsDelivr host are frequently reset or blackholed on some networks; jsDelivr's Fastly/Gcore
   * endpoints and gh-proxy serve the same bytes.
   */
  fun mirrorUrls(url: String): List<String> {
    val mirrors = mutableListOf<String>()
    val raw = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
    val github = Regex("""^https://github\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)/(.+)$""")
    val match = raw.find(url) ?: github.find(url)
    if (match != null) {
      val (owner, repo, branch, path) = match.destructured
      mirrors += "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path"
      mirrors += "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path"
      mirrors += "https://gh-proxy.com/https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
    } else if (url.startsWith("https://cdn.jsdelivr.net/")) {
      mirrors += url.replaceFirst("https://cdn.jsdelivr.net/", "https://fastly.jsdelivr.net/")
      mirrors += url.replaceFirst("https://cdn.jsdelivr.net/", "https://gcore.jsdelivr.net/")
    }
    return mirrors.distinct().filter { it != url }
  }

  fun readCombinedCache(context: Context): String? {
    val file = combinedCacheFile(context)
    return try {
      if (file.isFile) file.readText() else null
    } catch (e: Exception) {
      null
    }
  }

  fun writeCombinedCache(context: Context, text: String) {
    writeAtomically(combinedCacheFile(context), text)
  }

  internal fun readSourceCache(context: Context, url: String): String? {
    val file = sourceCacheFile(context, url)
    return try {
      if (file.isFile) file.readText() else null
    } catch (e: Exception) {
      null
    }
  }

  internal fun writeSourceCache(context: Context, url: String, text: String) {
    writeAtomically(sourceCacheFile(context, url), text)
  }

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun cacheDir(context: Context) = File(context.applicationContext.filesDir, CACHE_DIR)

  private fun combinedCacheFile(context: Context) = File(cacheDir(context), "combined.txt")

  private fun sourceCacheFile(context: Context, url: String) =
      File(File(cacheDir(context), "sources"), sha256(url) + ".txt")

  private fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (target.exists()) target.delete()
    if (!tmp.renameTo(target)) {
      target.writeText(text)
      tmp.delete()
    }
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }
}

/**
 * Downloads the enabled lists, validates each one, prepends them to the user's custom rules and
 * hands the result to the native engine.
 *
 * Cached copies are reused on Save so that re-applying does not download every list again; each
 * row has its own Update action that forces a fresh download.
 */
object AdBlockRuleCompiler {
  private const val MAX_SOURCE_BYTES = 5 * 1024 * 1024

  suspend fun apply(
      context: Context,
      sources: List<AdBlockSource>,
      customText: String,
      onProgress: (String) -> Unit = {},
  ): Int =
      withContext(Dispatchers.IO) {
        compile(context, sources, customText, emptyMap(), onProgress)
      }

  /**
   * Forces a fresh download of [source]. When the source is enabled, the active rule set is
   * rebuilt immediately. Returns the number of rules in that source and whether they were applied.
   */
  suspend fun refresh(
      context: Context,
      sources: List<AdBlockSource>,
      customText: String,
      source: AdBlockSource,
      onProgress: (String) -> Unit = {},
  ): Pair<Int, Boolean> =
      withContext(Dispatchers.IO) {
        onProgress(source.title)
        val text = download(source)
        AdBlockSources.writeSourceCache(context, source.url, text)
        val count = countRules(source, text)
        if (!source.enabled) {
          count to false
        } else {
          val applied = compile(context, sources, customText, mapOf(source.url to text), onProgress)
          applied to true
        }
      }

  private suspend fun compile(
      context: Context,
      sources: List<AdBlockSource>,
      customText: String,
      forced: Map<String, String>,
      onProgress: (String) -> Unit,
  ): Int {
    val parts = mutableListOf<String>()
    for (source in sources.filter { it.enabled }) {
      val text =
          forced[source.url]
              ?: AdBlockSources.readSourceCache(context, source.url)
              ?: download(source, onProgress).also {
                AdBlockSources.writeSourceCache(context, source.url, it)
              }
      // Guards against a truncated cache file as well as a bad download.
      countRules(source, text)
      parts += text
    }

    val combined = buildString {
      for (part in parts) {
        append(part)
        append("\n\n")
      }
      append(customText)
    }
    val count = App.get().applyAdBlockRules(combined)
    if (count < 0) {
      throw AdBlockSourceException("Rules: could not be applied")
    }
    AdBlockSources.writeCombinedCache(context, combined)
    return count
  }

  private fun countRules(source: AdBlockSource, text: String): Int {
    val count = App.get().countAdBlockRules(text)
    if (count <= 0) {
      throw AdBlockSourceException("${source.title}: no valid rules found")
    }
    return count
  }

  private class DownloadHttpException(val status: Int) : Exception("HTTP $status")

  private fun download(source: AdBlockSource, onProgress: (String) -> Unit = {}): String {
    onProgress(source.title)
    val urls = listOf(source.url) + AdBlockSources.mirrorUrls(source.url)
    var lastFailure: String? = null
    for (url in urls) {
      try {
        return downloadOnce(source.title, url)
      } catch (e: DownloadHttpException) {
        // A 4xx means the path is wrong; another host will not help.
        if (e.status in 400..499) {
          throw AdBlockSourceException("${source.title}: HTTP ${e.status}")
        }
        lastFailure = e.message
      } catch (e: Exception) {
        lastFailure = e.message ?: "download failed"
      }
    }
    val suffix = if (urls.size > 1) " (direct and mirror URLs failed)" else ""
    throw AdBlockSourceException("${source.title}: ${lastFailure ?: "download failed"}$suffix")
  }

  private fun downloadOnce(title: String, url: String): String {
    var conn: HttpURLConnection? = null
    try {
      conn =
          (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Tailscale-Android-AdBlock/1.0")
            setRequestProperty("Accept", "text/plain, text/*;q=0.9, */*;q=0.5")
          }
      val code = conn.responseCode
      if (code !in 200..299) {
        throw DownloadHttpException(code)
      }
      val contentType = conn.contentType?.lowercase().orEmpty()
      if (contentType.startsWith("image/") ||
          contentType.startsWith("audio/") ||
          contentType.startsWith("video/") ||
          contentType.contains("application/pdf") ||
          contentType.contains("application/zip") ||
          contentType.contains("application/json") ||
          contentType.contains("text/html")) {
        throw AdBlockSourceException("not a plain text list")
      }

      val bytes =
          conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              total += read
              if (total > MAX_SOURCE_BYTES) {
                throw AdBlockSourceException("list is larger than 5 MB")
              }
              out.write(buffer, 0, read)
            }
            out.toByteArray()
          }
      if (bytes.any { it == 0.toByte() }) {
        throw AdBlockSourceException("binary content")
      }
      val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
      if (looksLikeMarkup(text)) {
        throw AdBlockSourceException("not a filter list")
      }
      return text
    } catch (e: DownloadHttpException) {
      throw e
    } catch (e: AdBlockSourceException) {
      throw e
    } catch (e: Exception) {
      throw AdBlockSourceException(e.message ?: "download failed")
    } finally {
      conn?.disconnect()
    }
  }

  /** Rejects HTML/XML/JSON pages that a server might return instead of the list. */
  private fun looksLikeMarkup(text: String): Boolean {
    val head = text.trimStart().take(512).lowercase()
    return head.startsWith("<!doctype") ||
        head.startsWith("<html") ||
        head.startsWith("<?xml") ||
        head.startsWith("{") ||
        (head.startsWith("[") && !head.startsWith("[adblock"))
  }
}
