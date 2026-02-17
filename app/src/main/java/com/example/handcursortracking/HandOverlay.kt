package com.example.handcursortracking

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class HandOverlayView(context: Context) : View(context) {
    private var landmarks: List<NormalizedLandmark>? = null
    private val pointPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 15f
        style = Paint.Style.FILL
    }
    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

//    fun setLandmarks(newLandmarks: List<NormalizedLandmark>?) {
//        landmarks = newLandmarks
//        invalidate()
//    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        landmarks?.let { marks ->

            for (landmark in marks) {
                // Mirror X coordinates for front camera visual feedback
                val x = (1f - landmark.x()) * width
                val y = landmark.y() * height
                canvas.drawPoint(x, y, pointPaint)
            }


            drawLine(canvas, marks[5], marks[6]) // Index MCP to PIP
            drawLine(canvas, marks[6], marks[7]) // PIP to DIP
            drawLine(canvas, marks[7], marks[8]) // DIP to TIP
            

            drawLine(canvas, marks[2], marks[3]) 
            drawLine(canvas, marks[3], marks[4])
        }
    }

    private fun drawLine(canvas: Canvas, start: NormalizedLandmark, end: NormalizedLandmark) {
        val startX = (1f - start.x()) * width
        val startY = start.y() * height
        val endX = (1f - end.x()) * width
        val endY = end.y() * height
        canvas.drawLine(startX, startY, endX, endY, linePaint)
    }
}