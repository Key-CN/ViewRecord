package io.keyss.view_record

import android.media.MediaCodec
import android.media.MediaFormat
import kotlin.math.max

data class VideoRecordConfig(
    val videoMimeType: String,
    var outWidth: Int,
    var outHeight: Int,
    val colorFormat: Int,
    /** 比特率， 默认至少256kbps */
    var bitRate: Int = 256_000,
    /**
     * 默认帧率(最大帧率)采用电视级的24帧每秒，大部分fps都采用的不是整数
     * 为了让参数利于计算，且缩小文件尺寸，改为20
     * 实际视频是动态帧率
     */
    private var mFrameRate: Float = 20f,
    /**
     * I帧间隔：秒
     */
    var iFrameInterval: Float = 1f,
) {
    val videoMediaCodec: MediaCodec
    var videoTrackIndex: Int = -1
    var generateVideoFrameIndex: Long = 0

    /** 每帧时间，仅为方便计算使用 */
    private var mFpsMs: Double = 1000.0 / mFrameRate


    init {
        if (outWidth % 2 != 0) {
            outWidth -= 1
        }
        if (outHeight % 2 != 0) {
            outHeight -= 1
        }

        // config
        // acv h264
        //val mediaFormat = MediaFormat.createVideoFormat(mRecordMediaFormat, mOutWidth, mOutHeight)
        val mediaFormat = MediaFormat.createVideoFormat(videoMimeType, outWidth, outHeight)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        // 码率至少给个256Kbps吧
        bitRate = max(outWidth * outHeight, bitRate)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, mFrameRate)
        // 关键帧，单位居然是秒，25开始可以float
        mediaFormat.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        videoMediaCodec = MediaCodec.createEncoderByType(videoMimeType)
        videoMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoMediaCodec.start()
        VRLogger.d(
            "initVideoConfig: fps=$mFrameRate, fpsMs=$mFpsMs, BitRate=$bitRate, outputFormat=${videoMediaCodec.outputFormat}, width = $outWidth, height = $outHeight"
        )
    }

    fun destroy() {
        try {
            videoMediaCodec.stop()
            videoMediaCodec.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
