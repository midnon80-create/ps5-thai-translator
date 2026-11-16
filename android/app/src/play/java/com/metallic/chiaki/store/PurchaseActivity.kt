package com.metallic.chiaki.store

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams

/** Buy screen for the Google Play build. Works with a TV remote (big focusable buttons). */
class PurchaseActivity : AppCompatActivity()
{
	private lateinit var client: BillingClient
	private lateinit var status: TextView
	private lateinit var dayBtn: Button
	private lateinit var yearlyBtn: Button
	private lateinit var lifetimeBtn: Button
	private var day: ProductDetails? = null
	private var yearly: ProductDetails? = null
	private var lifetime: ProductDetails? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val pad = dp(28)
		val col = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad, pad, pad)
			setBackgroundColor(Color.rgb(13, 14, 18))
		}
		fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
			text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(4), 0, dp(4))
		}
		col.addView(text("Eyesmaxxing แปลเกมเป็นไทยด้วย AI", 26f))
		col.addView(text("แปลซับ ปุ่มกด คำแนะนำ และเอกสารในเกม ด้วย Gemini แบบได้อารมณ์ (ต้องต่อเน็ต) — ถ้าไม่สมัคร แอปยังแปลแบบออฟไลน์ได้ฟรี", 16f, Color.rgb(170, 176, 190)))
		status = text("กำลังเชื่อมต่อ Google Play…", 18f, Color.rgb(253, 224, 130))
		col.addView(status)
		dayBtn = button("ทดลอง 1 วัน 29 บาท") { buy(day) }
		yearlyBtn = button("รายปี 329 บาท") { buy(yearly) }
		lifetimeBtn = button("ตลอดชีพ 759 บาท จ่ายครั้งเดียว") { buy(lifetime) }
		col.addView(dayBtn)
		col.addView(yearlyBtn)
		col.addView(lifetimeBtn)
		col.addView(button("กู้คืนการซื้อ / ตรวจสถานะ") { status.text = "กำลังตรวจสอบ…"; Store.refresh(this) { status.text = it } })
		col.addView(text("ยกเลิกการต่ออายุได้ทุกเมื่อใน Google Play › การชำระเงินและการสมัครใช้บริการ", 14f, Color.rgb(130, 136, 150)))
		setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Color.rgb(8, 6, 13)); addView(col) })
		dayBtn.requestFocus()

		client = BillingClient.newBuilder(this)
			.setListener(Store.listener(applicationContext, { status.text = it }) { client })
			.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
			.build()
		client.startConnection(object : BillingClientStateListener
		{
			override fun onBillingSetupFinished(result: BillingResult)
			{
				if(result.responseCode != BillingClient.BillingResponseCode.OK)
				{
					runOnUiThread { status.text = "เชื่อมต่อ Google Play ไม่ได้ (${result.responseCode})" }
					return
				}
				load()
				Store.refresh(applicationContext) { runOnUiThread { status.text = it } }
			}

			override fun onBillingServiceDisconnected() {}
		})
	}

	private fun load()
	{
		fun q(id: String, type: String) = QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
		client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
			.setProductList(listOf(q(Store.SUB_YEARLY, BillingClient.ProductType.SUBS))).build()) { _, res ->
			yearly = res.productDetailsList.firstOrNull()
			runOnUiThread { yearly?.let { yearlyBtn.text = "รายปี " + yearlyPrice(it) } }
		}
		client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
			.setProductList(listOf(q(Store.ONE_TIME_DAY, BillingClient.ProductType.INAPP))).build()) { _, res ->
			day = res.productDetailsList.firstOrNull()
			runOnUiThread { day?.oneTimePurchaseOfferDetails?.let { dayBtn.text = "ทดลอง 1 วัน " + it.formattedPrice } }
		}
		client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
			.setProductList(listOf(q(Store.ONE_TIME_LIFETIME, BillingClient.ProductType.INAPP))).build()) { _, res ->
			lifetime = res.productDetailsList.firstOrNull()
			runOnUiThread { lifetime?.oneTimePurchaseOfferDetails?.let { lifetimeBtn.text = "ตลอดชีพ " + it.formattedPrice + " จ่ายครั้งเดียว" } }
		}
	}

	/** "ทดลองฟรี 7 วัน แล้ว ฿329/ปี" when a free-trial offer exists. */
	private fun yearlyPrice(d: ProductDetails): String
	{
		val offer = bestOffer(d) ?: return ""
		val phases = offer.pricingPhases.pricingPhaseList
		val paid = phases.lastOrNull()?.formattedPrice ?: ""
		val free = phases.firstOrNull { it.priceAmountMicros == 0L }
		return if(free != null) "ทดลองฟรี ${days(free.billingPeriod)} วัน แล้ว $paid/ปี" else "$paid/ปี"
	}

	private fun bestOffer(d: ProductDetails) = d.subscriptionOfferDetails?.let { offers ->
		offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: offers.firstOrNull()
	}

	private fun days(period: String): Int
	{
		val m = Regex("""P(\d+)([DWM])""").find(period) ?: return 7
		val n = m.groupValues[1].toInt()
		return when(m.groupValues[2]) { "W" -> n * 7; "M" -> n * 30; else -> n }
	}

	private fun buy(d: ProductDetails?)
	{
		if(d == null) { status.text = "ยังโหลดราคาไม่เสร็จ หรือสินค้ายังไม่เปิดขาย"; return }
		val pp = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
		bestOffer(d)?.let { pp.setOfferToken(it.offerToken) }
		client.launchBillingFlow(this, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pp.build())).build())
	}

	private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
		text = label
		isAllCaps = false
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
		minHeight = dp(64)
		layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) }
		gravity = Gravity.CENTER
		setOnClickListener { onClick() }
	}

	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

	override fun onDestroy()
	{
		super.onDestroy()
		if(::client.isInitialized) client.endConnection()
	}
}
