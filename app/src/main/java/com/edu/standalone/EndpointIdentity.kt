package com.edu.standalone

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** URL identity is also the storage boundary. A reset server needs a new identity. */
internal object EndpointIdentity {
    const val DEFAULT = "http://10.0.2.2:8080"

    fun normalize(value: String): String {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(scheme == "https" || (scheme == "http" && host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "Используйте HTTPS или адрес локального тестового сервера."
        }
        require(!host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535) {
            "Некорректный адрес сервера."
        }
        val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
        val authority = host + if (port == -1) "" else ":$port"
        return "$scheme://$authority${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    fun key(endpoint: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalize(endpoint).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
