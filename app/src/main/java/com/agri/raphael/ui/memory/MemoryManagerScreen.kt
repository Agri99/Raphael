package com.agri.raphael.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agri.raphael.memory.AlexandriaMemoryClient
import com.agri.raphael.memory.MemoryItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagerScreen(
    memoryClient: AlexandriaMemoryClient,
    initialDraftText: String = "",
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var memories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(initialDraftText.isNotEmpty()) }
    var draftText by remember { mutableStateOf(initialDraftText) }
    var draftIsCore by remember { mutableStateOf(false) }

    var editingMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var lastDeletedMemoryId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        coroutineScope.launch {
            isLoading = true
            memories = memoryClient.listMemories()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Shared Memories (Alexandria)") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = { refresh() }) {
                        Text("Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    draftText = ""
                    draftIsCore = false
                    showCreateDialog = true
                }
            ) {
                Text("+")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading && memories.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (memories.isEmpty()) {
                Text(
                    text = "No shared memories stored in Alexandria.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(memories, key = { it.id }) { mem ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (mem.isCore) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                if (mem.isCore) {
                                    Text(
                                        text = "[Core Profile Fact]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Text(
                                    text = mem.body,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { editingMemory = mem }) {
                                        Text("Edit")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                val success = memoryClient.deleteMemory(mem.id)
                                                if (success) {
                                                    lastDeletedMemoryId = mem.id
                                                    refresh()
                                                    val res = snackbarHostState.showSnackbar(
                                                        message = "Memory deleted",
                                                        actionLabel = "Undo",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        memoryClient.restoreMemory(mem.id)
                                                        refresh()
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Create Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Remember This") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = draftText,
                            onValueChange = { draftText = it },
                            label = { Text("Memory Fact (max 1500 chars)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = draftIsCore,
                                onCheckedChange = { draftIsCore = it }
                            )
                            Text("Mark as Core Profile Fact")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (draftText.isNotBlank()) {
                                showCreateDialog = false
                                coroutineScope.launch {
                                    memoryClient.createMemory(draftText.trim(), draftIsCore)
                                    refresh()
                                }
                            }
                        }
                    ) {
                        Text("Save Memory")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Edit Dialog
        editingMemory?.let { target ->
            var editBody by remember { mutableStateOf(target.body) }
            var editCore by remember { mutableStateOf(target.isCore) }

            AlertDialog(
                onDismissRequest = { editingMemory = null },
                title = { Text("Edit Stored Memory") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editBody,
                            onValueChange = { editBody = it },
                            label = { Text("Memory Body") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = editCore,
                                onCheckedChange = { editCore = it }
                            )
                            Text("Core Profile Fact")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editBody.isNotBlank()) {
                                editingMemory = null
                                coroutineScope.launch {
                                    memoryClient.updateMemory(target.id, editBody.trim(), editCore)
                                    refresh()
                                }
                            }
                        }
                    ) {
                        Text("Save Changes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingMemory = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
