package com.metallic.chiaki.session

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.preference.PreferenceManager

/**
 * Controller fixes set on the "จอย" screen, used while streaming:
 *  - button remap: physical key -> the key it should act as (fixes swapped buttons on TV boxes / adapters)
 *  - stick layout: where the right stick and triggers come from (some boxes report them on other axes,
 *    which looks like a stuck or self-moving stick)
 *  - dead zone: small stick movements are ignored (stops drifting)
 */
class PadConfig(context: Context)
{
	enum class Sticks(val value: String) { AUTO("auto"), Z_RZ("z"), RX_RY("rx") }

	private val sp = PreferenceManager.getDefaultSharedPreferences(context)

	val remap: Map<Int, Int> = parse(sp.getString(KEY_REMAP, "") ?: "")
	val sticks: Sticks = sp.getString(KEY_STICKS, "auto").let { v -> Sticks.values().firstOrNull { it.value == v } } ?: Sticks.AUTO
	val deadzone: Float = sp.getFloat(KEY_DEADZONE, DEFAULT_DEADZONE)

	fun mapKey(code: Int) = remap[code] ?: code

	/** Right stick on RX/RY (and triggers on Z/RZ) for this controller? */
	fun rightStickOnRx(device: InputDevice?): Boolean = when(sticks)
	{
		Sticks.Z_RZ -> false
		Sticks.RX_RY -> true
		Sticks.AUTO -> device != null &&
			device.getMotionRange(MotionEvent.AXIS_RX) != null && device.getMotionRange(MotionEvent.AXIS_RY) != null &&
			device.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null && device.getMotionRange(MotionEvent.AXIS_BRAKE) == null
	}

	/** Stick value with the dead zone removed and the rest stretched back to the full range. */
	fun stick(v: Float): Float
	{
		val a = kotlin.math.abs(v)
		if(a <= deadzone) return 0f
		return Math.copySign(((a - deadzone) / (1f - deadzone)).coerceAtMost(1f), v)
	}

	companion object
	{
		const val KEY_REMAP = "pad_remap"
		const val KEY_STICKS = "pad_sticks"
		const val KEY_DEADZONE = "pad_deadzone"
		const val DEFAULT_DEADZONE = 0.08f
		const val STRONG_DEADZONE = 0.16f

		/** The four face buttons the remap wizard asks for, in order: ✕ ○ □ △. */
		val FACE = intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)

		private fun parse(s: String): Map<Int, Int> = s.split(',').mapNotNull { p ->
			val kv = p.split(':')
			if(kv.size == 2) kv[0].toIntOrNull()?.let { k -> kv[1].toIntOrNull()?.let { v -> k to v } } else null
		}.toMap()

		fun saveRemap(context: Context, map: Map<Int, Int>) =
			PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putString(KEY_REMAP, map.filter { it.key != it.value }.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

		fun setSticks(context: Context, s: Sticks) =
			PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_STICKS, s.value).apply()

		fun setDeadzone(context: Context, d: Float) =
			PreferenceManager.getDefaultSharedPreferences(context).edit().putFloat(KEY_DEADZONE, d).apply()

		/** "แก้จอยค้าง": forget remaps and layouts, use a larger dead zone. */
		fun resetStuck(context: Context) =
			PreferenceManager.getDefaultSharedPreferences(context).edit()
				.remove(KEY_REMAP).remove(KEY_STICKS).putFloat(KEY_DEADZONE, STRONG_DEADZONE).apply()

		/** True for events from a game controller (not the TV remote). */
		fun fromPad(event: android.view.InputEvent) =
			event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
				event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
	}
}
