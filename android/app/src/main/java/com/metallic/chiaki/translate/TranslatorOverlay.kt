package com.metallic.chiaki.translate

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.metallic.chiaki.R

/**
 * The Thai subtitle boxes drawn over the video: embedded font, outline, size from the screen height,
 * and placement right above the game's English subtitle. Shared by the PS5 stream and the video test.
 * [root] must be a FrameLayout-based screen that contains the views of the translator overlay
 * (ids translatorOverlay, translatorStatus, translatorScreenText, translatorSpeechText).
 */
class TranslatorOverlay(private val root: View, private val video: SurfaceView) : TranslatorController.Ui
{
	private val context = root.context
	private val prefs = TranslatorPrefs(context)
	private val overlay: View = root.findViewById(R.id.translatorOverlay)
	private val status: TextView = root.findViewById(R.id.translatorStatus)
	private val screenText: SubtitleTextView = root.findViewById(R.id.translatorScreenText)
	private val speechText: SubtitleTextView = root.findViewById(R.id.translatorSpeechText)
	private val showEnglish = prefs.showEnglish
	private val textPx: Float
	private var lastSubtitleY: Int? = null
	private val font = ResourcesCompat.getFont(context, prefs.font.res)
	// prompts / hints / documents float in their own layer, placed next to or over the English they translate
	private val labelLayer = FrameLayout(context).also {
		it.isClickable = false
		it.isFocusable = false
		(root as ViewGroup).addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
	}
	private val labels = HashMap<Int, SubtitleTextView>()
	private val prompts = LinkedHashMap<Int, Pair<String, RectF>>()
	private val promptViews = ArrayList<SubtitleTextView>()
	private var screenShownAt = 0L
	private var screenHoldMs = 0L
	private val clearScreenNow = Runnable { screenText.isGone = true; curEnglish = ""; curThai = ""; prevThai = null }

	init
	{
		val metrics = context.resources.displayMetrics
		textPx = metrics.heightPixels * prefs.textHeightFraction
		for(v in listOf(screenText, speechText))
		{
			v.typeface = font
			v.includeFontPadding = true // room for Thai vowels and tone marks above and below
			v.setLineSpacing(0f, 1.15f)
			v.maxWidth = (metrics.widthPixels * 0.78f).toInt()
			v.maxLines = 3
			v.outlineColor = Color.BLACK
			// light dark band behind the Thai line (the game still shows through), like the GTA trailer sample
			v.background = GradientDrawable().apply {
				setColor(0x73000000) // 45 % black, measured from the trailer sample: the game still shows through
				cornerRadius = 0f
			}
			v.setShadowLayer(textPx * 0.05f, 0f, textPx * 0.02f, 0x80000000.toInt())
			v.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
			val padH = (textPx * 0.35f).toInt()
			val padV = (textPx * 0.28f).toInt()
			v.setPadding(padH, padV, padH, padV)
		}
		status.typeface = font
		place(null)
	}

	private fun setText(view: SubtitleTextView, thai: String, english: String, previous: String? = null)
	{
		// very long lines get a little smaller so they stay within two or three lines
		val scale = when {
			thai.length > 140 -> 0.78f
			thai.length > 90 -> 0.88f
			else -> 1f
		}
		view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * scale)
		view.outlineWidthPx = 0f // like the sample: plain white letters on the band
		val main = if(showEnglish) thai + '\n' + english else thai
		view.text = if(previous == null) main else android.text.SpannableStringBuilder().apply {
			// the line before stays a moment, smaller and dimmer, so a fast cutscene can still be read
			append(previous)
			setSpan(android.text.style.ForegroundColorSpan(0xB3FFFFFF.toInt()), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			append('\n')
			append(main)
		}
		view.isVisible = true
	}

	// the subtitle on screen now, and the one before it (kept briefly above it)
	private var curEnglish = ""
	private var curThai = ""
	private var prevThai: String? = null
	private val dropPrevious = Runnable {
		prevThai = null
		if(screenText.isVisible && curThai.isNotEmpty()) setText(screenText, curThai, curEnglish)
	}

	override fun showScreenText(thai: String, english: String, subtitleTop: Float?)
	{
		screenText.removeCallbacks(clearScreenNow)
		val now = SystemClock.elapsedRealtime()
		if(english != curEnglish)
		{
			// a new line: the previous Thai line moves up (dimmer) for PREVIOUS_KEEP_MS if it was shown only briefly
			prevThai = if(screenText.isVisible && curThai.isNotEmpty() && now - screenShownAt < PREVIOUS_IF_SHOWN_UNDER_MS) curThai else null
			curEnglish = english
			screenShownAt = now
			screenText.removeCallbacks(dropPrevious)
			if(prevThai != null) screenText.postDelayed(dropPrevious, PREVIOUS_KEEP_MS)
		}
		// same English line again = the better (AI) version of it replaces the quick one in place
		curThai = thai
		place(subtitleTop)
		setText(screenText, thai, english, prevThai)
		// long enough to read even if the game's subtitle vanishes quickly: 1.8-3.5 s by length
		screenHoldMs = (1300L + thai.length * 35L).coerceIn(1800L, 3500L)
	}

	override fun clearScreenText()
	{
		screenText.removeCallbacks(clearScreenNow)
		val left = screenHoldMs - (SystemClock.elapsedRealtime() - screenShownAt)
		if(left > 0) screenText.postDelayed(clearScreenNow, left) else screenText.isGone = true
	}

	override fun showLabel(id: Int, kind: TextKind, thai: String, box: RectF)
	{
		if(kind == TextKind.PROMPT)
		{
			removeLabel(id)
			prompts[id] = thai to RectF(box)
			layoutPrompts()
			return
		}
		val w = if(root.width > 0) root.width else context.resources.displayMetrics.widthPixels
		val h = if(root.height > 0) root.height else context.resources.displayMetrics.heightPixels
		// where the video sits inside the screen (it may be letterboxed)
		val v = IntArray(2).also { video.getLocationInWindow(it) }
		val o = IntArray(2).also { root.getLocationInWindow(it) }
		val vw = if(video.width > 0) video.width else w
		val vh = if(video.height > 0) video.height else h
		val l = v[0] - o[0] + box.left * vw
		val t = v[1] - o[1] + box.top * vh
		val r = v[0] - o[0] + box.right * vw
		val b = v[1] - o[1] + box.bottom * vh
		val margin = (h * 0.01f).toInt()

		val view = labels[id] ?: SubtitleTextView(context).also { tv ->
			tv.typeface = font
			tv.includeFontPadding = true
			tv.setLineSpacing(0f, 1.1f)
			tv.isClickable = false
			tv.isFocusable = false
			labelLayer.addView(tv)
			labels[id] = tv
		}
		val size: Float
		val width: Int
		val bg = GradientDrawable().apply { cornerRadius = textPx * 0.25f }
		when(kind)
		{
			TextKind.PROMPT ->
			{
				// small yellow tag right next to the button prompt
				size = textPx * 0.72f
				bg.setColor(0xD9000000.toInt())
				view.setTextColor(0xFFFFE082.toInt())
				view.maxLines = 2
				width = ViewGroup.LayoutParams.WRAP_CONTENT
				view.maxWidth = (w * 0.38f).toInt()
			}
			TextKind.HINT ->
			{
				// laid over the English tutorial box, so nothing extra covers the game
				size = textPx * 0.76f
				bg.setColor(0xEB111418.toInt())
				view.setTextColor(Color.WHITE)
				view.maxLines = 8
				width = (r - l + textPx).toInt().coerceIn((w * 0.24f).toInt(), (w * 0.6f).toInt())
			}
			else ->
			{
				size = textPx * 0.8f
				bg.setColor(0xF0101216.toInt())
				view.setTextColor(Color.WHITE)
				view.maxLines = 30
				width = (r - l + textPx).toInt().coerceIn((w * 0.34f).toInt(), (w * 0.85f).toInt())
			}
		}
		view.background = bg
		view.outlineColor = Color.BLACK
		val pad = (size * 0.45f).toInt()
		view.setPadding(pad, (pad * 0.6f).toInt(), pad, (pad * 0.6f).toInt())
		view.text = thai
		// documents: shrink until the Thai fits roughly where the English was (but stay readable)
		var px = size
		val maxH = if(kind == TextKind.DOCUMENT) maxOf((b - t) * 1.15f, h * 0.45f) else h * 0.5f
		var mw: Int
		var mh: Int
		while(true)
		{
			view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
			view.outlineWidthPx = if(kind == TextKind.PROMPT) px * 0.08f else 0f
			val spec = if(width > 0) View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
				else View.MeasureSpec.makeMeasureSpec((w * 0.38f).toInt(), View.MeasureSpec.AT_MOST)
			view.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
			mw = view.measuredWidth
			mh = view.measuredHeight
			if(mh <= maxH || px <= textPx * 0.55f) break
			px *= 0.9f
		}
		// laid over the English it translates (prompts keep the button symbol in Thai), at least as big as
		// the English box so no English edges show, and never covering anything else on screen
		val coverW = maxOf(mw, (r - l + 12).toInt())
		val coverH = maxOf(mh, (b - t + if(kind == TextKind.PROMPT) 6 else 12).toInt())
		view.minimumWidth = coverW
		view.minimumHeight = coverH
		mw = coverW
		mh = coverH
		val x: Int
		val y: Int
		if(kind == TextKind.PROMPT)
		{
			// grows toward the screen centre when the Thai is longer than the English
			val px0 = if((l + r) / 2 > w / 2f) (r + 6 - mw).toInt() else (l - 6).toInt()
			x = px0.coerceIn(margin, maxOf(margin, w - mw - margin))
			y = ((t + b) / 2 - mh / 2f).toInt().coerceIn(margin, maxOf(margin, h - mh - margin))
		}
		else
		{
			x = (l - 8).toInt().coerceIn(margin, maxOf(margin, w - mw - margin))
			y = (t - 6).toInt().coerceIn(margin, maxOf(margin, h - mh - margin))
		}
		view.layoutParams = FrameLayout.LayoutParams(if(width > 0) maxOf(width, mw) else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
			it.gravity = Gravity.TOP or Gravity.START
			it.leftMargin = x
			it.topMargin = y
		}
		view.isVisible = true
	}

	override fun removeLabel(id: Int)
	{
		labels.remove(id)?.let { labelLayer.removeView(it) }
		if(prompts.remove(id) != null) layoutPrompts()
	}

	override fun clearLabels()
	{
		labels.clear()
		prompts.clear()
		promptViews.clear()
		labelLayer.removeAllViews()
	}

	/**
	 * Thai for controller prompts: one floating yellow column right above each stack of English prompts,
	 * same order, aligned to the stack's outer edge. Never covers a prompt or the subtitle.
	 */
	private fun layoutPrompts()
	{
		promptViews.forEach { labelLayer.removeView(it) }
		promptViews.clear()
		if(prompts.isEmpty()) return
		val w = if(root.width > 0) root.width else context.resources.displayMetrics.widthPixels
		val h = if(root.height > 0) root.height else context.resources.displayMetrics.heightPixels
		val v = IntArray(2).also { video.getLocationInWindow(it) }
		val o = IntArray(2).also { root.getLocationInWindow(it) }
		val vw = if(video.width > 0) video.width else w
		val vh = if(video.height > 0) video.height else h
		class Stack(var l: Float, var t: Float, var r: Float, var b: Float, val lines: MutableList<String>)
		val stacks = ArrayList<Stack>()
		for((thai, bx) in prompts.values.sortedBy { it.second.top })
		{
			val l = v[0] - o[0] + bx.left * vw
			val t = v[1] - o[1] + bx.top * vh
			val r = v[0] - o[0] + bx.right * vw
			val b = v[1] - o[1] + bx.bottom * vh
			val st = stacks.lastOrNull()
			if(st != null && t - st.b < (b - t) * 2.2f && minOf(r, st.r) > maxOf(l, st.l) - 40)
			{
				st.lines.add(thai); st.l = minOf(st.l, l); st.r = maxOf(st.r, r); st.b = b
			}
			else stacks.add(Stack(l, t, r, b, mutableListOf(thai)))
		}
		val m = (h * 0.008f).toInt()
		val px = textPx * 0.68f
		for(st in stacks)
		{
			val right = (st.l + st.r) / 2 > w / 2f
			val tv = SubtitleTextView(context).also {
				it.typeface = font
				it.includeFontPadding = true
				it.setTextColor(0xFFFFE082.toInt())
				it.outlineColor = Color.BLACK
				it.outlineWidthPx = px * 0.16f
				it.setShadowLayer(px * 0.12f, 0f, px * 0.04f, 0xCC000000.toInt())
				it.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
				it.gravity = if(right) Gravity.END else Gravity.START
				it.maxWidth = (w * 0.4f).toInt()
				it.text = st.lines.joinToString("\n")
			}
			tv.measure(View.MeasureSpec.makeMeasureSpec((w * 0.4f).toInt(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
			val mw = tv.measuredWidth
			val mh = tv.measuredHeight
			val x = (if(right) st.r - mw else st.l).toInt().coerceIn(m, maxOf(m, w - mw - m))
			val y = if(st.t - mh - m >= m) (st.t - mh - m).toInt() else (st.b + m).toInt()
			labelLayer.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
				it.gravity = Gravity.TOP or Gravity.START
				it.leftMargin = x
				it.topMargin = y
			})
			promptViews.add(tv)
		}
	}


	override fun showSpeechText(thai: String, english: String)
	{
		if(!screenText.isVisible) place(null)
		setText(speechText, thai, english)
	}

	override fun clearSpeechText() { speechText.isGone = true }

	override fun showStatus(message: String?)
	{
		status.text = message ?: ""
		status.isVisible = message != null
	}

	/**
	 * Puts the Thai text where it covers the least: right above the English subtitle
	 * (found by the screen reader), or at the top/bottom edge if chosen in the settings.
	 */
	private fun place(subtitleTop: Float?)
	{
		val h = if(root.height > 0) root.height else context.resources.displayMetrics.heightPixels
		val lp = overlay.layoutParams as FrameLayout.LayoutParams
		val gap = (h * 0.012f).toInt()
		lp.topMargin = 0
		lp.bottomMargin = 0
		when(prefs.position)
		{
			TranslatorPrefs.Position.TOP ->
			{
				lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
				lp.topMargin = (h * 0.04f).toInt()
			}
			TranslatorPrefs.Position.BOTTOM ->
			{
				lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
				lp.bottomMargin = (h * 0.04f).toInt()
			}
			TranslatorPrefs.Position.ABOVE_SUBTITLE ->
			{
				if(subtitleTop != null)
				{
					val v = IntArray(2).also { video.getLocationInWindow(it) }
					val origin = IntArray(2).also { root.getLocationInWindow(it) }
					val videoH = if(video.height > 0) video.height else h
					lastSubtitleY = (v[1] - origin[1] + subtitleTop * videoH).toInt()
				}
				val y = lastSubtitleY
				lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
				lp.bottomMargin = if(y != null && y > h * 0.55f)
					// band bottom ~2.5 % of the screen above the English subtitle (as in the sample), never higher
					// than the lower part of the screen
					(h - y + (h * 0.025f).toInt()).coerceIn(gap, (h * 0.38f).toInt())
				else
					// no subtitle seen yet: low, where subtitles usually are
					(h * 0.07f).toInt()
			}
		}
		overlay.layoutParams = lp
	}

	private companion object
	{
		const val PREVIOUS_IF_SHOWN_UNDER_MS = 3000L
		const val PREVIOUS_KEEP_MS = 2500L
	}
}
