package server

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.event.KeyEvent
import java.net.InetSocketAddress
import java.time.Duration

/**
 * Lightweight HTTP bridge between the Python graph agent and IntelliJ.
 *
 * The Python agent calls this server to observe the UI and execute actions.
 * This server forwards those calls to IntelliJ via the Remote Robot library
 * (which already works correctly with the local IDE).
 *
 * Run with: ./gradlew runBridgeServer
 * Defaults: ROBOT_URL=http://localhost:8082, BRIDGE_PORT=7070
 *
 * Endpoints:
 *   GET  /ping   → health check
 *   GET  /tree   → full IntelliJ UI tree as HTML
 *   POST /action → execute a UI action (click, right_click, type, press_key)
 */
fun main() {
    val robotUrl = System.getenv("ROBOT_URL") ?: "http://localhost:8082"
    val port = System.getenv("BRIDGE_PORT")?.toIntOrNull() ?: 7070

    val robot = RemoteRobot(robotUrl)
    val httpClient = OkHttpClient()

    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/ping") { exchange ->
        respond(exchange, 200, """{"status":"ok"}""")
    }

    server.createContext("/tree") { exchange ->
        if (exchange.requestMethod != "GET") {
            respond(exchange, 405, """{"error":"use GET"}"""); return@createContext
        }
        try {
            val html = httpClient.newCall(Request.Builder().url(robotUrl).build())
                .execute().use { it.body!!.string() }
            respond(exchange, 200, html, "text/html")
        } catch (e: Exception) {
            respond(exchange, 500, errorJson(e))
        }
    }

    server.createContext("/action") { exchange ->
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"use POST"}"""); return@createContext
        }
        try {
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val obj = Json.parseToJsonElement(body).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("missing 'type' field")

            when (type) {
                "click" -> {
                    val xpath = obj["xpath"]!!.jsonPrimitive.content
                    robot.find<ComponentFixture>(byXpath(xpath), Duration.ofSeconds(10)).click()
                }
                "right_click" -> {
                    val xpath = obj["xpath"]!!.jsonPrimitive.content
                    robot.find<ComponentFixture>(byXpath(xpath), Duration.ofSeconds(10)).rightClick()
                }
                "type" -> {
                    val text = obj["text"]!!.jsonPrimitive.content
                    robot.keyboard { enterText(text) }
                }
                "press_key" -> {
                    when (val key = obj["key"]!!.jsonPrimitive.content) {
                        "Enter"        -> robot.keyboard { key(KeyEvent.VK_ENTER) }
                        "Escape"       -> robot.keyboard { key(KeyEvent.VK_ESCAPE) }
                        "Tab"          -> robot.keyboard { key(KeyEvent.VK_TAB) }
                        "Backspace"    -> robot.keyboard { key(KeyEvent.VK_BACK_SPACE) }
                        "context_menu" -> robot.keyboard { key(KeyEvent.VK_CONTEXT_MENU) }
                        "shift_f6"     -> robot.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_F6) }
                        else           -> throw IllegalArgumentException("Unknown key: $key")
                    }
                }
                else -> throw IllegalArgumentException("Unknown action type: $type")
            }

            respond(exchange, 200, """{"status":"ok"}""")
        } catch (e: Exception) {
            respond(exchange, 500, errorJson(e))
        }
    }

    server.executor = null
    server.start()
    println("Agent Bridge Server on port $port  (IDE: $robotUrl)")
    println("  GET  /ping")
    println("  GET  /tree")
    println("  POST /action  {type: click|right_click|type|press_key, ...}")
}

private fun respond(exchange: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders["Content-Type"] = contentType
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun errorJson(e: Exception): String {
    val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
    return """{"status":"error","message":"$msg"}"""
}
