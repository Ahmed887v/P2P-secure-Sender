package com.p2psecure.share

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngine {
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private var aes: SecretKeySpec? = null

    fun pubJwk(): String {
        val pub = pair.public as ECPublicKey
        val w = pub.w
        return JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", b64url(to32(w.affineX)))
            .put("y", b64url(to32(w.affineY)))
            .toString()
    }

    fun derive(peerJwkRaw: String) {
        val jwk = JSONObject(peerJwkRaw)
        val x = BigInteger(1, b64urlDecode(jwk.getString("x")))
        val y = BigInteger(1, b64urlDecode(jwk.getString("y")))
        val params = (pair.public as ECPublicKey).params
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        val peer = KeyFactory.getInstance("EC").generatePublic(spec)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(pair.private)
        ka.doPhase(peer, true)
        val shared = ka.generateSecret()
        val key = hkdf(shared, ByteArray(32), "p2p-v1".toByteArray(), 32)
        aes = SecretKeySpec(key, "AES")
    }

    fun encrypt(data: ByteArray, nonce: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, nonce))
        return c.doFinal(data)
    }

    fun ready(): Boolean = aes != null

    companion object {
        fun randomNonce(): ByteArray {
            val n = ByteArray(12)
            SecureRandom().nextBytes(n)
            return n
        }

        private fun to32(n: BigInteger): ByteArray {
            var b = n.toByteArray()
            if (b.size > 32) b = b.copyOfRange(b.size - 32, b.size)
            if (b.size < 32) b = ByteArray(32 - b.size) + b
            return b
        }

        private fun b64url(data: ByteArray): String =
            Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun b64urlDecode(text: String): ByteArray {
            var t = text.replace('-', '+').replace('_', '/')
            while (t.length % 4 != 0) t += "="
            return Base64.decode(t, Base64.DEFAULT)
        }

        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(0x01)
            return mac.doFinal().copyOf(length)
        }
    }
}
