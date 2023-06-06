package io.keyss.view_record

import android.graphics.Bitmap

/**
 * @author Key
 * Time: 2022/10/11 16:27
 * Description:
 */
interface ISourceProvider {
    /**
     * 下一帧图像
     */
    operator fun next(): Bitmap

    /**
     * 结果回调
     */
    fun onResult(isSuccessful: Boolean, result: String)
}
