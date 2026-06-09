package com.adbcommander

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight HTTP file server that streams a local content:// URI
 * so a TV on the same WiFi network can play it directly.
 *
 * Typical flow:
 *   1. Start the server → get the local port
 *   2. Build URL: http://<phone-ip>:<port>/file.<ext>
 *   3. Send ADB shell command to TV to open that URL
 *   4. TV's video player streams from the phone
 *   5. Stop the server after serving or after timeout
 */
class FileServer(
    private val fileUri: Uri,
    private val mimeType: String,
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val TAG = "FileServer"
        private const val BUFFER_SIZE = 8192
        private const val SERVE_TIMEOUT_MS = 600_000L // 10 minutes max
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var serverThread: Thread? = null
    private var servedCount = 0

    /**
     * Start the HTTP server. Pass port=0 to auto-select an available port.
     * Returns the actual port the server is listening on.
     */
    fun start(port: Int = 0): Int {
        serverSocket = ServerSocket(port)
        running = true
        val localPort = serverSocket!!.localPort
        serverThread = Thread({ serve() }, "FileServer-$localPort")
        serverThread?.start()

        // Auto-stop after timeout
        Thread({
            try { Thread.sleep(SERVE_TIMEOUT_MS) } catch (_: InterruptedException) {}
            if (running) {
                Log.d(TAG, "Server timeout — auto-stopping")
                stop()
            }
        }, "FileServer-Timeout").start()

        Log.d(TAG, "File server started on port $localPort")
        return localPort
    }

    private fun serve() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                Thread({ handleRequest(socket) }, "FileServer-Handler").start()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server accept error", e)
                break
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Read HTTP request (we only care that it's a GET)
            val headerBuffer = ByteArray(4096)
            input.read(headerBuffer)
            val requestStr = String(headerBuffer)

            // Handle range requests for seeking
            val rangeHeader = extractRange(requestStr)

            val fileStream: InputStream? = contentResolver.openInputStream(fileUri)
            if (fileStream == null) {
                val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                output.write(response.toByteArray())
                output.flush()
                socket.close()
                return
            }

            val totalSize = fileStream.available()
            Log.d(TAG, "Serving file, mimeType=$mimeType, size=$totalSize, range=$rangeHeader")

            if (rangeHeader != null && totalSize > 0) {
                // Partial content response (for seeking in video players)
                val parts = rangeHeader.split("=")
                if (parts.size == 2) {
                    val rangeParts = parts[1].split("-")
                    val start = rangeParts[0].toLongOrNull() ?: 0
                    val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty())
                        rangeParts[1].toLongOrNull() ?: (totalSize - 1)
                    else
                        totalSize - 1

                    // Skip to start position
                    var skipped = 0L
                    while (skipped < start) {
                        val s = fileStream.skip(start - skipped)
                        if (s <= 0) break
                        skipped += s
                    }

                    val contentLength = end - start + 1
                    val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Range: bytes $start-$end/$totalSize\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(header.toByteArray())

                    streamData(fileStream, output, contentLength)
                } else {
                    sendFullFile(fileStream, output, totalSize)
                }
            } else {
                sendFullFile(fileStream, output, totalSize)
            }

            servedCount++
            Log.d(TAG, "File served successfully (count=$servedCount)")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendFullFile(fileStream: InputStream, output: java.io.OutputStream, totalSize: Int) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mimeType\r\n" +
            if (totalSize > 0) "Content-Length: $totalSize\r\n" else "" +
            "Accept-Ranges: bytes\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray())
        streamData(fileStream, output, Long.MAX_VALUE)
    }

    private fun streamData(input: InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalWritten = 0L
        var bytesRead: Int
        while (totalWritten < maxBytes) {
            val toRead = minOf(buffer.size.toLong(), maxBytes - totalWritten).toInt()
            bytesRead = input.read(buffer, 0, toRead)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
            totalWritten += bytesRead
        }
        output.flush()
        input.close()
    }

    private fun extractRange(request: String): String? {
        val lines = request.split("\r\n")
        for (line in lines) {
            if (line.startsWith("Range:", ignoreCase = true)) {
                return line.substringAfter("Range:").trim()
            }
        }
        return null
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread = null
        Log.d(TAG, "File server stopped")
    }

    fun isRunning(): Boolean = running

    /**
     * Get the device's WiFi/network IP address (IPv4, non-loopback).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }
}
