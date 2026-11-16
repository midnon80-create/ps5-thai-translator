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
		setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })
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

	private fun runTests()
	{
		val prefs = TranslatorPrefs(this)
		PhraseBank.load(this)
		SlangRewriter.load(this)

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
