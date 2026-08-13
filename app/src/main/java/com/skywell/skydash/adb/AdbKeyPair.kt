package com.skywell.skydash.adb

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

class AdbKeyPair(val privateKey: PrivateKey, val publicKeyBytes: ByteArray) {

    fun signToken(token: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30.toByte(), 0x21.toByte(), 0x30.toByte(), 0x09.toByte(), 0x06.toByte(), 0x05.toByte(), 0x2b.toByte(),
            0x0e.toByte(), 0x03.toByte(), 0x02.toByte(), 0x1a.toByte(), 0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x14.toByte()
        )
        val dataToSign = prefix + token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(dataToSign)
    }

    fun getPublicKeyBase64(): String {
        val base64 = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
        return "$base64 skydash@localhost\u0000"
    }

    companion object {
        private const val PREFS_NAME = "adb_keys"
        private const val KEY_PRIV = "private_key"
        private const val KEY_PUB_BYTES = "public_key_bytes"

        fun getOrGenerate(context: Context): AdbKeyPair {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val privStr = prefs.getString(KEY_PRIV, null)
            val pubBytesStr = prefs.getString(KEY_PUB_BYTES, null)

            if (privStr != null && pubBytesStr != null) {
                try {
                    val privBytes = Base64.decode(privStr, Base64.DEFAULT)
                    val kf = KeyFactory.getInstance("RSA")
                    val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                    val publicKeyBytes = Base64.decode(pubBytesStr, Base64.DEFAULT)
                    return AdbKeyPair(privateKey, publicKeyBytes)
                } catch (e: Exception) {
                    // fall through to generate new one
                }
            }

            // Generate new RSA 2048 KeyPair
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val adbPubKeyBytes = convertToAdbPublicKey(kp)
            
            // Save to prefs
            val privBytes = kp.private.encoded
            prefs.edit()
                .putString(KEY_PRIV, Base64.encodeToString(privBytes, Base64.DEFAULT))
                .putString(KEY_PUB_BYTES, Base64.encodeToString(adbPubKeyBytes, Base64.DEFAULT))
                .apply()

            return AdbKeyPair(kp.private, adbPubKeyBytes)
        }

        private fun convertToAdbPublicKey(kp: KeyPair): ByteArray {
            val rsaPubKey = kp.public as RSAPublicKey
            val modulus = rsaPubKey.modulus
            
            // Adb expects exponent to be 65537 usually, but let's read it
            val exponent = rsaPubKey.publicExponent
            
            val numWords = 2048 / 32 // 64 words
            
            // Calculate n0inv = -1 / N mod 2^32
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = modulus.modInverse(r32).negate().mod(r32).toLong().toInt()

            // Calculate R^2 mod N where R = 2^(numWords * 32) = 2^2048
            val r = BigInteger.ONE.shiftLeft(2048)
            val rr = r.multiply(r).mod(modulus)

            // Convert modulus and RR to little-endian arrays of numWords ints
            val nBytes = getBigIntegerBytesLittleEndian(modulus, 256)
            val rrBytes = getBigIntegerBytesLittleEndian(rr, 256)

            val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(numWords)
            buffer.putInt(n0inv)
            buffer.put(nBytes)
            buffer.put(rrBytes)
            buffer.putInt(exponent.toInt())

            return buffer.array()
        }

        private fun getBigIntegerBytesLittleEndian(valIn: BigInteger, size: Int): ByteArray {
            val out = ByteArray(size)
            val raw = valIn.toByteArray()
            
            // BigInteger is Big Endian, we need Little Endian
            // Also BigInteger might contain leading sign byte
            var rawOffset = 0
            var bytesToCopy = raw.size
            if (raw[0].toInt() == 0 && raw.size > 1) {
                rawOffset = 1
                bytesToCopy--
            }

            val copyLen = Math.min(bytesToCopy, size)
            for (i in 0 until copyLen) {
                out[i] = raw[rawOffset + bytesToCopy - 1 - i]
            }
            return out
        }
    }
}
