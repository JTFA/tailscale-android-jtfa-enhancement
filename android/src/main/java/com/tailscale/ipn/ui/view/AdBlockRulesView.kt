// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.AdBlockRuleCompiler
import com.tailscale.ipn.AdBlockSource
import com.tailscale.ipn.AdBlockSources
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for the ad blocking rules. A rule set is made of two parts: the checked remote lists and
 * the custom rules typed below. Both are parsed by the native engine when Save is pressed.
 */
@Composable
fun AdBlockRulesView(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sources = remember { mutableStateListOf<AdBlockSource>().apply { addAll(AdBlockSources.load(context)) } }
  var text by remember { mutableStateOf(App.get().adblockRulesText()) }
  var savedText by remember { mutableStateOf(text) }
  var activeRules by remember { mutableStateOf(App.get().adblockRuleCount()) }
  var busy by remember { mutableStateOf(false) }
  var downloading by remember { mutableStateOf<String?>(null) }
  var showAddDialog by remember { mutableStateOf(false) }

  fun toast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
  }

  fun persist() {
    AdBlockSources.save(context, sources.toList())
  }

  fun updateSource(source: AdBlockSource) {
    if (busy) return
    busy = true
    downloading = source.title
    scope.launch {
      try {
        val (count, applied) =
            AdBlockRuleCompiler.refresh(
                context = context,
                sources = sources.toList(),
                customText = savedText,
                source = source,
            ) { title -> downloading = title }
        if (applied) {
          activeRules = App.get().adblockRuleCount()
        }
        toast(
            context.getString(
                if (applied) R.string.adblock_source_updated
                else R.string.adblock_source_updated_not_applied,
                source.title,
                count,
            )
        )
      } catch (e: Exception) {
        toast(e.message ?: context.getString(R.string.adblock_source_failed_fallback))
      } finally {
        busy = false
        downloading = null
      }
    }
  }

  fun save() {
    if (busy) return
    busy = true
    scope.launch {
      try {
        val count =
            AdBlockRuleCompiler.apply(
                context = context,
                sources = sources.toList(),
                customText = text,
            ) { title -> downloading = title }
        withContext(Dispatchers.IO) { App.get().setAdblockRulesText(text) }
        savedText = text
        activeRules = count
        toast(context.getString(R.string.adblock_saved_toast, count))
      } catch (e: Exception) {
        toast(e.message ?: context.getString(R.string.adblock_source_failed_fallback))
      } finally {
        busy = false
        downloading = null
      }
    }
  }

  Scaffold(
      topBar = {
        Header(titleRes = R.string.adblock_rules_screen_title, onBack = onBack)
      }
  ) { padding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
          Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                text = stringResource(R.string.adblock_sources_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.adblock_sources_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.size(4.dp))
          sources.forEachIndexed { index, source ->
            SourceRow(
                source = source,
                interactive = !busy,
                updating = downloading == source.title,
                onToggle = { enabled ->
                  sources[index] = source.copy(enabled = enabled)
                  persist()
                },
                onUpdate = { updateSource(source) },
                onDelete =
                    if (source.builtin) null
                    else {
                      {
                        sources.removeAt(index)
                        persist()
                      }
                    },
            )
            if (index < sources.lastIndex) {
              HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
            }
          }
          Spacer(modifier = Modifier.size(4.dp))
          OutlinedButton(
              onClick = { showAddDialog = true },
              enabled = !busy,
              modifier = Modifier.padding(horizontal = 14.dp),
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.adblock_source_add))
          }
          if (downloading != null) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              Spacer(modifier = Modifier.size(8.dp))
              Text(
                  text = stringResource(R.string.adblock_source_updating, downloading ?: ""),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
              text = stringResource(R.string.adblock_custom_rules),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.size(4.dp))
          Text(
              text = stringResource(R.string.adblock_rules_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.size(8.dp))
          OutlinedTextField(
              value = text,
              onValueChange = { text = it },
              placeholder = { Text(stringResource(R.string.adblock_rules_placeholder)) },
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
              text =
                  if (activeRules == 0) stringResource(R.string.adblock_rules_empty)
                  else stringResource(R.string.adblock_rules_saved, activeRules),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.size(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, enabled = !busy) {
              Text(stringResource(R.string.adblock_rules_save))
            }
            TextButton(
                onClick = { text = "" },
                enabled = !busy && text.isNotEmpty(),
            ) {
              Text(stringResource(R.string.adblock_rules_clear))
            }
          }
        }
      }

      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
              text = stringResource(R.string.adblock_rules_help_title),
              style = MaterialTheme.typography.titleSmall,
          )
          Spacer(modifier = Modifier.size(4.dp))
          Text(
              text = stringResource(R.string.adblock_rules_help),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 12.sp,
          )
        }
      }

      Spacer(modifier = Modifier.size(8.dp))
    }
  }

  if (showAddDialog) {
    AddSourceDialog(
        onDismiss = { showAddDialog = false },
        onAdd = { title, url ->
          sources.add(
              AdBlockSource(
                  id = UUID.randomUUID().toString(),
                  title = title.ifBlank { AdBlockSources.titleFromUrl(url) },
                  url = url,
                  builtin = false,
                  enabled = true,
              )
          )
          persist()
        },
        isDuplicate = { url -> sources.any { it.url.equals(url, ignoreCase = true) } },
    )
  }
}

@Composable
private fun SourceRow(
    source: AdBlockSource,
    interactive: Boolean,
    updating: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: (() -> Unit)?,
) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
        checked = source.enabled,
        onCheckedChange = onToggle,
        enabled = interactive,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
          text = source.title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = source.url,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
    if (updating) {
      CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
      IconButton(onClick = onUpdate, enabled = interactive) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = stringResource(R.string.adblock_source_update),
        )
      }
    }
    if (onDelete != null) {
      IconButton(onClick = onDelete, enabled = interactive) {
        Icon(
            Icons.Default.Delete,
            contentDescription = stringResource(R.string.adblock_source_delete),
        )
      }
    }
  }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, url: String) -> Unit,
    isDuplicate: (String) -> Boolean,
) {
  var url by remember { mutableStateOf("") }
  var title by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val invalidUrlError = stringResource(R.string.adblock_source_invalid_url)
  val duplicateError = stringResource(R.string.adblock_source_duplicate)

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.adblock_source_add)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = url,
              onValueChange = {
                url = it
                error = null
              },
              label = { Text(stringResource(R.string.adblock_source_url)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
              value = title,
              onValueChange = { title = it },
              label = { Text(stringResource(R.string.adblock_source_name)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
          error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = {
              val normalized = AdBlockSources.normalizeUrl(url)
              when {
                normalized == null -> error = invalidUrlError
                isDuplicate(normalized) -> error = duplicateError
                else -> {
                  onAdd(title, normalized)
                  onDismiss()
                }
              }
            }
        ) {
          Text(stringResource(R.string.adblock_source_add_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text(stringResource(android.R.string.cancel))
        }
      },
  )
}
