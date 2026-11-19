package com.metallic.chiaki.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.flow.first
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * "ทดสอบตัวแปล": checks every translator part on the TV without a PS5.
 * Draws a fake game subtitle, reads it, translates it, and runs speech recognition on a bundled clip.
 */
class TranslatorTestActivity : AppCompatActivity()
{
	private lateinit var out: TextView

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		out = TextView(this).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
			setPadding(48, 48, 48, 48)
			setTextColor(Color.WHITE)
		}
		setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Color.BLACK); addView(out) })
		log("ทดสอบตัวแปลภาษา…\n")
		Thread { runTests() }.start()
	}

	private fun log(s: String) = runOnUiThread { out.append(s + "\n") }

	private fun log(s: String, color: Int) = runOnUiThread {
		val span = android.text.SpannableString(s + "\n")
		span.setSpan(android.text.style.ForegroundColorSpan(color), 0, span.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		out.append(span)
	}

	private val green = Color.rgb(74, 222, 128)
	private val amber = Color.rgb(255, 184, 77)
	private val red = Color.rgb(255, 107, 107)

	/** Step-by-step status of everything a player needs, each with the fix in plain Thai. */
	private fun checklist(prefs: TranslatorPrefs)
	{
		log("ตรวจความพร้อม ทีละขั้น\n", Color.rgb(196, 181, 253))
		val problems = ArrayList<String>()
		fun item(ok: Boolean?, title: String, fix: String?)
		{
			val mark = when(ok) { true -> "✔"; false -> "✘"; null -> "•" }
			log("$mark $title", when(ok) { true -> green; false -> red; null -> amber })
			if(ok != true && fix != null) log("   วิธีแก้: $fix", amber)
			if(ok == false) problems += title
		}

		// 1. code + AI
		val cloud = if(prefs.serverUrl.isBlank()) null else CloudTranslator(prefs.serverUrl, prefs.cloudCode, CloudTranslator.deviceId(this))
		val p = cloud?.check() ?: CloudTranslator.Problem.OFFLINE
		when(p)
		{
			CloudTranslator.Problem.NONE, CloudTranslator.Problem.DAILY_LIMIT, CloudTranslator.Problem.POOL_EMPTY ->
				item(true, "รหัสใช้ได้ · แปลด้วย AI เปิดแล้ว", null)
			CloudTranslator.Problem.NOT_STARTED -> item(null, "รหัสใช้ได้ · AI ยังไม่ถึงเวลาเปิด (${CloudTranslator.aiStartText()})", "รอถึงเวลา แล้วออกจากเกมเข้าใหม่ 1 ครั้ง")
			CloudTranslator.Problem.OFFLINE -> item(false, "ต่อเซิร์ฟเวอร์แปลไม่ได้", "ตรวจว่าทีวีต่ออินเทอร์เน็ตได้ (ลองเปิด YouTube) แล้วกดทดสอบใหม่")
			CloudTranslator.Problem.NO_LICENCE, CloudTranslator.Problem.NEED_BUY ->
				item(false, "ยังไม่ได้ใส่รหัส", "ตั้งค่า › รหัสไลเซนส์ › พิมพ์รหัสจาก LINE › ตกลง")
			else -> item(false, CloudTranslator.problemText(p, cloud?.plan ?: ""), "ติดต่อผู้ขาย พร้อมถ่ายรูปหน้านี้")
		}
		if(p == CloudTranslator.Problem.NONE)
		{
			if(cloud != null && cloud.gameOkUntil > System.currentTimeMillis())
				item(true, "ระบบจำได้แล้วว่าทีวีนี้เล่น GTA 6", null)
			else
				item(null, "AI จะเปิดเต็มที่เมื่อแอปเห็นหน้า Rockstar / GTA 6 หรือชื่อในเกม (ระหว่างนั้นได้ AI 60 ประโยคแรกของวัน)", "เปิด GTA 6 ผ่านแอป แล้วเล่นต่อได้เลย")
		}

		// 2. PS5 paired
		val hosts = try { kotlinx.coroutines.runBlocking { com.metallic.chiaki.common.getDatabase(this@TranslatorTestActivity).registeredHostDao().count().first() } } catch(e: Throwable) { -1 }
		when
		{
			hosts > 0 -> item(true, "จับคู่ PS5 แล้ว ($hosts เครื่อง)", null)
			hosts == 0 -> item(false, "ยังไม่ได้จับคู่ PS5", "หน้าแรก › กดการ์ด PS5 › ล็อกอิน PSN › ใส่รหัส 8 หลักจาก PS5 (ตั้งค่า › ระบบ › การเล่นระยะไกล › เชื่อมโยงอุปกรณ์)")
		}

		// 3. controller on the TV
		val pads = Diag.gamepads()
		if(pads.isNotEmpty()) item(true, "จอยต่อกับทีวีแล้ว (${pads.joinToString()})", null)
		else item(false, "ยังไม่มีจอยต่อกับทีวี (จอยที่ต่อกับ PS5 ใช้ในแอปไม่ได้)",
			"เสียบสาย USB จากจอยเข้าทีวี หรือ ตั้งค่าทีวี › รีโมทและอุปกรณ์เสริม › เพิ่มอุปกรณ์เสริม › ที่จอยกด Create + PS ค้าง › เลือก Wireless Controller")

		// 4. translator settings
		if(prefs.enabled) item(true, "ตัวแปลเปิดอยู่ · อ่าน${if(prefs.region == TranslatorPrefs.Region.BOTTOM) "ซับล่างจอ" else "ทั้งจอ"} · ${if(prefs.mode == TranslatorPrefs.Mode.SCREEN) "ไม่ฟังเสียง (ลื่น)" else "ฟังเสียงด้วย (หนักเครื่อง)"}", null)
		else item(false, "ตัวแปลถูกปิดอยู่", "ตั้งค่า › เปิดตัวแปลภาษา")

		// 5. last play session
		fun ago(t: Long): String
		{
			if(t <= 0) return "ยังไม่เคย"
			val m = (System.currentTimeMillis() - t) / 60000
			return when { m < 1 -> "เมื่อสักครู่"; m < 60 -> "$m นาทีที่แล้ว"; m < 1440 -> "${m / 60} ชม.ที่แล้ว"; else -> "${m / 1440} วันที่แล้ว" }
		}
		val streamAt = Diag.at(this, Diag.STREAM_AT)
		val subAt = Diag.at(this, Diag.SUBTITLE_AT)
		val aiAt = Diag.at(this, Diag.AI_AT)
		val offAt = Diag.at(this, Diag.OFFLINE_AT)
		log("\nครั้งล่าสุดที่เล่น:")
		if(streamAt <= 0) item(null, "ยังไม่เคยเข้าเล่นผ่านแอป", "หน้าแรก › กดการ์ด PS5 ที่มีคำว่า (registered)")
		else
		{
			item(true, "เชื่อมต่อ PS5: ${ago(streamAt)}", null)
			if(subAt < streamAt - 60_000) item(false, "แอปยังไม่เห็นซับอังกฤษในเกมเลย",
				"เปิดซับไตเติลในเกม (Subtitles: On) · ไปฉากที่ตัวละครพูด · ถ้าซับในเกมอยู่กลาง/บนจอ: ตั้งค่า › แปลอะไรบนจอ › ทั้งจอ")
			else item(true, "เห็นซับอังกฤษในเกม: ${ago(subAt)}", null)
			if(aiAt > 0 && aiAt >= offAt) item(true, "แปลด้วย AI ล่าสุด: ${ago(aiAt)}", null)
			else if(offAt > 0)
			{
				val why = when(Diag.text(this, Diag.OFFLINE_WHY)) {
					"NO_LICENCE", "NEED_BUY" -> "ยังไม่ได้ใส่รหัส"
					"NOT_STARTED" -> "ยังไม่ถึงเวลาเปิด AI"
					"EXPIRED", "TRIAL_OVER" -> "รหัสหมดอายุ"
					"DAILY_LIMIT" -> "แปลครบโควตาของวันนี้"
					"OFFLINE", "NO_SERVER", "SERVER_FAILED" -> "เน็ตหลุด หรือเซิร์ฟเวอร์ตอบไม่ทัน"
					"OTHER_DEVICE" -> "รหัสใช้กับทีวีเครื่องอื่น"
					else -> "ยังไม่ได้เปิด AI ให้เกมนี้ (ไม่ใช่ GTA 6) หรือเน็ตช้า"
				}
				item(null, "ครั้งล่าสุดใช้คำแปลออฟไลน์ (${ago(offAt)}): $why", "แก้ตามรายการด้านบน แล้วออกจากเกมเข้าใหม่")
			}
		}
		if(Diag.at(this, Diag.NO_PAD_AT) > streamAt - 1 && streamAt > 0 && pads.isEmpty())
			log("   ตอนเล่นไม่พบจอยที่ต่อกับทีวี", amber)

		log(if(problems.isEmpty()) "\nสรุป: ✔ พร้อมเล่น" else "\nสรุป: ✘ ยังติด ${problems.size} ข้อ — แก้ตามวิธีด้านบน", if(problems.isEmpty()) green else red)
		log("\n— ทดสอบตัวแปลละเอียด —\n")
	}

	private fun runTests()
	{
		val prefs = TranslatorPrefs(this)
		PhraseBank.load(this)
		SlangRewriter.load(this)

		checklist(prefs)

		// 1. Thai dictionary
		log("1) เตรียมคลังคำแปลไทย (ครั้งแรกต้องต่อเน็ต)…")
		val translator = TextTranslator(prefs.geminiKey, prefs.geminiModel)
		var t0 = SystemClock.elapsedRealtime()
		val ready = translator.prepare()
		if(ready) log("   ✔ พร้อม (${SystemClock.elapsedRealtime() - t0} ms) · คลังวลีเกม ${PhraseBank.size} ประโยค · คลังแสลง/สำนวน ${SlangRewriter.size} คำ", green)
		else log("   ✘ โหลดไม่สำเร็จ ตรวจอินเทอร์เน็ต", red)

		// 2. Screen text
		log("\n2) อ่านซับบนจอ")
		val sample = "Where are you going? We need to leave right now."
		val frame = fakeFrame(sample)
		val reader = ScreenReader()
		val region = Bitmap.createBitmap(frame, 0, (frame.height * 0.55f).toInt(), frame.width, (frame.height * 0.45f).toInt())
		t0 = SystemClock.elapsedRealtime()
		val english = reader.read(region)
		val readMs = SystemClock.elapsedRealtime() - t0
		reader.close()
		log("   ซับจริง:  $sample")
		log("   อ่านได้:  $english  (${readMs} ms)")
		val readOk = SubtitleTracker.similar(SubtitleTracker.normalise(english), SubtitleTracker.normalise(sample)) > 0.8
		if(readOk) log("   ✔ อ่านถูก", green) else log("   ✘ อ่านไม่ตรง", red)
		var thaiOk = false
		if(english.isNotEmpty())
		{
			t0 = SystemClock.elapsedRealtime()
			val thai = translator.translate(GameText.fromScreen(english).text)
			log("   คำแปล:   ${thai ?: "(แปลไม่ได้)"}  (${SystemClock.elapsedRealtime() - t0} ms)")
			thaiOk = thai != null
		}
		val core = ready && readOk && thaiOk

		// 3. Speech: download the model once if needed, warm up (not timed), then time a second pass
		log("\n3) ฟังเสียงพูด")
		var speechWorks = false
		var speechOk = false
		val usedFast = false
		try
		{
			if(!SpeechModel.isReady(this))
			{
				log("   โหลดระบบฟังเสียงครั้งแรก (~${SpeechModel.SIZE_MB} MB ต้องต่อเน็ต)…")
				var lastShown = -10
				val ok = SpeechModel.ensure(this) { p -> if(p >= lastShown + 10) { lastShown = p; log("   … $p%") } }
				if(!ok) throw RuntimeException("โหลดไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วกดทดสอบใหม่")
			}
			val pcm = assets.open("speech_test.pcm").use { it.readBytes() } // raw 16 kHz mono 16-bit LE
			val samples = ShortArray(pcm.size / 2) { i ->
				((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
			}
			val audioSec = samples.size / AudioTap.SAMPLE_RATE.toFloat()
			t0 = SystemClock.elapsedRealtime()
			val asr = Speech.open(this)
			log("   โหลดระบบฟังเสียง ${SystemClock.elapsedRealtime() - t0} ms · อุ่นเครื่อง…")
			recognise(asr, samples)
			t0 = SystemClock.elapsedRealtime()
			val sentences = recognise(asr, samples).flatMap { SpeechSegments.split(it) }
			val took = (SystemClock.elapsedRealtime() - t0) / 1000f
			asr.release()
			speechWorks = sentences.sumOf { it.split(' ').size } >= 5
			val inTime = took <= audioSec
			log("   เสียงจริง: We have to get out of here before they find us. / Follow me. / Stay close.")
			log("   ได้ยิน:   ${sentences.joinToString(" / ")}")
			if(inTime) log("   ใช้เวลา %.1f วิ กับเสียงยาว %.1f วิ (ทันเวลาจริง ✔)".format(took, audioSec), green)
			else log("   ใช้เวลา %.1f วิ กับเสียงยาว %.1f วิ (ช้ากว่าเวลาจริง)".format(took, audioSec), amber)
			speechOk = speechWorks && inTime
			if(speechWorks)
			{
				val thai = translator.translateAll(sentences.map { GameText.fromSpeech(it).text })
				log("   คำแปล:   ${thai ?: "(แปลไม่ได้)"}")
			}
		}
		catch(e: Throwable)
		{
			log("   ระบบฟังเสียงใช้ไม่ได้: ${e.message}", amber)
		}
		translator.close()

		// 4. Gemini through the shared server (needs the licence code)
		log("\n4) แปลด้วย Gemini (รหัสไลเซนส์)")
		if(prefs.serverUrl.isBlank()) log("   ยังไม่เปิดใช้ในเวอร์ชันนี้ — ใช้ตัวแปลออฟไลน์", amber)
		else
		{
			val cloud = CloudTranslator(prefs.serverUrl, prefs.cloudCode, CloudTranslator.deviceId(this))
			val p = cloud.check()
			if(cloud.plan == "trial" && cloud.expires > 0)
			{
				val days = ((cloud.expires - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(0)
				log("   ทดลองแปลด้วย AI ฟรี เหลืออีก $days วัน", if(days > 0) green else amber)
			}
			if(cloud.plan == "day" && cloud.expires > System.currentTimeMillis())
			{
				val hours = ((cloud.expires - System.currentTimeMillis()) / 3_600_000L + 1).coerceAtLeast(1)
				log("   แพ็กทดลอง 1 วัน เหลืออีกประมาณ $hours ชั่วโมง", green)
			}
			if(p != CloudTranslator.Problem.NONE) log("   " + CloudTranslator.problemText(p, cloud.plan), amber)
			else
			{
				val sampleLine = "What the hell are you doing here? We gotta bounce, now!"
				t0 = SystemClock.elapsedRealtime()
				val th = cloud.translate(listOf(sampleLine), emptyList())?.firstOrNull()
				log("   ตัวอย่าง: $sampleLine")
				if(th != null) log("   Gemini:   $th  (${SystemClock.elapsedRealtime() - t0} ms)", green)
				else log("   " + CloudTranslator.problemText(cloud.problem), amber)
			}
		}

		// A: remember what this TV can do; switch speech off only when even the fast mode can't keep up
		prefs.setSpeechFast(usedFast)
		prefs.setSpeechSlow(!speechOk)
		if(!speechOk && prefs.mode != TranslatorPrefs.Mode.SCREEN && prefs.modeIsAutomatic)
		{
			prefs.setModeAutomatically(TranslatorPrefs.Mode.SCREEN)
			log("\nทีวีเครื่องนี้แปลเสียงพูดไม่ทัน แอปปิดให้แล้ว จะแปลจากซับบนจอแทน (เปิดเองได้ในตั้งค่า)", amber)
		}
		else if(!speechOk && prefs.mode != TranslatorPrefs.Mode.SCREEN)
			log("\nทีวีเครื่องนี้แปลเสียงพูดไม่ทัน แนะนำ ตั้งค่า › แปลอะไร › ข้อความบนจออย่างเดียว", amber)
		else if(speechOk && prefs.modeIsAutomatic && prefs.mode != TranslatorPrefs.Mode.BOTH)
			prefs.setModeAutomatically(TranslatorPrefs.Mode.BOTH) // e.g. a 32-bit TV that turned out fast enough

		// B: overall result
		when
		{
			core && speechOk ->
			{
				log("\nผลรวม: ✔ ตัวแปลทำงานครบ พร้อมใช้กับ PS5", green)
				if(usedFast) log("แปลเสียงพูด: ใช้โหมดเร็ว เพราะเครื่องประมวลผลช้า", amber)
			}
			core ->
			{
				log("\nผลรวม: ✔ พร้อมใช้ (แปลซับบนจอ)", green)
				log(if(speechWorks) "แปลเสียงพูด: ปิดไว้ เพราะเครื่องประมวลผลไม่ทัน" else "แปลเสียงพูด: ปิดไว้ เพราะใช้ไม่ได้บนเครื่องนี้", amber)
			}
			else -> log("\nผลรวม: มีบางส่วนไม่ผ่าน (ดูด้านบน)", red)
		}
	}

	/** Feeds the clip to the VAD + recogniser like live audio; returns the recognised phrases. */
	private fun recognise(asr: Speech, samples: ShortArray): List<String>
	{
		val out = ArrayList<String>()
		var i = 0
		while(i < samples.size)
		{
			val n = minOf(1600, samples.size - i)
			out += asr.feed(samples.copyOfRange(i, i + n), n)
			i += n
		}
		// half a second of silence lets the VAD close the last phrase, then flush whatever is left
		out += asr.feed(ShortArray(8000), 8000)
		out += asr.finish()
		return out
	}

	private fun fakeFrame(text: String): Bitmap
	{
		val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		c.drawColor(Color.rgb(40, 60, 50))
		val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
		c.drawText("120 / 300", 1100f, 60f, hud) // HUD noise that should be ignored
		val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 34f
			typeface = Typeface.DEFAULT_BOLD
			textAlign = Paint.Align.CENTER
			setShadowLayer(3f, 2f, 2f, Color.BLACK)
		}
		c.drawText(text, 640f, 650f, p)
		return bmp
	}
}
