package com.agri.raphael.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agri.raphael.data.ConversationRepository
import com.agri.raphael.data.local.ConversationEntity
import com.agri.raphael.data.local.MessageEntity
import com.agri.raphael.inference.InferenceBackend
import com.agri.raphael.inference.RemoteInferenceBackend
import com.agri.raphael.inference.StreamEvent
import com.agri.raphael.settings.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.UUID

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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val convId = _currentConversationId.value ?: return

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

        // Retrieve clean recent turns for prompt assembly (only completed turns, never failed drafts)
        val recentEntities = repository.getRecentMessages(convId, limit = 10)
        val messagesPayload = recentEntities
            .filter { it.status == "complete" }
            .map { it.role to it.content }

        val reqId = UUID.randomUUID().toString()
        activeRequestId = reqId

        val accumulatedText = StringBuilder()

        activeCall = inferenceBackend.streamChat(
            requestId = reqId,
            conversationId = convId,
            mode = _mode.value,
            messages = messagesPayload,
            onEvent = { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        accumulatedText.append(event.text)
                        viewModelScope.launch(ioDispatcher) {
                            repository.updateMessage(
                                draftMsg.copy(
                                    content = accumulatedText.toString(),
                                    status = "streaming"
                                )
                            )
                        }
                    }
                    is StreamEvent.Error -> {
                        _lastError.value = "${event.errorCode}: ${event.message}"
                    }
                    is StreamEvent.Done -> {}
                    is StreamEvent.Meta -> {}
                }
            },
            onComplete = { success, error, wasDone ->
                _isGenerating.value = false
                activeCall = null
                activeRequestId = null

                val finalStatus = if (success && wasDone) "complete" else "interrupted"
                if (!success || !wasDone) {
                    if (error != null && error != "Cancelled") {
                        _lastError.value = error
                    }
                }

                viewModelScope.launch(ioDispatcher) {
                    repository.updateMessage(
                        draftMsg.copy(
                            content = accumulatedText.toString(),
                            status = finalStatus
                        )
                    )
                }
            }
        )
    }

    fun stopGeneration() {
        val reqId = activeRequestId
        if (reqId != null) {
            inferenceBackend.cancelRequest(reqId)
        }
        activeCall?.cancel()
        _isGenerating.value = false

        // Mark current draft as interrupted
        activeDraftId?.let { dId ->
            viewModelScope.launch(ioDispatcher) {
                val currentMessages = repository.getRecentMessages(_currentConversationId.value ?: "", limit = 5)
                val target = currentMessages.find { it.id == dId }
                if (target != null && target.status == "streaming") {
                    repository.updateMessage(target.copy(status = "interrupted"))
                }
            }
        }
    }

    fun retryLast() {
        val convId = _currentConversationId.value ?: return
        if (_isGenerating.value) return

        viewModelScope.launch(ioDispatcher) {
            // Remove the last interrupted or failed assistant message if it exists
            val recent = repository.getRecentMessages(convId, limit = 2)
            val lastMsg = recent.lastOrNull()
            if (lastMsg != null && lastMsg.role == "assistant" && lastMsg.status != "complete") {
                repository.deleteMessage(lastMsg.id)
            }
            startAssistantGeneration(convId)
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
