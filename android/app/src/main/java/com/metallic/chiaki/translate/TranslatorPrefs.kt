package com.metallic.chiaki.translate

import android.content.Context
import androidx.preference.PreferenceManager

/** Translator settings, stored next to the normal Chiaki settings. Keys match res/xml/preferences.xml. */
class TranslatorPrefs(context: Context)
{
	enum class Mode { BOTH, SCREEN, SPEECH }
	enum class Region { BOTTOM, FULL }
	enum class Position { ABOVE_SUBTITLE, TOP, BOTTOM }
	enum class Font { SARABUN, KANIT }

	private val sp = PreferenceManager.getDefaultSharedPreferences(context)

	val enabled get() = sp.getBoolean("tr_enabled", true)

	// 32-bit-only TVs are usually slow: listening to speech on top of the stream makes them lag
	private val slowDevice = android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()

	// default: on-screen subtitles only - listening to speech costs the most CPU and games show subtitles anyway
	val mode get() = when(sp.getString("tr_mode", "screen")) {
		"screen" -> Mode.SCREEN
		"speech" -> Mode.SPEECH
		else -> Mode.BOTH
	}

	/** FULL = whole screen sorted into dialogue / prompts / hints / documents; BOTTOM = subtitles only (lighter). */
	/** Off (default) = only dialogue subtitles are translated; no boxes over buttons, tips or documents. */
	val translateUi get() = sp.getBoolean("tr_ui_text", false)

	// default: read only the lower part of the picture (where subtitles are) - about half the work, smoother play
	val region get() = if(sp.getString("tr_read_area", "bottom") == "full") Region.FULL else Region.BOTTOM

	val position get() = when(sp.getString("tr_position", "above")) {
		"top" -> Position.TOP
		"bottom" -> Position.BOTTOM
		else -> Position.ABOVE_SUBTITLE
	}

	val font get() = if(sp.getString("tr_font", "sarabun") == "kanit") Font.KANIT else Font.SARABUN

	/** Text height as a fraction of the screen height, so it looks the same on every TV size and resolution. */
	val textHeightFraction get() = when(sp.getString("tr_text_size", "medium")) {
		"small" -> 0.040f
		"large" -> 0.052f
		else -> 0.046f // medium = the size in the trailer sample
	}

	/** Set by the translator test: this TV can't recognise speech in real time. */
	val speechSlow get() = sp.getBoolean(KEY_SPEECH_SLOW, false)
	fun setSpeechSlow(v: Boolean) = sp.edit().putBoolean(KEY_SPEECH_SLOW, v).apply()

	/** Set by the test / the live watchdog: use the lighter speech settings on this TV. */
	val speechFast get() = sp.getBoolean(KEY_SPEECH_FAST, false)
	fun setSpeechFast(v: Boolean) = sp.edit().putBoolean(KEY_SPEECH_FAST, v).apply()

	/** True when "แปลอะไร" was chosen by the app (not the user), so the app may change it again. */
	val modeIsAutomatic get() = !sp.contains(KEY_MODE) || sp.getBoolean(KEY_MODE_AUTO, false)
	fun setModeAutomatically(m: Mode) { setMode(m); sp.edit().putBoolean(KEY_MODE_AUTO, true).apply() }

	fun setMode(m: Mode) = sp.edit().putString(KEY_MODE, when(m) { Mode.SCREEN -> "screen"; Mode.SPEECH -> "speech"; Mode.BOTH -> "both" }).apply()

	val showEnglish get() = sp.getBoolean("tr_show_english", false)

	/** Licence code from the seller (EYE-XXXX-XXXX): turns on Gemini translation through the shared server. */
	val licence get() = sp.getString(KEY_LICENCE, "")?.trim()?.uppercase().orEmpty()

	/**
	 * Code sent to the server: the typed licence, or "AUTO-TRIAL" (GitHub build only). Free trials are switched
	 * off on the server now (paid 1-day code instead); trials that already started still run to their end date.
	 */
	val cloudCode get() = licence.ifBlank { if(com.metallic.chiaki.store.Store.IS_PLAY) "" else "AUTO-TRIAL" }

	/** Hidden override for testing; normally the built-in server. */
	val serverUrl get() = sp.getString("tr_server_url", "")?.trim().orEmpty().ifEmpty { SERVER_URL }

	val geminiKey get() = sp.getString("tr_gemini_key", "")?.trim().orEmpty()

	val geminiModel get() = sp.getString("tr_gemini_model", "")?.trim().orEmpty().ifEmpty { DEFAULT_GEMINI_MODEL }

	companion object
	{
		const val KEY_MODE = "tr_mode"
		const val KEY_LICENCE = "tr_licence"
		/** Eyesmaxxing server (Cloudflare Worker); empty = cloud off until it is deployed. */
		const val SERVER_URL = "https://eyesmaxxing.midtdqxx.workers.dev"
		const val KEY_SPEECH_SLOW = "tr_speech_slow"
		const val KEY_SPEECH_SLOW_WARNED = "tr_speech_slow_warned"
		const val KEY_SPEECH_FAST = "tr_speech_fast"
		const val KEY_MODE_AUTO = "tr_mode_auto"
		const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"
	}
}
