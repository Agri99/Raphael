package com.agri.karen.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

class CoreMemoryClient(
    private val getBaseUrl: () -> String,
    private val getToken: () -> String
) {
    private val client = OkHttpClient()

    suspend fun listMemories(): Result<List<MemoryItem>> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory"
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${getToken()}")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $err"))
                }
                val text = resp.body?.string() ?: "{}"
                val root = JSONObject(text)
                val arr = root.optJSONArray("memories") ?: JSONArray()
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
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createMemory(bodyText: String, isCore: Boolean): Result<MemoryItem> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory"
        try {
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
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $err"))
                }
                val text = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val obj = JSONObject(text)
                val item = MemoryItem(
                    id = obj.getString("id"),
                    body = obj.optString("body", ""),
                    isCore = obj.optBoolean("is_core", false),
                    status = obj.optString("status", "approved"),
                    createdAt = obj.optLong("created_at", 0L),
                    updatedAt = obj.optLong("updated_at", 0L)
                )
                Result.success(item)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMemory(id: String, bodyText: String, isCore: Boolean): Result<MemoryItem> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id"
        try {
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
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $err"))
                }
                val text = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val obj = JSONObject(text)
                val item = MemoryItem(
                    id = obj.getString("id"),
                    body = obj.optString("body", ""),
                    isCore = obj.optBoolean("is_core", false),
                    status = obj.optString("status", "approved"),
                    createdAt = obj.optLong("created_at", 0L),
                    updatedAt = obj.optLong("updated_at", 0L)
                )
                Result.success(item)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMemory(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id"
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${getToken()}")
                .delete()
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    val err = resp.body?.string() ?: ""
                    Result.failure(Exception("HTTP ${resp.code}: $err"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreMemory(id: String): Result<MemoryItem> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/memory/$id/restore"
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${getToken()}")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $err"))
                }
                val text = resp.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
                val obj = JSONObject(text)
                val item = MemoryItem(
                    id = obj.getString("id"),
                    body = obj.optString("body", ""),
                    isCore = obj.optBoolean("is_core", false),
                    status = obj.optString("status", "approved"),
                    createdAt = obj.optLong("created_at", 0L),
                    updatedAt = obj.optLong("updated_at", 0L)
                )
                Result.success(item)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

typealias AlexandriaMemoryClient = CoreMemoryClient
