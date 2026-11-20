package com.metallic.chiaki.store

import android.app.Activity
import android.util.Log

/** Direct (GitHub APK) build: updates itself from GitHub; licence codes are sold outside the app. */
object Store
{
	const val IS_PLAY = false

	fun onAppStart(activity: Activity)
	{
		// newest version first, then (no code yet) the welcome box to type the code
		try { com.metallic.chiaki.update.UpdateManager.checkAndPrompt(activity, manual = false) { com.metallic.chiaki.translate.LicencePrompt.maybeShow(activity) } }
		catch(e: Throwable) { Log.e("Eyesmaxxing", "update check failed", e); com.metallic.chiaki.translate.LicencePrompt.maybeShow(activity) }
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
