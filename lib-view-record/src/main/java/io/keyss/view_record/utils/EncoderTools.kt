package io.keyss.view_record.utils

import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * @author Key
 * Time: 2022/10/11 16:27
 * Description:
 */
object EncoderTools {
    /**
     * 从我打印的capabilitiesForType.colorFormats来看，确实全部都支持：2135033992，另外出现的较多的是COLOR_FormatSurface
     * 从测试结果看 全部采用COLOR_FormatYUV420Flexible在某些机型上会导致花屏
     * private var mColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
     */
    fun getColorFormat(): Int {
        var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val formats = mediaCodecList()
        lab@ for (format in formats) {
            when (format) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                    colorFormat = format
                    break@lab
                }

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    colorFormat = format
                    break@lab
                }

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> {
                    colorFormat = format
                    break@lab
                }

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> {
                    colorFormat = format
                    break@lab
                }

                else -> break@lab
            }
        }
        return colorFormat
    }

    fun getPixels(colorFormat: Int, inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> ColorFormatUtil.encodeYUV420SP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> ColorFormatUtil.encodeYUV420P(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> ColorFormatUtil.encodeYUV420PSP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> ColorFormatUtil.encodeYUV420PP(
                yuv,
                argb,
                inputWidth,
                inputHeight
            )
        }
        return yuv
    }

    /**
     * 获取可以支持的格式
     */
    private fun mediaCodecList(): IntArray {
        val numCodecs = MediaCodecList.getCodecCount()
        var codecInfo: MediaCodecInfo? = null
        var i = 0
        while (i < numCodecs && codecInfo == null) {
            val info = MediaCodecList.getCodecInfoAt(i)
            if (!info.isEncoder) {
                i++
                continue
            }
            val types = info.supportedTypes
            var found = false
            // The decoder required by the rotation training
            var j = 0
            while (j < types.size && !found) {
                if (types[j] == "video/avc") {
                    found = true
                }
                j++
            }
            if (!found) {
                i++
                continue
            }
            codecInfo = info
            i++
        }
        val capabilities = codecInfo!!.getCapabilitiesForType("video/avc")
        return capabilities.colorFormats
    }
}
