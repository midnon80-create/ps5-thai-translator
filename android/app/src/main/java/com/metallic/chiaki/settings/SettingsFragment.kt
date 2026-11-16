// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
		}
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		// Speech translation was switched off because this TV can't keep up: warn once, but allow it.
		preferenceScreen.findPreference<androidx.preference.ListPreference>(com.metallic.chiaki.translate.TranslatorPrefs.KEY_MODE)?.setOnPreferenceChangeListener { _, value ->
			val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
			val wantsSpeech = value == "both" || value == "speech"
			if(wantsSpeech && sp.getBoolean(com.metallic.chiaki.translate.TranslatorPrefs.KEY_SPEECH_SLOW, false) &&
				!sp.getBoolean(com.metallic.chiaki.translate.TranslatorPrefs.KEY_SPEECH_SLOW_WARNED, false))
			{
				sp.edit().putBoolean(com.metallic.chiaki.translate.TranslatorPrefs.KEY_SPEECH_SLOW_WARNED, true).apply()
				android.app.AlertDialog.Builder(context)
					.setTitle("เปิดแปลเสียงพูด")
					.setMessage("ทีวีเครื่องนี้ประมวลผลเสียงได้ช้ากว่าเวลาจริง การแปลเสียงพูดอาจทำให้ภาพเกมกระตุกหรือคำแปลขึ้นช้า" + "\n\n" + "ถ้าเล่นแล้วไม่ลื่น ให้กลับมาเลือก \"ข้อความบนจออย่างเดียว\"")
					.setPositiveButton("เข้าใจแล้ว", null)
					.show()
			}
			// the user chose this themselves: the app must not switch it automatically any more
			sp.edit().putBoolean(com.metallic.chiaki.translate.TranslatorPrefs.KEY_MODE_AUTO, false).apply()
			true
		}
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		// the screen links in preferences.xml name the direct build's package; point them at this build
		fun fixIntents(g: androidx.preference.PreferenceGroup)
		{
			for(i in 0 until g.preferenceCount)
			{
				val p = g.getPreference(i)
				p.intent?.component?.let { c -> p.intent = android.content.Intent().setClassName(requireContext().packageName, c.className) }
				if(p is androidx.preference.PreferenceGroup) fixIntents(p)
			}
		}
		fixIntents(preferenceScreen)
		// Google Play build: buy inside the app (Play Billing); no self-update, no typed codes
		if(com.metallic.chiaki.store.Store.IS_PLAY)
		{
			preferenceScreen.findPreference<Preference>("tr_check_update")?.isVisible = false
			preferenceScreen.findPreference<Preference>(com.metallic.chiaki.translate.TranslatorPrefs.KEY_LICENCE)?.isVisible = false
			preferenceScreen.findPreference<Preference>("tr_buy")?.let {
				it.isVisible = true
				it.setOnPreferenceClickListener { activity?.let { a -> com.metallic.chiaki.store.Store.openPurchase(a) }; true }
			}
		}
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportAndShareAllSettings(activity, lifecycleScope)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, lifecycleScope)
			}
		}
	}
}
