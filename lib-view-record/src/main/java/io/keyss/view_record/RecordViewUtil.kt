package io.keyss.view_record

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * @author Key
 * Time: 2022/09/01 14:46
 * Description: Bitmap.Config.RGB_565 在某些手机上（已知三星S9）不设置跟布局背景色会是透明的，用565录制会变成黑色
 */
object RecordViewUtil {
    private const val TAG = "RecordViewUtil"
    private val mMainHandler = Handler(Looper.getMainLooper())

    fun getBitmapFromView(window: Window, targetView: View, width: Int? = null): Bitmap {
        val finalWidth = width ?: targetView.width
        if (finalWidth <= 0) {
            Log.i(TAG, "finalWidth=$finalWidth")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            copyPixelFromView(window, targetView, finalWidth)
        } else {
            convertViewToBitmap(targetView, finalWidth)
        }

        //return convertViewToBitmap(targetView, width ?: targetView.width)
    }

    /**
     * 这个宽高啥的先不管他，到时候再来看看要不要优化吧。
     */
    @Suppress("DEPRECATION")
    fun convertViewToBitmap(targetView: View, width: Int): Bitmap {
        // 优化宽高
        val recordWidth = if (width % 2 != 0) {
            width - 1
        } else {
            width
        }
        var recordHeight = if (recordWidth == targetView.width) {
            // 宽度不变，则高度也不变
            targetView.height
        } else {
            (targetView.height * (recordWidth.toFloat() / targetView.width)).toInt()
        }
        if (recordHeight % 2 != 0) {
            recordHeight -= 1
        }
        //开启DrawingCache
        targetView.isDrawingCacheEnabled = true
        var bitmap: Bitmap?
        targetView.buildDrawingCache()
        bitmap = targetView.drawingCache.copy(Bitmap.Config.ARGB_8888, true)
        targetView.destroyDrawingCache()
        if (bitmap == null) {
            Log.w(TAG, "获取不到drawingCache，采用Canvas方式")
            bitmap = Bitmap.createBitmap(recordWidth, recordHeight, Bitmap.Config.ARGB_8888)
            val bitmapHolder = Canvas(bitmap)
            targetView.draw(bitmapHolder)
        }
        return bitmap
    }

    /**
     * @param width 指定宽度，等比例缩放高度
     * @param targetView 只是为了算坐标，没其他用
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun copyPixelFromView(window: Window, targetView: View, width: Int): Bitmap {
        // 优化宽高
        val recordWidth = if (width % 2 != 0) {
            width - 1
        } else {
            width
        }
        var recordHeight = if (recordWidth == targetView.width) {
            // 宽度不变，则高度也不变
            targetView.height
        } else {
            (targetView.height * (recordWidth.toFloat() / targetView.width)).toInt()
        }
        if (recordHeight % 2 != 0) {
            recordHeight -= 1
        }

        //准备一个bitmap对象，用来将copy出来的区域绘制到此对象中，view应该是没有alpha的
        val bitmap = Bitmap.createBitmap(recordWidth, recordHeight, Bitmap.Config.ARGB_8888)
        // 黑屏
//        val bitmap = Bitmap.createBitmap(recordWidth, recordHeight, Bitmap.Config.RGB_565)

        //获取view在Window中的left-top顶点位置，基本上取的当前的window，且录的都是全部，所以都是[0,0]
        val location = IntArray(2)
        targetView.getLocationInWindow(location)
        var isSuccessful = false
        //请求转换
        //val start = System.currentTimeMillis()
        if (!window.isActive) {
            return bitmap
        }
        try {
            PixelCopy.request(
                window,
                // 截图区域的取值
                Rect(
                    location[0], location[1],
                    location[0] + targetView.width, location[1] + targetView.height
                ),
                bitmap,
                { copyResult ->
                    // 成功
                    isSuccessful = copyResult == PixelCopy.SUCCESS
                    // 走完外面才有回调，问题不大，当然最稳妥是回调，但是回调不能同步，得加协程
                },
                mMainHandler
            )
        } catch (e: IllegalArgumentException) {
            return bitmap
        }
        return bitmap
    }
}
