package com.metallic.chiaki.translate

/**
 * Cleans English from OCR or speech recognition before translating, so the translator sees
 * normal sentences: fixes common OCR slips, turns ALL CAPS into sentence case, splits off speaker names.
 */
object GameText
{
	class Prepared(val speaker: String?, val text: String)
	{
		/** Puts the untranslated speaker name back in front of the Thai line. */
		fun withSpeaker(thai: String) = if(speaker != null) "$speaker: $thai" else thai
	}

	private val speakerRe = Regex("""^([A-Z][A-Za-z0-9.'\- ]{0,22}?)\s*[:：]\s+(.+)$""")
	private val hyphenBreak = Regex("""(\p{L})- (\p{Ll})""")

	fun fromScreen(raw: String): Prepared
	{
		var s = raw.trim()
			.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
			.replace(hyphenBreak, "$1$2")
			.replace(Regex("""\s+[oOxX○✕]$"""), "") // an icon read as a letter after the subtitle
		s = fixOcr(s)
		var speaker: String? = null
		speakerRe.find(s)?.let { m ->
			val name = m.groupValues[1].trim()
			if(name.split(' ').size <= 3)
			{
				speaker = if(isMostlyCaps(name)) titleCase(name) else name
				s = m.groupValues[2]
			}
		}
		if(isMostlyCaps(s))
			s = sentenceCase(s)
		return Prepared(speaker, s.trim())
	}

	fun fromSpeech(raw: String): Prepared
	{
		var s = raw.trim()
		if(s.isEmpty()) return Prepared(null, s)
		s = s.replace(Regex("""\bi\b"""), "I")
			.replace(Regex("""\bi'(m|ll|ve|d)\b"""), "I'$1")
		s = s.replaceFirstChar { it.uppercaseChar() }
		if(s.last() !in ".?!") s += "."
		return Prepared(null, s)
	}

	private fun fixOcr(s: String) = s
		.replace(Regex("""(?<![A-Za-z])l(?=['’](m|ll|ve|d)\b)"""), "I") // l'm -> I'm
		.replace(Regex("""(?<=\s|^)l(?=\s)"""), "I")                     // lone l -> I
		.replace(Regex("""(?<=[a-z])0(?=[a-z])"""), "o")                 // w0rd -> word
		.replace(Regex("""\s+([,.!?])"""), "$1")
		.replace(Regex("""\.{4,}"""), "...")

	private fun isMostlyCaps(s: String): Boolean
	{
		val letters = s.filter { it.isLetter() }
		if(letters.length < 4) return false
		return letters.count { it.isUpperCase() } >= letters.length * 0.8
	}

	private fun sentenceCase(s: String): String
	{
		val lower = s.lowercase()
		val sb = StringBuilder(lower.length)
		var capNext = true
		for(c in lower)
		{
			if(capNext && c.isLetter()) { sb.append(c.uppercaseChar()); capNext = false }
			else sb.append(c)
			if(c in ".!?") capNext = true
		}
		return sb.toString()
			.replace(Regex("""\bi\b"""), "I")
			.replace(Regex("""\bi'(m|ll|ve|d)\b"""), "I'$1")
	}

	private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}
