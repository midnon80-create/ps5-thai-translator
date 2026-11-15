package com.metallic.chiaki.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads English text (subtitles, dialogue boxes) from a video frame with ML Kit, on-device.
 * Picks the block of lines that looks most like dialogue and ignores HUD numbers and stray words.
 */
class ScreenReader
{
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

	/** Text found on screen and where it is, in the pixel coordinates of the bitmap that was read. */
	class Found(val text: String, val box: Rect)

	/** Blocking; call off the main thread. Returns "" when no dialogue-like text is found. */
	fun read(frame: Bitmap): String = find(frame)?.text ?: ""

	/** Blocking; call off the main thread. Null when no dialogue-like text is found. */
	fun find(frame: Bitmap): Found?
	{
		val result = try
		{
			Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)), 3, TimeUnit.SECONDS)
		}
		catch(e: Exception) { return null }
		return pickDialogue(result, frame.width, frame.height)
	}

	fun close() = recognizer.close()

	private class Line(val text: String, val box: Rect)

	private fun pickDialogue(result: Text, width: Int, height: Int): Found?
	{
		val lines = result.textBlocks.flatMap { it.lines }
			.mapNotNull { l -> l.boundingBox?.let { Line(cleanLine(l.text), it) } }
			.filter { looksLikeWords(it.text) }
			.filter { it.box.height() >= height / 70 } // ignore tiny print
			.sortedBy { it.box.top }
		if(lines.isEmpty())
			return null

		// Group lines that sit right under each other and overlap horizontally (one subtitle / one text box).
		val groups = mutableListOf<MutableList<Line>>()
		for(line in lines)
		{
			val g = groups.lastOrNull()
			val prev = g?.last()
			if(prev != null &&
				line.box.top - prev.box.bottom < prev.box.height() * 1.2 &&
				min(line.box.right, prev.box.right) > max(line.box.left, prev.box.left) - prev.box.height())
				g.add(line)
			else
				groups.add(mutableListOf(line))
		}

		// Score: lots of real words, near the horizontal centre, lower on screen (subtitles live at the bottom).
		val best = groups.maxByOrNull { g ->
			val words = g.sumOf { wordCount(it.text) }
			val cx = g.map { it.box.centerX() }.average()
			val centred = 1.0 - min(1.0, abs(cx - width / 2.0) / (width / 2.0))
			val low = g.last().box.bottom.toDouble() / height
			words * (0.6 + 0.4 * centred) * (0.7 + 0.3 * low)
		} ?: return null

		val text = best.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
		if(wordCount(text) < 2 && text.count { it.isLetter() } < 8)
			return null
		val box = Rect(best.first().box)
		best.forEach { box.union(it.box) }
		return Found(text, box)
	}

	private fun cleanLine(s: String) = s
		.replace('|', 'I')
		.replace(Regex("[\\[\\]{}<>_~^*#@]"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()

	private fun looksLikeWords(s: String): Boolean
	{
		val letters = s.count { it.isLetter() }
		if(letters < 2) return false
		val nonSpace = s.count { !it.isWhitespace() }
		return letters.toFloat() / max(1, nonSpace) >= 0.6f
	}

	private fun wordCount(s: String) = s.split(' ').count { w -> w.count { it.isLetter() } >= 2 }
}

/** Decides when on-screen text has settled and is new enough to translate. */
class SubtitleTracker
{
	private var pendingKey = ""
	private var shownKey = ""
	private var emptySince = 0L

	sealed class Action
	{
		object None : Action()
		object Clear : Action()
		data class Show(val text: String) : Action()
	}

	fun update(text: String, now: Long): Action
	{
		val key = normalise(text)
		if(key.isEmpty())
		{
			pendingKey = ""
			if(shownKey.isEmpty()) return Action.None
			if(emptySince == 0L) emptySince = now
			if(now - emptySince > CLEAR_AFTER_MS)
			{
				shownKey = ""
				emptySince = 0L
				return Action.Clear
			}
			return Action.None
		}
		emptySince = 0L
		if(shownKey.isNotEmpty() && similar(key, shownKey) >= 0.85)
			return Action.None // same subtitle still on screen (OCR jitter)
		if(pendingKey.isNotEmpty() && similar(key, pendingKey) >= 0.9)
		{
			// Seen twice in a row: it stopped typing out, translate it.
			shownKey = key
			pendingKey = ""
			return Action.Show(text)
		}
		pendingKey = key
		return Action.None
	}

	fun reset()
	{
		pendingKey = ""
		shownKey = ""
		emptySince = 0L
	}

	companion object
	{
		private const val CLEAR_AFTER_MS = 1500L

		fun normalise(s: String) = s.lowercase().filter { it.isLetter() || it == ' ' }.replace(Regex(" +"), " ").trim()

		/** 1.0 = identical. Levenshtein ratio, bounded so long text stays cheap. */
		fun similar(a: String, b: String): Double
		{
			val x = a.take(240)
			val y = b.take(240)
			if(x == y) return 1.0
			if(x.isEmpty() || y.isEmpty()) return 0.0
			var prev = IntArray(y.length + 1) { it }
			var cur = IntArray(y.length + 1)
			for(i in 1..x.length)
			{
				cur[0] = i
				for(j in 1..y.length)
				{
					val cost = if(x[i - 1] == y[j - 1]) 0 else 1
					cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
				}
				val t = prev; prev = cur; cur = t
			}
			return 1.0 - prev[y.length].toDouble() / max(x.length, y.length)
		}
	}
}
