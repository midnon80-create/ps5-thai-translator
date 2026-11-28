package com.metallic.chiaki.hdmi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.os.SystemClock
import android.view.View

/**
 * 4K mode: the PS5 draws "เชื่อมต่อกับการเล่นระยะไกลแล้ว" at the top centre of its own HDMI picture for as long as
 * Remote Play is connected (the app has to stay connected to read the subtitles). The hidden Remote Play stream
 * has the same game picture without that box, so this view lays the same spot of the stream over it: the player
 * sees the game there, not the box. Soft edges, so the joint does not show.
 *
 * Draws nothing while the app's own pages are on screen, before the first picture, and as soon as the stream stops
 * (no fresh picture for 400 ms: the PS5 drops its box too when the connection ends).
 */
class BannerCover(context: Context) : View(context)
{
	companion object
	{
		// the patch, as parts of the picture (the box itself is about x 0.40-0.60, y 0.03-0.095)
		const val X0 = 0.315f; const val X1 = 0.685f; const val Y1 = 0.15f
		// fully covered inside this, fading out to the patch edge
		const val CX0 = 0.34f; const val CX1 = 0.66f; const val CY1 = 0.12f
		const val STALE_MS = 400L
	}

	/** The TV shows the PS5 (not our app's pages). Set from the service. */
	@Volatile var showing = false
		set(v) { field = v; postInvalidate() }

	@Volatile private var current: Bitmap? = null
	@Volatile private var fedAt = 0L
	private val bufs = arrayOfNulls<Bitmap>(3)
	private var next = 0
	private var px = IntArray(0)
	private var mask = IntArray(0)
	private var pw = 0; private var ph = 0
	private var lastFeed = 0L
	private var rowY = ByteArray(0); private var rowU = ByteArray(0); private var rowV = ByteArray(0)
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val dst = RectF()
	// where the patch really is (rounded to whole stream pixels), as parts of the picture
	@Volatile private var fx0 = X0; @Volatile private var fx1 = X1; @Volatile private var fy1 = Y1

	init
	{
		isFocusable = false
		isClickable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	/** One decoded stream picture (reader thread). [minGapMs] keeps weaker TVs light. */
	fun feed(img: Image, minGapMs: Long)
	{
		if(!showing) return
		val now = SystemClock.elapsedRealtime()
		if(now - lastFeed < minGapMs) return
		lastFeed = now
		val w = img.width; val h = img.height
		if(w < 64 || h < 64) return
		val x0 = (w * X0).toInt() and 1.inv()
		val x1 = (w * X1).toInt() and 1.inv()
		val oh = (h * Y1).toInt() and 1.inv()
		val ow = x1 - x0
		if(ow != pw || oh != ph)
		{
			pw = ow; ph = oh
			px = IntArray(ow * oh)
			mask = makeMask(ow, oh, w, h, x0)
			bufs.fill(null)
		}
		val planes = img.planes
		val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
		val yb = yP.buffer.duplicate(); val ub = uP.buffer.duplicate(); val vb = vP.buffer.duplicate()
		val yrs = yP.rowStride; val yps = yP.pixelStride
		val urs = uP.rowStride; val ups = uP.pixelStride
		val vrs = vP.rowStride; val vps = vP.pixelStride
		val cw = ow / 2
		val yLen = (ow - 1) * yps + 1
		val uLen = (cw - 1) * ups + 1
		val vLen = (cw - 1) * vps + 1
		if(rowY.size < yLen) rowY = ByteArray(yLen)
		if(rowU.size < uLen) rowU = ByteArray(uLen)
		if(rowV.size < vLen) rowV = ByteArray(vLen)
		val p = px; val m = mask
		for(y in 0 until oh)
		{
			// one row of the patch from each plane (bulk copies; much faster than byte by byte)
			yb.position(y * yrs + x0 * yps); yb.get(rowY, 0, yLen)
			if(y and 1 == 0)
			{
				val cy = y shr 1
				ub.position(cy * urs + (x0 shr 1) * ups); ub.get(rowU, 0, uLen)
				vb.position(cy * vrs + (x0 shr 1) * vps); vb.get(rowV, 0, vLen)
			}
			val o = y * ow
			for(x in 0 until ow)
			{
				// BT.709, video range (what the PS5 stream decodes to)
				val c = 298 * ((rowY[x * yps].toInt() and 0xff) - 16)
				val ci = x shr 1
				val u = (rowU[ci * ups].toInt() and 0xff) - 128
				val v = (rowV[ci * vps].toInt() and 0xff) - 128
				var r = (c + 459 * v + 128) shr 8
				var g = (c - 55 * u - 136 * v + 128) shr 8
				var b = (c + 541 * u + 128) shr 8
				if(r < 0) r = 0 else if(r > 255) r = 255
				if(g < 0) g = 0 else if(g > 255) g = 255
				if(b < 0) b = 0 else if(b > 255) b = 255
				p[o + x] = m[o + x] or (r shl 16) or (g shl 8) or b
			}
		}
		// three pictures in turn: the screen never draws the one being written
		val bmp = bufs[next] ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { bufs[next] = it }
		bmp.setPixels(p, 0, ow, 0, 0, ow, oh)
		next = (next + 1) % bufs.size
		fx0 = x0.toFloat() / w; fx1 = x1.toFloat() / w; fy1 = oh.toFloat() / h
		current = bmp
		fedAt = now
		postInvalidate()
	}

	/** Alpha of every patch pixel: solid over the box, fading smoothly to nothing at the patch edge (top = screen edge). */
	private fun makeMask(ow: Int, oh: Int, w: Int, h: Int, x0: Int): IntArray
	{
		val m = IntArray(ow * oh)
		val cx0 = w * CX0; val cx1 = w * CX1; val cy1 = h * CY1
		val fx = (cx0 - w * X0).coerceAtLeast(1f)
		val fy = (h * Y1 - cy1).coerceAtLeast(1f)
		for(y in 0 until oh)
		{
			val dy = ((y - cy1) / fy).coerceIn(0f, 1f)
			for(x in 0 until ow)
			{
				val sx = (x0 + x).toFloat()
				val dx = (maxOf(cx0 - sx, sx - cx1) / fx).coerceIn(0f, 1f)
				val t = maxOf(dx, dy)
				val a = 1f - t * t * (3f - 2f * t)
				m[y * ow + x] = ((a * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 24
			}
		}
		return m
	}

	override fun onDraw(canvas: Canvas)
	{
		val b = current ?: return
		if(!showing || SystemClock.elapsedRealtime() - fedAt > STALE_MS) return
		dst.set(width * fx0, 0f, width * fx1, height * fy1)
		canvas.drawBitmap(b, null, dst, paint)
		// look again shortly: if no new picture came by then, the patch goes (stream stopped)
		postInvalidateDelayed(STALE_MS + 50)
	}
}
