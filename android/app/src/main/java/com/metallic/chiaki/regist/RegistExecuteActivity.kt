// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.ActivityRegistExecuteBinding
import com.metallic.chiaki.lib.RegistInfo
import kotlin.math.max

class RegistExecuteActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_REGIST_INFO = "regist_info"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"
		const val EXTRA_PSN_NAME = "psn_name"

		const val RESULT_FAILED = Activity.RESULT_FIRST_USER
	}

	private lateinit var viewModel: RegistExecuteViewModel
	private lateinit var binding: ActivityRegistExecuteBinding

	/** What the PS5 answered (from the pairing log): null = it never answered. */
	private fun ps5Reason(): Pair<Int, Long>?
	{
		val log = viewModel.logNow()
		val code = Regex("Regist received HTTP code (\\d+)").find(log)?.groupValues?.get(1)?.toIntOrNull() ?: return null
		val reason = Regex("Application Reason: 0x([0-9a-fA-F]+)").find(log)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
		return code to reason
	}

	private fun failTitle(): String
	{
		val r = ps5Reason() ?: return "แอปคุยกับ PS5 ไม่ได้"
		return if(r.second == 0x80108b10L) "PS5 กำลังถูกใช้เล่นระยะไกลอยู่" else "PS5 ไม่รับ: ไอดีกับรหัส 8 หลักไม่ใช่ชุดเดียวกัน"
	}

	private fun failMessage(): String
	{
		val name = intent.getStringExtra(EXTRA_PSN_NAME)
		val r = ps5Reason() ?: return "PS5 ไม่ตอบเลย (ไม่เกี่ยวกับรหัสผิด)" + (viewModel.firstHost?.let { " · ลองที่ $it แล้วค้นทั้งวงเน็ตแล้ว" } ?: "") + "\n\nวิธีแก้\n" +
			"1. ทีวีกับ PS5 ต้องต่อเน็ตบ้านวงเดียวกัน\n" +
			"2. PS5 ต้องเปิดอยู่ (ไม่ใช่โหมดพัก) และเปิดค้างหน้ารหัส 8 หลักไว้\n" +
			"3. ถ้าทีวีเปิดแอป VPN หรือแอปบล็อกโฆษณา/เปลี่ยน DNS อยู่ ให้ปิดก่อน\n" +
			"4. ดูเลข IP จริงของ PS5: ตั้งค่า › เครือข่าย › สถานะการเชื่อมต่อ › ดูสถานะการเชื่อมต่อ · ถ้าไม่ตรงกับที่แอปลอง ให้ลบ PS5 เดิมในแอปแล้วเพิ่มด้วยเลข IP นี้\n" +
			"5. ยังไม่ได้: ปิด-เปิด PS5 กับเราเตอร์ แล้วลองใหม่"
		if(r.second == 0x80108b10L) return "มีอุปกรณ์อื่นต่อเล่นระยะไกลกับ PS5 อยู่\n\nวิธีแก้\n" +
			"1. ปิดแอปเล่นระยะไกลในมือถือ / คอม / ทีวีเครื่องอื่น\n" +
			"2. แล้วกดลองใหม่"
		return (if(name != null) "แอปใช้ไอดี: $name\n\n" else "") +
			"รหัส 8 หลักผูกกับผู้ใช้ที่กดขอรหัสบน PS5 · ไอดีในแอปต้องเป็นคนเดียวกันเป๊ะ\n\n" +
			"วิธีแก้ (ทำได้ทุกไอดี)\n" +
			"1. ที่ PS5 สลับไปเป็นผู้ใช้ไอดีที่จะใช้ (กดปุ่ม PS › รูปโปรไฟล์ › สลับผู้ใช้)\n" +
			"2. ในไอดีนั้น: ตั้งค่า › ระบบ › การเล่นระยะไกล › เปิด \"เปิดใช้การเล่นระยะไกล\"\n" +
			"3. ยังอยู่ไอดีนั้น: กด \"จับคู่อุปกรณ์\" ขอรหัสใหม่ (รหัสใช้ได้ 5 นาที)\n" +
			"4. ในแอปพิมพ์ชื่อออนไลน์ไอดีของผู้ใช้คนนั้น (ดูที่ ตั้งค่า › ผู้ใช้และบัญชี › บัญชี › โปรไฟล์)\n" +
			"5. ใส่รหัสใหม่แล้วกดจับคู่"
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistExecuteBinding.inflate(layoutInflater)
		setContentView(binding.root)

		viewModel = ViewModelProvider(this, viewModelFactory { RegistExecuteViewModel(getDatabase(this)) })
			.get(RegistExecuteViewModel::class.java)

		binding.logTextView.setHorizontallyScrolling(true)
		binding.logTextView.movementMethod = ScrollingMovementMethod()
		viewModel.logText.observe(this, Observer {
			val textLayout = binding.logTextView.layout ?: return@Observer
			val lineCount = textLayout.lineCount
			if(lineCount < 1)
				return@Observer
			binding.logTextView.text = it
			val scrollY = textLayout.getLineBottom(lineCount - 1) - binding.logTextView.height + binding.logTextView.paddingTop + binding.logTextView.paddingBottom
			binding.logTextView.scrollTo(0, max(scrollY, 0))
		})

		// the button is "ถัดไป" (next step) after a successful pairing; nothing to press while it runs
		binding.shareLogButton.visibility = View.GONE
		binding.shareLogButton.text = "ถัดไป ▶"
		binding.shareLogButton.icon = null

		viewModel.state.observe(this, Observer {
			binding.progressBar.visibility = if(it == RegistExecuteViewModel.State.RUNNING) View.VISIBLE else View.GONE
			when(it)
			{
				RegistExecuteViewModel.State.FAILED ->
				{
					binding.infoTextView.visibility = View.VISIBLE
					binding.infoTextView.text = "จับคู่ไม่สำเร็จ"
					setResult(RESULT_FAILED)
					if(dialog == null && !isFinishing)
						dialog = MaterialAlertDialogBuilder(this)
							.setTitle(failTitle())
							.setMessage(failMessage())
							.setPositiveButton("ลองใหม่") { _, _ -> finish() }
							.setOnDismissListener { dialog = null }
							.create()
							.also { it.show() }
				}
				RegistExecuteViewModel.State.SUCCESSFUL, RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE ->
				{
					binding.infoTextView.visibility = View.VISIBLE
					binding.infoTextView.text = "จับคู่ PS5 สำเร็จ ✔ กด \"ถัดไป\" เพื่อเช็กความพร้อมแล้วเข้าเกม"
					setResult(RESULT_OK)
					binding.shareLogButton.visibility = View.VISIBLE
					binding.shareLogButton.requestFocus()
					if(it == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE)
						showDuplicateDialog()
				}
				RegistExecuteViewModel.State.STOPPED ->
				{
					binding.infoTextView.visibility = View.GONE
					setResult(Activity.RESULT_CANCELED)
				}
				else -> binding.infoTextView.visibility = View.GONE
			}
		})

		binding.shareLogButton.setOnClickListener {
			// next step: back to the home screen, which opens the pre-game checklist for this PS5 by itself
			IntentCompat.getParcelableExtra(intent, EXTRA_REGIST_INFO, RegistInfo::class.java)?.host?.let { addr ->
				androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
					.putString(com.metallic.chiaki.tv.TvActivity.KEY_JUST_PAIRED, addr).apply()
			}
			setResult(RESULT_OK)
			finish()
		}

		val registInfo = IntentCompat.getParcelableExtra(intent, EXTRA_REGIST_INFO, RegistInfo::class.java)
		if(registInfo == null)
		{
			finish()
			return
		}
		viewModel.start(registInfo,
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0)
			else
				null)
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.stop()
	}

	private var dialog: AlertDialog? = null

	private fun showDuplicateDialog()
	{
		if(dialog != null)
			return

		val macStr = viewModel.host?.serverMac?.let { MacAddress(it).toString() } ?: ""

		dialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_regist_duplicate, macStr))
			.setNegativeButton(R.string.action_regist_discard) { _, _ ->  }
			.setPositiveButton(R.string.action_regist_overwrite) { _, _ ->
				viewModel.saveHost()
			}
			.create()
			.also { it.show() }

	}
}