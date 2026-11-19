package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Tesseract text reader: the fallback for TVs whose chip crashes inside ML Kit's reader
 * (and whose Google Play services has no text-recognition module). Plain portable code, works on any CPU.
 * Game subtitles are light text over video, so the frame is turned into dark-text-on-white first.
 */
class TessReader private constructor(private val api: TessBaseAPI)
{
	class Line(val text: String, val box: Rect)

	companion object
	{
		private const val LANG = "eng"

		/** Null if the language data can't be set up (should not happen: it ships inside the app). */
		fun open(context: Context): TessReader?
		{
			return try
			{
				val base = File(context.filesDir, "tess")
				val data = File(base, "tessdata/$LANG.traineddata")
				if(!data.exists() || data.length() < 1_000_000L)
				{
					data.parentFile?.mkdirs()
					val tmp = File(data.parentFile, "$LANG.tmp")
					context.assets.open("tessdata/$LANG.traineddata").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
					tmp.renameTo(data)
				}
				val api = TessBaseAPI()
				if(!api.init(base.absolutePath, LANG, TessBaseAPI.OEM_LSTM_ONLY)) { api.recycle(); return null }
				api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
				TessReader(api)
			}
			catch(e: Throwable) { null }
		}
	}

	/** Text lines with their boxes in the coordinates of [src]. Blocking. */
	@Synchronized fun lines(src: Bitmap): List<Line>
	{
		val scale = if(src.width <= 1000) 1.5f else 1f
		val img = binarize(src, scale)
		try
		{
			api.setImage(img)
			api.getUTF8Text() // runs the recognition
			val out = ArrayList<Line>()
			val it = api.resultIterator ?: return out
			try
			{
				val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
				it.begin()
				do
				{
					val t = it.getUTF8Text(level)?.replace('\n', ' ')?.trim() ?: continue
					if(t.isEmpty() || it.confidence(level) < 55f) continue
					val r = it.getBoundingRect(level)
					out += Line(t, Rect((r.left / scale).toInt(), (r.top / scale).toInt(), (r.right / scale).toInt(), (r.bottom / scale).toInt()))
				}
				while(it.next(level))
			}
			finally { it.delete() }
			return out
		}
		finally
		{
			api.clear()
			img.recycle()
		}
	}

	fun close() = try { api.recycle() } catch(_: Throwable) { }

	/** Light subtitle text -> black, everything else -> white (what Tesseract reads best). */
	private fun binarize(src: Bitmap, scale: Float): Bitmap
	{
		val w = (src.width * scale).toInt()
		val h = (src.height * scale).toInt()
		val s = if(scale != 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
		val px = IntArray(w * h)
		s.getPixels(px, 0, w, 0, 0, w, h)
		if(s !== src) s.recycle()
		for(i in px.indices)
		{
			val c = px[i]
			val r = c shr 16 and 0xff
			val g = c shr 8 and 0xff
			val b = c and 0xff
			val lum = (r * 3 + g * 6 + b) / 10
			// white or light-yellow text, not saturated colours
			val light = lum > 175 && minOf(r, g) > 150
			px[i] = if(light) -0x1000000 else -0x1
		}
		return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
	}
}
