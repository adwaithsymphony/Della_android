package com.della.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.della.app.AppContainer
import com.della.app.ui.common.DellaTopBar
import com.della.app.ui.common.DellaViewModel
import com.della.app.ui.common.LocalNav
import com.della.app.ui.common.dellaViewModel
import com.della.app.ui.common.fieldColors
import com.della.app.ui.theme.Della
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String, val basis: String? = null)

class AssistantViewModel(private val c: AppContainer) : DellaViewModel() {
    val chat = mutableStateListOf(
        ChatMessage(false, "Ask me about your business. I answer only from the records stored on this device, and I can't change anything.", "Deterministic question rules · no AI model configured"),
    )
    var thinking by mutableStateOf(false)
    val suggestions get() = c.assistant.suggestions

    fun ask(q: String) {
        if (q.isBlank() || thinking) return
        chat += ChatMessage(true, q.trim())
        thinking = true
        viewModelScope.launch {
            val a = try { c.assistant.ask(q) } catch (e: Exception) { null }
            chat += if (a == null) ChatMessage(false, "I couldn't compute that from the records.", null) else ChatMessage(false, a.text, a.basis)
            thinking = false
        }
    }
}

@Composable
fun AssistantScreen() {
    val vm = dellaViewModel { AssistantViewModel(it) }
    val nav = LocalNav.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size) { if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        DellaTopBar("Ask DELLA", onBack = { nav.popBackStack() }, subtitle = "Answers from your data only")
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(vm.chat) { _, m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Box(
                        Modifier.widthIn(max = 320.dp)
                            .background(if (m.fromUser) Della.Primary else Della.Panel, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Della.Text)
                            if (m.basis != null) Text("Source: ${m.basis}", style = MaterialTheme.typography.labelSmall, color = Della.TextMuted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            if (vm.thinking) item { Text("Calculating…", color = Della.TextMuted, style = MaterialTheme.typography.bodySmall) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.suggestions) { s ->
                AssistChip(onClick = { vm.ask(s) }, label = { Text(s) }, colors = AssistChipDefaults.assistChipColors(containerColor = Della.Panel, labelColor = Della.TextMuted))
            }
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("e.g. best sellers this week", color = Della.TextFaint) },
                singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { vm.ask(input); input = "" }),
            )
            Spacer(Modifier.padding(4.dp))
            IconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Della.Accent) }
        }
    }
}
