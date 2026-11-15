package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * PSN login on the customer's phone instead of the TV.
 * The TV shows a QR code for a one-time page it serves on the home network; the phone opens Sony's
 * normal login in its own browser, then pastes the final "redirect" address back into that page.
 * The TV turns the one-time code into the PSN Account ID. The password never touches the TV or this app.
 */
class PhoneLoginActivity : AppCompatActivity()
{
	private var server: ServerSocket? = null
	@Volatile private var done = false
	private lateinit var status: TextView

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "ล็อกอิน PSN ผ่านมือถือ"
		val dp = resources.displayMetrics.density

		val ip = lanAddress()
		val token = randomToken()
		val srv = try { ServerSocket(0) } catch(e: Exception) { null }
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER
			setBackgroundColor(Color.rgb(7, 11, 22))
			setPadding((40 * dp).toInt(), (24 * dp).toInt(), (40 * dp).toInt(), (24 * dp).toInt())
		}
		status = TextView(this).apply { setTextColor(Color.rgb(255, 184, 77)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
		if(ip == null || srv == null)
		{
			status.text = "หาเครือข่ายของทีวีไม่เจอ ตรวจว่าทีวีต่อ Wi-Fi หรือสาย LAN แล้วลองใหม่"
			root.addView(status)
			setContentView(root)
			return
		}
		server = srv
		val address = "http://$ip:${srv.localPort}/$token"

		val qr = ImageView(this).apply { setImageBitmap(qrBitmap(address, 720)) }
		val text = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding((32 * dp).toInt(), 0, 0, 0)
			addView(TextView(context).apply {
				setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
				this.text = "ล็อกอิน PSN ผ่านมือถือ"
			})
			addView(TextView(context).apply {
				setTextColor(Color.rgb(220, 228, 246)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
				setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
				this.text = "1. ต่อมือถือกับ Wi-Fi เดียวกับทีวี\n" +
					"2. สแกน QR นี้ด้วยกล้องมือถือ\n" +
					"3. ทำตามขั้นตอนบนมือถือ (ล็อกอิน Sony แล้วคัดลอกลิงก์กลับมาวาง)\n" +
					"4. ทีวีจะใส่รหัสบัญชีให้เองแล้วกลับไปหน้าจับคู่\n\n" +
					"สแกนไม่ได้? พิมพ์ในเบราว์เซอร์มือถือ:\n$address"
			})
			addView(status)
		}
		root.addView(qr, LinearLayout.LayoutParams((300 * dp).toInt(), (300 * dp).toInt()))
		root.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
		setContentView(root)
		status.text = "รอมือถือ…"

		Thread({ serve(srv, token) }, "phone-login").apply { isDaemon = true; start() }
	}

	override fun onDestroy()
	{
		try { server?.close() } catch(_: Exception) { }
		super.onDestroy()
	}

	// ---------------------------------------------------------------- tiny HTTP server (LAN only, one random path)
	private fun serve(srv: ServerSocket, token: String)
	{
		while(!srv.isClosed && !done)
		{
			val s = try { srv.accept() } catch(e: Exception) { break }
			Thread { try { handle(s, token) } catch(_: Exception) { } finally { try { s.close() } catch(_: Exception) { } } }.start()
		}
	}

	private fun handle(s: Socket, token: String)
	{
		s.soTimeout = 15000
		val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
		val requestLine = input.readLine() ?: return
		var contentLength = 0
		while(true)
		{
			val h = input.readLine() ?: break
			if(h.isEmpty()) break
			if(h.lowercase().startsWith("content-length:")) contentLength = h.substring(15).trim().toIntOrNull() ?: 0
		}
		val parts = requestLine.split(' ')
		val method = parts.getOrNull(0) ?: ""
		val path = parts.getOrNull(1) ?: ""
		if(path.trimEnd('/') != "/$token") { respond(s, 404, "<h3>ไม่พบหน้านี้ — สแกน QR บนทีวีใหม่</h3>"); return }
		if(method == "GET") { respond(s, 200, formPage(null)); return }
		if(method != "POST") { respond(s, 405, "method"); return }

		val body = CharArray(contentLength.coerceAtMost(16384))
		var read = 0
		while(read < body.size) { val n = input.read(body, read, body.size - read); if(n < 0) break; read += n }
		val pasted = String(body, 0, read).split('&')
			.firstOrNull { it.startsWith("url=") }?.substring(4)?.let { URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()
		val code = if(pasted.startsWith("http")) Uri.parse(pasted).getQueryParameter("code") else pasted.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,}")) }
		if(code.isNullOrEmpty()) { respond(s, 200, formPage("ไม่พบรหัสในลิงก์ที่วาง — คัดลอกลิงก์ทั้งหมดจากช่องที่อยู่ของหน้าที่ขึ้นคำว่า redirect")); return }

		runOnUiThread { status.text = "กำลังหารหัสบัญชี…" }
		val id = try { PsnAuth.accountIdFromCode(code) } catch(e: Exception) { null }
		if(id == null)
		{
			respond(s, 200, formPage("ใช้รหัสนี้ไม่ได้ (อาจหมดอายุ) — กดล็อกอิน Sony ใหม่อีกครั้ง แล้วรีบคัดลอกลิงก์มาวาง"))
			runOnUiThread { status.text = "รหัสใช้ไม่ได้ ลองใหม่บนมือถือ" }
			return
		}
		done = true
		respond(s, 200, "<h2>สำเร็จ ✅</h2><p>ทีวีได้รหัสบัญชีแล้ว กลับไปที่ทีวี แล้วใส่ PIN 8 หลักจาก PS5 ได้เลย</p>")
		runOnUiThread {
			PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PsnLoginActivity.PREF_LAST_ACCOUNT_ID, id).apply()
			setResult(Activity.RESULT_OK, Intent().putExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID, id))
			finish()
		}
	}

	private fun respond(s: Socket, code: Int, html: String)
	{
		val page = "<!doctype html><html lang=th><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
			"<title>PS5 แปลไทย</title><style>body{font-family:sans-serif;background:#070b16;color:#eef3ff;padding:20px;line-height:1.6;font-size:17px}" +
			"a.b,button{display:block;width:100%;text-align:center;background:#38d6ff;color:#04121c;font-weight:bold;font-size:18px;border:0;border-radius:12px;padding:14px;margin:10px 0;text-decoration:none}" +
			"textarea{width:100%;height:110px;font-size:15px;border-radius:10px;padding:8px}.e{background:#3a1515;border:1px solid #ff6b6b;padding:10px;border-radius:10px}" +
			"ol li{margin:8px 0}</style></head><body>$html</body></html>"
		val bytes = page.toByteArray(Charsets.UTF_8)
		val out = s.getOutputStream()
		out.write(("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
		out.write(bytes)
		out.flush()
	}

	private fun formPage(error: String?) = buildString {
		append("<h2>ล็อกอิน PSN สำหรับทีวี</h2>")
		if(error != null) append("<p class=e>").append(error).append("</p>")
		append("<ol><li>กดปุ่มด้านล่าง แล้วล็อกอินบัญชี PSN (บัญชีเดียวกับที่ใช้บน PS5)</li>")
		append("<li>ล็อกอินเสร็จ จะเป็นหน้าที่มีคำว่า <b>redirect</b> — กดที่ช่องที่อยู่ (URL) ด้านบน แล้ว <b>คัดลอกลิงก์ทั้งหมด</b></li>")
		append("<li>กลับมาหน้านี้ วางลิงก์ในช่อง แล้วกด <b>ส่งให้ทีวี</b> (ทำภายใน 2–3 นาทีหลังล็อกอิน)</li></ol>")
		append("<a class=b target=_blank rel=noopener href='").append(PsnAuth.LOGIN_URL.replace("'", "%27")).append("'>1) ล็อกอิน Sony</a>")
		append("<form method=post><textarea name=url placeholder='วางลิงก์ที่ขึ้นต้นด้วย https://remoteplay.dl.playstation.net/... ที่นี่'></textarea>")
		append("<button type=submit>2) ส่งให้ทีวี</button></form>")
		append("<p style='color:#9fb0d0;font-size:14px'>รหัสผ่านพิมพ์บนหน้าของ Sony เท่านั้น ทีวีและแอปไม่เห็นรหัสผ่าน</p>")
	}

	// ---------------------------------------------------------------- helpers
	private fun lanAddress(): String? = try
	{
		NetworkInterface.getNetworkInterfaces().toList()
			.filter { it.isUp && !it.isLoopback }
			.flatMap { it.inetAddresses.toList() }
			.filterIsInstance<Inet4Address>()
			.firstOrNull { it.isSiteLocalAddress }?.hostAddress
	}
	catch(e: Exception) { null }

	private fun randomToken(): String
	{
		val b = ByteArray(9).also { SecureRandom().nextBytes(it) }
		return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
	}

	private fun qrBitmap(text: String, size: Int): Bitmap
	{
		val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
		val px = IntArray(size * size) { i -> if(m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
		return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
	}
}

/** Sony's Remote Play login, shared by the TV and phone login screens. */
object PsnAuth
{
	// Same public client as Sony's Remote Play app for Windows (see scripts/psn-account-id.py)
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
		"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
		"&redirect_uri=$REDIRECT&scope=psn:clientapp&request_locale=th_TH&ui=pr&service_logo=ps" +
		"&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	/** Blocking. One-time login code → base64 PSN Account ID, or null. */
	fun accountIdFromCode(code: String): String?
	{
		val body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") +
			"&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
		val tokenConn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
			setRequestProperty("Authorization", basicAuth())
			setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		}
		val token = try
		{
			tokenConn.outputStream.use { it.write(body.toByteArray()) }
			if(tokenConn.responseCode != 200) return null
			JSONObject(tokenConn.inputStream.bufferedReader().readText()).optString("access_token").ifEmpty { return null }
		}
		finally { tokenConn.disconnect() }

		val infoConn = (URL(TOKEN_URL + "/" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection).apply {
			connectTimeout = 10000; readTimeout = 10000
			setRequestProperty("Authorization", basicAuth())
		}
		try
		{
			if(infoConn.responseCode != 200) return null
			val userId = JSONObject(infoConn.inputStream.bufferedReader().readText()).optString("user_id").toBigIntegerOrNull() ?: return null
			val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId.toLong()).array()
			return Base64.encodeToString(bytes, Base64.NO_WRAP)
		}
		finally { infoConn.disconnect() }
	}
}
