package com.metallic.chiaki.translate

import android.content.Context
import android.util.Log

/**
 * Offline "slang to plain English" step before machine translation.
 * Offline translators handle standard English well but garble slang, idioms and spoken contractions
 * ("we're in some deep shit", "kinda", "gonna"). This rewrites them into plain English using a
 * hand-curated table (assets/slang_en*.txt, "slang => plain"), longest phrase first.
 */
object SlangRewriter
{
	private class Rule(val words: List<String>, val replacement: String)

	@Volatile private var byFirst: Map<String, List<Rule>> = emptyMap()
	@Volatile private var loaded = false
	val size get() = byFirst.values.sumOf { it.size }

	fun load(context: Context)
	{
		if(loaded) return
		synchronized(this)
		{
			if(loaded) return
			// phrase -> rule; files load in name order and a later file overrides the same phrase
			val all = LinkedHashMap<String, Rule>()
			val files = (context.assets.list("") ?: emptyArray()).filter { it.startsWith("slang_en") && it.endsWith(".txt") }.sorted()
			for(f in files)
			{
				try
				{
					context.assets.open(f).bufferedReader(Charsets.UTF_8).useLines { lines ->
						for(line in lines)
						{
							if(line.isBlank() || line.startsWith("#")) continue
							val i = line.indexOf(" => ")
							if(i <= 0) continue
							val words = tokens(line.substring(0, i))
							if(words.isEmpty()) continue
							all[words.joinToString(" ")] = Rule(words, line.substring(i + 4).trim())
						}
					}
				}
				catch(e: Exception) { Log.w("SlangRewriter", "could not load $f", e) }
			}
			// longest phrase wins
			val rules = all.values.groupBy { it.words[0] }
			byFirst = rules.mapValues { (_, v) -> v.sortedByDescending { it.words.size } }
			loaded = true
		}
	}

	/** Lowercase words; apostrophes kept inside words, other punctuation dropped. */
	private fun tokens(s: String): List<String> = s.lowercase().replace('’', '\'')
		.split(Regex("[^a-z0-9']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

	/**
	 * Rewrites slang in [text]. Punctuation between words is kept; replaced words come out lowercase,
	 * and the first letter of the sentence keeps its capital.
	 */
	fun rewrite(text: String): String
	{
		if(byFirst.isEmpty() || text.isBlank()) return text
		// split into word tokens and the separators between them, so punctuation survives
		val parts = Regex("[A-Za-z0-9’']+|[^A-Za-z0-9’']+").findAll(text).map { it.value }.toList()
		val wordIdx = parts.indices.filter { parts[it][0].isLetterOrDigit() }
		if(wordIdx.isEmpty()) return text
		val words = wordIdx.map { parts[it].lowercase().replace('’', '\'').trim('\'') }
		val out = StringBuilder()
		var pi = 0     // next part to copy
		var w = 0
		var changed = false
		while(w < words.size)
		{
			val rule = byFirst[words[w]]?.firstOrNull { r ->
				r.words.size <= words.size - w && r.words.indices.all { k -> r.words[k] == words[w + k] &&
					// only plain spaces/commas-free gaps inside a phrase, so we never glue two sentences
					(k == 0 || !Regex("[.!?]").containsMatchIn(parts[wordIdx[w + k] - 1])) }
			}
			if(rule == null) { w++; continue }
			val start = wordIdx[w]
			val end = wordIdx[w + rule.words.size - 1]
			while(pi < start) out.append(parts[pi++])
			out.append(rule.replacement)
			pi = end + 1
			w += rule.words.size
			changed = true
		}
		if(!changed) return text
		while(pi < parts.size) out.append(parts[pi++])
		var result = out.toString().replace(Regex(" {2,}"), " ").trim()
		if(text.firstOrNull()?.isUpperCase() == true && result.isNotEmpty())
			result = result[0].uppercaseChar() + result.substring(1)
		return result
	}
}
