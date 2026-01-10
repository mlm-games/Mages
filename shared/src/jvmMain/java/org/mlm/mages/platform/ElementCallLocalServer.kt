package org.mlm.mages.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ElementCallLocalServer {
    private val started = AtomicBoolean(false)
    @Volatile private var server: HttpServer? = null
    @Volatile private var port: Int = -1

    fun ensureStarted(): String {
        if (started.compareAndSet(false, true)) {
            // Debug: Check if resources exist
            val cl = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val indexUrl = cl.getResource("element-call/index.html")
            println("[ElementCallServer] index.html resource URL: $indexUrl")

            if (indexUrl == null) {
                println("[ElementCallServer] ERROR: element-call/index.html not found on classpath!")
                // List what's available
                val rootUrl = cl.getResource("element-call")
                println("[ElementCallServer] element-call folder URL: $rootUrl")
            }

            val addr = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
            val s = HttpServer.create(addr, 0)
            s.createContext("/") { ex -> handle(ex) }
            s.executor = Executors.newFixedThreadPool(4)
            s.start()
            server = s
            port = s.address.port
            println("[ElementCallServer] Started on http://127.0.0.1:$port")
        }
        val p = port
        check(p > 0) { "ElementCallLocalServer failed to start" }
        return "http://127.0.0.1:$p"
    }

    fun indexUrl(): String = ensureStarted() + "/element-call/index.html"

    fun baseUrl(): String = ensureStarted()

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        println("[ElementCallServer] Stopping...")
        server?.stop(0)
        server = null
        port = -1
    }

    private fun handle(ex: HttpExchange) {
        val rawPath = ex.requestURI.path ?: "/"
        println("[ElementCallServer] ${ex.requestMethod} $rawPath")

        try {
            // Handle CORS preflight
            if (ex.requestMethod == "OPTIONS") {
                ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
                ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                ex.responseHeaders.add("Access-Control-Allow-Headers", "*")
                ex.sendResponseHeaders(204, -1)
                return
            }

            // Map URL to resource path
            val resourcePath = when {
                rawPath == "/" -> "element-call/index.html"
                rawPath == "/element-call" || rawPath == "/element-call/" -> "element-call/index.html"
                rawPath.startsWith("/element-call/") -> rawPath.removePrefix("/").let {
                    if (it == "element-call/") "element-call/index.html" else it
                }
                rawPath.startsWith("/") -> "element-call${rawPath}" // /assets/foo.js -> element-call/assets/foo.js
                else -> "element-call/$rawPath"
            }

            // Security check
            if (resourcePath.contains("..") || resourcePath.contains("\\")) {
                sendText(ex, 400, "Bad path")
                return
            }

            val bytes = readResourceBytes(resourcePath)
            if (bytes == null) {
                println("[ElementCallServer] 404: $resourcePath")
                sendText(ex, 404, "Not found: $resourcePath")
                return
            }

            val contentType = contentTypeFor(resourcePath)
            println("[ElementCallServer] 200: $resourcePath ($contentType, ${bytes.size} bytes)")

            ex.responseHeaders.add("Content-Type", contentType)
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")

            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (t: Throwable) {
            println("[ElementCallServer] Error handling $rawPath: ${t.message}")
            t.printStackTrace()
            runCatching { sendText(ex, 500, "Server error: ${t.message}") }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun readResourceBytes(path: String): ByteArray? {
        val cl = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        return cl.getResourceAsStream(path)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun sendText(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "wasm" -> "application/wasm"
        "map" -> "application/json; charset=utf-8"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "eot" -> "application/vnd.ms-fontobject"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }
}