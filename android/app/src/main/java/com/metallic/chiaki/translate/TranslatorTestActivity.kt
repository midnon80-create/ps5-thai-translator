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
import org.vosk.Model
import org.vosk.Recognizer

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

		// 1. Thai dictionary
		log("1) เตรียมคลังคำแปลไทย (ครั้งแรกต้องต่อเน็ต)…")
		val translator = TextTranslator(prefs.geminiKey, prefs.geminiModel)
		var t0 = SystemClock.elapsedRealtime()
		val ready = translator.prepare()
		if(ready) log("   ✔ พร้อม (${SystemClock.elapsedRealtime() - t0} ms) · คลังวลีเกมออฟไลน์ ${PhraseBank.size} ประโยค", green)
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

		// 3. Speech: one warm-up pass (not timed) so a slow first run doesn't count; the timed pass decides
		log("\n3) ฟังเสียงพูด")
		var speechFast = false
		var speechWorks = false
		try
		{
			t0 = SystemClock.elapsedRealtime()
			val model = Model(ModelAssets.ensure(this).absolutePath)
			log("   โหลดระบบฟังเสียง ${SystemClock.elapsedRealtime() - t0} ms")
			val pcm = assets.open("speech_test.pcm").use { it.readBytes() } // raw 16 kHz mono 16-bit LE
			val samples = ShortArray(pcm.size / 2) { i ->
				((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
			}
			log("   อุ่นเครื่อง…")
			recognise(model, samples)
			t0 = SystemClock.elapsedRealtime()
			val sentences = recognise(model, samples)
			val took = (SystemClock.elapsedRealtime() - t0) / 1000f
			model.close()
			val audioSec = samples.size / AudioTap.SAMPLE_RATE.toFloat()
			speechWorks = sentences.sumOf { it.split(' ').size } >= 5
			speechFast = took <= audioSec
			log("   เสียงจริง: we have to get out of here before they find us / follow me, stay close")
			log("   ได้ยิน:   ${sentences.joinToString(" / ")}")
			if(speechFast) log("   ใช้เวลา %.1f วิ กับเสียงยาว %.1f วิ (ทันเวลาจริง ✔)".format(took, audioSec), green)
			else log("   ใช้เวลา %.1f วิ กับเสียงยาว %.1f วิ (ช้ากว่าเวลาจริง)".format(took, audioSec), amber)
			if(speechWorks)
			{
				val thai = translator.translateAll(sentences.map { GameText.fromSpeech(it).text })
				log("   คำแปล:   ${thai ?: "(แปลไม่ได้)"}")
			}
		}
		catch(e: Throwable)
		{
			log("   ระบบฟังเสียงใช้ไม่ได้บนเครื่องนี้: ${e.message}", amber)
		}
		translator.close()

		// A: a TV that can't listen in real time gets speech translation switched off (and remembered)
		val speechOk = speechWorks && speechFast
		prefs.setSpeechSlow(!speechOk)
		if(!speechOk && prefs.mode != TranslatorPrefs.Mode.SCREEN)
		{
			prefs.setMode(TranslatorPrefs.Mode.SCREEN)
			log("\nทีวีเครื่องนี้แปลเสียงพูดไม่ทัน แอปปิดให้แล้ว จะแปลจากซับบนจอแทน (เปิดเองได้ในตั้งค่า)", amber)
		}

		// B: overall result
		when
		{
			core && speechOk -> log("\nผลรวม: ✔ ตัวแปลทำงานครบ พร้อมใช้กับ PS5", green)
			core ->
			{
				log("\nผลรวม: ✔ พร้อมใช้ (แปลซับบนจอ)", green)
				log(if(speechWorks) "แปลเสียงพูด: ปิดไว้ เพราะเครื่องประมวลผลไม่ทัน" else "แปลเสียงพูด: ปิดไว้ เพราะใช้ไม่ได้บนเครื่องนี้", amber)
			}
			else -> log("\nผลรวม: มีบางส่วนไม่ผ่าน (ดูด้านบน)", red)
		}
	}

	/** Runs the clip through Vosk and returns the sentences cut at pauses. */
	private fun recognise(model: Model, samples: ShortArray): List<String>
	{
		val rec = Recognizer(model, AudioTap.SAMPLE_RATE.toFloat())
		rec.setWords(true)
		val out = ArrayList<String>()
		var i = 0
		while(i < samples.size)
		{
			val n = minOf(1600, samples.size - i)
			if(rec.acceptWaveForm(samples.copyOfRange(i, i + n), n))
				out += SpeechSegments.split(rec.result)
			i += n
		}
		out += SpeechSegments.split(rec.finalResult)
		rec.close()
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
