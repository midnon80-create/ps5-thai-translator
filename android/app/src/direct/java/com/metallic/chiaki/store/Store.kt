package com.metallic.chiaki.store

import android.app.Activity
import android.util.Log

/** Direct (GitHub APK) build: updates itself from GitHub. The app is free: nothing is sold. */
object Store
{
	const val IS_PLAY = false

	fun onAppStart(activity: Activity)
	{
		// the app is free now: only the newest-version check
		try { com.metallic.chiaki.update.UpdateManager.checkAndPrompt(activity, manual = false) { } }
		catch(e: Throwable) { Log.e("Eyesmaxxing", "update check failed", e) }
	}

	/** Not used in this build (the settings screen shows the licence-code field instead). */
	fun openPurchase(activity: Activity, product: String? = null) {}

	/** Not used in this build (purchases are Google Play only). */
	fun refresh(context: android.content.Context, done: ((String) -> Unit)?) { done?.invoke("รุ่นนี้ใช้รหัสจากผู้ขาย") }

	const val ONE_TIME_WEEK = "eyes_week"
	const val SUB_MONTHLY = "eyes_monthly"
	const val SUB_YEARLY = "eyes_yearly"
	const val ONE_TIME_LIFETIME = "eyes_lifetime"
}
