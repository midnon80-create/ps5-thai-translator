package com.metallic.chiaki.store

import android.app.Activity

/** Google Play build: the app is free, so there is no billing (Play updates the app itself). */
object Store
{
	const val IS_PLAY = true

	fun onAppStart(activity: Activity) {}

	fun openPurchase(activity: Activity, product: String? = null) {}

	fun refresh(context: android.content.Context, done: ((String) -> Unit)?) { done?.invoke("แอปเล่นฟรี ไม่ต้องซื้อ") }

	const val ONE_TIME_WEEK = "eyes_week"
	const val SUB_MONTHLY = "eyes_monthly"
	const val SUB_YEARLY = "eyes_yearly"
	const val ONE_TIME_LIFETIME = "eyes_lifetime"
}
