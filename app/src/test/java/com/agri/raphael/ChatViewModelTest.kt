package com.agri.raphael

import com.agri.raphael.data.ConversationRepository
import com.agri.raphael.data.local.ConversationDao
import com.agri.raphael.data.local.ConversationEntity
import com.agri.raphael.data.local.MessageDao
import com.agri.raphael.data.local.MessageEntity
import com.agri.raphael.inference.InferenceBackend
import com.agri.raphael.inference.StreamEvent
import com.agri.raphael.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Call
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeMessageDao
    private lateinit var fakeConvDao: FakeConversationDao
    private lateinit var repository: ConversationRepository
    private lateinit var fakeBackend: FakeInferenceBackend
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeConvDao = FakeConversationDao()
        fakeDao = FakeMessageDao()
        repository = ConversationRepository(fakeConvDao, fakeDao)

        fakeBackend = FakeInferenceBackend()
        viewModel = ChatViewModel(
            repository = repository,
            inferenceBackend = fakeBackend,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testModeToggle() {
        assertEquals("fast", viewModel.mode.value)
        viewModel.toggleMode()
        assertEquals("deep", viewModel.mode.value)
        viewModel.toggleMode()
        assertEquals("fast", viewModel.mode.value)
    }

    @Test
    fun testSelectConversationUpdatesState() = runTest {
        val convId = "conv-123"
        viewModel.selectConversation(convId)
        assertEquals(convId, viewModel.currentConversationId.value)
    }

    @Test
    fun testSendUserMessageSavesToRepository() = runTest {
        val convId = "conv-test"
        viewModel.selectConversation(convId)
        viewModel.sendUserMessage("Hello Raphael")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        assertTrue(messages.any { it.role == "user" && it.content == "Hello Raphael" })
    }

    @Test
    fun testRetrySelectsAndDeletesOnlyIncompleteAssistantMessage() = runTest {
        val convId = "conv-retry-test"
        viewModel.selectConversation(convId)

        // 1. User message (completed, earlier timestamp)
        val userMsg = MessageEntity(
            id = "user-1",
            conversationId = convId,
            role = "user",
            content = "Original Question",
            createdAtEpochMs = 1000L,
            status = "complete"
        )
        fakeDao.insertMessage(userMsg)

        // 2. Interrupted assistant message (incomplete, later timestamp)
        val interruptedAssistant = MessageEntity(
            id = "asst-interrupted-1",
            conversationId = convId,
            role = "assistant",
            content = "Partial text before failure...",
            createdAtEpochMs = 2000L,
            status = "interrupted"
        )
        fakeDao.insertMessage(interruptedAssistant)

        // Verify pre-retry setup
        val initialRecent = fakeDao.getRecentMessages(convId, limit = 5)
        assertEquals(2, initialRecent.size)
        assertEquals("asst-interrupted-1", initialRecent[0].id) // Newest first

        // 3. Trigger Retry
        viewModel.retryLast()
        testDispatcher.scheduler.advanceUntilIdle()

        // 4. Assert user message was preserved and old interrupted draft was deleted
        val currentMessages = fakeDao.getMessagesForConversation(convId).first()
        assertTrue("User message must be preserved", currentMessages.any { it.id == "user-1" })
        assertFalse("Old interrupted assistant draft must be deleted", currentMessages.any { it.id == "asst-interrupted-1" })
    }

    @Test
    fun testStopGenerationBindsToOriginatingConversation() = runTest {
        val convA = "conv-A"
        val convB = "conv-B"

        viewModel.selectConversation(convA)
        // Configure backend to keep streaming without completing automatically
        fakeBackend.autoComplete = false

        viewModel.sendUserMessage("Generate something long")
        testDispatcher.scheduler.advanceUntilIdle()

        val msgsA = fakeDao.getMessagesForConversation(convA).first()
        val streamingDraft = msgsA.find { it.role == "assistant" }
        assertTrue("Draft must exist in conv-A", streamingDraft != null)
        assertEquals("streaming", streamingDraft?.status)

        // User navigates to conv-B while generation is active in conv-A
        viewModel.selectConversation(convB)
        assertEquals(convB, viewModel.currentConversationId.value)

        // User hits Stop
        viewModel.stopGeneration()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify draft in conv-A is marked interrupted, and conv-B has no messages
        val updatedMsgsA = fakeDao.getMessagesForConversation(convA).first()
        val stoppedDraft = updatedMsgsA.find { it.id == streamingDraft?.id }
        assertEquals("interrupted", stoppedDraft?.status)

        val msgsB = fakeDao.getMessagesForConversation(convB).first()
        assertTrue("conv-B must remain empty", msgsB.isEmpty())
    }

    @Test
    fun testStreamEventsSerializeInOrderAndComplete() = runTest {
        val convId = "conv-stream-test"
        viewModel.selectConversation(convId)

        fakeBackend.autoComplete = false
        fakeBackend.onStreamChatStarted = { onEvent, onComplete ->
            onEvent(StreamEvent.Delta("Part 1: Hello. "))
            onEvent(StreamEvent.Delta("Part 2: Raphael. "))
            onEvent(StreamEvent.Delta("Part 3: Complete."))
            onComplete(true, null, true)
        }

        viewModel.sendUserMessage("Greet me")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val asstMsg = messages.find { it.role == "assistant" }
        assertTrue(asstMsg != null)
        assertEquals("Part 1: Hello. Part 2: Raphael. Part 3: Complete.", asstMsg?.content)
        assertEquals("complete", asstMsg?.status)
    }
}

class FakeConversationDao : ConversationDao {
    private val convs = MutableStateFlow<List<ConversationEntity>>(emptyList())

    override suspend fun insertConversation(conversation: ConversationEntity) {
        convs.value = convs.value + conversation
    }

    override suspend fun updateConversation(conversation: ConversationEntity) {
        convs.value = convs.value.map { if (it.id == conversation.id) conversation else it }
    }

    override fun getAllConversations(): Flow<List<ConversationEntity>> = convs

    override suspend fun getConversationById(id: String): ConversationEntity? =
        convs.value.firstOrNull { it.id == id }

    override suspend fun deleteConversationById(id: String) {
        convs.value = convs.value.filter { it.id != id }
    }
}

class FakeMessageDao : MessageDao {
    private val messages = MutableStateFlow<List<MessageEntity>>(emptyList())

    override suspend fun insertMessage(message: MessageEntity) {
        messages.value = messages.value + message
    }

    override suspend fun updateMessage(message: MessageEntity) {
        messages.value = messages.value.map { if (it.id == message.id) message else it }
    }

    override fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> = messages

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<MessageEntity> =
        messages.value.filter { it.conversationId == conversationId }
            .sortedByDescending { it.createdAtEpochMs }
            .take(limit)

    override suspend fun deleteMessageById(id: String) {
        messages.value = messages.value.filter { it.id != id }
    }
}

class FakeInferenceBackend : InferenceBackend {
    var autoComplete = true
    var onStreamChatStarted: (((StreamEvent) -> Unit, (Boolean, String?, Boolean) -> Unit) -> Unit)? = null

    override fun streamChat(
        requestId: String,
        conversationId: String,
        mode: String,
        messages: List<Pair<String, String>>,
        onEvent: (StreamEvent) -> Unit,
        onComplete: (Boolean, String?, Boolean) -> Unit
    ): Call? {
        if (onStreamChatStarted != null) {
            onStreamChatStarted?.invoke(onEvent, onComplete)
        } else if (autoComplete) {
            onComplete(true, null, true)
        }
        return null
    }

    override fun cancelRequest(requestId: String) {}
}
