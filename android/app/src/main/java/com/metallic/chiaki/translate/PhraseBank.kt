package com.metallic.chiaki.translate

import android.content.Context
import android.util.Log

/**
 * Offline bank of common game lines with hand-written natural Thai (assets/phrasebook_th.txt).
 * A hit replaces the stiff machine translation; misses fall through to ML Kit.
 * Matching ignores case, punctuation and apostrophes; near-matches (OCR / speech slips) count when
 * at least [FUZZY_MIN] similar.
 */
object PhraseBank
{
	private const val FILE = "phrasebook_th.txt"
	private const val FUZZY_MIN = 0.90

	@Volatile private var exact: Map<String, String> = emptyMap()
	@Volatile private var byFirstWord: Map<String, List<Pair<String, String>>> = emptyMap()
	@Volatile private var loaded = false

	fun load(context: Context)
	{
		if(loaded) return
		synchronized(this)
		{
			if(loaded) return
			val map = HashMap<String, String>()
			try
			{
				context.assets.open(FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
					for(line in lines)
					{
						if(line.isBlank() || line.startsWith("#")) continue
						val i = line.indexOf(" | ")
						if(i <= 0) continue
						val k = key(line.substring(0, i))
						val th = line.substring(i + 3).trim()
						if(k.isNotEmpty() && th.isNotEmpty()) map[k] = th
					}
				}
			}
			catch(e: Exception) { Log.w("PhraseBank", "could not load $FILE", e) }
			exact = map
			byFirstWord = map.entries.groupBy({ it.key.substringBefore(' ') }, { it.key to it.value })
			loaded = true
		}
	}

	val size get() = exact.size

	/** Lowercase, no punctuation, no apostrophes ("Let's go!" -> "lets go"). */
	fun key(s: String): String = s.lowercase()
		.replace('’', '\'').replace("'", "")
		.map { if(it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
		.replace(Regex(" +"), " ").trim()

	fun lookup(english: String): String?
	{
		val k = key(english)
		if(k.isEmpty()) return null
		exact[k]?.let { return it }
		// near-match: same first word, similar length (keeps this cheap)
		val first = k.substringBefore(' ')
		var best: String? = null
		var bestScore = FUZZY_MIN
		for((cand, th) in byFirstWord[first] ?: return null)
		{
			if(kotlin.math.abs(cand.length - k.length) > maxOf(3, k.length / 6)) continue
			val score = SubtitleTracker.similar(cand, k)
			if(score >= bestScore) { bestScore = score; best = th }
		}
		return best
	}
}
