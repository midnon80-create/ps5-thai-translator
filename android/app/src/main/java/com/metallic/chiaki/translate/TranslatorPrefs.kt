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

	val mode get() = when(sp.getString("tr_mode", "both")) {
		"screen" -> Mode.SCREEN
		"speech" -> Mode.SPEECH
		else -> Mode.BOTH
	}

	val region get() = if(sp.getString("tr_region", "bottom") == "full") Region.FULL else Region.BOTTOM

	val position get() = when(sp.getString("tr_position", "above")) {
		"top" -> Position.TOP
		"bottom" -> Position.BOTTOM
		else -> Position.ABOVE_SUBTITLE
	}

	val font get() = if(sp.getString("tr_font", "sarabun") == "kanit") Font.KANIT else Font.SARABUN

	/** Text height as a fraction of the screen height, so it looks the same on every TV size and resolution. */
	val textHeightFraction get() = when(sp.getString("tr_text_size", "medium")) {
		"small" -> 0.034f
		"large" -> 0.048f
		else -> 0.040f
	}

	val showEnglish get() = sp.getBoolean("tr_show_english", false)

	val geminiKey get() = sp.getString("tr_gemini_key", "")?.trim().orEmpty()

	val geminiModel get() = sp.getString("tr_gemini_model", "")?.trim().orEmpty().ifEmpty { DEFAULT_GEMINI_MODEL }

	companion object
	{
		const val DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"
	}
}
