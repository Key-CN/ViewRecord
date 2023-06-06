package io.keyss.view_record

/**
 * @author Key
 * Time: 2022/10/11 16:27
 * Description:
 */
object ColorFormatUtil {
    fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                // val a = argb[index] and -0x1000000 shr 24
                val r = argb[index] and 0xff0000 shr 16
                val g = argb[index] and 0xff00 shr 8
                val b = argb[index] and 0xff shr 0
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                val v = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    yuv420sp[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
                index++
            }
        }
    }

    fun encodeYUV420P(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + width * height / 4
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                // val a = argb[index] and -0x1000000 shr 24
                val r = argb[index] and 0xff0000 shr 16
                val g = argb[index] and 0xff00 shr 8
                val b = argb[index] and 0xff shr 0
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                val v = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[vIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    yuv420sp[uIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                }
                index++
            }
        }
    }

    fun encodeYUV420PSP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                // val a = argb[index] and -0x1000000 shr 24
                val r = argb[index] and 0xff0000 shr 16
                val g = argb[index] and 0xff00 shr 8
                val b = argb[index] and 0xff shr 0
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                val v = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[yIndex + 1] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    yuv420sp[yIndex + 3] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
                if (index % 2 == 0) {
                    yIndex++
                }
                index++
            }
        }
    }

    fun encodeYUV420PP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        var vIndex = yuv420sp.size / 2
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                // val a = argb[index] and -0x1000000 shr 24
                val r = argb[index] and 0xff0000 shr 16
                val g = argb[index] and 0xff00 shr 8
                val b = argb[index] and 0xff shr 0
                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                val v = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                if (j % 2 == 0 && index % 2 == 0) { // 0
                    yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                    yuv420sp[yIndex + 1] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    yuv420sp[vIndex + 1] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                    yIndex++
                } else if (j % 2 == 0 && index % 2 == 1) { //1
                    yuv420sp[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                } else if (j % 2 == 1 && index % 2 == 0) { //2
                    yuv420sp[vIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                    vIndex++
                } else if (j % 2 == 1 && index % 2 == 1) { //3
                    yuv420sp[vIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                }
                index++
            }
        }
    }
}
