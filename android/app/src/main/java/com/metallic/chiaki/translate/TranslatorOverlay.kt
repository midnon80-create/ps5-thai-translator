package com.metallic.chiaki.translate

import android.graphics.Color
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

	init
	{
		val metrics = context.resources.displayMetrics
		textPx = metrics.heightPixels * prefs.textHeightFraction
		val font = ResourcesCompat.getFont(context,
			if(prefs.font == TranslatorPrefs.Font.KANIT) R.font.kanit_medium else R.font.sarabun_semibold)
		for(v in listOf(screenText, speechText))
		{
			v.typeface = font
			v.includeFontPadding = true // room for Thai vowels and tone marks above and below
			v.setLineSpacing(0f, 1.15f)
			v.maxWidth = (metrics.widthPixels * 0.78f).toInt()
			v.maxLines = 4
			v.outlineColor = Color.BLACK
			v.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
			val padH = (textPx * 0.6f).toInt()
			val padV = (textPx * 0.32f).toInt()
			v.setPadding(padH, padV, padH, padV)
		}
		status.typeface = font
		place(null)
	}

	private fun setText(view: SubtitleTextView, thai: String, english: String)
	{
		// very long lines get a little smaller so they stay within two or three lines
		val scale = when {
			thai.length > 140 -> 0.78f
			thai.length > 90 -> 0.88f
			else -> 1f
		}
		view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * scale)
		view.outlineWidthPx = textPx * scale * 0.10f
		view.text = if(showEnglish) thai + '\n' + english else thai
		view.isVisible = true
	}

	override fun showScreenText(thai: String, english: String, subtitleTop: Float?)
	{
		place(subtitleTop)
		setText(screenText, thai, english)
	}

	override fun clearScreenText() { screenText.isGone = true }

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
				lp.bottomMargin = if(y != null && y > h * 0.3f)
					(h - y + gap).coerceIn(gap, (h * 0.7f).toInt())
				else
					// no subtitle seen yet, or text high up (menus, top dialogue boxes): the usual subtitle strip
					(h * 0.20f).toInt()
			}
		}
		overlay.layoutParams = lp
	}
}
