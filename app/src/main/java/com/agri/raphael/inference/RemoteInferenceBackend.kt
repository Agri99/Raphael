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

interface InferenceBackend {
    fun streamChat(
        requestId: String,
        conversationId: String,
        mode: String,
        messages: List<Pair<String, String>>,
        onEvent: (StreamEvent) -> Unit,
        onComplete: (Boolean, String?, Boolean) -> Unit
    ): Call?

    fun cancelRequest(requestId: String)
}

class RemoteInferenceBackend(
    private val getBaseUrl: () -> String,
    private val getToken: () -> String
) : InferenceBackend {
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
        onComplete: (Boolean, String?, Boolean) -> Unit // (success, error, wasDone)
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
                    onComplete(false, "Cancelled", false)
                } else {
                    onComplete(false, e.message ?: "Network failure", false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        onComplete(false, "HTTP ${resp.code}: $errBody", false)
                        return
                    }

                    val source: BufferedSource = resp.body?.source() ?: run {
                        onComplete(false, "Empty response stream", false)
                        return
                    }

                    var currentEvent = "message"
                    var receivedDone = false
                    var receivedError: String? = null

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
                                            receivedDone = true
                                            onEvent(
                                                StreamEvent.Done(
                                                    finishReason = dataObj.optString("finish_reason", "stop")
                                                )
                                            )
                                        }
                                        "error" -> {
                                            val code = dataObj.optString("error_code", "INTERNAL_ERROR")
                                            val msg = dataObj.optString("message", "Stream error")
                                            receivedError = "$code: $msg"
                                            onEvent(StreamEvent.Error(errorCode = code, message = msg))
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

                        if (receivedError != null) {
                            onComplete(false, receivedError, false)
                        } else if (!receivedDone) {
                            onComplete(false, "Stream ended unexpectedly before terminal done event.", false)
                        } else {
                            onComplete(true, null, true)
                        }
                    } catch (ex: Exception) {
                        if (call.isCanceled()) {
                            onComplete(false, "Cancelled", false)
                        } else {
                            onComplete(false, ex.message ?: "Stream read failure", false)
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
