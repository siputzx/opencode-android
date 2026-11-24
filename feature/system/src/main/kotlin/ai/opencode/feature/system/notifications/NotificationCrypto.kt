package ai.opencode.feature.system.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The v2 wire format. Neither the relay nor FCM receives these fields in clear.
 *
 * P-256 ECDH, HKDF-SHA256 (salt: the ref, info: [CONTEXT], 32 bytes) and AES-256-GCM over
 * `nonce(12) | ciphertext | tag(16)` authenticating `"<context>:<ref>"`. JCA stands in for
 * CryptoKit; keys keep CryptoKit's shapes (a 32-byte private scalar, a 65-byte X9.63 public key) so
 * both phones speak the same bytes to the server.
 */
object NotificationCrypto {
    const val CONTEXT = "OpenCode mobile notifications v2"
    const val MAXIMUM_BOX_BYTES = 2700

    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    sealed class Failure(message: String) : Exception(message) {
        data object InvalidRef : Failure("invalid ref") { private fun readResolve(): Any = InvalidRef }
        data object InvalidPublicKey : Failure("invalid public key") { private fun readResolve(): Any = InvalidPublicKey }
        data object InvalidBox : Failure("invalid box") { private fun readResolve(): Any = InvalidBox }
        data object InvalidPayload : Failure("invalid payload") { private fun readResolve(): Any = InvalidPayload }
    }

    /**
     * A decrypted message, `{v:1, type, sessionID, title, body, data}`. A known type needs a session
     * and a title; an unknown one may have neither, and without a title it isn't shown.
     */
    data class Payload(val type: String, val sessionID: String?, val title: String, val body: String) {
        val isKnownType: Boolean get() = type in KNOWN_TYPES

        companion object {
            val KNOWN_TYPES = setOf("session.idle", "question", "permission")

            fun parse(text: String): Payload? {
                val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
                val version = (root["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (version != 1) return null
                val type = stringOf(root, "type") ?: return null
                val title = stringOf(root, "title") ?: ""
                val body = stringOf(root, "body") ?: ""
                var sessionID = stringOf(root, "sessionID")
                if (sessionID != null && !isValid(sessionID = sessionID)) sessionID = null
                if (type in KNOWN_TYPES && (sessionID == null || title.isEmpty())) return null
                return Payload(type, sessionID, title, body)
            }

            private fun stringOf(root: JsonObject, key: String): String? =
                (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    fun isValid(sessionID: String): Boolean {
        val bytes = sessionID.toByteArray(Charsets.UTF_8)
        return sessionID.startsWith("ses") && bytes.size in 4..200 &&
            bytes.all { it in 48..57 || it in 65..90 || it in 97..122 || it.toInt() == 95 }
    }

    /** A ref is a lowercase canonical UUID. */
    fun validate(ref: String) {
        val canonical = runCatching { UUID.fromString(ref).toString() }.getOrNull()
        if (canonical != ref) throw Failure.InvalidRef
    }

    fun sharedKey(privateKey: PrivateKey, publicKey: String, ref: String): ByteArray {
        validate(ref)
        val data = strictBase64(publicKey)
        if (data == null || data.size != 65 || data[0].toInt() != 4) throw Failure.InvalidPublicKey
        val peer = runCatching { publicKey(x963 = data) }.getOrElse { throw Failure.InvalidPublicKey }
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }
        return hkdfSHA256(secret, salt = ref.toByteArray(Charsets.UTF_8), info = CONTEXT.toByteArray(Charsets.UTF_8), length = 32)
    }

    fun open(box: String, ref: String, key: ByteArray): Payload {
        validate(ref)
        if (box.toByteArray(Charsets.UTF_8).size > ((MAXIMUM_BOX_BYTES + 2) / 3) * 4) throw Failure.InvalidBox
        val data = strictBase64(box)
        if (data == null || data.size !in (NONCE_BYTES + TAG_BYTES + 1)..MAXIMUM_BOX_BYTES) throw Failure.InvalidBox
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, data, 0, NONCE_BYTES))
            updateAAD("$CONTEXT:$ref".toByteArray(Charsets.UTF_8))
            doFinal(data, NONCE_BYTES, data.size - NONCE_BYTES)
        }
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        }.getOrElse { throw Failure.InvalidPayload }
        return Payload.parse(text) ?: throw Failure.InvalidPayload
    }

    // Key shapes.

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** A new P-256 key pair. */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        return rawRepresentation(pair.private as ECPrivateKey) to x963Representation(pair.public as ECPublicKey)
    }

    fun privateKey(rawRepresentation: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, rawRepresentation), curve))

    fun publicKey(x963: ByteArray): PublicKey {
        require(x963.size == 65 && x963[0].toInt() == 4)
        val point = ECPoint(BigInteger(1, x963.copyOfRange(1, 33)), BigInteger(1, x963.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
    }

    fun rawRepresentation(key: ECPrivateKey): ByteArray = fixed(key.s, 32)

    fun x963Representation(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixed(key.w.affineX, 32) + fixed(key.w.affineY, 32)

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == size -> bytes
            bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size)
            else -> ByteArray(size - bytes.size) + bytes
        }
    }

    /** Padded standard base64 that encodes back to itself. */
    internal fun strictBase64(text: String): ByteArray? {
        val data = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        return if (Base64.getEncoder().encodeToString(data) == text) data else null
    }

    internal fun hkdfSHA256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val output = ArrayList<Byte>(length)
        var block = ByteArray(0)
        var counter = 1
        while (output.size < length) {
            expand.update(block)
            expand.update(info)
            expand.update(counter.toByte())
            block = expand.doFinal()
            output.addAll(block.toList())
            counter += 1
        }
        return output.take(length).toByteArray()
    }
}
