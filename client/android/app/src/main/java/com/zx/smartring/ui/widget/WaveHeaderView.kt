package com.zx.smartring.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zx.smartring.R
import androidx.core.content.ContextCompat

class WaveHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            ContextCompat.getColor(context, R.color.deep_teal),
            ContextCompat.getColor(context, R.color.header_bottom),
            Shader.TileMode.CLAMP
        )
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(width.toFloat(), 0f)
        path.lineTo(width.toFloat(), height * 0.92f)
        path.quadTo(width * 0.62f, height * 1.04f, 0f, height * 0.96f)
        path.close()
        canvas.drawPath(path, paint)
        paint.shader = null
    }
}
