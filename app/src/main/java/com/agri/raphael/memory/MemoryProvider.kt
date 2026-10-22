package com.agri.raphael.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class MemoryItem(
    val id: String,
    val body: String = "",
    val isCore: Boolean = false,
    val status: String = "approved",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

class AlexandriaMemoryClient(
    private val getBaseUrl: () -> String,
    private val getToken: () -> String
) {
    private val client = OkHttpClient()

    suspend fun listMemories(): List<MemoryItem> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val text = resp.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(text)
            val arr = root.optJSONArray("memories") ?: return@withContext emptyList()
            val result = mutableListOf<MemoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    MemoryItem(
                        id = obj.getString("id"),
                        body = obj.optString("body", ""),
                        isCore = obj.optBoolean("is_core", false),
                        status = obj.optString("status", "approved"),
                        createdAt = obj.optLong("created_at", 0L),
                        updatedAt = obj.optLong("updated_at", 0L)
                    )
                )
            }
            result
        }
    }

    suspend fun createMemory(bodyText: String, isCore: Boolean): MemoryItem? = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory"
        val payload = JSONObject().apply {
            put("client_request_id", UUID.randomUUID().toString())
            put("body", bodyText)
            put("is_core", isCore)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val text = resp.body?.string() ?: return@withContext null
            val obj = JSONObject(text)
            MemoryItem(
                id = obj.getString("id"),
                body = obj.optString("body", ""),
                isCore = obj.optBoolean("is_core", false),
                status = obj.optString("status", "approved"),
                createdAt = obj.optLong("created_at", 0L),
                updatedAt = obj.optLong("updated_at", 0L)
            )
        }
    }

    suspend fun updateMemory(id: String, bodyText: String, isCore: Boolean): MemoryItem? = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id"
        val payload = JSONObject().apply {
            put("body", bodyText)
            put("is_core", isCore)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .patch(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val text = resp.body?.string() ?: return@withContext null
            val obj = JSONObject(text)
            MemoryItem(
                id = obj.getString("id"),
                body = obj.optString("body", ""),
                isCore = obj.optBoolean("is_core", false),
                status = obj.optString("status", "approved"),
                createdAt = obj.optLong("created_at", 0L),
                updatedAt = obj.optLong("updated_at", 0L)
            )
        }
    }

    suspend fun deleteMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .delete()
            .build()

        client.newCall(request).execute().use { resp ->
            resp.isSuccessful
        }
    }

    suspend fun restoreMemory(id: String): MemoryItem? = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id/restore"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val text = resp.body?.string() ?: return@withContext null
            val obj = JSONObject(text)
            MemoryItem(
                id = obj.getString("id"),
                body = obj.optString("body", ""),
                isCore = obj.optBoolean("is_core", false),
                status = obj.optString("status", "approved"),
                createdAt = obj.optLong("created_at", 0L),
                updatedAt = obj.optLong("updated_at", 0L)
            )
        }
    }
}
