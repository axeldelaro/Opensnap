package io.github.axeldelaro.opensnap.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr Signatures over secp256k1 for Nostr NIP-01 / NIP-40 / NIP-59 / NIP-78 compatibility.
 */
object Bip340Schnorr {

    private val curveParams = CustomNamedCurves.getByName("secp256k1")
    private val curve = curveParams.curve
    private val G: ECPoint = curveParams.g
    private val n: BigInteger = curveParams.n
    private val p: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16
    )
    private val THREE = BigInteger.valueOf(3L)
    private val SEVEN = BigInteger.valueOf(7L)
    private val SQRT_EXP: BigInteger = p.add(BigInteger.ONE).shiftRight(2)
    private val multiplier = FixedPointCombMultiplier()
    private val secureRandom = SecureRandom()

    data class KeyPair(
        val privateKey32: ByteArray,
        val publicKeyXOnly32: ByteArray
    )

    fun generateKeyPair(): KeyPair {
        while (true) {
            val priv = ByteArray(32).also { secureRandom.nextBytes(it) }
            val d = BigInteger(1, priv)
            if (d > BigInteger.ZERO && d < n) {
                return KeyPair(priv, publicKeyFromPrivate(priv))
            }
        }
    }

    fun deriveFromSeed32(seed32: ByteArray): KeyPair {
        require(seed32.size == 32)
        var candidate = seed32.copyOf()
        while (true) {
            val d = BigInteger(1, candidate)
            if (d > BigInteger.ZERO && d < n) {
                return KeyPair(candidate, publicKeyFromPrivate(candidate))
            }
            candidate = MessageDigest.getInstance("SHA-256").digest(candidate)
        }
    }

    fun publicKeyFromPrivate(secKey32: ByteArray): ByteArray {
        require(secKey32.size == 32) { "secp256k1 secret key must be 32 bytes" }
        val d0 = BigInteger(1, secKey32)
        require(d0 > BigInteger.ZERO && d0 < n) { "Invalid secp256k1 scalar" }
        val point = multiplier.multiply(G, d0).normalize()
        return bigIntTo32Bytes(point.affineXCoord.toBigInteger())
    }

    fun sign(
        msg32: ByteArray,
        secKey32: ByteArray,
        auxRand32: ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }
    ): ByteArray {
        require(msg32.size == 32) { "BIP-340 message digest must be 32 bytes" }
        require(secKey32.size == 32) { "BIP-340 secret key must be 32 bytes" }
        require(auxRand32.size == 32) { "BIP-340 auxRand must be 32 bytes" }

        val d0 = BigInteger(1, secKey32)
        require(d0 > BigInteger.ZERO && d0 < n) { "Out-of-range secret scalar" }

        val P = multiplier.multiply(G, d0).normalize()
        val pxBytes = bigIntTo32Bytes(P.affineXCoord.toBigInteger())
        val py = P.affineYCoord.toBigInteger()
        val d = if (!py.testBit(0)) d0 else n.subtract(d0)

        val dBytes = bigIntTo32Bytes(d)
        val auxHash = taggedHash("BIP0340/aux", auxRand32)
        val t = ByteArray(32) { idx -> (dBytes[idx].toInt() xor auxHash[idx].toInt()).toByte() }

        val randHash = taggedHash("BIP0340/nonce", t + pxBytes + msg32)
        val k0 = BigInteger(1, randHash).mod(n)
        check(k0 != BigInteger.ZERO) { "Zero nonce scalar" }

        val R = multiplier.multiply(G, k0).normalize()
        val rxBytes = bigIntTo32Bytes(R.affineXCoord.toBigInteger())
        val ry = R.affineYCoord.toBigInteger()
        val k = if (!ry.testBit(0)) k0 else n.subtract(k0)

        val eHash = taggedHash("BIP0340/challenge", rxBytes + pxBytes + msg32)
        val e = BigInteger(1, eHash).mod(n)

        val s = k.add(e.multiply(d)).mod(n)
        return rxBytes + bigIntTo32Bytes(s)
    }

    fun verify(msg32: ByteArray, pubKeyX32: ByteArray, sig64: ByteArray): Boolean {
        if (msg32.size != 32 || pubKeyX32.size != 32 || sig64.size != 64) return false
        return try {
            val P = liftX(BigInteger(1, pubKeyX32)) ?: return false
            val rBytes = sig64.copyOfRange(0, 32)
            val sBytes = sig64.copyOfRange(32, 64)
            val r = BigInteger(1, rBytes)
            val s = BigInteger(1, sBytes)
            if (r >= p || s >= n) return false

            val eHash = taggedHash("BIP0340/challenge", rBytes + pubKeyX32 + msg32)
            val e = BigInteger(1, eHash).mod(n)

            // R = s*G - e*P = s*G + (n - e)*P
            val sG = multiplier.multiply(G, s)
            val negEP = P.multiply(n.subtract(e).mod(n))
            val R = sG.add(negEP).normalize()
            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().testBit(0)) return false
            R.affineXCoord.toBigInteger() == r
        } catch (_: Exception) {
            false
        }
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x < BigInteger.ZERO || x >= p) return null
        val c = x.modPow(THREE, p).add(SEVEN).mod(p)
        val y = c.modPow(SQRT_EXP, p)
        if (y.modPow( BigInteger.TWO, p) != c) return null
        val evenY = if (!y.testBit(0)) y else p.subtract(y)
        return curve.validatePoint(x, evenY)
    }

    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val tagHash = sha256.digest(tag.encodeToByteArray())
        sha256.reset()
        sha256.update(tagHash)
        sha256.update(tagHash)
        sha256.update(msg)
        return sha256.digest()
    }

    private fun bigIntTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also {
                System.arraycopy(raw, 0, it, 32 - raw.size, raw.size)
            }
        }
    }
}
