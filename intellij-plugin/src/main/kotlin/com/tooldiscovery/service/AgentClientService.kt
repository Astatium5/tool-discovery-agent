package com.tooldiscovery.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Application-level service for communicating with the Tool Discovery Agent.
 *
 * Provides HTTP and WebSocket communication with the Python agent service.
 */
@Service(Service.Level.APP)
class AgentClientService {
    private val logger = Logger.getInstance(AgentClientService::class.java)
    private val gson = Gson()

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private var webSocket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var agentUrl: String = "http://localhost:8080"
        private set

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    interface ConnectionListener {
        fun onConnected()

        fun onDisconnected(reason: String?)

        fun onError(error: String)
    }

    private val connectionListeners = mutableListOf<ConnectionListener>()

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    fun setAgentUrl(url: String) {
        agentUrl = url.removeSuffix("/")
    }

    fun connect() {
        serviceScope.launch {
            try {
                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/health")
                            .get()
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    isConnected = true
                    logger.info("Connected to agent at $agentUrl")
                    withContext(Dispatchers.Main) {
                        connectionListeners.forEach { it.onConnected() }
                    }
                } else {
                    isConnected = false
                    val error = "Health check failed: ${response.code}"
                    logger.warn(error)
                    withContext(Dispatchers.Main) {
                        connectionListeners.forEach { it.onError(error) }
                    }
                }
            } catch (e: Exception) {
                isConnected = false
                logger.error("Failed to connect to agent", e)
                withContext(Dispatchers.Main) {
                    connectionListeners.forEach { it.onError(e.message ?: "Connection failed") }
                }
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected = false

        serviceScope.launch(Dispatchers.Main) {
            connectionListeners.forEach { it.onDisconnected(null) }
        }

        logger.info("Disconnected from agent")
    }

    suspend fun getStatus(): Result<StatusResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/")
                            .get()
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    val body =
                        response.body?.string() ?: return@withContext Result.failure(
                            IOException("Empty response"),
                        )
                    val status = gson.fromJson(body, StatusResponse::class.java)
                    Result.success(status)
                } else {
                    Result.failure(IOException("Request failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun discoverTools(
        categories: List<String>? = null,
        maxDepth: Int = 5,
    ): Result<List<ToolInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val requestBody =
                    gson.toJson(
                        mapOf(
                            "categories" to categories,
                            "max_depth" to maxDepth,
                        ),
                    )

                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/discover")
                            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    val body =
                        response.body?.string() ?: return@withContext Result.failure(
                            IOException("Empty response"),
                        )
                    val type = object : TypeToken<List<ToolInfo>>() {}.type
                    val tools: List<ToolInfo> = gson.fromJson(body, type)
                    Result.success(tools)
                } else {
                    Result.failure(IOException("Discovery failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getTools(): Result<List<ToolInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/tools")
                            .get()
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    val body =
                        response.body?.string() ?: return@withContext Result.failure(
                            IOException("Empty response"),
                        )
                    val type = object : TypeToken<List<ToolInfo>>() {}.type
                    val tools: List<ToolInfo> = gson.fromJson(body, type)
                    Result.success(tools)
                } else {
                    Result.failure(IOException("Request failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createPlan(
        goal: String,
        context: Map<String, Any>? = null,
    ): Result<RefactoringPlan> =
        withContext(Dispatchers.IO) {
            try {
                val requestBody =
                    gson.toJson(
                        mapOf(
                            "goal" to goal,
                            "context" to context,
                        ),
                    )

                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/plan")
                            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    val body =
                        response.body?.string() ?: return@withContext Result.failure(
                            IOException("Empty response"),
                        )
                    val plan = gson.fromJson(body, RefactoringPlan::class.java)
                    Result.success(plan)
                } else {
                    Result.failure(IOException("Planning failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun executePlan(
        plan: RefactoringPlan,
        verify: Boolean = true,
    ): Result<List<StepResult>> =
        withContext(Dispatchers.IO) {
            try {
                val requestBody =
                    gson.toJson(
                        mapOf(
                            "plan" to plan,
                            "verify" to verify,
                        ),
                    )

                val response =
                    client.newCall(
                        Request.Builder()
                            .url("$agentUrl/execute")
                            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                            .build(),
                    ).execute()

                if (response.isSuccessful) {
                    val body =
                        response.body?.string() ?: return@withContext Result.failure(
                            IOException("Empty response"),
                        )
                    val type = object : TypeToken<List<StepResult>>() {}.type
                    val results: List<StepResult> = gson.fromJson(body, type)
                    Result.success(results)
                } else {
                    Result.failure(IOException("Execution failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun connectWebSocket(listener: AgentWebSocketListener) {
        val wsUrl = agentUrl.replace("http", "ws") + "/ws"

        webSocket =
            client.newWebSocket(
                Request.Builder()
                    .url(wsUrl)
                    .build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        logger.info("WebSocket connected")
                        listener.onOpen(webSocket, response)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val message = gson.fromJson(text, JsonObject::class.java)
                        listener.onMessage(webSocket, message)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        listener.onClosing(webSocket, code, reason)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        logger.info("WebSocket closed: $code - $reason")
                        listener.onClosed(webSocket, code, reason)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        logger.error("WebSocket failure", t)
                        listener.onFailure(webSocket, t, response)
                    }
                },
            )
    }

    fun sendCommand(
        command: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val combined = mutableMapOf<String, Any?>("command" to command)
        combined.putAll(data)
        val message = gson.toJson(combined)
        webSocket?.send(message)
    }

    fun dispose() {
        disconnect()
        serviceScope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun getInstance(): AgentClientService {
            return ApplicationManager.getApplication().getService(AgentClientService::class.java)
        }
    }

    data class StatusResponse(
        val status: String,
        val tools_discovered: Int,
        val version: String,
    )

    data class ToolInfo(
        val name: String,
        val path: String,
        val shortcut: String?,
        val description: String?,
        val category: String,
    )

    data class RefactoringPlan(
        val name: String,
        val description: String,
        val steps: List<RefactoringStep>,
        val confidence: Double,
        val prerequisites: List<String>,
        val risks: List<String>,
    )

    data class RefactoringStep(
        val step_number: Int,
        val tool_name: String,
        val action: String,
        val target: String,
        val parameters: Map<String, Any>,
        val expected_result: String,
    )

    data class StepResult(
        val step_number: Int,
        val status: String,
        val message: String,
        val error: String?,
    )
}

abstract class AgentWebSocketListener {
    open fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {}

    abstract fun onMessage(
        webSocket: WebSocket,
        message: JsonObject,
    )

    open fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {}

    open fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {}

    open fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {}
}
