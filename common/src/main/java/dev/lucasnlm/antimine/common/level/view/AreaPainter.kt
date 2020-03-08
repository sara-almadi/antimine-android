package dev.lucasnlm.antimine.common.level.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.lucasnlm.antimine.common.R
import dev.lucasnlm.antimine.common.level.data.Area
import dev.lucasnlm.antimine.common.level.data.Mark
import dev.lucasnlm.antimine.common.level.model.AreaPalette
import dev.lucasnlm.antimine.common.level.repository.DrawableRepository

fun Area.paintOnCanvas(
    context: Context,
    canvas: Canvas,
    isAmbientMode: Boolean,
    isLowBitAmbient: Boolean,
    isFocused: Boolean,
    drawableRepository: DrawableRepository,
    paintSettings: AreaPaintSettings,
    areaPalette: AreaPalette,
    markPadding: Int? = null,
    minePadding: Int? = null
) {
    paintSettings.run {
        if (isCovered) {
            if (isAmbientMode) {
                painter.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.0f
                    isAntiAlias = !isLowBitAmbient
                    color = areaPalette.border
                }
            } else {
                painter.apply {
                    style = Paint.Style.FILL
                    isAntiAlias = !isLowBitAmbient
                    color = areaPalette.covered
                    alpha = if (highlighted) 155 else 255
                }
            }

            painter.run {
                canvas.drawRoundRect(rectF, radius, radius, this)
            }

            when (mark) {
                Mark.Flag -> {
                    val padding = markPadding ?: context.resources.getDimension(R.dimen.mark_padding).toInt()

                    val flag = if (mistake) {
                        drawableRepository.provideRedFlagDrawable(context)
                    } else {
                        drawableRepository.provideFlagDrawable(context)
                    }

                    flag?.setBounds(
                        rectF.left.toInt() + padding,
                        rectF.top.toInt() + padding,
                        rectF.right.toInt() - padding,
                        rectF.bottom.toInt() - padding
                    )
                    flag?.draw(canvas)
                }
                Mark.Question -> {
                    val question = drawableRepository.provideQuestionDrawable(context)

                    question?.setBounds(
                        rectF.left.toInt(),
                        rectF.top.toInt(),
                        rectF.right.toInt(),
                        rectF.bottom.toInt()
                    )
                    question?.draw(canvas)
                }
                else -> {}
            }
        } else {
            if (isAmbientMode) {
                painter.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                    isAntiAlias = !isLowBitAmbient
                    color = areaPalette.border
                }
            } else {
                painter.apply {
                    style = Paint.Style.FILL
                    isAntiAlias = !isLowBitAmbient
                    color = areaPalette.uncovered
                }
            }

            painter.run {
                canvas.drawRoundRect(rectF, radius, radius, this)
            }

            if (hasMine) {
                val padding = minePadding ?: context.resources.getDimension(R.dimen.mine_padding).toInt()

                val mine = when {
                    isAmbientMode -> drawableRepository.provideMineLow(context)
                    mistake -> drawableRepository.provideMineExploded(context)
                    else -> drawableRepository.provideMine(context)
                }

                mine?.setBounds(
                    rectF.left.toInt() + padding,
                    rectF.top.toInt() + padding,
                    rectF.right.toInt() - padding,
                    rectF.bottom.toInt() - padding
                )
                mine?.draw(canvas)
            } else if (minesAround > 0) {
                painter.color = when (minesAround) {
                    1 -> areaPalette.minesAround1
                    2 -> areaPalette.minesAround2
                    3 -> areaPalette.minesAround3
                    4 -> areaPalette.minesAround4
                    5 -> areaPalette.minesAround5
                    6 -> areaPalette.minesAround6
                    7 -> areaPalette.minesAround7
                    else -> areaPalette.minesAround8
                }
                drawText(canvas, painter, minesAround.toString(), paintSettings)
            }

            if (highlighted) {
                val highlightWidth = context.resources.getDimension(R.dimen.highlight_stroke)
                val halfWidth = highlightWidth * 0.5f

                painter.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = highlightWidth
                    isAntiAlias = !isLowBitAmbient
                    color = areaPalette.highlight

                    val rect = RectF(
                        rectF.left + halfWidth,
                        rectF.top + halfWidth,
                        rectF.right - halfWidth,
                        rectF.bottom - halfWidth
                    )

                    canvas.drawRoundRect(rect, radius, radius, this)
                }
            }
        }

        if (isFocused) {
            val highlightWidth = context.resources.getDimension(R.dimen.highlight_stroke)
            val halfWidth = highlightWidth * 0.5f

            painter.apply {
                style = Paint.Style.STROKE
                strokeWidth = highlightWidth
                isAntiAlias = !isLowBitAmbient
                color = areaPalette.focus
            }

            val rect = RectF(
                rectF.left + halfWidth,
                rectF.top + halfWidth,
                rectF.right - halfWidth,
                rectF.bottom - halfWidth
            )

            canvas.drawRoundRect(rect, radius, radius * 0.25f, painter)
        }
    }
}

private fun drawText(canvas: Canvas, paint: Paint, text: String, paintSettings: AreaPaintSettings) {
    paintSettings.run {
        val bounds = RectF(rectF).apply {
            right = paint.measureText(text, 0, text.length)
            bottom = paint.descent() - paint.ascent()
            left += (rectF.width() - right) / 2.0f
            top += (rectF.height() - bottom) / 2.0f
        }

        canvas.drawText(text, rectF.width() * 0.5f, bounds.top - paint.ascent(), paint)
    }
}
