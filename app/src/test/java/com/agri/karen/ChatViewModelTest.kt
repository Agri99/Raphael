package com.agri.karen

import com.agri.karen.data.ConversationRepository
import com.agri.karen.data.local.ConversationDao
import com.agri.karen.data.local.ConversationEntity
import com.agri.karen.data.local.MessageDao
import com.agri.karen.data.local.MessageEntity
import com.agri.karen.inference.InferenceBackend
import com.agri.karen.inference.StreamEvent
import com.agri.karen.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        viewModel.sendUserMessage("Hello K.A.R.E.N.")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        assertTrue(messages.any { it.role == "user" && it.content == "Hello K.A.R.E.N." })
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
            onEvent(StreamEvent.Delta("Part 2: K.A.R.E.N.. "))
            onEvent(StreamEvent.Delta("Part 3: Complete."))
            onComplete(true, null, true)
        }

        viewModel.sendUserMessage("Greet me")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val asstMsg = messages.find { it.role == "assistant" }
        assertTrue(asstMsg != null)
        assertEquals("Part 1: Hello. Part 2: K.A.R.E.N.. Part 3: Complete.", asstMsg?.content)
        assertEquals("complete", asstMsg?.status)
    }

    @Test
    fun testRapidRepeatedSendIgnored() = runTest {
        val convId = "conv-rapid-send"
        viewModel.selectConversation(convId)
        fakeBackend.autoComplete = false

        viewModel.sendUserMessage("First prompt")
        // Rapid second call while first generation is initiating
        viewModel.sendUserMessage("Second prompt")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val userMessages = messages.filter { it.role == "user" }
        assertEquals(1, userMessages.size)
        assertEquals("First prompt", userMessages[0].content)
    }

    @Test
    fun testRetryDoesNotDeleteStaleInterruptedMessageAfterCompletedTurn() = runTest {
        val convId = "conv-stale-retry"
        viewModel.selectConversation(convId)

        // Turn 1 (interrupted in history)
        fakeDao.insertMessage(MessageEntity("u-1", convId, "user", "Q1", 1000L, "complete"))
        fakeDao.insertMessage(MessageEntity("a-1", convId, "assistant", "Partial Q1", 2000L, "interrupted"))

        // Turn 2 (completed successfully)
        fakeDao.insertMessage(MessageEntity("u-2", convId, "user", "Q2", 3000L, "complete"))
        fakeDao.insertMessage(MessageEntity("a-2", convId, "assistant", "Answer Q2", 4000L, "complete"))

        // Invoke Retry
        viewModel.retryLast()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify old interrupted draft from Turn 1 is NOT deleted
        val messages = fakeDao.getMessagesForConversation(convId).first()
        assertTrue("Historical interrupted message must be preserved", messages.any { it.id == "a-1" })
        assertTrue("Completed latest response must be preserved", messages.any { it.id == "a-2" })
    }

    @Test
    fun testStopDuringStreamingPreservesAccumulatedText() = runTest {
        val convId = "conv-stop-accumulated"
        viewModel.selectConversation(convId)

        fakeBackend.autoComplete = false
        var emitDelta: ((StreamEvent) -> Unit)? = null

        fakeBackend.onStreamChatStarted = { onEvent, _ ->
            emitDelta = onEvent
        }

        viewModel.sendUserMessage("Start stream")
        testDispatcher.scheduler.advanceUntilIdle()

        emitDelta?.invoke(StreamEvent.Delta("Accumulated Part 1. "))
        emitDelta?.invoke(StreamEvent.Delta("Accumulated Part 2."))
        testDispatcher.scheduler.advanceUntilIdle()

        // Call stop
        viewModel.stopGeneration()
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val asstDraft = messages.find { it.role == "assistant" }
        assertTrue(asstDraft != null)
        assertEquals("Accumulated Part 1. Accumulated Part 2.", asstDraft?.content)
        assertEquals("interrupted", asstDraft?.status)
    }

    @Test
    fun testRapidRepeatedRetryIgnored() = runTest {
        val convId = "conv-rapid-retry"
        viewModel.selectConversation(convId)

        fakeDao.insertMessage(MessageEntity("u-1", convId, "user", "Prompt 1", 1000L, "complete"))
        fakeDao.insertMessage(MessageEntity("a-1", convId, "assistant", "Incomplete", 2000L, "interrupted"))

        fakeBackend.autoComplete = false

        // Two rapid calls to retryLast before advancing dispatcher
        viewModel.retryLast()
        viewModel.retryLast()
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val assistantDrafts = messages.filter { it.role == "assistant" }
        // Only one new generation draft should have been created (old a-1 was deleted)
        assertEquals(1, assistantDrafts.size)
        assertEquals("streaming", assistantDrafts[0].status)
    }

    @Test
    fun testStopVersusDoneTerminalRace() = runTest {
        val convId = "conv-stop-vs-done"
        viewModel.selectConversation(convId)

        fakeBackend.autoComplete = false
        var emitDelta: ((StreamEvent) -> Unit)? = null
        var emitComplete: ((Boolean, String?, Boolean) -> Unit)? = null

        fakeBackend.onStreamChatStarted = { onEvent, onComplete ->
            emitDelta = onEvent
            emitComplete = onComplete
        }

        viewModel.sendUserMessage("Streaming prompt")
        testDispatcher.scheduler.advanceUntilIdle()

        emitDelta?.invoke(StreamEvent.Delta("Partial content before user stop."))
        testDispatcher.scheduler.advanceUntilIdle()

        // User calls stop
        viewModel.stopGeneration()

        // Late 'done' arrival from network pipeline
        emitComplete?.invoke(true, null, true)
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val asstMsg = messages.find { it.role == "assistant" }
        assertTrue(asstMsg != null)
        // Late 'done' MUST NOT overwrite 'interrupted'
        assertEquals("interrupted", asstMsg?.status)
        assertEquals("Partial content before user stop.", asstMsg?.content)
    }

    @Test
    fun testStopVersusNetworkFailureRace() = runTest {
        val convId = "conv-stop-vs-net-fail"
        viewModel.selectConversation(convId)

        fakeBackend.autoComplete = false
        var emitDelta: ((StreamEvent) -> Unit)? = null
        var emitComplete: ((Boolean, String?, Boolean) -> Unit)? = null

        fakeBackend.onStreamChatStarted = { onEvent, onComplete ->
            emitDelta = onEvent
            emitComplete = onComplete
        }

        viewModel.sendUserMessage("Prompt testing failure race")
        testDispatcher.scheduler.advanceUntilIdle()

        emitDelta?.invoke(StreamEvent.Delta("Content before disconnect."))
        testDispatcher.scheduler.advanceUntilIdle()

        // User triggers stop
        viewModel.stopGeneration()

        // Socket/network failure arrives afterwards
        emitComplete?.invoke(false, "Socket closed", false)
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = fakeDao.getMessagesForConversation(convId).first()
        val asstMsg = messages.find { it.role == "assistant" }
        assertTrue(asstMsg != null)
        assertEquals("interrupted", asstMsg?.status)
        assertEquals("Content before disconnect.", asstMsg?.content)
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

    override fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        messages.map { list ->
            list.filter { it.conversationId == conversationId }
                .sortedBy { it.createdAtEpochMs }
        }

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
