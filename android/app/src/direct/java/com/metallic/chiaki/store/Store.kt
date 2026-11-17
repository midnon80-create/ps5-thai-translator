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
	fun openPurchase(activity: Activity) {}
}
