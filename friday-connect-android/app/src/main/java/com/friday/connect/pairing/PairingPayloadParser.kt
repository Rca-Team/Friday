package com.friday.connect.pairing

import android.net.Uri
import com.friday.connect.core.PairingOffer
import org.json.JSONObject
import java.time.Instant

object PairingPayloadParser {
    fun parse(raw: String): PairingOffer? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // 1. Unpack code blocks if scanned from markdown/docs
        val cleaned = if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed.substringAfter("\n").substringBeforeLast("```").trim()
        } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim()
        } else {
            trimmed
        }

        // 2. Parse direct or nested JSON
        val jsonOffer = tryParseJson(cleaned)
        if (jsonOffer != null) return jsonOffer

        // 3. Parse URL formats (http://ip:port/..., https://tunnel..., friday://pair?...)
        val urlOffer = tryParseUrl(cleaned)
        if (urlOffer != null) return urlOffer

        // 4. Parse bare 6-digit code (e.g. "885369" or "Code: 885369")
        val codeOffer = tryParseNumericCode(cleaned)
        if (codeOffer != null) return codeOffer

        return null
    }

    private fun tryParseJson(str: String): PairingOffer? {
        val candidate = when {
            str.startsWith("{") && str.endsWith("}") -> str
            str.contains("{") && str.contains("}") -> {
                str.substring(str.indexOf('{'), str.lastIndexOf('}') + 1)
            }
            else -> return null
        }

        return try {
            val root = JSONObject(candidate)
            val json = when {
                root.has("offer") && root.optJSONObject("offer") != null -> root.getJSONObject("offer")
                root.has("qr_payload") && root.optJSONObject("qr_payload") != null -> root.getJSONObject("qr_payload")
                root.has("data") && root.optJSONObject("data") != null -> root.getJSONObject("data")
                else -> root
            }

            val host = json.optString("host", "").trim()
            val port = json.optInt("port", 8765).let { if (it <= 0) 8765 else it }
            val pairingToken = json.optString("pairing_token", "").trim()
            val pairingCode = json.optString("pairing_code", "").trim()
            val expires = json.optInt("expires", 300).let { if (it <= 0) 300 else it }
            val createdAt = json.optString("created_at", Instant.now().toString())
            val tunnelUrl = if (json.has("tunnel_url") && !json.isNull("tunnel_url")) {
                json.optString("tunnel_url").takeIf { it.isNotBlank() }
            } else null

            if (pairingCode.isNotBlank() || pairingToken.isNotBlank() || host.isNotBlank()) {
                PairingOffer(
                    service = json.optString("service", "_FRIDAY._tcp.local."),
                    host = if (host.isNotBlank()) host else "127.0.0.1",
                    port = port,
                    pairingToken = pairingToken,
                    pairingCode = pairingCode,
                    expiresInSeconds = expires,
                    createdAt = createdAt,
                    tunnelUrl = tunnelUrl
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryParseUrl(str: String): PairingOffer? {
        if (!str.startsWith("http://") && !str.startsWith("https://") && !str.startsWith("friday://")) {
            return null
        }
        return try {
            val uri = Uri.parse(str)
            val code = uri.getQueryParameter("code") 
                ?: uri.getQueryParameter("pairing_code") 
                ?: uri.getQueryParameter("c") 
                ?: ""
            val token = uri.getQueryParameter("token") 
                ?: uri.getQueryParameter("pairing_token") 
                ?: uri.getQueryParameter("t") 
                ?: ""
            val tunnelParam = uri.getQueryParameter("tunnel") 
                ?: uri.getQueryParameter("tunnel_url")
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else (if (str.startsWith("https://")) 443 else 8765)

            val resolvedTunnel = if (str.startsWith("https://") && (host.contains("trycloudflare") || host.contains(".loca.lt"))) {
                "${uri.scheme}://${uri.host}"
            } else tunnelParam

            PairingOffer(
                service = "_FRIDAY._tcp.local.",
                host = if (host.isNotBlank()) host else "127.0.0.1",
                port = port,
                pairingToken = token,
                pairingCode = code,
                expiresInSeconds = 300,
                createdAt = Instant.now().toString(),
                tunnelUrl = resolvedTunnel
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun tryParseNumericCode(str: String): PairingOffer? {
        val match = Regex("""\b(\d{6})\b""").find(str) ?: return null
        val code = match.groupValues[1]
        return PairingOffer(
            service = "_FRIDAY._tcp.local.",
            host = "",
            port = 8765,
            pairingToken = "",
            pairingCode = code,
            expiresInSeconds = 300,
            createdAt = Instant.now().toString(),
            tunnelUrl = null
        )
    }
}
