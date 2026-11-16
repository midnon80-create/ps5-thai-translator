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

	/** One group of English lines on screen, sorted into a [TextKind] (null = ignore: HUD, signs, labels). */
	class Block(val text: String, val box: Rect, val lines: Int, val kind: TextKind)

	/**
	 * Blocking; reads the whole frame and sorts every text group by kind:
	 * the best dialogue line, controller prompts, tutorial/hint boxes and long documents.
	 */
	fun readAll(frame: Bitmap): List<Block>
	{
		val result = try
		{
			Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)), 3, TimeUnit.SECONDS)
		}
		catch(e: Exception) { return emptyList() }
		val w = frame.width
		val h = frame.height
		val lines = result.textBlocks.flatMap { it.lines }
			.mapNotNull { l -> l.boundingBox?.let { Line(cleanLine(l.text), it) } }
			.filter { looksLikeWords(it.text) || UiText.looksLikePrompt(it.text) }
			.filter { it.box.height() >= h / 70 }
			.sortedBy { it.box.top }
		if(lines.isEmpty()) return emptyList()
		val groups = mutableListOf<MutableList<Line>>()
		for(line in lines)
		{
			// join under the group it continues (close below, overlapping horizontally, similar text height)
			val g = groups.lastOrNull { gr ->
				val prev = gr.last()
				line.box.top - prev.box.bottom < prev.box.height() * 1.6 && line.box.top >= prev.box.top &&
					min(line.box.right, prev.box.right) > max(line.box.left, prev.box.left) - prev.box.height() &&
					abs(line.box.height() - prev.box.height()) < prev.box.height() * 0.5
			}
			if(g != null) g.add(line) else groups.add(mutableListOf(line))
		}
		// a stack of button prompts = one prompt per line; a paragraph that mentions buttons stays one box
		val split = groups.flatMap { g ->
			if(g.size in 2..4 && g.all { UiText.looksLikePrompt(it.text) }) g.map { mutableListOf(it) } else listOf(g)
		}
		val out = ArrayList<Block>()
		var bestDialogue: Block? = null
		var bestScore = 0.0
		for(g in split)
		{
			val text = g.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
			val box = Rect(g.first().box).also { b -> g.forEach { b.union(it.box) } }
			val words = wordCount(text)
			val cx = box.centerX().toDouble() / w
			val bottom = box.bottom.toDouble() / h
			val kind = when
			{
				g.size == 1 && UiText.looksLikePrompt(text) -> TextKind.PROMPT
				g.size >= 4 && words >= 20 -> TextKind.DOCUMENT
				abs(cx - 0.5) < 0.2 && bottom > 0.6 && g.size <= 3 && words >= 2 -> TextKind.DIALOGUE
				Regex("""^[A-Z][A-Za-z.' -]{1,20}:\s""").containsMatchIn(text) && bottom > 0.5 && words >= 2 -> TextKind.DIALOGUE
				words >= 5 && g.size <= 6 -> TextKind.HINT
				words >= 20 -> TextKind.DOCUMENT
				else -> null
			} ?: continue
			val block = Block(text, box, g.size, kind)
			if(kind == TextKind.DIALOGUE)
			{
				val score = words * (0.6 + 0.4 * (1 - min(1.0, abs(cx - 0.5) * 2))) * (0.7 + 0.3 * bottom)
				if(score > bestScore) { bestScore = score; bestDialogue = block }
			}
			else out.add(block)
		}
		bestDialogue?.let { out.add(0, it) }
		// keep the screen tidy: at most 2 prompts, 1 hint, 1 document
		return out.filter { it.kind == TextKind.DIALOGUE } +
			out.filter { it.kind == TextKind.PROMPT }.take(3) +
			out.filter { it.kind == TextKind.HINT }.sortedByDescending { wordCount(it.text) }.take(2) +
			out.filter { it.kind == TextKind.DOCUMENT }.sortedByDescending { wordCount(it.text) }.take(1)
	}

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
		// a shorter, partial read of the line already shown (OCR missed a bit): keep the full one
		if(shownKey.isNotEmpty() && key.length < shownKey.length && shownKey.split(' ').toSet().containsAll(key.split(' ')))
			return Action.None
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

/**
 * Prompts, hints and documents: shown once they have stayed still for two reads in a row
 * (game UI doesn't move; signs and posters in the 3D world do, so they are never translated),
 * removed shortly after they leave the screen. Boxes are fractions of the frame (0..1).
 */
class ItemTracker
{
	class Item(val id: Int, val kind: TextKind, var text: String, var box: android.graphics.RectF, var lastSeen: Long)
	{
		var seen = 1
		var shown = false
	}

	sealed class Event
	{
		class Show(val item: Item) : Event()
		class Remove(val id: Int) : Event()
	}

	private val items = ArrayList<Item>()
	private var nextId = 1

	fun update(blocks: List<ScreenReader.Block>, w: Int, h: Int, now: Long): List<Event>
	{
		val events = ArrayList<Event>()
		val matched = HashSet<Item>()
		for(b in blocks)
		{
			val box = android.graphics.RectF(b.box.left.toFloat() / w, b.box.top.toFloat() / h, b.box.right.toFloat() / w, b.box.bottom.toFloat() / h)
			val key = SubtitleTracker.normalise(b.text)
			val hit = items.firstOrNull { i -> i !in matched && i.kind == b.kind &&
				kotlin.math.abs(i.box.centerX() - box.centerX()) < 0.03f && kotlin.math.abs(i.box.centerY() - box.centerY()) < 0.03f &&
				SubtitleTracker.similar(SubtitleTracker.normalise(i.text), key) >= 0.8 }
			if(hit == null)
			{
				items.add(Item(nextId++, b.kind, b.text, box, now).also { n -> matched.add(n) })
				continue
			}
			matched.add(hit)
			hit.lastSeen = now
			hit.seen++
			// a longer read of the same box (text still typing out) replaces the old one
			if(b.text.length > hit.text.length) hit.text = b.text
			hit.box = box
			if(!hit.shown && hit.seen >= 2)
			{
				hit.shown = true
				events.add(Event.Show(hit))
			}
		}
		val gone = items.filter { it !in matched && now - it.lastSeen > linger(it.kind) }
		for(g in gone) { if(g.shown) events.add(Event.Remove(g.id)); g.shown = false }
		items.removeAll(gone)
		return events
	}

	fun reset() { items.clear() }

	private fun linger(kind: TextKind) = when(kind)
	{
		TextKind.PROMPT -> 700L
		TextKind.HINT -> 1500L
		TextKind.DOCUMENT -> 2000L
		TextKind.DIALOGUE -> 1500L
	}
}
