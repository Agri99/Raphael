package com.agri.raphael.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agri.raphael.data.ConversationRepository
import com.agri.raphael.data.local.MessageEntity
import com.agri.raphael.inference.InferenceBackend
import com.agri.raphael.inference.StreamEvent
import com.agri.raphael.settings.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.UUID

private sealed class GenerationEvent {
    data class Delta(val text: String) : GenerationEvent()
    data class Terminal(val success: Boolean, val error: String?, val wasDone: Boolean) : GenerationEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ConversationRepository,
    private val inferenceBackend: InferenceBackend,
    private val preferencesStore: PreferencesStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _mode = MutableStateFlow("fast")
    val mode: StateFlow<String> = _mode.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var activeCall: Call? = null
    private var activeRequestId: String? = null
    private var activeDraftId: String? = null
    private var activeConversationId: String? = null
    private var generationChannel: Channel<GenerationEvent>? = null

    init {
        // Load default mode preference on startup
        if (preferencesStore != null) {
            viewModelScope.launch {
                preferencesStore.selectedMode.collect { savedMode ->
                    if (savedMode in listOf("fast", "deep")) {
                        _mode.value = savedMode
                    }
                }
            }
        }
    }

    val messages: StateFlow<List<MessageEntity>> = _currentConversationId
        .flatMapLatest { convId ->
            if (convId == null) flowOf(emptyList())
            else repository.getMessages(convId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun selectConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        _lastError.value = null
    }

    fun setMode(newMode: String) {
        if (newMode in listOf("fast", "deep")) {
            _mode.value = newMode
            preferencesStore?.let {
                viewModelScope.launch { it.setSelectedMode(newMode) }
            }
        }
    }

    fun toggleMode() {
        val newMode = if (_mode.value == "fast") "deep" else "fast"
        setMode(newMode)
    }

    fun sendUserMessage(text: String) {
        if (_isGenerating.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val convId = _currentConversationId.value ?: return

        // Set generation active synchronously on caller thread to prevent rapid-send races
        _isGenerating.value = true
        _lastError.value = null

        val userMessageId = UUID.randomUUID().toString()
        val userMsg = MessageEntity(
            id = userMessageId,
            conversationId = convId,
            role = "user",
            content = trimmed,
            createdAtEpochMs = System.currentTimeMillis(),
            status = "complete"
        )

        viewModelScope.launch(ioDispatcher) {
            repository.saveMessage(userMsg)
            startAssistantGeneration(convId)
        }
    }

    private suspend fun startAssistantGeneration(convId: String) {
        _isGenerating.value = true
        _lastError.value = null
        activeConversationId = convId

        val draftId = UUID.randomUUID().toString()
        activeDraftId = draftId
        val draftMsg = MessageEntity(
            id = draftId,
            conversationId = convId,
            role = "assistant",
            content = "",
            createdAtEpochMs = System.currentTimeMillis(),
            status = "streaming",
            modelLabel = _mode.value
        )
        repository.saveMessage(draftMsg)

        // Retrieve clean recent turns for prompt assembly (only completed turns, never failed/partial drafts)
        val recentEntities = repository.getRecentMessages(convId, limit = 10)
        val messagesPayload = recentEntities
            .filter { it.status == "complete" }
            .sortedBy { it.createdAtEpochMs }
            .map { it.role to it.content }

        val reqId = UUID.randomUUID().toString()
        activeRequestId = reqId

        val channel = Channel<GenerationEvent>(Channel.UNLIMITED)
        generationChannel = channel
        val accumulatedText = StringBuilder()

        // Single serialized coroutine writer that processes deltas and terminal transition in order
        viewModelScope.launch(ioDispatcher) {
            var isTerminated = false
            for (event in channel) {
                when (event) {
                    is GenerationEvent.Delta -> {
                        if (!isTerminated) {
                            accumulatedText.append(event.text)
                            repository.updateMessage(
                                draftMsg.copy(
                                    content = accumulatedText.toString(),
                                    status = "streaming"
                                )
                            )
                        }
                    }
                    is GenerationEvent.Terminal -> {
                        if (!isTerminated) {
                            isTerminated = true
                            val finalStatus = if (event.success && event.wasDone) "complete" else "interrupted"
                            if (!event.success || !event.wasDone) {
                                if (event.error != null && event.error != "Cancelled") {
                                    _lastError.value = event.error
                                }
                            }
                            repository.updateMessage(
                                draftMsg.copy(
                                    content = accumulatedText.toString(),
                                    status = finalStatus
                                )
                            )
                            _isGenerating.value = false
                            activeCall = null
                            activeRequestId = null
                            activeDraftId = null
                            activeConversationId = null
                        }
                    }
                }
            }
        }

        activeCall = inferenceBackend.streamChat(
            requestId = reqId,
            conversationId = convId,
            mode = _mode.value,
            messages = messagesPayload,
            onEvent = { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        channel.trySend(GenerationEvent.Delta(event.text))
                    }
                    is StreamEvent.Error -> {
                        _lastError.value = "${event.errorCode}: ${event.message}"
                    }
                    is StreamEvent.Done -> {}
                    is StreamEvent.Meta -> {}
                }
            },
            onComplete = { success, error, wasDone ->
                channel.trySend(GenerationEvent.Terminal(success, error, wasDone))
                channel.close()
            }
        )
    }

    fun stopGeneration() {
        val reqId = activeRequestId
        if (reqId != null) {
            inferenceBackend.cancelRequest(reqId)
        }
        activeCall?.cancel()

        // Terminate channel to flush and write interrupted state bound to originating conversation.
        // The serialized channel writer is the SOLE owner of draft content/status updates to eliminate racing writers.
        generationChannel?.trySend(GenerationEvent.Terminal(success = false, error = "Cancelled", wasDone = false))
        generationChannel?.close()
        generationChannel = null
    }

    fun retryLast() {
        val convId = _currentConversationId.value ?: return
        if (_isGenerating.value) return

        // Synchronously acquire generation lock on caller thread to prevent rapid-retry races
        _isGenerating.value = true
        _lastError.value = null

        viewModelScope.launch(ioDispatcher) {
            // Retrieve recent messages for this conversation (ordered newest first)
            val recent = repository.getRecentMessages(convId, limit = 5)
            if (recent.isEmpty()) {
                _isGenerating.value = false
                return@launch
            }

            val newestMessage = recent.first()

            when {
                // Case 1: The newest message in the conversation is an incomplete assistant draft.
                // It belongs to the latest turn. Delete it and retry generation for this turn.
                newestMessage.role == "assistant" && newestMessage.status != "complete" -> {
                    repository.deleteMessage(newestMessage.id)
                    startAssistantGeneration(convId)
                }
                // Case 2: The newest message is a user message (assistant generation failed before completing).
                newestMessage.role == "user" -> {
                    startAssistantGeneration(convId)
                }
                // Case 3: The newest message is a completed assistant message.
                // The latest turn is already complete. Do not delete older historical interrupted messages.
                else -> {
                    _isGenerating.value = false
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val repository: ConversationRepository,
    private val backend: InferenceBackend,
    private val preferencesStore: PreferencesStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(repository, backend, preferencesStore, ioDispatcher) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
