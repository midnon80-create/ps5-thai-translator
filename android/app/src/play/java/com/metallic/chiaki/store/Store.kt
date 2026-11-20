package com.metallic.chiaki.store

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import com.metallic.chiaki.translate.CloudTranslator
import com.metallic.chiaki.translate.TranslatorPrefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Play build: Gemini access is bought with Google Play Billing.
 *  - eyes_day      one-time 29 ฿ pass, 24 hours (one per Google account)
 *  - eyes_monthly  subscription 149 ฿ (base plan "monthly")
 *  - eyes_yearly   subscription (base plan "yearly")
 *  - eyes_lifetime one-time product
 * Every purchase is checked by the Eyesmaxxing server (Google Play Developer API), which turns it into a
 * licence for this TV; the licence code is stored like a typed code, so the translator works unchanged.
 */
object Store
{
	const val IS_PLAY = true
	const val ONE_TIME_DAY = "eyes_day"
	const val ONE_TIME_WEEK = "eyes_week"
	const val SUB_MONTHLY = "eyes_monthly"
	const val SUB_YEARLY = "eyes_yearly"
	const val ONE_TIME_LIFETIME = "eyes_lifetime"
	private const val TAG = "EyesStore"

	fun onAppStart(activity: Activity) = refresh(activity.applicationContext, null)

	fun openPurchase(activity: Activity, product: String? = null) =
		activity.startActivity(Intent(activity, PurchaseActivity::class.java).apply { if(product != null) putExtra(PurchaseActivity.EXTRA_PRODUCT, product) })

	/** Re-checks what this Google account owns (new purchase, renewal, restore on a new TV). */
	fun refresh(context: Context, done: ((String) -> Unit)?)
	{
		val main = Handler(Looper.getMainLooper())
		lateinit var client: BillingClient
		client = BillingClient.newBuilder(context)
			.setListener { _, _ -> }
			.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
			.build()
		client.startConnection(object : BillingClientStateListener
		{
			override fun onBillingSetupFinished(result: BillingResult)
			{
				if(result.responseCode != BillingClient.BillingResponseCode.OK)
				{
					client.endConnection(); main.post { done?.invoke("เชื่อมต่อ Google Play ไม่ได้") }; return
				}
				val all = ArrayList<Purchase>()
				client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { _, subs ->
					all += subs
					client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, inapp ->
						all += inapp
						Thread {
							val msg = handle(context, client, all)
							client.endConnection()
							main.post { done?.invoke(msg) }
						}.start()
					}
				}
			}

			override fun onBillingServiceDisconnected() {}
		})
	}

	/** Claims every completed purchase with the server (blocking). Returns a status line for the screen. */
	fun handle(context: Context, client: BillingClient, purchases: List<Purchase>): String
	{
		val done = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
		if(done.isEmpty())
			return if(purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) "รอการชำระเงินจาก Google Play" else "ยังไม่ได้สมัคร"
		// lifetime first (never runs out), then yearly, then the 1-day pass
		val best = done.sortedByDescending { if(it.products.contains(ONE_TIME_LIFETIME)) 4 else if(it.products.contains(SUB_YEARLY)) 3 else if(it.products.contains(SUB_MONTHLY)) 2 else if(it.products.contains(ONE_TIME_WEEK)) 1 else 0 }
		var status = "ยืนยันการซื้อไม่สำเร็จ ลองใหม่อีกครั้ง"
		for(p in best)
		{
			val res = claim(context, p) ?: continue
			if(!res.optBoolean("ok"))
			{
				status = when(res.optString("error")) { "play_not_configured" -> "ระบบยืนยันการซื้อยังไม่เปิด (แจ้งผู้พัฒนา)"; "expired" -> "การสมัครหมดอายุแล้ว"; else -> status }
				continue
			}
			PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putString(TranslatorPrefs.KEY_LICENCE, res.optString("code")).apply()
			// 1-day / 7-day passes are used up after claiming (so they can be bought again next time);
			// the server keeps the licence until its end date. Other products are only acknowledged.
			if(p.products.any { it == ONE_TIME_DAY || it == ONE_TIME_WEEK })
				client.consumeAsync(com.android.billingclient.api.ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r, _ ->
					Log.i(TAG, "consume ${r.responseCode}")
				}
			else if(!p.isAcknowledged)
				client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
					Log.i(TAG, "acknowledge ${r.responseCode}")
				}
			return describe(res.optString("plan"), res.optLong("expires", 0L))
		}
		return status
	}

	fun describe(plan: String, expires: Long): String
	{
		val until = if(expires > 0) " ถึง " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("th", "TH")).format(java.util.Date(expires)) else ""
		return when(plan)
		{
			"life" -> "ใช้งานแบบตลอดชีพ ✔"
			"trial" -> "ช่วงทดลองฟรี$until ✔"
			"month" -> "สมาชิกรายเดือน$until ✔"
			"week" -> "แพ็ก 7 วัน$until ✔"
			"day" -> "แพ็กทดลอง 1 วัน" + (if(expires > 0) " ถึง " + java.text.SimpleDateFormat("d MMM HH:mm น.", java.util.Locale("th", "TH")).format(java.util.Date(expires)) else "") + " ✔"
			else -> "สมาชิกรายปี$until ✔"
		}
	}

	private fun claim(context: Context, p: Purchase): JSONObject?
	{
		val url = TranslatorPrefs(context).serverUrl
		if(url.isBlank()) return null
		var conn: HttpURLConnection? = null
		return try
		{
			conn = (URL(url.trimEnd('/') + "/v1/play/claim").openConnection() as HttpURLConnection).apply {
				requestMethod = "POST"; connectTimeout = 5000; readTimeout = 15000; doOutput = true
				setRequestProperty("Content-Type", "application/json")
			}
			val body = JSONObject().put("token", p.purchaseToken).put("product", p.products.firstOrNull() ?: "")
				.put("device", CloudTranslator.deviceId(context))
			conn.outputStream.use { it.write(body.toString().toByteArray()) }
			val stream = if(conn.responseCode in 200..299) conn.inputStream else conn.errorStream
			JSONObject(stream.bufferedReader().readText())
		}
		catch(e: Exception) { Log.w(TAG, "claim failed", e); null }
		finally { conn?.disconnect() }
	}

	/** Listener used by the purchase screen. */
	fun listener(context: Context, onStatus: (String) -> Unit, client: () -> BillingClient) = PurchasesUpdatedListener { result, purchases ->
		val main = Handler(Looper.getMainLooper())
		when(result.responseCode)
		{
			BillingClient.BillingResponseCode.OK -> Thread {
				val msg = handle(context, client(), purchases ?: emptyList())
				main.post { onStatus(msg) }
			}.start()
			BillingClient.BillingResponseCode.USER_CANCELED -> onStatus("ยกเลิกการซื้อ")
			BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(context, onStatus)
			else -> onStatus("ซื้อไม่สำเร็จ (${result.responseCode}) ลองใหม่อีกครั้ง")
		}
	}
}
