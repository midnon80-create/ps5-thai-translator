package com.metallic.chiaki.store

import android.app.Activity
import android.util.Log

/** Direct (GitHub APK) build: updates itself from GitHub; licence codes are sold outside the app. */
object Store
{
	const val IS_PLAY = false

	fun onAppStart(activity: Activity)
	{
		try { com.metallic.chiaki.update.UpdateManager.checkAndPrompt(activity, manual = false) }
		catch(e: Throwable) { Log.e("Eyesmaxxing", "update check failed", e) }
	}

	/** Not used in this build (the settings screen shows the licence-code field instead). */
	fun openPurchase(activity: Activity) {}
}
