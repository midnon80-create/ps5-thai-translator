package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Subtitle text with a solid dark outline, so Thai stays readable on bright and busy scenes
 * while the background box can stay light and cover less of the game.
 */
class SubtitleTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
	: AppCompatTextView(context, attrs)
{
	var outlineColor = Color.BLACK
	var outlineWidthPx = 0f

	private var drawingOutline = false

	override fun onDraw(canvas: Canvas)
	{
		if(outlineWidthPx <= 0f)
		{
			super.onDraw(canvas)
			return
		}
		val fill = currentTextColor
		drawingOutline = true
		paint.style = Paint.Style.STROKE
		paint.strokeJoin = Paint.Join.ROUND
		paint.strokeWidth = outlineWidthPx
		setTextColor(outlineColor)
		super.onDraw(canvas)
		paint.style = Paint.Style.FILL
		setTextColor(fill)
		drawingOutline = false
		super.onDraw(canvas)
	}

	// setTextColor() inside onDraw would otherwise trigger a redraw loop
	override fun invalidate()
	{
		if(!drawingOutline) super.invalidate()
	}
}
