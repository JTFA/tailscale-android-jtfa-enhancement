// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for the user supplied AdGuard filter list. The list is stored as text and parsed by the
 * native engine when saved; there are no built-in rules.
 */
@Composable
fun AdBlockRulesView(onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  var text by remember { mutableStateOf(App.get().adblockRulesText()) }
  var savedText by remember { mutableStateOf(text) }
  var activeRules by remember { mutableStateOf(App.get().adblockRuleCount()) }
  var saving by remember { mutableStateOf(false) }

  Scaffold(topBar = { Header(titleRes = R.string.adblock_rules_title, onBack = onBack) }) { padding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
              text = stringResource(R.string.adblock_rules_desc),
              style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(modifier = Modifier.size(8.dp))
          OutlinedTextField(
              value = text,
              onValueChange = { text = it },
              label = { Text(stringResource(R.string.adblock_rules_title)) },
              placeholder = { Text(stringResource(R.string.adblock_rules_placeholder)) },
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
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
            Button(
                onClick = {
                  saving = true
                  scope.launch {
                    val count =
                        withContext(Dispatchers.IO) {
                          App.get().setAdblockRulesText(text)
                          App.get().applyAdBlockRules(text)
                        }
                    if (count >= 0) {
                      activeRules = count
                      savedText = text
                    }
                    saving = false
                  }
                },
                enabled = !saving && text != savedText,
            ) {
              Text(stringResource(R.string.adblock_rules_save))
            }
            TextButton(
                onClick = { text = "" },
                enabled = !saving && text.isNotEmpty(),
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
}
