package com.terminuke.app.domain

data class HostInput(
    val label: String,
    val hostname: String,
    val port: String,
    val username: String,
    val id: Long = 0,
    val authType: String = "password",
    val keyAlias: String? = null,
)

object HostValidator {
    fun validate(input: HostInput): Map<String, String> = buildMap {
        if (input.label.trim().length !in 1..64) put("label", "Enter a label (1–64 characters).")
        if (!isValidHost(input.hostname.trim())) put("hostname", "Enter a hostname or IP address without a scheme.")
        val portNumber = input.port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) put("port", "Port must be between 1 and 65535.")
        if (!input.username.matches(Regex("^[a-z_][a-z0-9_-]{0,31}$"))) {
            put("username", "Use a Linux-style username (1–32 lowercase letters, digits, _ or -).")
        }
        if (input.authType !in setOf("password", "key", "keyboard-interactive")) {
            put("authType", "Choose a supported authentication method.")
        }
        if (input.authType == "key" && input.keyAlias.isNullOrBlank()) {
            put("keyAlias", "Select an SSH private key for this host.")
        }
    }

    fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253 || host.contains("://") || host.any { it == '/' || it.isWhitespace() }) return false
        if (host.matches(Regex("[0-9.]+"))) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
        if (host.contains(':')) return host.matches(Regex("[0-9a-fA-F:]+")) // IPv6 literal
        return host.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}
