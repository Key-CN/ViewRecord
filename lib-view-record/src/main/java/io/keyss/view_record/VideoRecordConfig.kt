package io.keyss.view_record

import android.media.MediaCodec
import android.media.MediaFormat
import kotlin.math.max

data class VideoRecordConfig(
    /** 编码类型 */
    val videoMimeType: String,
    val colorFormat: Int,
    var outWidth: Int,
    var outHeight: Int,
    /** 最终实际采用的比特率 */
    var bitRate: Int,
    /** 实际视频是动态帧率 */
    val frameRate: Float,
    /** I帧间隔：秒 */
    val iFrameInterval: Float,
) {
    val videoMediaCodec: MediaCodec
    var videoTrackIndex: Int = -1
    var generateVideoFrameIndex: Long = 0

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
        mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate)
        // 关键帧，单位居然是秒，25开始可以float
        mediaFormat.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        videoMediaCodec = MediaCodec.createEncoderByType(videoMimeType)
        videoMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoMediaCodec.start()
        VRLogger.d(
            "initVideoConfig: fps=$frameRate, BitRate=$bitRate, outputFormat=${videoMediaCodec.outputFormat}, width = $outWidth, height = $outHeight"
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
