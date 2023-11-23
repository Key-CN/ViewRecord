package io.keyss.view_record.utils

import java.nio.ByteBuffer

/**
 * Description:
 *
 * Time: 2023/11/21 21:17
 * @author Key
 */
object FrameUtil {
    //H264 IDR
    const val IDR = 5

    //H265 IDR
    const val IDR_N_LP = 20
    const val IDR_W_DLP = 19

    fun isKeyFrame(videoMime: String, videoBuffer: ByteBuffer): Boolean {
        val header = ByteArray(5)
        videoBuffer.duplicate()[header, 0, header.size]
        return if (videoMime == CodecUtil.H264_MIME && header[4].toInt() and 0x1F == IDR) {  //h264
            true
        } else { //h265
            (videoMime == CodecUtil.H265_MIME && header[4].toInt() shr 1 and 0x3f == IDR_W_DLP
                    || header[4].toInt() shr 1 and 0x3f == IDR_N_LP)
        }
    }
}
