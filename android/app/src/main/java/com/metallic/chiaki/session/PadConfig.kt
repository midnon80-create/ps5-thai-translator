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

	/** The player's own fix first, then the built-in layout for PlayStation pads on TVs without a Sony driver. */
	fun mapKey(code: Int, device: InputDevice? = null): Int =
		remap[code] ?: (if(sonyGeneric(device)) SONY_GENERIC_KEYS[code] else null) ?: code

	/** Right stick on RX/RY (and triggers on Z/RZ) for this controller? */
	fun rightStickOnRx(device: InputDevice?): Boolean = when(sticks)
	{
		Sticks.Z_RZ -> false
		Sticks.RX_RY -> true
		Sticks.AUTO -> !sonyGeneric(device) && device != null &&
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

		/** A logical code meaning "touchpad click" (no Android keycode exists for it). */
		const val KEY_TOUCHPAD = KeyEvent.KEYCODE_BUTTON_16

		/**
		 * DualShock 4 / DualSense on TVs whose kernel has no Sony driver (old Android TV, many boxes):
		 * the generic HID driver sends the buttons in report order, so ○ arrives as BUTTON_C (= PS button!),
		 * L1/R1 as Y/Z, Share/Options as L2/R2, and the right stick sits on Z/RZ with the triggers on RX/RY.
		 */
		private val SONY_GENERIC_KEYS = mapOf(
			KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_X,       // □
			KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,       // ✕
			KeyEvent.KEYCODE_BUTTON_C to KeyEvent.KEYCODE_BUTTON_B,       // ○
			KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,       // △
			KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_L1,
			KeyEvent.KEYCODE_BUTTON_Z to KeyEvent.KEYCODE_BUTTON_R1,
			KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L2,
			KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R2,
			KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_SELECT,  // Share / Create
			KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_START,   // Options
			KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_THUMBL,
			KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_THUMBR,
			KeyEvent.KEYCODE_BUTTON_MODE to KeyEvent.KEYCODE_BUTTON_MODE,  // PS
			KeyEvent.KEYCODE_BUTTON_THUMBL to KEY_TOUCHPAD,
		)

		/** PlayStation pad running on the generic driver? (Sony driver pads have trigger axes and no BUTTON_C/Z) */
		fun sonyGeneric(device: InputDevice?): Boolean
		{
			if(device == null || device.vendorId != 0x054C) return false
			if(device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null) return false
			val has = device.hasKeys(KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z)
			return has[0] || has[1]
		}

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

		/**
		 * Once after updating to 1.7.7: old button fixes were made before the app knew the PlayStation layout
		 * (and the old wizard set only 4 buttons), so they now make things worse. Start clean; returns true if it cleared something.
		 */
		fun migrateOnce(context: Context): Boolean
		{
			val sp = PreferenceManager.getDefaultSharedPreferences(context)
			if(sp.getBoolean("pad_clean_177", false)) return false
			val had = !sp.getString(KEY_REMAP, "").isNullOrEmpty() || sp.contains(KEY_STICKS) || sp.contains(KEY_DEADZONE)
			sp.edit().remove(KEY_REMAP).remove(KEY_STICKS).remove(KEY_DEADZONE).putBoolean("pad_clean_177", true).apply()
			if(had) com.metallic.chiaki.common.Preferences(context).swapCrossMoon = false
			return had
		}

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
