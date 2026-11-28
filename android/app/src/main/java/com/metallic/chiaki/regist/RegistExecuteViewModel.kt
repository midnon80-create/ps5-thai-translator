// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.metallic.chiaki.common.AppDatabase
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.lib.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegistExecuteViewModel(val database: AppDatabase): ViewModel()
{
	enum class State
	{
		IDLE,
		RUNNING,
		STOPPED,
		FAILED,
		SUCCESSFUL,
		SUCCESSFUL_DUPLICATE,
	}

	private val _state = MutableLiveData<State>(State.IDLE)
	val state: LiveData<State> get() = _state

	private val log = ChiakiRxLog(ChiakiLog.Level.ALL.value/* and ChiakiLog.Level.VERBOSE.value.inv()*/)
	private var regist: Regist? = null

	val logText: LiveData<String> = log.logText.asLiveData()
	/** The pairing log right now (the LiveData above can lag a moment behind). */
	fun logNow(): String = log.logText.value

	var host: RegistHost? = null
		private set

	private var assignManualHostId: Long? = null
	private var info: RegistInfo? = null
	/** The PS5's address that was tried first (shown when nothing answers). */
	var firstHost: String? = null
		private set
	private var triedWholeNetwork = false

	fun start(info: RegistInfo, assignManualHostId: Long?)
	{
		if(regist != null)
			return
		try
		{
			regist = Regist(info, log.log, this::registEvent)
			this.info = info
			if(firstHost == null) firstHost = info.host
			this.assignManualHostId = assignManualHostId
			_state.value = State.RUNNING
		}
		catch(error: CreateError)
		{
			log.log.e("Failed to create Regist: ${error.errorCode}")
			_state.value = State.FAILED
		}
	}

	fun stop()
	{
		regist?.stop()
	}

	private fun registEvent(event: RegistEvent)
	{
		when(event)
		{
			is RegistEventCanceled -> _state.postValue(State.STOPPED)
			is RegistEventFailed -> viewModelScope.launch(Dispatchers.Main) { onFailed() }
			is RegistEventSuccess -> registSuccess(event.host)
		}
	}

	/**
	 * The PS5 never answered at the address the app had (old / wrong address, or the TV could not even send to it):
	 * once, look for it on the whole home network instead (broadcast); it answers from its real address.
	 */
	private fun onFailed()
	{
		val i = info
		val answered = logNow().contains("Regist received HTTP code")
		if(i == null || answered || triedWholeNetwork || (i.broadcast && i.host == "255.255.255.255"))
		{
			_state.value = State.FAILED
			return
		}
		triedWholeNetwork = true
		log.log.i("PS5 did not answer at ${i.host}: searching the whole home network")
		regist?.dispose()
		regist = null
		try
		{
			regist = Regist(RegistInfo(i.target, "255.255.255.255", true, i.psnOnlineId, i.psnAccountId, i.pin), log.log, this::registEvent)
		}
		catch(error: CreateError)
		{
			log.log.e("Failed to create Regist: ${error.errorCode}")
			_state.value = State.FAILED
		}
	}

	private fun registSuccess(host: RegistHost)
	{
		this.host = host
		viewModelScope.launch {
			val existing = withContext(Dispatchers.IO) {
				database.registeredHostDao().getByMac(MacAddress(host.serverMac))
			}
			if(existing != null)
			{
				_state.value = State.SUCCESSFUL_DUPLICATE
			}
			else
			{
				saveHost()
			}
		}
	}

	fun saveHost()
	{
		val host = host ?: return
		val assignManualHostId = assignManualHostId
		viewModelScope.launch(Dispatchers.IO) {
			val dao = database.registeredHostDao()
			val manualHostDao = database.manualHostDao()
			val registeredHost = RegisteredHost(host)
			dao.deleteByMac(registeredHost.serverMac)
			val registeredHostId = dao.insert(registeredHost)
			if(assignManualHostId != null)
			{
				manualHostDao.assignRegisteredHost(assignManualHostId, registeredHostId)
			}
			withContext(Dispatchers.Main) {
				Log.i("RegistExecute", "Registered Host saved in db")
				_state.value = State.SUCCESSFUL
			}
		}
	}

	override fun onCleared()
	{
		super.onCleared()
		regist?.dispose()
	}
}
