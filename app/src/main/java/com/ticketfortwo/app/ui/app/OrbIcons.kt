package com.ticketfortwo.app.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 首页圆形入口的图标 —— 与效果图 home-orbs-pastel3.html 的 SVG 完全一致
 * （实机之前用的 Share/PlayArrow 是另一个样子，被用户指出）。
 *
 * path 颜色写死黑色无妨：Icon(tint = …) 通过 ColorFilter 对整个矢量统一着色。
 */
private var _share: ImageVector? = null

/** 分享：显示器 + 向上箭头 + 底座（投屏出去）。 */
val OrbShareIcon: ImageVector
    get() {
        _share?.let { return it }
        val v = ImageVector.Builder(
            name = "OrbShare",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.6f, 4.2f)
                horizontalLineTo(18.4f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, 2.6f, 2.6f)
                verticalLineTo(14.2f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, -2.6f, 2.6f)
                horizontalLineTo(5.6f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, -2.6f, -2.6f)
                verticalLineTo(6.8f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, 2.6f, -2.6f)
                close()
                moveTo(12f, 14.2f)
                verticalLineTo(7.6f)
                moveTo(9.1f, 10.4f)
                lineTo(12f, 7.5f)
                lineTo(14.9f, 10.4f)
                moveTo(8.2f, 20.8f)
                horizontalLineTo(15.8f)
            }
        }.build()
        _share = v
        return v
    }

private var _view: ImageVector? = null

/** 观看：手机 + 播放三角。 */
val OrbViewIcon: ImageVector
    get() {
        _view?.let { return it }
        val v = ImageVector.Builder(
            name = "OrbView",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.2f, 2.6f)
                horizontalLineTo(14.8f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, 2.6f, 2.6f)
                verticalLineTo(18.8f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, -2.6f, 2.6f)
                horizontalLineTo(9.2f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, -2.6f, -2.6f)
                verticalLineTo(5.2f)
                arcToRelative(2.6f, 2.6f, 0f, false, true, 2.6f, -2.6f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.2f, 9.6f)
                lineTo(11.2f, 14.4f)
                lineTo(15.2f, 12f)
                close()
            }
        }.build()
        _view = v
        return v
    }
