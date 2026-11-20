package com.metallic.chiaki.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.metallic.chiaki.R
import kotlin.math.min

/**
 * Design system of the TV interface (black / purple). All sizes are pixels of a 1920x1080 design canvas
 * that [Stage] scales to the real screen, so every TV and box shows exactly the same layout.
 */
object Ui
{
	const val BG = 0xFF07050C.toInt()
	const val PANEL = 0xFF120D1F.toInt()
	const val PANEL2 = 0xFF1A1230.toInt()
	const val LINE = 0xFF2C2147.toInt()
	const val VIOLET = 0xFF8B5CF6.toInt()
	const val VIOLET2 = 0xFFA78BFA.toInt()
	const val GLOW = 0xFFC084FC.toInt()
	const val PURPLE = 0xFFA855F7.toInt()
	const val TEXT = 0xFFF5F1FF.toInt()
	const val MUTED = 0xFFA59CC0.toInt()
	const val DIM = 0xFF6F6690.toInt()
	const val OK = 0xFF4ADE80.toInt()
	const val WARN = 0xFFFBBF24.toInt()
	const val RING = 0xFFEFE7FF.toInt()

	enum class W { REGULAR, MEDIUM, SEMIBOLD, BOLD, EXTRABOLD }

	private val faces = HashMap<W, Typeface?>()
	fun face(c: Context, w: W): Typeface? = faces.getOrPut(w) {
		ResourcesCompat.getFont(c, when(w) {
			W.REGULAR -> R.font.kanit_regular; W.MEDIUM -> R.font.kanit_medium; W.SEMIBOLD -> R.font.kanit_semibold
			W.BOLD -> R.font.kanit_bold; W.EXTRABOLD -> R.font.kanit_extrabold
		})
	}

	fun text(c: Context, s: CharSequence, size: Float, color: Int = TEXT, w: W = W.REGULAR, gravity: Int = Gravity.START): TextView =
		TextView(c).apply {
			text = s
			setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
			setTextColor(color)
			typeface = face(c, w)
			this.gravity = gravity
			includeFontPadding = true
			setLineSpacing(0f, 1.08f)
		}

	fun round(fill: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
		setColor(fill); cornerRadius = radius
		if(stroke > 0) setStroke(stroke, strokeColor)
	}

	fun gradient(colors: IntArray, radius: Float, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
		stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable(orientation, colors).apply {
		cornerRadius = radius
		if(stroke > 0) setStroke(stroke, strokeColor)
	}

	fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0) = FrameLayout.LayoutParams(w, h).apply { leftMargin = l; topMargin = t }
	fun llp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
		LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

	const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
	const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

	fun col(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
	fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

	fun image(c: Context, res: Int, scale: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP) =
		ImageView(c).apply { setImageResource(res); scaleType = scale }

	/** Picture showing only its upper part (the shots carry their own burned-in subtitles at the bottom). */
	fun topImage(c: Context, res: Int, keep: Float) = object : ImageView(c)
	{
		init { setImageResource(res); scaleType = ScaleType.MATRIX }
		override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int)
		{
			super.onSizeChanged(w, h, ow, oh)
			val d = drawable ?: return
			val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight * keep
			val s = maxOf(w / iw, h / ih)
			imageMatrix = Matrix().apply { setScale(s, s); postTranslate((w - iw * s) / 2f, 0f) }
		}
	}

	/** Clips a child to rounded corners. */
	fun clipRound(v: View, radius: Float)
	{
		v.outlineProvider = object : android.view.ViewOutlineProvider()
		{
			override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
		}
		v.clipToOutline = true
	}

	/** Purple glow behind a focused card (coloured shadows: Android 9+; older TVs get the white ring only). */
	fun glow(v: View, on: Boolean)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
		{
			v.outlineAmbientShadowColor = GLOW
			v.outlineSpotShadowColor = PURPLE
		}
		v.animate().translationZ(if(on) 40f else 0f).setDuration(180).start()
	}

	/** Small round button icon used in the hint bar (✕ ○ ◀ ▶). */
	fun hint(c: Context, items: List<Pair<String, String>>): View
	{
		val r = row(c).apply { gravity = Gravity.CENTER }
		for((ico, label) in items)
		{
			val b = text(c, ico, 20f, TEXT, W.MEDIUM, Gravity.CENTER).apply { background = round(0, 19f, 2, MUTED) }
			r.addView(b, llp(38, 38, l = 46))
			r.addView(text(c, label, 24f, MUTED), llp(WRAP, WRAP, l = 10))
		}
		return r
	}

	fun dots(c: Context, count: Int, on: Int): View
	{
		val r = row(c).apply { gravity = Gravity.CENTER }
		for(i in 0 until count)
			r.addView(View(c).apply { background = round(if(i == on) VIOLET2 else 0xFF3A2D5C.toInt(), 7f) },
				llp(if(i == on) 46 else 14, 14, l = 7, r = 7))
		return r
	}

	/** Logo + name, top-left of every page. */
	fun brand(c: Context): View
	{
		val r = row(c).apply { gravity = Gravity.CENTER_VERTICAL }
		val logo = image(c, R.drawable.eyes_logo, ImageView.ScaleType.FIT_CENTER)
		clipRound(logo, 14f)
		r.addView(logo, llp(58, 58))
		r.addView(text(c, "Eyesmaxxing", 30f, TEXT, W.SEMIBOLD), llp(WRAP, WRAP, l = 16))
		return r
	}

	fun ease() = DecelerateInterpolator(2.2f)
}

/** Dark background with two soft purple lights and faint diagonal lines (as in the approved design). */
class BgDrawable : Drawable()
{
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)
	override fun draw(canvas: Canvas)
	{
		val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
		canvas.drawColor(Ui.BG)
		p.shader = RadialGradient(w * 0.85f, -h * 0.1f, w * 0.6f, 0x388B5CF6, 0, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, p)
		p.shader = RadialGradient(-w * 0.05f, h * 1.1f, w * 0.5f, 0x24C084FC, 0, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, p)
		p.shader = null
		p.strokeWidth = 2f
		p.color = 0x308B5CF6
		canvas.drawLine(w * 0.58f, 0f, w * 0.31f, h, p)
		p.color = 0x228B5CF6
		canvas.drawLine(w * 0.88f, 0f, w * 0.62f, h, p)
	}
	override fun setAlpha(alpha: Int) {}
	override fun setColorFilter(colorFilter: ColorFilter?) {}
	@Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * Holds a fixed 1920x1080 design canvas and scales it to fit the screen (letterboxed if the ratio differs):
 * the same picture on a 32" 720p TV, a 65" 4K TV, a stick or an old box.
 */
class Stage(c: Context) : FrameLayout(c)
{
	val canvas = FrameLayout(c)

	init
	{
		background = BgDrawable()
		canvas.background = BgDrawable()
		canvas.clipChildren = false
		addView(canvas, LayoutParams(1920, 1080))
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int)
	{
		super.onLayout(changed, l, t, r, b)
		val w = (r - l).toFloat(); val h = (b - t).toFloat()
		val s = min(w / 1920f, h / 1080f)
		canvas.pivotX = 0f; canvas.pivotY = 0f
		canvas.scaleX = s; canvas.scaleY = s
		canvas.translationX = (w - 1920f * s) / 2f
		canvas.translationY = (h - 1080f * s) / 2f
	}
}

/**
 * A card that can be chosen with the remote or a controller: on focus it grows a little, gets a white ring
 * and a purple glow; OK / ✕ clicks it.
 */
open class Card(c: Context, private val radius: Float = 26f, private val fill: Int = Ui.PANEL,
	private val selected: () -> Boolean = { false }) : FrameLayout(c)
{
	var grow = 1.05f

	init
	{
		isFocusable = true
		isFocusableInTouchMode = false
		isClickable = true
		clipChildren = false
		refresh()
		setOnFocusChangeListener { _, f ->
			refresh()
			animate().scaleX(if(f) grow else 1f).scaleY(if(f) grow else 1f).setDuration(200).setInterpolator(Ui.ease()).start()
			Ui.glow(this, f)
		}
	}

	open fun refresh()
	{
		val sel = selected()
		background = when
		{
			isFocused -> Ui.gradient(intArrayOf(0xFF2A1A52.toInt(), 0xFF1A1230.toInt()), radius, stroke = 4, strokeColor = Ui.RING)
			sel -> Ui.gradient(intArrayOf(0xFF2A1A52.toInt(), 0xFF160F2A.toInt()), radius, stroke = 3, strokeColor = Ui.VIOLET2)
			else -> Ui.round(fill, radius, 2, Ui.LINE)
		}
	}
}

/** Animates a number of float steps (used for the toggle switch). */
fun animateFloat(from: Float, to: Float, ms: Long, update: (Float) -> Unit) = ValueAnimator.ofFloat(from, to).apply {
	duration = ms; interpolator = Ui.ease(); addUpdateListener { update(it.animatedValue as Float) }; start()
}
