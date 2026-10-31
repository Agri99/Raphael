package com.agri.karen.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agri.karen.memory.CoreMemoryClient
import com.agri.karen.memory.MemoryItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagerScreen(
    memoryClient: CoreMemoryClient,
    initialDraftText: String = "",
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var memories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Dialog states
    var showCreateDialog by remember { mutableStateOf(initialDraftText.isNotEmpty()) }
    var draftText by remember { mutableStateOf(initialDraftText) }
    var draftIsCore by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    var editingMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        coroutineScope.launch {
            isLoading = true
            loadError = null
            val result = memoryClient.listMemories()
            if (result.isSuccess) {
                memories = result.getOrDefault(emptyList())
            } else {
                loadError = result.exceptionOrNull()?.message ?: "Failed to reach C.O.R.E."
            }
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
                title = { Text("Shared Memories (C.O.R.E.)") },
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
                    createError = null
                    showCreateDialog = true
                }
            ) {
                Text("+")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error banner if list fetch failed
            if (loadError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection error: $loadError",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { refresh() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading && memories.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (memories.isEmpty() && loadError == null) {
                    Text(
                        text = "No shared memories stored in C.O.R.E.",
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
                                        TextButton(onClick = {
                                            editError = null
                                            editingMemory = mem
                                        }) {
                                            Text("Edit")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val res = memoryClient.deleteMemory(mem.id)
                                                    if (res.isSuccess) {
                                                        refresh()
                                                        val snack = snackbarHostState.showSnackbar(
                                                            message = "Memory deleted",
                                                            actionLabel = "Undo",
                                                            duration = SnackbarDuration.Short
                                                        )
                                                        if (snack == SnackbarResult.ActionPerformed) {
                                                            memoryClient.restoreMemory(mem.id)
                                                            refresh()
                                                        }
                                                    } else {
                                                        snackbarHostState.showSnackbar(
                                                            message = "Failed to delete: ${res.exceptionOrNull()?.message}"
                                                        )
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
        }

        // Create Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Remember This") },
                text = {
                    Column {
                        if (createError != null) {
                            Text(
                                text = createError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = draftText,
                            onValueChange = {
                                draftText = it
                                createError = null
                            },
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
                                coroutineScope.launch {
                                    val res = memoryClient.createMemory(draftText.trim(), draftIsCore)
                                    if (res.isSuccess) {
                                        showCreateDialog = false
                                        refresh()
                                    } else {
                                        createError = res.exceptionOrNull()?.message ?: "Failed to save memory."
                                    }
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
                        if (editError != null) {
                            Text(
                                text = editError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = editBody,
                            onValueChange = {
                                editBody = it
                                editError = null
                            },
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
                                coroutineScope.launch {
                                    val res = memoryClient.updateMemory(target.id, editBody.trim(), editCore)
                                    if (res.isSuccess) {
                                        editingMemory = null
                                        refresh()
                                    } else {
                                        editError = res.exceptionOrNull()?.message ?: "Failed to update memory."
                                    }
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
