package com.metallic.chiaki.translate

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The shared translation library of the picked game, kept on the TV.
 *
 * Every player sees the same subtitles, so once a line has been translated for anyone, the next players can show
 * it with no network wait at all: the library is downloaded when the game starts (then only what is new) and looked
 * up here first. The TV reads subtitles off the picture, so a letter or two can come out wrong ("timne"): lines are
 * matched by the same near-match as the server (MinHash of letter trigrams + edit distance), not only exactly.
 */
object GameLibrary
{
	private const val TAG = "GameLibrary"
	private const val NEAR_MIN = 0.86
	private val SEEDS = intArrayOf(0x9e3779b1.toInt(), 0x85ebca77.toInt(), 0xc2b2ae3d.toInt(), 0x27d4eb2f)

	private val lines = HashMap<String, String>()
	private val bands = Array(4) { HashMap<Int, MutableList<String>>() }
	@Volatile private var game = ""
	@Volatile private var loaded = false
	@Volatile private var syncing = false

	val size get() = synchronized(lines) { lines.size }

	/** Same as the server's key(): lower case, apostrophes dropped, everything but letters/digits = space. */
	fun key(s: String): String
	{
		val b = StringBuilder(s.length)
		for(ch in s.lowercase()) when
		{
			ch == '\'' || ch == '’' -> {}
			Character.isLetterOrDigit(ch) -> b.append(ch)
			else -> b.append(' ')
		}
		return b.toString().replace(Regex(" +"), " ").trim()
	}

	private fun fnv(s: String, seed: Int): Int
	{
		var h = 0x811c9dc5.toInt() xor seed
		for(c in s) { h = h xor c.code; h *= 0x01000193 }
		h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
		return h
	}

	/** 4 MinHash values of the letter trigrams (identical numbers to the server's sketch()). */
	private fun sketch(k: String): IntArray?
	{
		val t = k.replace(" ", "")
		if(t.length < 8) return null
		return IntArray(4) { n ->
			var m = -1L // as unsigned
			for(i in 0..t.length - 3)
			{
				val h = fnv(t.substring(i, i + 3), SEEDS[n]).toLong() and 0xffffffffL
				if(m < 0 || h < m) m = h
			}
			m.toInt()
		}
	}

	private fun similarity(a: String, b: String): Double
	{
		if(a == b) return 1.0
		val n = a.length; val m = b.length
		if(n == 0 || m == 0) return 0.0
		if(kotlin.math.abs(n - m).toDouble() / maxOf(n, m) > 1 - NEAR_MIN) return 0.0
		var prev = IntArray(m + 1) { it }; var cur = IntArray(m + 1)
		for(i in 1..n)
		{
			cur[0] = i
			for(j in 1..m) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if(a[i - 1] == b[j - 1]) 0 else 1)
			val t = prev; prev = cur; cur = t
		}
		return 1.0 - prev[m].toDouble() / maxOf(n, m)
	}

	private fun addLocked(k: String, th: String)
	{
		if(lines.put(k, th) == null) sketch(k)?.forEachIndexed { i, v -> bands[i].getOrPut(v) { ArrayList(2) }.add(k) }
	}

	/** Thai for an English subtitle, or null. Exact first, then the closest near spelling. */
	fun find(english: String): String?
	{
		val k = key(english)
		if(k.isEmpty()) return null
		synchronized(lines)
		{
			lines[k]?.let { return it }
			if(k.length < 12) return null
			val sk = sketch(k) ?: return null
			var best: String? = null; var bestS = NEAR_MIN
			val seen = HashSet<String>()
			for(i in 0 until 4) for(c in bands[i][sk[i]].orEmpty())
			{
				if(!seen.add(c)) continue
				val s = similarity(k, c)
				if(s >= bestS) { best = c; bestS = s }
			}
			return best?.let { lines[it] }
		}
	}

	/** A line the server just translated: known from now on (also for the rest of this game). */
	fun put(english: String, th: String)
	{
		val k = key(english)
		if(k.isNotEmpty() && th.isNotBlank()) synchronized(lines) { addLocked(k, th) }
	}

	private fun file(c: Context, g: String) = File(c.filesDir, "library_$g.tsv")

	/** Loads the saved library, then downloads what is new (background thread). */
	fun prepare(c: Context, g: String, cloud: CloudTranslator?)
	{
		if(g.isEmpty()) return
		if(game != g) { synchronized(lines) { lines.clear(); bands.forEach { it.clear() } }; loaded = false; game = g }
		val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(c)
		if(!loaded) try
		{
			val f = file(c, g)
			if(f.exists()) synchronized(lines) {
				f.forEachLine { l -> val t = l.indexOf('\t'); if(t > 0) addLocked(l.substring(0, t), l.substring(t + 1)) }
			}
			loaded = true
			Log.i(TAG, "library $g: ${size} lines on the TV")
		}
		catch(e: Throwable) { Log.w(TAG, "library load failed", e) }
		if(cloud == null || syncing) return
		syncing = true
		try
		{
			var since = prefs.getLong("library_since_$g", 0L)
			var added = 0
			repeat(5) {
				val res = cloud.library(g, since) ?: return@repeat
				val arr = res.optJSONArray("lines") ?: return@repeat
				val out = StringBuilder()
				synchronized(lines) {
					for(i in 0 until arr.length())
					{
						val p = arr.optJSONArray(i) ?: continue
						val k = p.optString(0); val th = p.optString(1)
						if(k.isEmpty() || th.isEmpty() || k.contains('\t') || th.contains('\n')) continue
						addLocked(k, th.replace('\t', ' ')); out.append(k).append('\t').append(th.replace('\t', ' ')).append('\n'); added++
					}
				}
				if(out.isNotEmpty()) file(c, g).appendText(out.toString())
				since = res.optLong("until", since)
				prefs.edit().putLong("library_since_$g", since).apply()
				if(!res.optBoolean("more", false)) return@repeat
			}
			Log.i(TAG, "library $g: +$added new, ${size} lines")
		}
		catch(e: Throwable) { Log.w(TAG, "library sync failed", e) }
		finally { syncing = false }
	}
}
