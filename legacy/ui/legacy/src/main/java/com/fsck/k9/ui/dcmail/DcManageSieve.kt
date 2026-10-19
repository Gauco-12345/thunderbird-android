package com.fsck.k9.ui.dcmail

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DC Mail: minimal ManageSieve (RFC 5804) client: STARTTLS, AUTHENTICATE PLAIN, LISTSCRIPTS, GETSCRIPT, PUTSCRIPT,
 * SETACTIVE. Only TLS-protected connections are used; credentials are never sent in plain text.
 *
 * Must be used from a background thread.
 */
class DcManageSieve private constructor(
    private var socket: Socket,
) : Closeable {
    private var input: InputStream = BufferedInputStream(socket.getInputStream())
    private var output: OutputStream = socket.getOutputStream()

    class SieveException(message: String) : Exception(message)

    data class Script(val name: String, val active: Boolean)

    data class Response(val ok: Boolean, val lines: List<String>, val status: String)

    var implementation: String = ""
        private set
    var extensions: Set<String> = emptySet()
        private set

    private fun readCapabilities(): Response {
        val response = readResponse()
        val sieveLine = response.lines.firstOrNull { it.startsWith("\"SIEVE\"", ignoreCase = true) }
        extensions = sieveLine?.let { unquote(it.substringAfter(' ')) }
            ?.split(' ')?.map { it.lowercase() }?.toSet().orEmpty()
        implementation = response.lines.firstOrNull { it.startsWith("\"IMPLEMENTATION\"", ignoreCase = true) }
            ?.let { unquote(it.substringAfter(' ')) }.orEmpty()
        return response
    }

    private fun startTls(host: String) {
        val lines = readCapabilities().lines
        if (lines.none { it.equals("\"STARTTLS\"", ignoreCase = true) }) {
            throw SieveException("Der Server bietet keine verschlüsselte Verbindung (STARTTLS) an.")
        }
        command("STARTTLS").requireOk("STARTTLS")

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(socket, host, socket.port, true) as SSLSocket
        tls.startHandshake()
        socket = tls
        input = BufferedInputStream(tls.getInputStream())
        output = tls.getOutputStream()
        // After STARTTLS the server sends its capabilities again
        readCapabilities()
    }

    private fun authenticate(username: String, password: String) {
        val token = Base64.encodeToString(
            "\u0000$username\u0000$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val response = command("AUTHENTICATE \"PLAIN\" \"$token\"")
        if (!response.ok) {
            throw SieveException("Anmeldung fehlgeschlagen: ${response.status}")
        }
    }

    fun listScripts(): List<Script> {
        val response = command("LISTSCRIPTS").requireOk("LISTSCRIPTS")
        return response.lines.mapNotNull { line ->
            val name = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            Script(name.replace("\\\"", "\"").replace("\\\\", "\\"), line.endsWith("ACTIVE", ignoreCase = true))
        }
    }

    fun getScript(name: String): String {
        val response = command("GETSCRIPT ${quote(name)}").requireOk("GETSCRIPT")
        return response.lines.joinToString("\r\n")
    }

    fun putScript(name: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        output.write("PUTSCRIPT ${quote(name)} {${bytes.size}+}\r\n".toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        readResponse().requireOk("PUTSCRIPT")
    }

    fun setActive(name: String) {
        command("SETACTIVE ${quote(name)}").requireOk("SETACTIVE")
    }

    override fun close() {
        try {
            command("LOGOUT")
        } catch (e: IOException) {
            // ignore
        } catch (e: SieveException) {
            // ignore
        }
        try {
            socket.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun command(line: String): Response {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        return readResponse()
    }

    /**
     * Reads lines until OK/NO/BYE. Literals ({n} or {n+}) are read as raw text and returned split into lines.
     */
    private fun readResponse(): Response {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine() ?: throw SieveException("Verbindung vom Server getrennt")
            val upper = line.uppercase()
            when {
                upper == "OK" || upper.startsWith("OK ") -> return Response(true, lines, line)
                upper == "NO" || upper.startsWith("NO ") -> return Response(false, lines, line)
                upper == "BYE" || upper.startsWith("BYE ") -> return Response(false, lines, line)
                LITERAL.matches(line.trim()) -> {
                    // The literal length is given in bytes (octets), not characters
                    val length = LITERAL.find(line.trim())!!.groupValues[1].toInt()
                    val buffer = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(buffer, read, length - read)
                        if (count < 0) throw SieveException("Verbindung vom Server getrennt")
                        read += count
                    }
                    lines.addAll(String(buffer, Charsets.UTF_8).split("\r\n", "\n"))
                }
                else -> lines.add(line)
            }
        }
    }

    /**
     * Reads one line as UTF-8 bytes up to LF; the trailing CR/LF is removed. Returns null at end of stream.
     */
    private fun readLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            }
            if (value == '\n'.code) break
            bytes.write(value)
        }
        return bytes.toString("UTF-8").removeSuffix("\r")
    }

    private fun Response.requireOk(command: String): Response {
        if (!ok) throw SieveException("$command fehlgeschlagen: $status")
        return this
    }

    companion object {
        const val DEFAULT_PORT = 4190
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private val LITERAL = Regex("^\\{(\\d+)\\+?\\}$")

        /**
         * Connects with STARTTLS and logs in. Throws [IOException] or [SieveException] with a readable message.
         */
        fun connect(host: String, port: Int, username: String, password: String): DcManageSieve {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val client = DcManageSieve(socket)
            try {
                client.startTls(host)
                client.authenticate(username, password)
            } catch (e: IOException) {
                client.closeQuietly()
                throw e
            } catch (e: SieveException) {
                client.closeQuietly()
                throw e
            }
            return client
        }

        private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun unquote(value: String) = value.trim().removePrefix("\"").removeSuffix("\"")
    }

    private fun closeQuietly() {
        try {
            socket.close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
