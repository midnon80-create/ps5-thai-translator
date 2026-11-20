package com.metallic.chiaki.tv

import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Controller picture for the "จอย" screen: every button / stick lights up while it is pressed, so the player
 * sees at once whether buttons are swapped or a stick moves by itself. Drawn in a 740x470 box.
 */
class PadView(c: Context) : View(c)
{
	private val down = HashSet<Int>()
	private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
	private var l2 = 0f; private var r2 = 0f
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)
	private val body = Path().apply {
		moveTo(150f, 90f); quadTo(370f, 40f, 590f, 90f); quadTo(700f, 120f, 720f, 300f); quadTo(735f, 430f, 640f, 440f)
		quadTo(575f, 445f, 520f, 340f); lineTo(220f, 340f); quadTo(165f, 445f, 100f, 440f); quadTo(5f, 430f, 20f, 300f)
		quadTo(40f, 120f, 150f, 90f); close()
	}
	private val on = 0xFFA78BFA.toInt()
	private val off = 0xFF3A2D5C.toInt()

	fun key(code: Int, pressed: Boolean) { if(pressed) down += code else down -= code; invalidate() }

	fun motion(e: MotionEvent)
	{
		lx = e.getAxisValue(MotionEvent.AXIS_X); ly = e.getAxisValue(MotionEvent.AXIS_Y)
		rx = e.getAxisValue(MotionEvent.AXIS_Z); ry = e.getAxisValue(MotionEvent.AXIS_RZ)
		if(e.device?.getMotionRange(MotionEvent.AXIS_RX) != null && e.device?.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null)
		{ rx = e.getAxisValue(MotionEvent.AXIS_RX); ry = e.getAxisValue(MotionEvent.AXIS_RY) }
		l2 = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
		r2 = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
		val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
		fun set(k: Int, v: Boolean) { if(v) down += k else down -= k }
		set(KeyEvent.KEYCODE_DPAD_LEFT + 1000, hx < -0.5f); set(KeyEvent.KEYCODE_DPAD_RIGHT + 1000, hx > 0.5f)
		set(KeyEvent.KEYCODE_DPAD_UP + 1000, hy < -0.5f); set(KeyEvent.KEYCODE_DPAD_DOWN + 1000, hy > 0.5f)
		invalidate()
	}

	private fun lit(vararg codes: Int) = codes.any { it in down || it + 1000 in down }

	override fun onDraw(canvas: Canvas)
	{
		val s = minOf(width / 740f, height / 470f)
		canvas.save()
		canvas.translate((width - 740f * s) / 2f, (height - 470f * s) / 2f)
		canvas.scale(s, s)
		// shoulder buttons
		p.style = Paint.Style.FILL
		p.color = if(lit(KeyEvent.KEYCODE_BUTTON_L1)) on else off; canvas.drawRoundRect(120f, 52f, 250f, 80f, 14f, 14f, p)
		p.color = if(lit(KeyEvent.KEYCODE_BUTTON_R1)) on else off; canvas.drawRoundRect(490f, 52f, 620f, 80f, 14f, 14f, p)
		p.color = mix(off, on, if(lit(KeyEvent.KEYCODE_BUTTON_L2)) 1f else l2); canvas.drawRoundRect(130f, 18f, 240f, 48f, 14f, 14f, p)
		p.color = mix(off, on, if(lit(KeyEvent.KEYCODE_BUTTON_R2)) 1f else r2); canvas.drawRoundRect(500f, 18f, 610f, 48f, 14f, 14f, p)
		// body
		p.shader = LinearGradient(0f, 40f, 0f, 445f, 0xFF2A1D4A.toInt(), 0xFF140E26.toInt(), Shader.TileMode.CLAMP)
		canvas.drawPath(body, p); p.shader = null
		p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF6D5AA8.toInt(); canvas.drawPath(body, p)
		// touchpad
		p.style = Paint.Style.FILL; p.color = 0xFF0D0918.toInt(); canvas.drawRoundRect(280f, 100f, 460f, 190f, 20f, 20f, p)
		// d-pad
		fun pad(x: Float, y: Float, vararg k: Int) { p.color = if(lit(*k)) on else off; canvas.drawRoundRect(x, y, x + 34f, y + 34f, 6f, 6f, p) }
		pad(140f, 170f, KeyEvent.KEYCODE_DPAD_UP); pad(140f, 238f, KeyEvent.KEYCODE_DPAD_DOWN)
		pad(106f, 204f, KeyEvent.KEYCODE_DPAD_LEFT); pad(174f, 204f, KeyEvent.KEYCODE_DPAD_RIGHT)
		// face buttons: △ top, ○ right, ✕ bottom, □ left
		face(canvas, 590f, 170f, "△", KeyEvent.KEYCODE_BUTTON_Y)
		face(canvas, 635f, 215f, "○", KeyEvent.KEYCODE_BUTTON_B)
		face(canvas, 590f, 260f, "✕", KeyEvent.KEYCODE_BUTTON_A)
		face(canvas, 545f, 215f, "□", KeyEvent.KEYCODE_BUTTON_X)
		// options / share / PS
		p.color = if(lit(KeyEvent.KEYCODE_BUTTON_SELECT)) on else off; canvas.drawRoundRect(240f, 110f, 262f, 150f, 8f, 8f, p)
		p.color = if(lit(KeyEvent.KEYCODE_BUTTON_START)) on else off; canvas.drawRoundRect(478f, 110f, 500f, 150f, 8f, 8f, p)
		p.color = if(lit(KeyEvent.KEYCODE_BUTTON_MODE)) on else off; canvas.drawCircle(370f, 260f, 16f, p)
		// sticks (the dot follows the stick; lit when clicked)
		stick(canvas, 270f, 300f, lx, ly, KeyEvent.KEYCODE_BUTTON_THUMBL)
		stick(canvas, 470f, 300f, rx, ry, KeyEvent.KEYCODE_BUTTON_THUMBR)
		canvas.restore()
	}

	private fun face(canvas: Canvas, x: Float, y: Float, label: String, code: Int)
	{
		val l = lit(code)
		p.style = Paint.Style.FILL
		p.color = if(l) on else off
		if(l) p.setShadowLayer(18f, 0f, 0f, 0xFFC084FC.toInt())
		canvas.drawCircle(x, y, 24f, p)
		p.clearShadowLayer()
		p.color = if(l) Color.WHITE else 0xFFA59CC0.toInt()
		p.textSize = 24f; p.textAlign = Paint.Align.CENTER
		canvas.drawText(label, x, y + 9f, p)
	}

	private fun stick(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float, click: Int)
	{
		p.style = Paint.Style.FILL; p.color = if(lit(click)) 0xFF3B2A66.toInt() else 0xFF1D1534.toInt()
		canvas.drawCircle(x, y, 40f, p)
		p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF4B3C7A.toInt(); canvas.drawCircle(x, y, 40f, p)
		p.style = Paint.Style.FILL
		val moved = kotlin.math.abs(sx) > 0.08f || kotlin.math.abs(sy) > 0.08f
		p.color = if(moved) on else 0xFF6D5AA8.toInt()
		canvas.drawCircle(x + sx * 24f, y + sy * 24f, 14f, p)
	}

	private fun mix(a: Int, b: Int, t: Float): Int
	{
		val k = t.coerceIn(0f, 1f)
		return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
			(Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
			(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
	}
}
