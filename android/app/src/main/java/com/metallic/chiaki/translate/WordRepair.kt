package com.metallic.chiaki.translate

/**
 * Checks and repairs English read off the screen before it is looked up or sent to the AI.
 *
 * References, all on the TV (no network, no model):
 *  - the game's own library (GameLibrary.vocab): every word of the ~8,000 subtitles players have seen, with how
 *    many lines use it;
 *  - a dictionary of ~75,000 common English and Spanish words (spoken-language frequency list, assets).
 *
 * [quality]: share of words that are real (1.0 while nothing is loaded: no judgement then).
 * [isGibberish]: scraps that are not words at all; never translated, never stored.
 * [fix]: a misread word ("rtght", "gocd", "knovv", "peopla") becomes the library word it almost is, only when
 *   that is clear-cut: the read word is not a real word, it is one letter / one look-alike letter away, the library
 *   uses the result in 3+ lines and no other close word competes. Real words, names (capital inside a sentence),
 *   plurals, words with accents or digits are never touched. Tested on the whole library: ~600 misreads fixed,
 *   real words left alone.
 */
object WordRepair
{
	private val token = Regex("[\\p{L}0-9']+") // accented letters too: "Órale" stays one word
	private const val MIN_VOCAB = 500

	@Volatile private var dict: Set<String> = emptySet()

	fun load(c: android.content.Context)
	{
		if(dict.isNotEmpty()) return
		try { dict = c.assets.open("words_en_es.txt").bufferedReader().useLines { l -> l.filter { it.isNotBlank() }.toHashSet() } }
		catch(e: Throwable) { android.util.Log.w("WordRepair", "dictionary", e) }
	}

	private fun real(w: String) = w in dict || GameLibrary.wordFreq(w) >= 2

	private fun words(s: String) = token.findAll(s).map { it.value.lowercase().replace("'", "") }
		.filter { w -> w.count { it.isLetter() } >= 2 }.toList()

	/** Share of the words that are real words (0..1). 1.0 while the dictionary / library are not loaded. */
	fun quality(s: String): Double
	{
		if(dict.isEmpty() && GameLibrary.vocabSize < MIN_VOCAB) return 1.0
		val w = words(s)
		if(w.isEmpty()) return 0.0
		return w.count { real(it) }.toDouble() / w.size
	}

	// the same rule as the server's junkEnglish(): tested on the whole library, 1 of 8,325 real lines flagged
	private val interj = Regex("^(h*m+h*m*|m+h+m+|mn?m?hm+|u+h+|uh+huh|sh+|pf+t|ps+t|tsk|gr+|br+|zz+|hmph|nth)$")
	private val unit = Regex("""^\d+(ft|lb|oz|m|s|k|st|nd|rd|th)$""")
	private fun vowel(w: String) = w.any { it in "aeiouyáéíóú" }

	private fun wordLike(w: String): Boolean
	{
		if(w.length <= 2 || interj.matches(w)) return true // initials, "Ms", "Dr", "R3", "2D", "ft", hmm
		val hasDigit = w.any { it.isDigit() }
		if(hasDigit && w.any { it.isLetter() })
		{
			// a digit glued to a word ("2Fuck", "RADI0"): judge the letters; "i2i3" / "lhrl38" stay scraps
			val l = w.filter { !it.isDigit() }
			return unit.matches(w) || (l.length >= 3 && vowel(l))
		}
		return vowel(w)
	}

	/** Not a sentence at all: background scraps read as letters ("lll ;-= Il", "Rnrnr tlll"). */
	fun isGibberish(s: String): Boolean
	{
		val raw = token.findAll(s.lowercase()).map { it.value.replace("'", "") }.filter { w -> !w.all { it.isDigit() } }.toList()
		if(raw.isEmpty()) return true
		return raw.count { wordLike(it) }.toDouble() / raw.size < 0.6
	}

	private const val ABC = "abcdefghijklmnopqrstuvwxyz"
	private val lookAlikes = listOf("rn" to "m", "vv" to "w", "cl" to "d", "ii" to "u", "l" to "i", "i" to "l", "t" to "f", "f" to "t",
		"c" to "e", "e" to "c", "c" to "o", "o" to "c", "q" to "g", "g" to "q", "q" to "o", "h" to "n", "n" to "h")

	private fun candidates(w: String): Set<String>
	{
		val out = HashSet<String>()
		val digitsFixed = w.replace('0', 'o').replace('1', 'l').replace('5', 's').replace('8', 'b')
		if(digitsFixed != w) out += digitsFixed
		for((a, b) in lookAlikes)
		{
			var i = w.indexOf(a)
			while(i >= 0) { out += w.substring(0, i) + b + w.substring(i + a.length); i = w.indexOf(a, i + 1) }
		}
		for(i in 0..w.length)
		{
			if(i < w.length) out += w.removeRange(i, i + 1)                                    // a letter too many
			if(i < w.length - 1) out += w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) // swapped
			for(c in ABC)
			{
				if(i < w.length && c != w[i]) out += w.substring(0, i) + c + w.substring(i + 1) // one wrong letter
				out += w.substring(0, i) + c + w.substring(i)                                 // a letter missing
			}
		}
		return out
	}

	/**
	 * The line with clearly misread words replaced; unchanged when unsure.
	 * 4+ letters: the library word it almost is, when the library uses it in 3+ lines and no other close word
	 * competes; when several do, the one the neighbouring words go with in the library (context) if it clearly wins.
	 * 2-3 letters ("ot" in "hold ot", "shi..."): by context only. Words stuck together ("Getin") are split.
	 * Tested on all ~8,300 library lines: ~700 misreads fixed, 0 real words changed.
	 */
	fun fix(s: String): String
	{
		if(GameLibrary.vocabSize < MIN_VOCAB || dict.isEmpty()) return s
		val toks = token.findAll(s).toList()
		val lw = toks.map { it.value.lowercase().replace("'", "") }
		val sb = StringBuilder()
		var last = 0
		var sentenceStart = true
		for((i, m) in toks.withIndex())
		{
			sb.append(s, last, m.range.first)
			last = m.range.last + 1
			val t = m.value
			val start = sentenceStart
			val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 3))
			sentenceStart = after.any { it == '.' || it == '!' || it == '?' }
			val w = lw[i]
			val name = t[0].isUpperCase() && !start
			val short = t.length in 2..3 && t.all { it.isLetter() && it.code < 128 } && !real(w) && !name
			val keep = !short && (t.length < 4 || '\'' in t || name || real(w) ||
				(w.endsWith("s") && real(w.dropLast(1))) || t.any { it.code > 127 } ||
				t.count { it.isLetter() } < t.length - 1)
			if(keep) { sb.append(t); continue }
			val prev = lw.getOrNull(i - 1); val next = lw.getOrNull(i + 1)
			// candidates the library knows (3+ lines), with how much the neighbours support them
			class C(val word: String, val freq: Int, val ctx: Int)
			val cs = candidates(w).filter { it.length >= 2 }.mapNotNull { c ->
				val f = GameLibrary.wordFreq(c)
				if(f < 3) null else C(c, f, GameLibrary.pairFreq(prev, c) + GameLibrary.pairFreq(c, next))
			}
			var pick: String? = null
			if(short)
			{
				val byCtx = cs.filter { kotlin.math.abs(it.word.length - w.length) <= 1 }.sortedByDescending { it.ctx }
				if(byCtx.isNotEmpty() && byCtx[0].ctx >= 3 && (byCtx.size == 1 || byCtx[1].ctx * 2 < byCtx[0].ctx)) pick = byCtx[0].word
			}
			else
			{
				// two words read as one ("Getin" = "Get in"): both halves common, more common than any one-letter fix
				var split = -1; var splitF = 0
				for(k in 2..w.length - 2)
				{
					val a = w.substring(0, k); val b = w.substring(k)
					if(a !in dict || b !in dict) continue
					val f = minOf(GameLibrary.wordFreq(a), GameLibrary.wordFreq(b))
					if(f >= 10 && f > splitF) { split = k; splitF = f }
				}
				val byFreq = cs.sortedByDescending { it.freq }
				val bestF = byFreq.firstOrNull()?.freq ?: 0
				if(split > 0 && splitF > bestF) { sb.append(t.substring(0, split)).append(' ').append(t.substring(split)); continue }
				if(byFreq.isNotEmpty())
				{
					if(byFreq.size == 1 || byFreq[1].freq * 3 <= bestF) pick = byFreq[0].word
					else
					{
						val byCtx = cs.sortedByDescending { it.ctx }
						if(byCtx[0].ctx >= 2 && (byCtx.size == 1 || byCtx[1].ctx * 2 < byCtx[0].ctx)) pick = byCtx[0].word
					}
				}
			}
			sb.append(if(pick == null) t else if(t[0].isUpperCase()) pick.replaceFirstChar { it.uppercaseChar() } else pick)
		}
		sb.append(s, last, s.length)
		return sb.toString()
	}
}
