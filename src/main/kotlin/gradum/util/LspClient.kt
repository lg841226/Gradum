/*
 * Copyright (c) 2026 Gradum team, Some Rights Reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * LspClient.kt  2026-06-20 20:22:43 Created by gwy
 */

package gradum.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import org.slf4j.LoggerFactory

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val lspLogger: org.slf4j.Logger = LoggerFactory.getLogger("LspClient")
private val lspJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Process-level singleton managing one [LspConnection] per language. Connections
 * are spawned lazily on first access, kept alive for the agent session, and
 * reaped when [shutdown] is called (e.g., on session end).
 */
object LspClient {
    private val connections: ConcurrentHashMap<String, LspConnection> = ConcurrentHashMap()

    fun connectionFor(path: Path): LspConnection? {
        val serverSpec: LspServerSpec = LspServerRegistry.findByPath(path) ?: return null
        if (!isLspServerInstalled(serverSpec)) {
            lspLogger.warn("LSP server for ${serverSpec.languageId} not installed — ${serverSpec.installHint}")
            return null
        }
        val projectRoot: Path = findProjectRoot(path, serverSpec.projectMarker)
        return connections.computeIfAbsent(serverSpec.languageId) { LspConnection.connect(serverSpec, projectRoot) }
    }

    fun shutdown() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}

/**
 * One live connection to a single LSP server. Owns the subprocess, the
 * Content-Length framed JSON-RPC channel, and a request-id → future map for
 * correlating responses.
 */
class LspConnection private constructor(
    private val spec: LspServerSpec,
    private val process: Process,
    private val projectRoot: Path,
) {
    private val stdin: BufferedOutputStream = BufferedOutputStream(process.outputStream)
    private val stdout: BufferedInputStream = BufferedInputStream(process.inputStream)
    private val stderrThread: Thread = Thread { drainStderr() }.apply { isDaemon = true; start() }
    private val nextRequestId: AtomicInteger = AtomicInteger(0)
    private val pending: ConcurrentHashMap<Int, CompletableFuture<JsonElement>> = ConcurrentHashMap()
    private val readerThread: Thread = Thread { readLoop() }.apply { isDaemon = true; start() }

    @Volatile private var alive: Boolean = true

    companion object {
        fun connect(spec: LspServerSpec, projectRoot: Path): LspConnection {
            val process: Process = ProcessBuilder(spec.command).directory(projectRoot.toFile()).redirectErrorStream(false).start()
            val connection: LspConnection = LspConnection(spec, process, projectRoot)
            connection.initialize()
            return connection
        }
    }

    private fun initialize() {
        val initParams: JsonObject = buildJsonObject {
            put("processId", ProcessHandle.current().pid())
            put("rootUri", projectRoot.toUri().toString())
            put("capabilities", buildJsonObject { put("textDocument", buildJsonObject { put("synchronization", buildJsonObject { put("didSave", true) }) }) })
        }
        sendRequest("initialize", initParams)
        sendNotification("initialized", buildJsonObject { })
    }

    fun sendRequest(method: String, params: JsonElement): JsonElement {
        check(alive) { "LSP connection for ${spec.languageId} is dead" }
        val requestId: Int = nextRequestId.incrementAndGet()
        val future: CompletableFuture<JsonElement> = CompletableFuture()
        pending[requestId] = future
        val message: JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            put("params", params)
        }
        writeFramed(message)
        return future.get(30, TimeUnit.SECONDS)
    }

    fun sendNotification(method: String, params: JsonElement) {
        if (!alive) return
        val message: JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        writeFramed(message)
    }

    fun didOpen(path: Path, content: String, version: Int) {
        sendNotification("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", path.toUri().toString())
                put("languageId", spec.languageId)
                put("version", version)
                put("text", content)
            })
        })
    }

    fun didChange(path: Path, content: String, version: Int) {
        sendNotification("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", path.toUri().toString())
                put("version", version)
            })
            put("contentChanges", buildJsonObject { put("text", content) })
        })
    }

    fun isAlive(): Boolean = alive && process.isAlive

    fun close() {
        alive = false
        runCatching { sendRequest("shutdown", buildJsonObject { }) }
        runCatching { sendNotification("exit", buildJsonObject { }) }
        process.destroy()
        pending.values.forEach { it.completeExceptionally(IOException("LSP connection closed")) }
    }

    private fun writeFramed(message: JsonObject) {
        val encodedJson: String = lspJson.encodeToString(JsonElement.serializer(), message)
        val payloadBytes: ByteArray = encodedJson.toByteArray(Charsets.UTF_8)
        synchronized(stdin) {
            stdin.write("Content-Length: ${payloadBytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
            stdin.write(payloadBytes)
            stdin.flush()
        }
    }

    private fun readLoop() {
        try {
            while (alive) {
                val rpcMessage: JsonElement = readMessage()
                val requestId: Int? = (rpcMessage as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                if (requestId != null) {
                    val pendingFuture: CompletableFuture<JsonElement>? = pending.remove(requestId)
                    val responsePayload: JsonElement = rpcMessage["result"] ?: buildJsonObject { put("error", rpcMessage["error"] ?: JsonPrimitive("unknown")) }
                    pendingFuture?.complete(responsePayload)
                }
            }
        } catch (e: Exception) {
            alive = false
            lspLogger.warn("LSP reader for ${spec.languageId} died: ${e.message}")
            pending.values.forEach { it.completeExceptionally(e) }
        }
    }

    private fun readMessage(): JsonElement {
        val headers: MutableMap<String, String> = mutableMapOf()
        while (true) {
            val line: String = readLineOrThrow(stdout) ?: throw IOException("LSP stream closed during headers")
            if (line.isEmpty()) break
            val colonIndex: Int = line.indexOf(':')
            if (colonIndex > 0) headers[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
        }

        val contentLength: Int = headers["content-length"]?.toIntOrNull() ?: throw IOException("Missing Content-Length header from ${spec.languageId}")
        val body: ByteArray = ByteArray(contentLength)
        var bytesRead: Int = 0
        while (bytesRead < contentLength) {
            val chunkSize: Int = stdout.read(body, bytesRead, contentLength - bytesRead)
            if (chunkSize < 0) throw IOException("LSP stream closed mid-body from ${spec.languageId}")
            bytesRead += chunkSize
        }

        return lspJson.parseToJsonElement(String(body, Charsets.UTF_8))
    }

    private fun drainStderr() {
        process.errorStream.bufferedReader().forEachLine { line: String -> lspLogger.info("[${spec.languageId}] $line") }
    }

    private fun readLineOrThrow(stream: BufferedInputStream): String? {
        val lineBuffer: StringBuilder = StringBuilder()
        while (true) {
            val nextByte: Int = stream.read()
            if (nextByte == -1) return if (lineBuffer.isEmpty()) null else lineBuffer.toString()
            if (nextByte == '\n'.code) return lineBuffer.toString()
            if (nextByte != '\r'.code) lineBuffer.append(nextByte.toChar())
        }
    }
}
