package io.keyss.view_record.utils

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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


/**
 * @author Key
 * Time: 2022/09/01 14:46
 * Description: Bitmap.Config.RGB_565 在某些手机上（已知三星S9）不设置跟布局背景色会是透明的，用565录制会变成黑色
 */
object RecordViewUtil {
    private const val TAG = "RecordViewUtil"
    private val mBitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
    private val mMainHandler = Handler(Looper.getMainLooper())

    /**
     * @param width 指定宽度，等比例缩放高度
     */
    @Throws
    fun getBitmapFromView(window: Window, targetView: View, width: Int? = null): Bitmap {
        val finalWidth = width ?: targetView.width
        if (finalWidth <= 0) {
            //Log.w(TAG, "finalWidth=$finalWidth")
            throw IllegalArgumentException("宽度小于等于0, finalWidth=$finalWidth")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            copyPixelFromView(window, targetView, finalWidth)
        } else {
            convertViewToBitmap(targetView, finalWidth)
        }
    }

    /**
     * Android 26(O)(8.0)以下的版本，使用此方法，某些情况下颜色有偏差，已经View采集不全，比如Android14上摄像头内容未采集到
     * 如果采用drawingCache.copy宽高未生效，还是View原始的宽高，要生效需采用Canvas方式
     * drawingCache.copy耗时：6-9ms, Canvas方式还更快，所以目前改为Canvas实现
     */
    @Throws
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
        val bitmap = Bitmap.createBitmap(recordWidth, recordHeight, mBitmapConfig)
        val canvas = Canvas(bitmap)
        // 保存当前状态，目前只改变一次，多余
        //val saveCount = canvas.save()
        // 缩放Canvas来匹配目标Bitmap
        canvas.scale(recordWidth.toFloat() / targetView.width, recordHeight.toFloat() / targetView.height)
        // 将View绘制到Canvas上
        targetView.draw(canvas)
        // 恢复Canvas状态
        //canvas.restoreToCount(saveCount)
        return bitmap
    }

    /**
     * @param width 指定宽度，等比例缩放高度
     * @param targetView 只是为了算坐标，没其他用
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @Throws
    fun copyPixelFromView(window: Window, targetView: View, width: Int): Bitmap {
        //Log.i(TAG, "current Thread: ${Thread.currentThread().name}")
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

        // 黑屏
        // val bitmap = Bitmap.createBitmap(recordWidth, recordHeight, Bitmap.Config.RGB_565)
        //准备一个bitmap对象，用来将copy出来的区域绘制到此对象中，view应该是没有alpha的
        val bitmap = Bitmap.createBitmap(recordWidth, recordHeight, mBitmapConfig)

        //获取view在Window中的left-top顶点位置，基本上取的当前的window，且录的都是全部，所以都是[0,0]
        val location = IntArray(2)
        targetView.getLocationInWindow(location)
        var isSuccessful = false
        //请求转换
        //val start = System.currentTimeMillis()
        if (!window.isActive) {
            textErrorBitmap(bitmap, "窗口未激活")
            return bitmap
        }
        //val start = System.currentTimeMillis()
        //val latch = CountDownLatch(1)
        val future = CompletableFuture<Boolean>()
        try {
            PixelCopy.request(
                window,
                // 截图区域的取值，左上右下
                Rect(
                    location[0],
                    location[1],
                    location[0] + targetView.width,
                    location[1] + targetView.height
                ),
                bitmap,
                { copyResult ->
                    // 走完外面才有回调，问题不大，当然最稳妥是回调，但是回调不能同步，得加协程
                    //Log.i(TAG, "回调内isSuccessful=${copyResult == PixelCopy.SUCCESS}, 耗时=${System.currentTimeMillis() - start}ms")
                    //isSuccessful = copyResult == PixelCopy.SUCCESS
                    //latch.countDown()
                    future.complete(copyResult == PixelCopy.SUCCESS)
                },
                mMainHandler
            )
            //latch.await(100, TimeUnit.MILLISECONDS)
            isSuccessful = future.get(100, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
            textErrorBitmap(bitmap, Log.getStackTraceString(e))
        }
        //Log.i(TAG, "回调外isSuccessful=$isSuccessful, 耗时=${System.currentTimeMillis() - start}ms")
        if (!isSuccessful) {
            textErrorBitmap(bitmap)
        }
        return bitmap
    }

    private fun textErrorBitmap(bitmap: Bitmap, message: String? = "图像丢失") {
        // 在bitmap上写上错误信息
        //val start = System.currentTimeMillis()
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.RED
        paint.textSize = 50f
        canvas.drawText(message.takeIf { !it.isNullOrBlank() } ?: "图像丢失!", 10f, 100f, paint)
        //Log.i(TAG, "textErrorBitmap耗时=${System.currentTimeMillis() - start}ms")
    }
}
