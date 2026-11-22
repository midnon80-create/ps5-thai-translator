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

	/** Hardware HEVC decoder at 720p 60 (weaker boxes / phones). Software HEVC is too slow and breaks the picture. */
	val hevc720: Boolean = hevc1080 || try
	{
		MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
			!info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
				!info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") &&
				info.getCapabilitiesForType("video/hevc").videoCapabilities?.areSizeAndRateSupported(1280, 720, 60.0) != false
		}
	}
	catch(_: Throwable) { false }

	/** Strong enough for the sharpest picture? */
	val strong get() = hevc1080 && ramGb >= 1.7 && sdk >= 26

	fun autoTitle(c: Context) = when(autoLevel(c, this)) { 0 -> "1080p คมสุดสำหรับเครื่องนี้"; 1 -> "1080p สำหรับเครื่องนี้"; else -> "1080p เบาเครื่อง" }

	companion object
	{
		const val KEY_QUALITY = "tv_quality" // auto | smooth | sharp
		/** Auto mode only: 0 = sharpest, 1 = 1080p lighter, 2 = 720p. Learned from how the last games went. */
		private const val KEY_AUTO_STEP = "tv_auto_step"
		private const val KEY_CLEAN_RUNS = "tv_auto_clean"

		fun quality(c: Context) = PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_QUALITY, "auto") ?: "auto"

		private fun autoLevel(c: Context, caps: DeviceCaps): Int
		{
			val step = PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY_AUTO_STEP, 0)
			val base = if(!caps.strong) 2 else if(caps.wifiGhz == 2.4) 1 else 0
			return (base + step).coerceIn(0, 2)
		}

		/** Writes the stream settings for the chosen quality (auto = what this device handles well). */
		fun apply(c: Context, mode: String = quality(c))
		{
			PreferenceManager.getDefaultSharedPreferences(c).edit().putString(KEY_QUALITY, mode).apply()
			val caps = DeviceCaps(c)
			val p = Preferences(c)
			// PS5 Remote Play tops out at 1080p 60 (no 4K); sharpness comes from the bit rate and H.265
			// always 1080p now (720p is gone): weaker TVs get a lighter bit rate / H.264 instead of a smaller picture
			val level = when(mode) { "sharp" -> 0; else -> autoLevel(c, caps) }
			val hevc = caps.hevc1080
			p.resolution = Preferences.Resolution.RES_1080P
			p.fps = Preferences.FPS.FPS_60
			p.codec = if(hevc) Preferences.Codec.CODEC_H265 else Preferences.Codec.CODEC_H264
			p.bitrate = when(level)
			{
				// big screens: more data = fewer blocks; the TV itself upscales 1080p to its 4K panel
				0 -> if(caps.wired) 35000 else if(caps.wifiGhz == 5.0) 30000 else 22000
				1 -> 15000
				else -> if(hevc) 10000 else 12000
			}
		}

		/**
		 * After a game (auto mode): count broken frames in the session log. A broken-up picture means the network or
		 * the decoder could not keep up, so the next game uses one step lighter; three clean games step back up.
		 */
		fun learnFromSession(c: Context, log: java.io.File?, seconds: Long)
		{
			if(quality(c) != "auto" || log == null || !log.exists() || seconds < 60) return
			val lines = try { log.readLines() } catch(_: Throwable) { return }
			val bad = lines.count { it.contains("missing or corrupt frame", true) || it.contains("Failed to get input buffer", true) }
			val perMin = bad * 60.0 / seconds
			val sp = PreferenceManager.getDefaultSharedPreferences(c)
			val step = sp.getInt(KEY_AUTO_STEP, 0)
			when
			{
				perMin >= 3.0 -> sp.edit().putInt(KEY_AUTO_STEP, (step + 1).coerceAtMost(2)).putInt(KEY_CLEAN_RUNS, 0).apply()
				perMin < 0.5 && step > 0 -> {
					val clean = sp.getInt(KEY_CLEAN_RUNS, 0) + 1
					if(clean >= 3) sp.edit().putInt(KEY_AUTO_STEP, step - 1).putInt(KEY_CLEAN_RUNS, 0).apply()
					else sp.edit().putInt(KEY_CLEAN_RUNS, clean).apply()
				}
			}
			apply(c, "auto")
		}
	}
}
