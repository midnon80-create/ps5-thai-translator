package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val pad = PadConfig(context)

	/** Lets go of every button and stick (app paused, controller reconnected): nothing stays held down. */
	fun releaseAll()
	{
		keyControllerState.buttons = 0U; keyControllerState.l2State = 0U; keyControllerState.r2State = 0U
		motionControllerState.buttons = 0U; motionControllerState.l2State = 0U; motionControllerState.r2State = 0U
		motionControllerState.leftX = 0; motionControllerState.leftY = 0; motionControllerState.rightX = 0; motionControllerState.rightY = 0
		controllerStateUpdated()
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		// fixes from the "จอย" screen: swapped buttons on boxes / adapters
		val keyCode = if(PadConfig.fromPad(event)) pad.mapKey(event.keyCode, event.device) else event.keyCode

		when(keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
		}

		val buttonMask: UInt = when(keyCode)
		{
			// most pads send the d-pad as a hat axis (MotionEvents); some boxes / adapters send keys instead
			KeyEvent.KEYCODE_DPAD_LEFT -> if(PadConfig.fromPad(event)) ControllerState.BUTTON_DPAD_LEFT else return false
			KeyEvent.KEYCODE_DPAD_RIGHT -> if(PadConfig.fromPad(event)) ControllerState.BUTTON_DPAD_RIGHT else return false
			KeyEvent.KEYCODE_DPAD_UP -> if(PadConfig.fromPad(event)) ControllerState.BUTTON_DPAD_UP else return false
			KeyEvent.KEYCODE_DPAD_DOWN -> if(PadConfig.fromPad(event)) ControllerState.BUTTON_DPAD_DOWN else return false
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			PadConfig.KEY_TOUCHPAD -> ControllerState.BUTTON_TOUCHPAD
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	/**
	 * The DualSense touchpad: Android TV makes it a separate pointer ("mouse") device, so pressing it moved an arrow
	 * instead of reaching the game. A click on a Sony controller's pointer device = the PS5 touchpad button.
	 * Returns true when the event was the controller's touchpad (it is then never used as a mouse).
	 */
	fun onPointerEvent(event: MotionEvent): Boolean
	{
		val pointer = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
			event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
		if(!pointer || event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
		val d = event.device ?: return false
		val name = d.name.orEmpty().lowercase()
		val sony = d.vendorId == 0x054C || "dualsense" in name || "wireless controller" in name || "dualshock" in name
		if(!sony) return false
		val pressed = when(event.actionMasked)
		{
			MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_DOWN -> true
			MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
			else -> (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
		}
		val was = (keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD) != 0U
		if(pressed != was)
		{
			keyControllerState.buttons = if(pressed) keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
				else keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			controllerStateUpdated()
		}
		return true
	}

	/**
	 * Per-controller self-calibration ("the camera keeps spinning by itself").
	 *  - A stick that rests a little off centre (worn / drifting pads) turns the camera slowly forever: an axis that
	 *    stays still near the centre for 2 s becomes the new zero.
	 *  - Some pads / TVs declare their axes wrongly, so the right stick was read from the trigger axes, which rest at
	 *    -1: the game saw the stick held full left/up all the time and the camera spun non-stop. Stick axes never rest
	 *    pinned at an end; when the ones in use do (for 1.5 s, both of them) the other pair is used instead.
	 */
	private class StickCal
	{
		private val axes = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
		private val last = FloatArray(6) { Float.NaN }
		private val stillSince = LongArray(6)
		private val centre = FloatArray(6)
		/** 0 = no verdict, 1 = Z/RZ rest pinned (they are triggers), 2 = RX/RY rest pinned (they are triggers). */
		var pinnedPair = 0
			private set
		private var pinnedSince = 0L
		private var pinnedWhich = 0

		fun watch(e: MotionEvent)
		{
			val now = android.os.SystemClock.elapsedRealtime()
			for(i in axes.indices)
			{
				val v = e.getAxisValue(axes[i])
				if(last[i].isNaN() || kotlin.math.abs(v - last[i]) > 0.02f) { stillSince[i] = now; last[i] = v; continue }
				// still for 3 s very close to the middle: that is where this (worn) stick rests. Only small offsets
				// (real drift is < 0.15): a stick the player holds gently for a while (slow walk, careful aim) must never
				// become the new centre, or letting go of it made the character walk / the camera turn by itself.
				if(now - stillSince[i] > 3000 && kotlin.math.abs(v) < 0.15f) centre[i] = v
			}
			// the pair in use as the right stick both pinned at an end while the other pair rests near the middle
			// (a trigger rests exactly at the end and does not move; a stick pushed into a corner is never that still)
			fun pinned(a: Int, b: Int) = kotlin.math.abs(last[a]) > 0.98f && kotlin.math.abs(last[b]) > 0.98f &&
				now - stillSince[a] > 1500 && now - stillSince[b] > 1500
			val zPinned = pinned(2, 3)
			val rxPinned = pinned(4, 5)
			val zMid = kotlin.math.abs(last[2]) < 0.3f && kotlin.math.abs(last[3]) < 0.3f
			val rxMid = kotlin.math.abs(last[4]) < 0.3f && kotlin.math.abs(last[5]) < 0.3f
			val which = if(zPinned && rxMid) 1 else if(rxPinned && zMid) 2 else 0
			if(which == 0 || which != pinnedWhich) { pinnedWhich = which; pinnedSince = if(which == 0) 0L else now; return }
			if(now - pinnedSince > 1500) pinnedPair = which
		}

		fun centred(axis: Int, v: Float): Float
		{
			val i = axes.indexOf(axis)
			return if(i < 0) v else (v - centre[i]).coerceIn(-1f, 1f)
		}

		/** Stick let go: inside the dead zone around the real middle OR around the learned rest point = no movement. */
		fun atRest(axis: Int, v: Float, dz: Float): Boolean
		{
			val i = axes.indexOf(axis)
			return kotlin.math.abs(v) <= dz || (i >= 0 && kotlin.math.abs(v - centre[i]) <= dz)
		}
	}
	private val cals = HashMap<Int, StickCal>()

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(onPointerEvent(event)) return true
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		// sticks with a dead zone (no drifting); the right stick / triggers come from other axes on some boxes
		val cal = cals.getOrPut(event.deviceId) { StickCal() }
		cal.watch(event)
		// the right stick's axes as declared, unless they turned out to be triggers (resting pinned at -1 / +1)
		val auto = pad.sticks == PadConfig.Sticks.AUTO
		val declared = pad.rightStickOnRx(event.device)
		val rx = if(auto && cal.pinnedPair == 1) true else if(auto && cal.pinnedPair == 2) false else declared
		fun axis(a: Int): Short { val v = event.getAxisValue(a); return if(cal.atRest(a, v, pad.deadzone)) 0 else pad.stick(cal.centred(a, v)).signedAxis() }
		motionControllerState.leftX = axis(MotionEvent.AXIS_X)
		motionControllerState.leftY = axis(MotionEvent.AXIS_Y)
		motionControllerState.rightX = axis(if(rx) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z)
		motionControllerState.rightY = axis(if(rx) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ)
		val sonyGeneric = PadConfig.sonyGeneric(event.device)
		fun trigger(main: Int, alt: Int, rxAxis: Int): Float
		{
			if((sonyGeneric && auto) || (auto && cal.pinnedPair == 2))
			{
				// PlayStation pad on the generic driver: triggers on RX (L2) / RY (R2), resting at -1
				val v = event.getAxisValue(if(main == MotionEvent.AXIS_LTRIGGER) MotionEvent.AXIS_RX else MotionEvent.AXIS_RY)
				return ((v + 1f) / 2f).coerceIn(0f, 1f)
			}
			if(rx)
			{
				// some of those pads report the trigger as -1..1 (rest = -1), others as 0..1
				val v = event.getAxisValue(rxAxis)
				val range = event.device?.getMotionRange(rxAxis)
				return (if(range != null && range.min < -0.5f) (v + 1f) / 2f else v).coerceIn(0f, 1f)
			}
			return maxOf(event.getAxisValue(main), event.getAxisValue(alt)).coerceIn(0f, 1f)
		}
		motionControllerState.l2State = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_Z).unsignedAxis()
		motionControllerState.r2State = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_RZ).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}