package com.metallic.chiaki.translate

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.metallic.chiaki.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "ทดสอบกับวิดีโอ": plays a video file and runs the same live translator as the PS5 stream,
 * so the translation can be checked (or recorded for a demo) without a PS5.
 */
@OptIn(UnstableApi::class)
class VideoTestActivity : AppCompatActivity()
{
	private lateinit var surface: SurfaceView
	private lateinit var hint: TextView
	private var player: ExoPlayer? = null
	private var translator: TranslatorController? = null
	private var videoUri: Uri? = null
	private val main = Handler(Looper.getMainLooper())

	private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if(uri == null) finish() else play(uri)
	}

	private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if(granted) pickFromLibrary() else
		{
			Toast.makeText(this, "ต้องอนุญาตให้อ่านวิดีโอก่อน", Toast.LENGTH_LONG).show()
			finish()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_video_test)
		surface = findViewById(R.id.videoSurface)
		hint = findViewById(R.id.videoHint)
		val saved = savedInstanceState?.getString("uri")?.let(Uri::parse)
		if(saved != null) videoUri = saved else pickVideo()
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		super.onSaveInstanceState(outState)
		videoUri?.let { outState.putString("uri", it.toString()) }
	}

	private fun pickVideo()
	{
		val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
		if(picker.resolveActivity(packageManager) != null)
		{
			try { openDocument.launch(arrayOf("video/*")); return } catch(e: Exception) { }
		}
		// Many TVs ship without a file picker: list the videos the TV can see (USB drives included).
		val perm = if(Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
		if(ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) pickFromLibrary()
		else askPermission.launch(perm)
	}

	private fun pickFromLibrary()
	{
		val items = mutableListOf<Pair<String, Uri>>()
		val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
		contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
			null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
			while(c.moveToNext())
				items.add(c.getString(1) to ContentUris.withAppendedId(collection, c.getLong(0)))
		}
		if(items.isEmpty())
		{
			AlertDialog.Builder(this)
				.setMessage("ไม่พบไฟล์วิดีโอในทีวี\nคัดลอกไฟล์ .mp4 ลงแฟลชไดรฟ์แล้วเสียบทีวี จากนั้นเปิดหน้านี้ใหม่")
				.setPositiveButton("ตกลง") { _, _ -> finish() }
				.setOnCancelListener { finish() }
				.show()
			return
		}
		AlertDialog.Builder(this)
			.setTitle("เลือกวิดีโอ")
			.setItems(items.map { it.first }.toTypedArray()) { _, i -> play(items[i].second) }
			.setOnCancelListener { finish() }
			.show()
	}

	private fun play(uri: Uri)
	{
		videoUri = uri
		if(player == null) startPlayer()
	}

	private fun startPlayer()
	{
		val uri = videoUri ?: return
		val renderers = object : DefaultRenderersFactory(this)
		{
			override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
				DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(TapAudioProcessor())).build()
		}
		val p = ExoPlayer.Builder(this, renderers).build()
		p.setVideoSurfaceView(surface)
		p.addListener(object : Player.Listener
		{
			override fun onVideoSizeChanged(size: VideoSize) = fitVideo(size)
			override fun onIsPlayingChanged(isPlaying: Boolean)
			{
				translator?.setStreamConnected(isPlaying)
			}
			override fun onPlayerError(error: androidx.media3.common.PlaybackException)
			{
				Toast.makeText(this@VideoTestActivity, "เล่นไฟล์นี้ไม่ได้: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
			}
		})
		p.setMediaItem(MediaItem.fromUri(uri))
		p.prepare()
		p.playWhenReady = true
		player = p

		translator = TranslatorController(this, surface, TranslatorOverlay(findViewById(R.id.videoRoot), surface)).also { it.start() }
		hint.visibility = View.VISIBLE
		main.postDelayed({ hint.visibility = View.GONE }, 7000)
	}

	private fun fitVideo(size: VideoSize)
	{
		if(size.width == 0 || size.height == 0) return
		val root = findViewById<View>(R.id.videoRoot)
		val vw = root.width.toFloat()
		val vh = root.height.toFloat()
		if(vw == 0f || vh == 0f) return
		val aspect = size.width * size.pixelWidthHeightRatio / size.height
		val (w, h) = if(vw / vh > aspect) (vh * aspect) to vh else vw to (vw / aspect)
		surface.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean
	{
		val p = player ?: return super.onKeyDown(keyCode, event)
		when(keyCode)
		{
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
			KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> p.playWhenReady = !p.playWhenReady
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekTo(maxOf(0, p.currentPosition - 10_000))
			KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekTo(p.currentPosition + 10_000)
			else -> return super.onKeyDown(keyCode, event)
		}
		return true
	}

	override fun onStart()
	{
		super.onStart()
		if(videoUri != null && player == null) startPlayer()
	}

	override fun onStop()
	{
		super.onStop()
		translator?.stop()
		translator = null
		player?.release()
		player = null
	}
}

/** Passes the player's audio through unchanged and copies it to the translator's speech recognizer. */
@OptIn(UnstableApi::class)
private class TapAudioProcessor : BaseAudioProcessor()
{
	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat
	{
		if(inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
			return AudioProcessor.AudioFormat.NOT_SET // leave other formats alone
		AudioTap.nativeConfigure(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
		return inputAudioFormat
	}

	override fun queueInput(inputBuffer: ByteBuffer)
	{
		val size = inputBuffer.remaining()
		if(size == 0) return
		val shorts = ShortArray(size / 2)
		inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
		AudioTap.nativePush(shorts, shorts.size)
		replaceOutputBuffer(size).put(inputBuffer).flip()
	}
}
