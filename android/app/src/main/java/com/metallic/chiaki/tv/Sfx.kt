package com.metallic.chiaki.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/**
 * Menu sounds (soft "ตึก" when the cover row moves, a chime when a game is picked, whooshes between pages...).
 * All short, quiet and made for the app (res/raw/sfx_*.ogg). The player can switch them off in ตั้งค่าซับ › เสียงเมนู.
 */
object Sfx
{
	enum class S(val res: Int, val vol: Float)
	{
		TICK(R.raw.sfx_tick, 0.85f), FOCUS(R.raw.sfx_focus, 0.55f), CLICK(R.raw.sfx_click, 0.55f), CONFIRM(R.raw.sfx_confirm, 0.9f),
		PAGE(R.raw.sfx_page, 0.6f), OPEN(R.raw.sfx_open, 0.7f), CLOSE(R.raw.sfx_close, 0.6f),
		ON(R.raw.sfx_toggle_on, 0.75f), OFF(R.raw.sfx_toggle_off, 0.75f), WARN(R.raw.sfx_warn, 0.6f), BACK(R.raw.sfx_back, 0.6f)
	}

	const val KEY = "tv_ui_sound"

	private var pool: SoundPool? = null
	private var app: Context? = null
	private val ids = IntArray(S.values().size)
	private val ready = HashSet<Int>()
	private val lastAt = LongArray(S.values().size)

	/** Time of the last remote / controller key press: focus sounds only follow the player's own moves. */
	@Volatile var lastKeyAt = 0L

	fun init(c: Context)
	{
		if(pool != null) return
		try
		{
			app = c.applicationContext
			val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
			p.setOnLoadCompleteListener { _, id, status -> if(status == 0) synchronized(ready) { ready.add(id) } }
			S.values().forEach { ids[it.ordinal] = p.load(c.applicationContext, it.res, 1) }
			pool = p
		}
		catch(_: Throwable) { pool = null }
	}

	fun enabled(): Boolean = app?.let { PreferenceManager.getDefaultSharedPreferences(it).getString(KEY, "on") != "off" } ?: false

	fun play(s: S)
	{
		val p = pool ?: return
		if(!enabled()) return
		val id = ids[s.ordinal]
		if(!synchronized(ready) { id in ready }) return
		// the same sound twice within 45 ms (fast scrolling) plays once
		val now = SystemClock.elapsedRealtime()
		if(now - lastAt[s.ordinal] < 45) return
		lastAt[s.ordinal] = now
		try { p.play(id, s.vol, s.vol, 1, 0, 1f) } catch(_: Throwable) { }
	}

	/** Focus moved to a card: a tiny blip, only when the player pressed a key just now (not when a page opens). */
	fun focus() { if(SystemClock.elapsedRealtime() - lastKeyAt < 400) play(S.FOCUS) }
}
