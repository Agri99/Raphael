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
        messages.value.filter { it.conversationId == conversationId }.takeLast(limit)

    override suspend fun deleteMessageById(id: String) {
        messages.value = messages.value.filter { it.id != id }
    }
}

class FakeInferenceBackend : InferenceBackend {
    override fun streamChat(
        requestId: String,
        conversationId: String,
        mode: String,
        messages: List<Pair<String, String>>,
        onEvent: (StreamEvent) -> Unit,
        onComplete: (Boolean, String?, Boolean) -> Unit
    ): Call? {
        onComplete(true, null, true)
        return null
    }

    override fun cancelRequest(requestId: String) {}
}

