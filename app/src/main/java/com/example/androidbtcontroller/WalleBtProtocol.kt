// WalleBtProtocol.kt
@file:Suppress("MemberVisibilityCanBePrivate")

package com.example.androidbtcontroller

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Utilities for WALL-E Bluetooth V2 protocol (authenticated).
 *
 * Line protocol (client -> server):
 *  - CMD2:<left_i>;<right_i>;<seq>;<ts_ms>;<sn_hex>;<hmac_hex>
 *    Base string for HMAC: "CMD2|<left_i>|<right_i>|<seq>|<ts_ms>|<sn_hex>"
 *  - PING2:<seq>;<ts_ms>;<sn_hex>;<hmac_hex>
 *    Base string for HMAC: "PING2|<seq>|<ts_ms>|<sn_hex>"
 *
 * Server hello (server -> client):
 *  - SRV:HELLO ver=2 sn=<sn_hex>
 *
 * Server responses (server -> client):
 *  - ACK2:<seq>;ok
 *  - NAK2:<seq>;code=<reason>  (bad_nonce, bad_hmac, old_seq)
 *
 * left_i/right_i range: [-1000, 1000] mapping to [-1.000, 1.000]
 */
object WalleBtProtocol {

    data class ServerHello(
        val version: Int,
        val sessionNonceHex: String
    )

    sealed class AckResult {
        data class Ack(val seq: Long) : AckResult()
        data class Nak(val seq: Long, val code: String) : AckResult()
    }

    // ---------- Parsing ----------

    /**
     * Parse a server hello line like: "SRV:HELLO ver=2 sn=<hex>"
     */
    fun parseServerHello(line: String): ServerHello? {
        if (!line.startsWith("SRV:HELLO")) return null
        // Simple and robust parse
        // Expected tokens: ["SRV:HELLO", "ver=2", "sn=<hex>"]
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size < 3) return null
        val verPart = parts[1].trim()
        val snPart = parts[2].trim()

        val version = verPart.substringAfter("ver=", missingDelimiterValue = "").toIntOrNull() ?: return null
        val sn = snPart.substringAfter("sn=", missingDelimiterValue = "")
        if (sn.isEmpty()) return null

        return ServerHello(version, sn)
    }

    /**
     * Parse an ACK2/NAK2 line from the server.
     * Returns null if the line is not an ACK2/NAK2.
     */
    fun parseAckOrNak(line: String): AckResult? {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("ACK2:") -> {
                // "ACK2:<seq>;ok"
                val body = trimmed.removePrefix("ACK2:")
                val seqStr = body.substringBefore(';', missingDelimiterValue = body)
                val seq = seqStr.toLongOrNull() ?: return null
                AckResult.Ack(seq)
            }
            trimmed.startsWith("NAK2:") -> {
                // "NAK2:<seq>;code=<reason>"
                val body = trimmed.removePrefix("NAK2:")
                val seqStr = body.substringBefore(';', missingDelimiterValue = body)
                val seq = seqStr.toLongOrNull() ?: return null
                val code = body.substringAfter("code=", missingDelimiterValue = "").ifEmpty { "unknown" }
                AckResult.Nak(seq, code)
            }
            else -> null
        }
    }

    // ---------- Builders ----------

    /**
     * Build a CMD2 line. leftInt/rightInt are clamped to [-1000, 1000].
     * seq must monotonically increase per connection.
     * tsMs is the device's current time in milliseconds.
     * sessionNonceHex comes from ServerHello.sn.
     * secret is the shared secret configured on the robot.
     */
    fun buildCmd2(
        leftInt: Int,
        rightInt: Int,
        seq: Long,
        tsMs: Long,
        sessionNonceHex: String,
        secret: String
    ): String {
        val li = leftInt.coerceIn(-1000, 1000)
        val ri = rightInt.coerceIn(-1000, 1000)
        val base = "CMD2|$li|$ri|$seq|$tsMs|$sessionNonceHex"
        val mac = hmacSha256Hex(secret, base)
        return "CMD2:$li;$ri;$seq;$tsMs;$sessionNonceHex;$mac"
    }

    /**
     * Build a PING2 line. Use during idle to maintain freshness.
     */
    fun buildPing2(
        seq: Long,
        tsMs: Long,
        sessionNonceHex: String,
        secret: String
    ): String {
        val base = "PING2|$seq|$tsMs|$sessionNonceHex"
        val mac = hmacSha256Hex(secret, base)
        return "PING2:$seq;$tsMs;$sessionNonceHex;$mac"
    }

    // ---------- Helpers ----------

    /**
     * Map a float in [-1.0, 1.0] to an int in [-1000, 1000].
     */
    fun floatToInt(x: Float): Int {
        val clamped = x.coerceIn(-1.0f, 1.0f)
        return Math.round(clamped * 1000.0f)
    }

    /**
     * Map a double in [-1.0, 1.0] to an int in [-1000, 1000].
     */
    fun doubleToInt(x: Double): Int {
        val clamped = x.coerceIn(-1.0, 1.0)
        return kotlin.math.round(clamped * 1000.0).toInt()
    }

    /**
     * Validate that the ack corresponds to the given sequence.
     */
    fun isAckFor(result: AckResult?, seq: Long): Boolean {
        return (result as? AckResult.Ack)?.seq == seq
    }

    /**
     * Validate that the nak corresponds to the given sequence.
     */
    fun isNakFor(result: AckResult?, seq: Long): Boolean {
        return (result as? AckResult.Nak)?.seq == seq
    }

    // ---------- Crypto ----------

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return raw.toHexLowercase()
    }

    private fun ByteArray.toHexLowercase(): String {
        val out = StringBuilder(this.size * 2)
        for (b in this) {
            val i = b.toInt() and 0xff
            out.append(HEX[i ushr 4])
            out.append(HEX[i and 0x0f])
        }
        return out.toString()
    }

    private val HEX = charArrayOf(
        '0','1','2','3','4','5','6','7','8','9',
        'a','b','c','d','e','f'
    )
}