package com.metallic.chiaki.translate

import android.content.Context
import android.util.Log

/**
 * Hand-written Thai for phrases that machine translation gets wrong, used INSIDE a sentence
 * (assets/phrase_th_*.txt, "english phrase => ไทย"; "^" = start of line only, "$" = end of line only).
 *
 * [mask] swaps each known phrase for a placeholder ("Zq1"), the machine translates the rest,
 * then [unmask] puts the Thai back: "Sharp as ever, Jason." -> "Zq1, Jason." -> MT -> "แม่นเหมือนเคยนะเนี่ย Jason"
 * If the translator mangles a placeholder, [Masked.parts] lets the caller translate the pieces
 * between phrases separately instead.
 */
object ThaiPhrases
{
	private class Entry(val re: Regex, val thai: String, val len: Int)

	class Masked(val text: String, val thai: List<String>)
	{
		val any get() = thai.isNotEmpty()
		/** The whole line was known phrases (and punctuation): no machine translation needed. */
		val onlyPhrases get() = any && text.replace(TOKEN_RE, "").none { it.isLetterOrDigit() }
		/** Text between the placeholders, in order (for the fallback). */
		fun parts(): List<String> = text.split(TOKEN_RE)
	}

	@Volatile private var entries: List<Entry> = emptyList()
	@Volatile private var loaded = false
	val size get() = entries.size

	private val TOKEN_RE = Regex("""\s*Zq(\d+)\s*""")
	// what the offline translator may turn "Zq1" into
	private val TOKEN_OUT_RE = Regex("""(?i)\s*(?:zq|ซีคิว|ซคิว|แซดคิว|แซ็ดคิว|ซีกิว|ซีคิ้ว|แซดคิ้ว)\s?(\d+)\s*""")

	fun load(context: Context)
	{
		if(loaded) return
		synchronized(this)
		{
			if(loaded) return
			val map = LinkedHashMap<String, String>()
			val files = (context.assets.list("") ?: emptyArray()).filter { it.startsWith("phrase_th_") && it.endsWith(".txt") }.sorted()
			for(f in files) try
			{
				context.assets.open(f).bufferedReader(Charsets.UTF_8).useLines { lines ->
					for(line in lines)
					{
						if(line.isBlank() || line.startsWith("#")) continue
						val i = line.indexOf(" => ")
						if(i <= 0) continue
						map[line.substring(0, i).trim().lowercase()] = line.substring(i + 4).trim()
					}
				}
			}
			catch(e: Exception) { Log.w("ThaiPhrases", "could not load $f", e) }
			entries = map.mapNotNull { (en, th) -> compile(en, th) }.sortedByDescending { it.len }
			loaded = true
		}
	}

	private fun compile(en: String, th: String): Entry?
	{
		if(th.isEmpty()) return null
		var p = en
		val start = p.startsWith("^").also { if(it) p = p.substring(1) }
		val end = p.endsWith("$").also { if(it) p = p.dropLast(1) }
		p = p.trim()
		if(p.isEmpty()) return null
		// words may be separated by any spacing; apostrophes optional / curly
		val body = p.split(Regex("\\s+")).joinToString("""[\s,]+""") { w ->
			Regex.escape(w.replace("'", "\u0000")).replace("\u0000", """\E['’]?\Q""")
		}
		val pre = if(start) """^\W*""" else """(?<![\p{L}'’])"""
		// "Brian's" still matches "brian"; other apostrophes (don't, can't) don't
		val post = if(end) """(?=[\W]*$)""" else """(?!\p{L})(?!['’](?![sS]\b))"""
		return try { Entry(Regex("(?i)$pre$body$post"), th, p.length) } catch(e: Exception) { null }
	}

	fun mask(text: String): Masked
	{
		if(entries.isEmpty()) return Masked(text, emptyList())
		var t = text
		val thai = ArrayList<String>()
		for(e in entries)
		{
			if(!e.re.containsMatchIn(t)) continue
			t = e.re.replace(t) { thai.add(e.thai); " Zq${thai.size} " }
		}
		// "Brian's doing us a favor" / "Brian's doing" = "Brian is doing"; "Jason's car" keeps the 's
		t = t.replace(Regex("""(Zq\d+)\s*['’]s\s+(?=Zq)"""), "$1 ")
			.replace(Regex("""(Zq\d+)\s*['’]s(?=\s+\w+ing\b)"""), "$1 is")
			.replace(Regex("""(Zq\d+)\s+(['’]s\b)"""), "$1$2")
		return Masked(t.replace(Regex(" {2,}"), " ").trim(), thai)
	}

	private val THAI = Regex("[฀-๿]")

	/**
	 * Puts the Thai back; null if a placeholder went missing (caller should use the parts fallback).
	 * A Thai phrase that sat inside a clause in English is glued to the Thai around it (no stray spaces);
	 * English names keep their spaces ("ตาม Lucia ไป").
	 */
	fun unmask(translated: String, masked: Masked): String?
	{
		val glueL = HashSet<Int>()
		val glueR = HashSet<Int>()
		for(m in Regex("""Zq(\d+)""").findAll(masked.text))
		{
			val n = m.groupValues[1].toInt()
			val th = masked.thai.getOrNull(n - 1) ?: continue
			if(!THAI.containsMatchIn(th)) continue
			val before = masked.text.substring(0, m.range.first).trimEnd()
			val after = masked.text.substring(m.range.last + 1).trimStart()
			if(before.isNotEmpty() && (before.last().isLetterOrDigit() || before.last() == '\'')) glueL.add(n)
			if(after.isNotEmpty() && after.first().isLetterOrDigit()) glueR.add(n)
		}
		val found = HashSet<Int>()
		var out = TOKEN_OUT_RE.replace(translated) { m ->
			val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
			val th = masked.thai.getOrNull(n - 1) ?: return@replace m.value
			found.add(n)
			(if(n in glueL) "\u0001" else " ") + th + (if(n in glueR) "\u0002" else " ")
		}
		if(found.size != masked.thai.size) return null
		out = out.replace(Regex("""([฀-๿])\s*\u0001"""), "$1")
			.replace(Regex("""\u0002\s*(?=[฀-๿])"""), "")
			.replace('\u0001', ' ').replace('\u0002', ' ')
		return tidy(out)
	}

	/** Fallback: Thai phrases and translated pieces in the original order. */
	fun joinParts(translatedParts: List<String?>, masked: Masked): String
	{
		val sb = StringBuilder()
		for(i in translatedParts.indices)
		{
			translatedParts[i]?.let { sb.append(it).append(' ') }
			masked.thai.getOrNull(i)?.let { sb.append(it).append(' ') }
		}
		return tidy(sb.toString())
	}

	fun tidy(s: String): String
	{
		var t = s.replace(Regex("""\s+(['’]s)\b"""), "$1")
			.replace(Regex("""\s+([!?.,…])"""), "$1")
			.replace(Regex("""^[\s.,]+"""), "")
			.replace(Regex("""([\u0E00-\u0E7F]),\s*"""), "$1 ")
			.replace(Regex("""([\u0E00-\u0E7F])\.(?=\s|$)"""), "$1") // Thai subtitles don't use full stops
			.replace(Regex(" {2,}"), " ").trim()
		if(THAI.containsMatchIn(t)) t = t.replace(Regex("""(?<!\.)\.$"""), "")
		return t
	}
}
