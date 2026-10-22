package com.agri.raphael.inference

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class StreamEvent {
    data class Meta(val requestId: String, val apiVersion: String) : StreamEvent()
    data class Delta(val text: String) : StreamEvent()
    data class Done(val finishReason: String) : StreamEvent()
    data class Error(val errorCode: String, val message: String) : StreamEvent()
}

class RemoteInferenceBackend(
    private val getBaseUrl: () -> String,
    private val getToken: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun streamChat(
        requestId: String,
        conversationId: String,
        mode: String,
        messages: List<Pair<String, String>>, // role to content
        onEvent: (StreamEvent) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ): Call {
        val jsonMessages = JSONArray()
        for ((role, content) in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", content)
            jsonMessages.put(msgObj)
        }

        val requestObj = JSONObject()
        requestObj.put("request_id", requestId)
        requestObj.put("conversation_id", conversationId)
        requestObj.put("mode", mode)
        requestObj.put("messages", jsonMessages)

        val body = requestObj.toString().toRequestBody("application/json".toMediaType())
        val url = "${getBaseUrl().trimEnd('/')}/v1/chat"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    onComplete(false, "Cancelled")
                } else {
                    onComplete(false, e.message ?: "Network failure")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        onComplete(false, "HTTP ${resp.code}: $errBody")
                        return
                    }

                    val source: BufferedSource = resp.body?.source() ?: run {
                        onComplete(false, "Empty response stream")
                        return
                    }

                    var currentEvent = "message"
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) {
                                currentEvent = "message"
                                continue
                            }

                            if (trimmed.startsWith("event:")) {
                                currentEvent = trimmed.removePrefix("event:").trim()
                            } else if (trimmed.startsWith("data:")) {
                                val payload = trimmed.removePrefix("data:").trim()
                                try {
                                    val dataObj = JSONObject(payload)
                                    when (currentEvent) {
                                        "meta" -> {
                                            onEvent(
                                                StreamEvent.Meta(
                                                    requestId = dataObj.optString("request_id", ""),
                                                    apiVersion = dataObj.optString("api_version", "v1")
                                                )
                                            )
                                        }
                                        "delta" -> {
                                            val text = dataObj.optString("text", "")
                                            if (text.isNotEmpty()) {
                                                onEvent(StreamEvent.Delta(text))
                                            }
                                        }
                                        "done" -> {
                                            onEvent(
                                                StreamEvent.Done(
                                                    finishReason = dataObj.optString("finish_reason", "stop")
                                                )
                                            )
                                        }
                                        "error" -> {
                                            onEvent(
                                                StreamEvent.Error(
                                                    errorCode = dataObj.optString("error_code", "INTERNAL_ERROR"),
                                                    message = dataObj.optString("message", "Stream error")
                                                )
                                            )
                                        }
                                    }
                                } catch (jsonEx: Exception) {
                                    // Raw text fallback
                                    if (currentEvent == "delta") {
                                        onEvent(StreamEvent.Delta(payload))
                                    }
                                }
                            }
                        }
                        onComplete(true, null)
                    } catch (ex: Exception) {
                        if (call.isCanceled()) {
                            onComplete(false, "Cancelled")
                        } else {
                            onComplete(false, ex.message)
                        }
                    }
                }
            }
        })
        return call
    }

    fun cancelRequest(requestId: String) {
        val url = "${getBaseUrl().trimEnd('/')}/v1/chat/requests/$requestId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${getToken()}")
            .delete()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
