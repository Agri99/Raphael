package com.agri.karen.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agri.karen.data.local.MessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit = {},
    onSaveMemoryRequest: (String) -> Unit = {}
) {
    LaunchedEffect(conversationId) {
        viewModel.selectConversation(conversationId)
    }

    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val listState = rememberLazyListState()

    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("K.A.R.E.N.") },
                actions = {
                    FilterChip(
                        selected = mode == "deep",
                        onClick = { viewModel.toggleMode() },
                        label = { Text(if (mode == "deep") "Deep (Extended Budget)" else "Fast") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error banner
            if (lastError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lastError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.retryLast() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onRememberThis = { onSaveMemoryRequest(msg.content) },
                        onRetry = if (msg.status == "interrupted" && msg.role == "assistant") {
                            { viewModel.retryLast() }
                        } else null
                    )
                }
            }

            // Input Bar
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask K.A.R.E.N....") },
                        maxLines = 4,
                        enabled = !isGenerating
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    if (isGenerating) {
                        Button(
                            onClick = { viewModel.stopGeneration() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    viewModel.sendUserMessage(textInput)
                                    textInput = ""
                                }
                            },
                            enabled = textInput.isNotBlank()
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    onRememberThis: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    var expandedMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable { expandedMenu = true }
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = message.content.ifEmpty { if (message.status == "streaming") "..." else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (message.status == "interrupted") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "[Interrupted]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (onRetry != null) {
                            FilledTonalButton(
                                onClick = onRetry,
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    if (onRetry != null) {
                        DropdownMenuItem(
                            text = { Text("Retry response") },
                            onClick = {
                                expandedMenu = false
                                onRetry()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remember this") },
                        onClick = {
                            expandedMenu = false
                            onRememberThis()
                        }
                    )
                }
            }
        }
    }
}
