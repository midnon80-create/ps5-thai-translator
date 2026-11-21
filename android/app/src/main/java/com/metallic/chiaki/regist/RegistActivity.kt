// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ext.RevealActivity
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.lang.IllegalArgumentException

/**
 * Pairing with a PS5: the player types only the public PSN name (Online ID) and the 8-digit PIN from the PS5.
 * The app never asks for a PSN e-mail or password: the Eyesmaxxing server looks up the account ID that
 * pairing needs from the public name.
 */
class RegistActivity: AppCompatActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"

		private const val PIN_LENGTH = 8

		private const val REQUEST_REGIST = 1
		private const val PREF_LAST_PSN_NAME = "psn_last_name"
		private val PSN_NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{2,15}$")
	}

	private lateinit var viewModel: RegistViewModel
	private lateinit var binding: ActivityRegistBinding

	override val revealWindow: Window get() = window
	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout

	/** Account ID found for the PSN name the player typed. */
	private var resolvedAccountId: String? = null
	private var resolvedFor: String? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistBinding.inflate(layoutInflater)
		setContentView(binding.root)
		handleReveal()

		viewModel = ViewModelProvider(this).get(RegistViewModel::class.java)

		binding.hostEditText.setText(intent.getStringExtra(EXTRA_HOST) ?: "255.255.255.255")
		binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)

		binding.registButton.setOnClickListener { doRegist() }

		// the PSN name typed last time, so a second PS5 needs nothing new
		if(savedInstanceState == null && binding.psnIdEditText.text.isNullOrEmpty())
			androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
				.getString(PREF_LAST_PSN_NAME, null)
				?.let { binding.psnIdEditText.setText(it) }

		binding.ps4VersionRadioGroup.check(when(viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5) {
			RegistViewModel.ConsoleVersion.PS5 -> R.id.ps5RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> R.id.ps4VersionGE8RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> R.id.ps4VersionGE7RadioButton
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.id.ps4VersionLT7RadioButton
		})

		binding.ps4VersionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			viewModel.ps4Version.value = when(checkedId)
			{
				R.id.ps5RadioButton -> RegistViewModel.ConsoleVersion.PS5
				R.id.ps4VersionGE8RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_8
				R.id.ps4VersionGE7RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_7
				R.id.ps4VersionLT7RadioButton -> RegistViewModel.ConsoleVersion.PS4_LT_7
				else -> RegistViewModel.ConsoleVersion.PS5
			}
		}

		viewModel.ps4Version.observe(this, Observer {
			binding.psnAccountIdHelpGroup.visibility = if(it == RegistViewModel.ConsoleVersion.PS4_LT_7) View.GONE else View.VISIBLE
			binding.psnIdTextInputLayout.hint = getString(when(it!!)
			{
				RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.hint_regist_psn_online_id
				else -> R.string.hint_regist_psn_account_id
			})
			binding.pinHelpBeforeTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_before else R.string.regist_pin_instructions_ps4_before)
			binding.pinHelpNavigationTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_navigation else R.string.regist_pin_instructions_ps4_navigation)
		})
	}

	/** Asks the Eyesmaxxing server for the account ID that belongs to a public PSN name, then pairs. */
	private fun resolveThenRegist(name: String)
	{
		val wait = android.app.AlertDialog.Builder(this).setTitle("กำลังค้นหาบัญชี PSN…").setMessage(name).setCancelable(false).show()
		val ctx = applicationContext
		Thread {
			var id: String? = null
			var err: String? = null
			var shown: String = name
			try
			{
				val url = com.metallic.chiaki.translate.TranslatorPrefs(ctx).serverUrl.trimEnd('/') + "/v1/psn/account"
				val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
				c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 15000; c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
				val body = org.json.JSONObject().put("online_id", name)
					.put("device", com.metallic.chiaki.translate.CloudTranslator.deviceId(ctx))
				c.outputStream.use { it.write(body.toString().toByteArray()) }
				val txt = (if(c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
				val j = org.json.JSONObject(txt)
				if(j.optBoolean("ok")) { id = j.optString("account_id"); shown = j.optString("online_id", name) }
				else err = j.optString("error")
			}
			catch(e: Exception) { err = "offline" }
			runOnUiThread {
				try { wait.dismiss() } catch(_: Throwable) {}
				if(isFinishing) return@runOnUiThread
				val found = id
				if(found != null)
				{
					resolvedAccountId = found; resolvedFor = name
					androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_LAST_PSN_NAME, name).apply()
					android.widget.Toast.makeText(this, "พบบัญชี PSN: $shown ✔", android.widget.Toast.LENGTH_SHORT).show()
					doRegist()
					return@runOnUiThread
				}
				val title = when(err) { "not_found" -> "ไม่พบชื่อ PSN \"$name\""; "offline" -> "ต่ออินเทอร์เน็ตไม่ได้"; else -> "ค้นหาบัญชี PSN ไม่ได้ตอนนี้" }
				val msg = when(err) {
					"not_found" -> "ตรวจตัวสะกดชื่อ PSN (ดูได้ที่ PS5 › กดรูปโปรไฟล์มุมขวาบน) · ถ้าตั้งความเป็นส่วนตัวไว้ไม่ให้ใครค้นหา ให้ตั้ง \"ใครค้นหาคุณได้\" เป็น \"ทุกคน\" ชั่วคราว"
					"offline" -> "ตรวจว่าทีวีต่ออินเทอร์เน็ตได้ แล้วลองอีกครั้ง"
					"too_many" -> "ลองบ่อยเกินไป รอสักครู่แล้วลองใหม่"
					else -> "ระบบค้นหาชื่อ PSN ขัดข้องชั่วคราว ลองใหม่ในอีกสักครู่ หรือแจ้งผู้ขาย"
				}
				android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ตกลง", null).show()
			}
		}.start()
	}

	private fun doRegist()
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5

		val host = binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = binding.broadcastCheckBox.isChecked

		val psnId = binding.psnIdEditText.text.toString().trim()
		val psnOnlineId: String? = if(ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7) psnId else null
		val nameLike = PSN_NAME.matches(psnId)
		// a PSN name is looked up on the server; a raw account ID (advanced users) is still accepted as typed
		val idText = if(nameLike) (if(resolvedFor == psnId) resolvedAccountId ?: "" else "") else psnId
		val psnAccountId: ByteArray? =
			if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7)
				try { Base64.decode(idText, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
			else
				null
		val psnIdValid = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> psnOnlineId?.isNotEmpty() ?: false
			else -> nameLike || (psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE)
		}

		val pin = binding.pinEditText.text.toString()
		val pinValid = pin.length == PIN_LENGTH

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid)
				getString(when(ps4Version)
				{
					RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.regist_psn_online_id_invalid
					else -> R.string.regist_psn_account_id_invalid
				})
			else
				null
		binding.pinEditText.error = if(!pinValid) getString(R.string.regist_pin_invalid, PIN_LENGTH) else null

		if(!hostValid || !psnIdValid || !pinValid)
			return

		// PSN name typed: look the account ID up first, then this runs again with it
		if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7 && nameLike && resolvedFor != psnId)
		{
			resolveThenRegist(psnId)
			return
		}
		if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7 && (psnAccountId == null || psnAccountId.size != RegistInfo.ACCOUNT_ID_SIZE))
			return

		val target = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS5 -> Target.PS5_1
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> Target.PS4_10
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> Target.PS4_9
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> Target.PS4_8
		}

		val registInfo = RegistInfo(target, host, broadcast, psnOnlineId, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			startActivityForResult(it, REQUEST_REGIST)
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_REGIST && resultCode == RESULT_OK)
			finish()
	}
}
