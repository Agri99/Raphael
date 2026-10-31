package com.agri.karen.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agri.karen.security.TokenStore
import com.agri.karen.settings.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesStore: PreferencesStore,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentGatewayUrl by preferencesStore.gatewayUrl.collectAsState(initial = "")
    val currentMode by preferencesStore.selectedMode.collectAsState(initial = "fast")

    var gatewayUrlInput by remember(currentGatewayUrl) { mutableStateOf(currentGatewayUrl) }
    var tokenInput by remember { mutableStateOf(TokenStore.getToken(context) ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }
    var connectionTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateway & Privacy Settings") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Private Gateway Connection", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gatewayUrlInput,
                onValueChange = { gatewayUrlInput = it },
                label = { Text("Gateway URL (Tailscale Serve HTTPS)") },
                placeholder = { Text("https://laptop-name.tailnet.ts.net") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Bearer Token (Keystore Protected)") },
                placeholder = { Text("Enter 32-byte secret token") },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            preferencesStore.setGatewayUrl(gatewayUrlInput.trim())
                            TokenStore.saveToken(context, tokenInput.trim())
                            connectionTestStatus = "Settings saved securely."
                        }
                    }
                ) {
                    Text("Save Settings")
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isTestingConnection = true
                            connectionTestStatus = "Probing gateway..."
                            val result = testConnection(gatewayUrlInput.trim(), tokenInput.trim())
                            connectionTestStatus = result
                            isTestingConnection = false
                        }
                    },
                    enabled = !isTestingConnection
                ) {
                    Text(if (isTestingConnection) "Testing..." else "Test Connection")
                }
            }

            connectionTestStatus?.let { status ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider()

            Text("Inference Mode Preference", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = currentMode == "fast",
                    onClick = { coroutineScope.launch { preferencesStore.setSelectedMode("fast") } }
                )
                Text("Fast (Low Latency)")
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(
                    selected = currentMode == "deep",
                    onClick = { coroutineScope.launch { preferencesStore.setSelectedMode("deep") } }
                )
                Text("Deep (Extended Budget)")
            }

            HorizontalDivider()

            Text("Security & Privacy Controls", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    TokenStore.clearToken(context)
                    tokenInput = ""
                    connectionTestStatus = "Bearer token erased from Android Keystore."
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect & Erase Token")
            }
        }
    }
}

private suspend fun testConnection(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
    if (baseUrl.isBlank()) return@withContext "Error: Gateway URL cannot be empty."
    val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    try {
        // Probe /v1/health
        val healthReq = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/health").get().build()
        client.newCall(healthReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@withContext "Health probe failed: HTTP ${resp.code}"
            }
        }

        // Probe /v1/info with auth
        val infoReq = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/info")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(infoReq).execute().use { resp ->
            if (resp.code == 401) {
                return@withContext "Gateway reachable, but Bearer Token was rejected (HTTP 401)."
            } else if (!resp.isSuccessful) {
                return@withContext "Gateway reachable, but /v1/info failed: HTTP ${resp.code}"
            }
        }
        return@withContext "Connection verified successfully. Gateway is healthy and token is authenticated."
    } catch (e: Exception) {
        return@withContext "Private endpoint unreachable: ${e.message ?: "Connection timed out"}. Verify Tailscale and laptop power."
    }
}
