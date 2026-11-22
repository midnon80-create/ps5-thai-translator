package com.metallic.chiaki.translate

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs requests to the Eyesmaxxing server, so the shared translation library answers only this app
 * (HMAC-SHA256 over time stamp, device and body). The key comes from the private build, not the public source.
 */
internal object AppSig
{
	private val key: ByteArray? by lazy {
		val m = com.metallic.chiaki.BuildConfig.EYES_K
		if(m.length != 64) null
		else ByteArray(32) { i -> (m.substring(i * 2, i * 2 + 2).toInt(16) xor ((i * 73 + 41) and 0xff)).toByte() }
	}

	private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

	/** Headers to add (empty when this build has no key). */
	fun headers(device: String, body: ByteArray): Map<String, String>
	{
		val k = key ?: return emptyMap()
		val ts = System.currentTimeMillis().toString()
		val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }
		val msg = ts + "\n" + device + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(body))
		return mapOf("x-eyes-ts" to ts, "x-eyes-sig" to hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8))))
	}
}
