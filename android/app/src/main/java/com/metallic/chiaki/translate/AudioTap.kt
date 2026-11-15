package com.metallic.chiaki.translate

/** Reads the game audio (mono, 16 kHz, 16-bit) copied out of the stream by cpp/audio-tap.c. */
object AudioTap
{
	const val SAMPLE_RATE = 16000

	init
	{
		System.loadLibrary("chiaki-jni")
	}

	@JvmStatic external fun nativeSetEnabled(enabled: Boolean)
	@JvmStatic external fun nativeRead(out: ShortArray, max: Int): Int
	@JvmStatic external fun nativeConfigure(channels: Int, rate: Int)
	@JvmStatic external fun nativePush(data: ShortArray, count: Int)
}
