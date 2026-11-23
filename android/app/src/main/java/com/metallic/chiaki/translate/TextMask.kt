package com.metallic.chiaki.translate

import android.graphics.Bitmap

/**
 * Lifts game subtitles off a busy picture.
 *
 * In gameplay (not cutscenes) GTA draws its subtitles as thin white letters with a dark edge straight over the
 * 3D scene: over a bright shop counter, signs and labels the text reader saw nothing (or glued shop labels onto the
 * line), so whole conversations were never translated. Subtitle letters are bright pixels with a dark edge right
 * beside them; flat bright areas (walls, boxes, sky) have no such edge inside them. This keeps only those letters
 * and draws them black on white, which the reader handles like a page of print.
 */
object TextMask
{
	private const val BRIGHT = 180
	private const val EDGE_DROP = 80

	fun make(src: Bitmap): Bitmap
	{
		val w = src.width; val h = src.height
		val px = IntArray(w * h)
		src.getPixels(px, 0, w, 0, 0, w, h)
		val lum = IntArray(w * h)
		for(i in px.indices)
		{
			val c = px[i]
			lum[i] = ((c shr 16 and 0xff) * 3 + (c shr 8 and 0xff) * 6 + (c and 0xff)) / 10
		}
		val k = w / 1920f // the picture handed over is the full width of the game picture
		val r = maxOf(1, Math.round(2.5f * k))
		val g = maxOf(1, Math.round(2f * k))
		// darkest value within r (square window, done as two 1-D passes)
		val near = minFilter(lum, w, h, r)
		val ring = BooleanArray(w * h)
		for(i in lum.indices) ring[i] = lum[i] >= BRIGHT && near[i] <= lum[i] - EDGE_DROP
		// grow the letter edges by g so the inside of each stroke is filled too (bright pixels only)
		val grown = grow(ring, w, h, g)
		for(i in px.indices) px[i] = if(grown[i] && lum[i] >= BRIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
		return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
	}

	private fun minFilter(a: IntArray, w: Int, h: Int, r: Int): IntArray
	{
		val t = IntArray(a.size)
		for(y in 0 until h)
		{
			val row = y * w
			for(x in 0 until w)
			{
				var m = 255
				val x0 = maxOf(0, x - r); val x1 = minOf(w - 1, x + r)
				for(xx in x0..x1) { val v = a[row + xx]; if(v < m) m = v }
				t[row + x] = m
			}
		}
		val o = IntArray(a.size)
		for(y in 0 until h)
		{
			val y0 = maxOf(0, y - r); val y1 = minOf(h - 1, y + r)
			for(x in 0 until w)
			{
				var m = 255
				for(yy in y0..y1) { val v = t[yy * w + x]; if(v < m) m = v }
				o[y * w + x] = m
			}
		}
		return o
	}

	private fun grow(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray
	{
		val t = BooleanArray(a.size)
		for(y in 0 until h)
		{
			val row = y * w
			for(x in 0 until w)
			{
				val x0 = maxOf(0, x - r); val x1 = minOf(w - 1, x + r)
				var on = false
				for(xx in x0..x1) if(a[row + xx]) { on = true; break }
				t[row + x] = on
			}
		}
		val o = BooleanArray(a.size)
		for(y in 0 until h)
		{
			val y0 = maxOf(0, y - r); val y1 = minOf(h - 1, y + r)
			for(x in 0 until w)
			{
				var on = false
				for(yy in y0..y1) if(t[yy * w + x]) { on = true; break }
				o[y * w + x] = on
			}
		}
		return o
	}
}
