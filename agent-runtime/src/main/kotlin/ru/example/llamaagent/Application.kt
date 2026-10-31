package ru.example.llamaagent

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import java.nio.charset.StandardCharsets

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

val AGENT_NAME = System.getenv("AGENT_NAME") ?: "llama-agent"
val AGENT_MODEL = System.getenv("AGENT_MODEL") ?: "llama3.2:latest"

val AGENT_PORT = (System.getenv("AGENT_PORT") ?: "8081").toInt().also {
    require(it in 1..65535) { "AGENT_PORT must be between 1 and 65535" }
}

val AGENT_HOST_IP = System.getenv("AGENT_HOST_IP") ?: "172.16.172.20"
val AGENT_TITLE = System.getenv("AGENT_TITLE") ?: "Llama Agent"

val AGENT_DESCRIPTION = System.getenv("AGENT_DESCRIPTION")
    ?: "Русскоязычный агент на базе llama 3.2"

val SYSTEM_PROMPT = System.getenv("AGENT_SYSTEM_PROMPT")
    ?: "Ты русскоязычный ассистент. Всегда отвечай только на русском языке. " +
       "Не смешивай русский и английский языки. Отвечай кратко и понятно."

val AGENT_THINK: Boolean? = System.getenv("AGENT_THINK")?.let {
    requireNotNull(it.toBooleanStrictOrNull()) {
        "AGENT_THINK must be true or false"
    }
}

fun startLoadingAnimation(): Thread {
    val animationThread = Thread {
        val frames = listOf(
            "llama starting.  ",
            "llama starting.. ",
            "llama starting..."
        )

        var index = 0

        try {
            while (!Thread.currentThread().isInterrupted) {
                print("\r${frames[index]}")
                System.out.flush()

                index = (index + 1) % frames.size
                Thread.sleep(400)
            }
        } catch (e: InterruptedException) {
            // Нормальное завершение анимации
        }
    }

    animationThread.start()
    return animationThread
}

fun stopLoadingAnimation(animationThread: Thread) {
    animationThread.interrupt()
    animationThread.join()

    // Очищаем оставшийся текст анимации
    print("\r" + " ".repeat(30) + "\r")
    System.out.flush()
}

fun askLlama(userQuestion: String): String {
    require(userQuestion.isNotBlank()) { "Question must not be blank" }

    val requestBody = buildJsonObject {
        put("model", AGENT_MODEL)

        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            }
            addJsonObject {
                put("role", "user")
                put("content", userQuestion)
            }
        }

        put("stream", false)
        AGENT_THINK?.let { put("think", it) }

        putJsonObject("options") {
            put("temperature", 0.2)
        }
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:11434/api/chat"))
        .timeout(java.time.Duration.ofMinutes(5))
        .header("Content-Type", "application/json")
        .POST(
            HttpRequest.BodyPublishers.ofString(
                requestBody,
                StandardCharsets.UTF_8
            )
        )
        .build()

    val client = HttpClient.newHttpClient()
    val animationThread = startLoadingAnimation()
    val startTime = System.nanoTime()

    try {
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        )

        val elapsedSeconds =
            (System.nanoTime() - startTime) / 1_000_000_000.0

        println("\nAgent: $AGENT_NAME; model: $AGENT_MODEL")
        println("HTTP status: ${response.statusCode()}")
        println("Response time: %.2f seconds".format(elapsedSeconds))

        check(response.statusCode() in 200..299) {
            "Ollama HTTP ${response.statusCode()}: ${response.body()}"
        }

        val responseJson =
            Json.parseToJsonElement(response.body()).jsonObject

        val answer = responseJson["message"]
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull

        return answer?.takeIf { it.isNotBlank() }
            ?: error("Ollama returned no non-empty message.content")

    } finally {
        stopLoadingAnimation(animationThread)
    }
}

fun agentDescription(): String {
    return """
        {
          "@context": [
            "https://www.w3.org/2022/wot/td/v1.1",
            {
              "lmos": "https://eclipse.dev/lmos/protocol/v1",
              "htv": "http://www.w3.org/2011/http#"
            }
          ],
          "@type": "lmos:Agent",
          "id": "urn:uuid:$AGENT_NAME",
          "title": ${JsonPrimitive(AGENT_TITLE)},
          "description": ${JsonPrimitive(AGENT_DESCRIPTION)},
          "actions": {
            "ask": {
              "description": "Задать вопрос языковой модели",
              "safe": true,
              "idempotent": false,
              "synchronous": true,
              "input": {
                "type": "object",
                "properties": {
                  "question": {
                    "type": "string",
                    "description": "Вопрос пользователя"
                  }
                },
                "required": ["question"]
              },
              "output": {
                "type": "object",
                "properties": {
                  "answer": {
                    "type": "string",
                    "description": "Ответ языковой модели"
                  }
                },
                "required": ["answer"]
              },
              "forms": [
                {
                  "op": "invokeaction",
                  "href": "http://$AGENT_HOST_IP:$AGENT_PORT/ask",
                  "contentType": "application/json",
                  "htv:methodName": "POST"
                }
              ]
            }
          }
        }
    """.trimIndent()
}

fun lastUserText(payload: JsonObject): String =
    payload["variables"]?.jsonObject?.get("request")?.jsonObject?.get("messages")?.jsonArray
        ?.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        ?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""

fun agentResultNext(id: String, answer: String): String = buildJsonObject {
    put("id", id)
    put("type", "next")
    putJsonObject("payload") { putJsonObject("data") { putJsonObject("agent") {
        put("status", "success")
        put("type", "MESSAGE")
        put("responseTime", 0.0)
        put("toolCalls", JsonArray(emptyList()))
        put("context", JsonArray(emptyList()))
        put("anonymizationEntities", JsonArray(emptyList()))
        putJsonArray("messages") { addJsonObject {
            put("role", "assistant")
            put("content", answer)
            put("format", "text")
        } }
    } } }
}.toString()

fun Application.module() {
    install(WebSockets)
    routing {
        get("/health") {
            val body = buildJsonObject {
                put("status", "ok")
                put("service", AGENT_NAME)
                put("model", AGENT_MODEL)
                put("port", AGENT_PORT)
            }

            call.respondText(
                body.toString(),
                ContentType.Application.Json
            )
        }
        get("/.well-known/agent-description") {
            call.respondText(agentDescription(), ContentType("application", "ld+json"))
        }
        post("/ask") {
            val question = try {
                Json.parseToJsonElement(call.receiveText()).jsonObject["question"]?.jsonPrimitive?.content
            } catch (e: Exception) { null }
            if (question.isNullOrBlank()) {
                call.respondText("""{"error":"Field 'question' is required"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            try {
                val answer = withContext(Dispatchers.IO) { askLlama(question) }
                call.respondText(buildJsonObject { put("answer", answer) }.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(buildJsonObject { put("error", e.message ?: "Unknown error") }.toString(),
                    ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
        webSocket("/subscriptions") {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = Json.parseToJsonElement(frame.readText()).jsonObject
                when (msg["type"]?.jsonPrimitive?.content) {
                    "connection_init" -> send("""{"type":"connection_ack"}""")
                    "ping" -> send("""{"type":"pong"}""")
                    "subscribe" -> {
    val id = msg["id"]?.jsonPrimitive?.content
        ?: error("Subscribe message must contain id")

    try {
        val payload = msg["payload"]?.jsonObject
            ?: error("Subscribe message must contain payload")

        val text = lastUserText(payload)

        require(text.isNotBlank()) {
            "Request must contain a non-empty user message in variables.request.messages"
        }

        val answer = withContext(Dispatchers.IO) {
            askLlama(text)
        }

        send(agentResultNext(id, answer))

        send(
            buildJsonObject {
                put("id", id)
                put("type", "complete")
            }.toString()
        )

    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e

    } catch (e: Exception) {
        application.log.error(
            "Agent $AGENT_NAME failed to process operation $id",
            e
        )

        send(
            buildJsonObject {
                put("id", id)
                put("type", "error")

                putJsonArray("payload") {
                    addJsonObject {
                        put(
                            "message",
                            e.message ?: "Agent request failed"
                        )
                    }
                }
            }.toString()
        )
    }
}

                }
            }
        }
    }
}

fun main() {
    println(
        "Starting agent=$AGENT_NAME model=$AGENT_MODEL port=$AGENT_PORT"
    )

    embeddedServer(
        Netty,
        port = AGENT_PORT,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}
