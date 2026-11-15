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

	private fun runTests()
	{
		val prefs = TranslatorPrefs(this)
		var ok = true

		// 1. Thai dictionary
		log("1) เตรียมคลังคำแปลไทย (ครั้งแรกต้องต่อเน็ต)…")
		val translator = TextTranslator(prefs.geminiKey, prefs.geminiModel)
		var t0 = SystemClock.elapsedRealtime()
		val ready = translator.prepare()
		log(if(ready) "   ✔ พร้อม (${SystemClock.elapsedRealtime() - t0} ms)" else "   ✘ โหลดไม่สำเร็จ ตรวจอินเทอร์เน็ต")
		ok = ok && ready

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
		log(if(readOk) "   ✔ อ่านถูก" else "   ✘ อ่านไม่ตรง")
		ok = ok && readOk

		if(english.isNotEmpty())
		{
			t0 = SystemClock.elapsedRealtime()
			val thai = translator.translate(english)
			log("   คำแปล:   ${thai ?: "(แปลไม่ได้)"}  (${SystemClock.elapsedRealtime() - t0} ms)")
			ok = ok && thai != null
		}

		// 3. Speech
		log("\n3) ฟังเสียงพูด")
		try
		{
			t0 = SystemClock.elapsedRealtime()
			val model = Model(ModelAssets.ensure(this).absolutePath)
			log("   โหลดระบบฟังเสียง ${SystemClock.elapsedRealtime() - t0} ms")
			val rec = Recognizer(model, AudioTap.SAMPLE_RATE.toFloat())
			val pcm = assets.open("speech_test.pcm").use { it.readBytes() } // raw 16 kHz mono 16-bit LE
			val samples = ShortArray(pcm.size / 2) { i ->
				((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
			}
			t0 = SystemClock.elapsedRealtime()
			val sb = StringBuilder()
			var i = 0
			while(i < samples.size)
			{
				val n = minOf(1600, samples.size - i)
				if(rec.acceptWaveForm(samples.copyOfRange(i, i + n), n))
					sb.append(JSONObject(rec.result).optString("text")).append(' ')
				i += n
			}
			sb.append(JSONObject(rec.finalResult).optString("text"))
			val audioSec = samples.size / AudioTap.SAMPLE_RATE.toFloat()
			val took = (SystemClock.elapsedRealtime() - t0) / 1000f
			val heard = sb.toString().trim()
			log("   เสียงจริง: we have to get out of here before they find us follow me stay close")
			log("   ได้ยิน:   $heard")
			log("   ใช้เวลา %.1f วิ กับเสียงยาว %.1f วิ %s".format(took, audioSec, if(took < audioSec) "(ทันเวลาจริง ✔)" else "(ช้ากว่าเวลาจริง ✘)"))
			rec.close()
			model.close()
			if(heard.isNotEmpty())
				log("   คำแปล:   ${translator.translate(heard) ?: "(แปลไม่ได้)"}")
			ok = ok && heard.split(' ').size >= 5 && took < audioSec
		}
		catch(e: Throwable)
		{
			log("   ✘ ผิดพลาด: ${e.message}")
			ok = false
		}
		translator.close()

		log(if(ok) "\nผลรวม: ✔ ตัวแปลทำงานครบ พร้อมใช้กับ PS5" else "\nผลรวม: มีบางส่วนไม่ผ่าน (ดูด้านบน)")
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
