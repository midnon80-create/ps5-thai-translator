package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Signs in to PSN on Sony's own login page (inside the app) and works out the PSN Account ID
 * needed to pair with a PS5, so nobody needs a computer for it.
 * The password is typed only into Sony's page; the app only reads the one-time code Sony returns
 * and keeps nothing but the resulting Account ID.
 */
class PsnLoginActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
		const val PREF_LAST_ACCOUNT_ID = "psn_last_account_id"

		// Same public client as Sony's Remote Play app for Windows (see scripts/psn-account-id.py)
		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
			"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
			"&redirect_uri=$REDIRECT&scope=psn:clientapp&request_locale=th_TH&ui=pr&service_logo=ps" +
			"&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"
	}

	private lateinit var webView: WebView
	private lateinit var status: TextView
	private lateinit var progress: ProgressBar
	@Volatile private var handled = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = "ล็อกอิน PSN"

		val root = FrameLayout(this)
		webView = try { WebView(this) } catch(e: Exception)
		{
			// Some TVs ship without a working WebView
			failWith("ทีวีเครื่องนี้เปิดหน้าล็อกอินไม่ได้ ให้ใส่ PSN Account ID เองแทน")
			return
		}
		progress = ProgressBar(this)
		status = TextView(this).apply {
			textSize = 20f
			gravity = Gravity.CENTER
			setPadding(48, 48, 48, 48)
			visibility = View.GONE
		}
		root.addView(webView, FrameLayout.LayoutParams(-1, -1))
		root.addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
		root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
		setContentView(root)

		// start from a clean session every time, and leave no Sony login behind afterwards
		CookieManager.getInstance().removeAllCookies(null)

		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = intercept(request.url)

			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(view: WebView, url: String) = intercept(Uri.parse(url))

			override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?)
			{
				if(intercept(Uri.parse(url))) view.stopLoading()
				progress.visibility = View.VISIBLE
			}

			override fun onPageFinished(view: WebView, url: String)
			{
				progress.visibility = View.GONE
			}
		}
		webView.loadUrl(LOGIN_URL)
	}

	private fun intercept(uri: Uri): Boolean
	{
		if(!uri.toString().startsWith(REDIRECT)) return false
		if(handled) return true
		handled = true
		val code = uri.getQueryParameter("code")
		webView.visibility = View.GONE
		if(code.isNullOrEmpty())
		{
			failWith("ล็อกอินไม่สำเร็จ ลองใหม่อีกครั้ง")
			return true
		}
		showStatus("กำลังหารหัสบัญชี…")
		Thread {
			val id = try { fetchAccountId(code) } catch(e: Exception) { null }
			runOnUiThread {
				CookieManager.getInstance().removeAllCookies(null)
				if(id == null)
					failWith("หารหัสบัญชีไม่สำเร็จ ตรวจอินเทอร์เน็ตแล้วลองใหม่")
				else
				{
					PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_LAST_ACCOUNT_ID, id).apply()
					setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, id))
					finish()
				}
			}
		}.start()
		return true
	}

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	private fun fetchAccountId(code: String): String?
	{
		val body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8") +
			"&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
		val tokenConn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			doOutput = true
			connectTimeout = 10000
			readTimeout = 10000
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
			connectTimeout = 10000
			readTimeout = 10000
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

	private fun showStatus(msg: String)
	{
		progress.visibility = View.VISIBLE
		status.text = msg
		status.visibility = View.VISIBLE
	}

	private fun failWith(msg: String)
	{
		if(!::status.isInitialized)
		{
			setContentView(TextView(this).apply { text = msg; textSize = 20f; setPadding(48, 48, 48, 48) })
			return
		}
		progress.visibility = View.GONE
		status.text = msg
		status.visibility = View.VISIBLE
	}

	override fun onBackPressed()
	{
		if(::webView.isInitialized && webView.visibility == View.VISIBLE && webView.canGoBack()) webView.goBack()
		else super.onBackPressed()
	}

	override fun onDestroy()
	{
		if(::webView.isInitialized) webView.destroy()
		super.onDestroy()
	}
}
