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

/**
 * Google Play purchase. Opened from a package card with EXTRA_PRODUCT: Google Play's own payment sheet opens
 * right away. Without it: a simple list (works with a remote or a controller) plus "restore purchases".
 */
class PurchaseActivity : AppCompatActivity()
{
	companion object { const val EXTRA_PRODUCT = "product" }

	private lateinit var client: BillingClient
	private lateinit var status: TextView
	private val details = HashMap<String, ProductDetails>()
	private val buttons = HashMap<String, Button>()
	private var autoBuy: String? = null

	private val products = listOf(
		Triple(Store.ONE_TIME_WEEK, BillingClient.ProductType.INAPP, "7 วัน 49 บาท"),
		Triple(Store.SUB_MONTHLY, BillingClient.ProductType.SUBS, "รายเดือน 189 บาท (แนะนำ)"),
		Triple(Store.SUB_YEARLY, BillingClient.ProductType.SUBS, "รายปี 759 บาท"),
		Triple(Store.ONE_TIME_LIFETIME, BillingClient.ProductType.INAPP, "ตลอดชีพ 1,099 บาท จ่ายครั้งเดียว"))

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		autoBuy = intent.getStringExtra(EXTRA_PRODUCT)
		val pad = dp(28)
		val col = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad, pad, pad)
		}
		fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
			text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(4), 0, dp(4))
		}
		col.addView(text("Eyesmaxxing · ซับไทยด้วย AI", 26f))
		status = text(if(autoBuy != null) "กำลังเปิดหน้าชำระเงินของ Google Play…" else "กำลังเชื่อมต่อ Google Play…", 18f, Color.rgb(196, 181, 253))
		col.addView(status)
		for((id, _, label) in products)
		{
			val b = button(label) { buy(id) }
			buttons[id] = b
			col.addView(b)
		}
		col.addView(button("กู้คืนการซื้อ / ตรวจสถานะ") { status.text = "กำลังตรวจสอบ…"; Store.refresh(this) { status.text = it } })
		col.addView(text("สมาชิกรายเดือน/รายปี ยกเลิกการต่ออายุได้ทุกเมื่อใน Google Play › การชำระเงินและการสมัครใช้บริการ", 14f, Color.rgb(130, 136, 150)))
		setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Color.rgb(7, 5, 12)); addView(col) })
		buttons[autoBuy ?: Store.SUB_MONTHLY]?.requestFocus()

		client = BillingClient.newBuilder(this)
			.setListener(Store.listener(applicationContext, { msg -> runOnUiThread { status.text = msg; if(autoBuy != null && msg.contains("✔")) finishSoon() } }) { client })
			.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
			.build()
		client.startConnection(object : BillingClientStateListener
		{
			override fun onBillingSetupFinished(result: BillingResult)
			{
				if(result.responseCode != BillingClient.BillingResponseCode.OK)
				{
					runOnUiThread { status.text = "เชื่อมต่อ Google Play ไม่ได้ (${result.responseCode}) · ตรวจว่าทีวีล็อกอินบัญชี Google แล้ว" }
					return
				}
				load()
			}

			override fun onBillingServiceDisconnected() {}
		})
	}

	private fun finishSoon() = status.postDelayed({ if(!isFinishing) finish() }, 2500)

	private fun load()
	{
		for((id, type, _) in products)
		{
			val q = QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
			client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(q)).build()) { _, res ->
				val d = res.productDetailsList.firstOrNull()
				runOnUiThread {
					if(d != null)
					{
						details[id] = d
						price(d)?.let { p -> buttons[id]?.text = products.first { it.first == id }.third.substringBefore(" ") + " " + p }
					}
					if(id == autoBuy)
					{
						if(d != null) buy(id)
						else status.text = "แพ็กเกจนี้ยังไม่เปิดขายบน Google Play (ช่วงทดสอบใช้ฟรีได้เลย)"
					}
				}
			}
		}
	}

	private fun price(d: ProductDetails): String? =
		d.oneTimePurchaseOfferDetails?.formattedPrice
			?: bestOffer(d)?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice?.let { p ->
				if(d.productId == Store.SUB_MONTHLY) "$p/เดือน" else "$p/ปี" }

	private fun bestOffer(d: ProductDetails) = d.subscriptionOfferDetails?.let { offers ->
		offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: offers.firstOrNull()
	}

	private fun buy(id: String)
	{
		val d = details[id]
		if(d == null) { status.text = "ยังโหลดราคาไม่เสร็จ หรือแพ็กเกจนี้ยังไม่เปิดขาย"; return }
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
