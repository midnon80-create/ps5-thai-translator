package com.metallic.chiaki.tv

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.preference.PreferenceManager
import com.metallic.chiaki.common.Preferences

/**
 * What this TV / box can do, and the picture settings that suit it.
 * Remote Play from a PS5 is at most 1080p 60; big TVs look sharpest with 1080p + HEVC (H.265) at a high
 * bit rate, weak boxes play smoothest at 720p.
 */
class DeviceCaps(private val context: Context)
{
	val android = Build.VERSION.RELEASE ?: "?"
	val sdk = Build.VERSION.SDK_INT
	val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
	val ramGb = (context.getSystemService(ActivityManager::class.java)?.let { am ->
		ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem } ?: 0L) / 1_000_000_000.0

	/** Hardware HEVC decoder that can do 1080p 60. */
	val hevc1080: Boolean = try
	{
		MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
			!info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
				!info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") &&
				(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ||
					info.getCapabilitiesForType("video/hevc").videoCapabilities?.areSizeAndRateSupported(1920, 1080, 60.0) != false)
		}
	}
	catch(_: Throwable) { false }

	val wired: Boolean
	val wifi: Boolean
	init
	{
		val cm = context.getSystemService(ConnectivityManager::class.java)
		val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch(_: Throwable) { null }
		wired = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
		wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
	}

	/** Wi-Fi band in GHz (5 / 2.4), or 0 when unknown. */
	val wifiGhz: Double = try
	{
		@Suppress("DEPRECATION")
		val f = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.frequency ?: 0
		if(f > 4900) 5.0 else if(f in 2000..2600) 2.4 else 0.0
	}
	catch(_: Throwable) { 0.0 }

	/** Strong enough for the sharpest picture? */
	val strong get() = hevc1080 && ramGb >= 1.7 && sdk >= 26

	fun autoTitle() = if(strong) "1080p 60 เฟรม" else "720p 60 เฟรม"

	companion object
	{
		const val KEY_QUALITY = "tv_quality" // auto | smooth | sharp

		fun quality(c: Context) = PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_QUALITY, "auto") ?: "auto"

		/** Writes the stream settings for the chosen quality (auto = what this TV handles well). */
		fun apply(c: Context, mode: String = quality(c))
		{
			PreferenceManager.getDefaultSharedPreferences(c).edit().putString(KEY_QUALITY, mode).apply()
			val caps = DeviceCaps(c)
			val p = Preferences(c)
			val sharp = when(mode) { "sharp" -> true; "smooth" -> false; else -> caps.strong }
			p.resolution = if(sharp) Preferences.Resolution.RES_1080P else Preferences.Resolution.RES_720P
			p.fps = Preferences.FPS.FPS_60
			p.codec = if(caps.hevc1080 || !sharp) Preferences.Codec.CODEC_H265 else Preferences.Codec.CODEC_H264
			// big TVs: more data per frame = less blocky picture (the stream presets are conservative)
			// (not on 2.4 GHz Wi-Fi: that band can't carry it smoothly)
			p.bitrate = if(sharp && caps.wifiGhz != 2.4) 25000 else null
		}
	}
}
