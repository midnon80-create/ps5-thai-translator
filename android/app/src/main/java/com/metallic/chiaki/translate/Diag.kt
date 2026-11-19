package com.metallic.chiaki.translate

import android.content.Context
import android.view.InputDevice
import androidx.preference.PreferenceManager

/**
 * What happened during the last play session, for the "ตรวจปัญหา" (check) screen:
 * did the PS5 stream connect, were English subtitles seen, did the AI answer, why was offline used.
 * Writes are throttled (at most once per 5 s per item).
 */
object Diag
{
	const val STREAM_AT = "diag_stream_at"
	const val SUBTITLE_AT = "diag_subtitle_at"
	const val AI_AT = "diag_ai_at"
	const val OFFLINE_AT = "diag_offline_at"
	const val OFFLINE_WHY = "diag_offline_why"
	const val NO_PAD_AT = "diag_no_pad_at"

	private val lastWrite = HashMap<String, Long>()

	fun mark(context: Context, key: String, extra: Pair<String, String>? = null)
	{
		val now = System.currentTimeMillis()
		synchronized(lastWrite) {
			if(now - (lastWrite[key] ?: 0L) < 5000L) return
			lastWrite[key] = now
		}
		val e = PreferenceManager.getDefaultSharedPreferences(context).edit().putLong(key, now)
		extra?.let { e.putString(it.first, it.second) }
		e.apply()
	}

	fun at(context: Context, key: String) = PreferenceManager.getDefaultSharedPreferences(context).getLong(key, 0L)
	fun text(context: Context, key: String) = PreferenceManager.getDefaultSharedPreferences(context).getString(key, "") ?: ""

	/** Names of game controllers connected to the TV (USB or Bluetooth). A controller paired to the PS5 is not listed. */
	fun gamepads(): List<String> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
		val d = InputDevice.getDevice(id) ?: return@mapNotNull null
		val s = d.sources
		val pad = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
		if(pad && !d.isVirtual) d.name else null
	}.distinct()
}
