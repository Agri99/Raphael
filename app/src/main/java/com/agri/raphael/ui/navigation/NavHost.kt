package com.agri.raphael.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.agri.raphael.data.ConversationRepository
import com.agri.raphael.data.local.RaphaelDatabase
import com.agri.raphael.inference.RemoteInferenceBackend
import com.agri.raphael.memory.AlexandriaMemoryClient
import com.agri.raphael.security.TokenStore
import com.agri.raphael.settings.PreferencesStore
import com.agri.raphael.ui.chat.ChatScreen
import com.agri.raphael.ui.chat.ChatViewModel
import com.agri.raphael.ui.conversation.ConversationListScreen
import com.agri.raphael.ui.memory.MemoryManagerScreen
import com.agri.raphael.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Composable
fun RaphaelNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val database = remember { RaphaelDatabase.getDatabase(context) }
    val repository = remember {
        ConversationRepository(database.conversationDao(), database.messageDao())
    }
    val preferencesStore = remember { PreferencesStore(context) }

    fun getBaseUrl(): String {
        return runBlocking { preferencesStore.gatewayUrl.first() }
    }

    fun getToken(): String {
        return TokenStore.getToken(context) ?: ""
    }

    val inferenceBackend = remember {
        RemoteInferenceBackend(
            getBaseUrl = { getBaseUrl() },
            getToken = { getToken() }
        )
    }

    val memoryClient = remember {
        AlexandriaMemoryClient(
            getBaseUrl = { getBaseUrl() },
            getToken = { getToken() }
        )
    }

    val chatViewModel = remember {
        ChatViewModel(repository, inferenceBackend, preferencesStore)
    }

    NavHost(navController = navController, startDestination = "conversations") {
        composable("conversations") {
            ConversationListScreen(
                repository = repository,
                onOpenConversation = { convId ->
                    navController.navigate("chat/$convId")
                },
                onOpenMemories = {
                    navController.navigate("memories")
                },
                onOpenSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable(
            route = "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val convId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            ChatScreen(
                conversationId = convId,
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onSaveMemoryRequest = { draftText ->
                    navController.navigate("memories?draft=${android.net.Uri.encode(draftText)}")
                }
            )
        }

        composable(
            route = "memories?draft={draft}",
            arguments = listOf(
                navArgument("draft") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val draft = backStackEntry.arguments?.getString("draft") ?: ""
            MemoryManagerScreen(
                memoryClient = memoryClient,
                initialDraftText = draft,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                preferencesStore = preferencesStore,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
