package com.vito.relay

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing codes look like 7KQ2M-XP4AT-9HC0R-WZ6NE: 20 characters, about 100 bits of randomness.
 * The alphabet skips I, L, O and U so a code read aloud or typed by hand is hard to get wrong.
 */
object Pairing {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 20
    private val GROUPED = Regex("[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{5}")

    fun newCode(): String {
        val random = SecureRandom()
        val raw = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return format(String(raw))
    }

    /** Accepts a code typed or pasted in any shape and returns the canonical form, or null. */
    fun normalize(input: String): String? {
        val upper = input.uppercase()
        val candidate = GROUPED.find(upper)?.value ?: upper
        val cleaned = candidate
            .map { c ->
                when (c) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> c
                }
            }
            .filter { it in ALPHABET }
            .joinToString("")
        return if (cleaned.length == LENGTH) format(cleaned) else null
    }

    private fun format(raw: String): String = raw.chunked(5).joinToString("-")
}

/**
 * Everything derived from a pairing code: the relay topic both phones use and an AES-256 key.
 * The relay server only ever sees the topic name and encrypted bytes.
 */
class Keys(code: String) {
    val topic: String
    private val key: SecretKeySpec

    init {
        val secret = code.toByteArray(Charsets.UTF_8)
        topic = "relay_" + hex(hmac(secret, "relay-topic-v1")).take(32)
        key = SecretKeySpec(hmac(secret, "relay-aes-v1"), "AES")
    }

    fun seal(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + body)
    }

    /** Returns null for anything that was not sealed with this exact code. */
    fun open(sealed: String): String? = try {
        val all = Base64.getDecoder().decode(sealed.trim())
        if (all.size <= IV_BYTES + TAG_BITS / 8) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private fun hmac(secret: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
