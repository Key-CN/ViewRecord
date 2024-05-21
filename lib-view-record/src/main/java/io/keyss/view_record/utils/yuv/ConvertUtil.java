package io.keyss.view_record.utils.yuv;

import android.graphics.Bitmap;
import android.media.MediaCodecInfo;

/**
 * Description: argb的提取和YUV的转换都是一样的，不一样的只是放到的位置不同
 * <p>
 * YUV420 Semi-Planar (NV12/NV21(Packed)): 效率：高，因为内存布局紧凑，UV 分量存储在一起，减少了内存访问。
 * YUV420 Planar (I420): 效率：次高，因为虽然每个分量单独存储，但 Y、U、V 分量可以独立访问，减少了数据交错的复杂度。
 * <p>
 * Example YUV images 4x4 px.
 * <p>
 * semi planar NV12 example:
 * <p>
 * Y1   Y2   Y3   Y4
 * Y5   Y6   Y7   Y8
 * Y9   Y10  Y11  Y12
 * Y13  Y14  Y15  Y16
 * V1   U1   V2   U2
 * V3   U3   V4   U4
 * <p>
 * <p>
 * Packed Semi Planar NV21 example:
 * <p>
 * Y1   Y2   Y3   Y4
 * Y5   Y6   Y7   Y8
 * Y9   Y10  Y11  Y12
 * Y13  Y14  Y15  Y16
 * U1   V1   U2   V2
 * U3   V3   U4   V4
 * <p>
 * <p>
 * Packed Planar YV12 example:
 * <p>
 * Y1   Y2   Y3   Y4
 * Y5   Y6   Y7   Y8
 * Y9   Y10  Y11  Y12
 * Y13  Y14  Y15  Y16
 * U1   U2   U3   U4
 * V1   V2   V3   V4
 * <p>
 * <p>
 * planar YV21(I420) example:
 * <p>
 * Y1   Y2   Y3   Y4
 * Y5   Y6   Y7   Y8
 * Y9   Y10  Y11  Y12
 * Y13  Y14  Y15  Y16
 * V1   V2   V3   V4
 * U1   U2   U3   U4
 * <p>
 * <p>
 * Time: 2024/5/20 18:40
 *
 * @author Key
 */
public class ConvertUtil {
    public static byte[] convertBitmapToYUVByteArray(Bitmap bitmap, int colorFormat) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        return switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ->
                    convertToYUV420SemiPlanar(argb, width, height);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ->
                    convertToYUV420Planar(argb, width, height);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar ->
                    convertToYUV420PackedPlanar(argb, width, height);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar ->
                    convertToYUV420PackedSemiPlanar(argb, width, height);
            default -> throw new IllegalArgumentException("Unsupported color format: " + colorFormat);
        };
    }

    /**
     * (21)NV12格式，YYYYYYYYY, UV交替存储（UVUVUV...）
     */
    private static byte[] convertToYUV420SemiPlanar(int[] argb, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int argbIndex = j * width + i;
                int r = (argb[argbIndex] >> 16) & 0xff;
                int g = (argb[argbIndex] >> 8) & 0xff;
                int b = argb[argbIndex] & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                y = Math.max(0, Math.min(255, y));
                yuv[yIndex++] = (byte) y;

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    u = Math.max(0, Math.min(255, u));
                    v = Math.max(0, Math.min(255, v));

                    yuv[uvIndex++] = (byte) u; // NV12: UVUVUV...
                    yuv[uvIndex++] = (byte) v; // NV12: UVUVUV...
                }
            }
        }
        return yuv;
    }

    /**
     * (39)NV21格式，YYYYYYYYY, VU交替存储（VUVUVU...）
     */
    private static byte[] convertToYUV420PackedSemiPlanar(int[] argb, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int argbIndex = j * width + i;
                int r = (argb[argbIndex] >> 16) & 0xff;
                int g = (argb[argbIndex] >> 8) & 0xff;
                int b = argb[argbIndex] & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                y = Math.max(0, Math.min(255, y));
                yuv[yIndex++] = (byte) y;

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    u = Math.max(0, Math.min(255, u));
                    yuv[uvIndex++] = (byte) u;
                } else if (j % 2 == 0 && i % 2 == 1) {
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    v = Math.max(0, Math.min(255, v));
                    yuv[uvIndex++] = (byte) v;
                }
            }
        }
        return yuv;
    }

    /**
     * (19)YV21格式，YUV分量顺序分开存储: YYYYYYYYYYYYYYYY...UUUU...VVVV...
     */
    private static byte[] convertToYUV420Planar(int[] argb, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int argbIndex = j * width + i;
                int r = (argb[argbIndex] >> 16) & 0xff;
                int g = (argb[argbIndex] >> 8) & 0xff;
                int b = argb[argbIndex] & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                y = Math.max(0, Math.min(255, y));
                yuv[yIndex++] = (byte) y;

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    u = Math.max(0, Math.min(255, u));
                    yuv[uIndex++] = (byte) u;

                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    v = Math.max(0, Math.min(255, v));
                    yuv[vIndex++] = (byte) v;
                }
            }
        }
        return yuv;
    }

    /**
     * Packed先V再U，和非Packed相反
     * (20)YV12：YYYYYYYYYYYY...VVVVVVVVV...UUUUUUU....
     */
    private static byte[] convertToYUV420PackedPlanar(int[] argb, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int argbIndex = j * width + i;
                int r = (argb[argbIndex] >> 16) & 0xff;
                int g = (argb[argbIndex] >> 8) & 0xff;
                int b = argb[argbIndex] & 0xff;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                y = Math.max(0, Math.min(255, y));
                yuv[yIndex++] = (byte) y;

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    u = Math.max(0, Math.min(255, u));
                    v = Math.max(0, Math.min(255, v));

                    yuv[uIndex++] = (byte) u;
                    yuv[vIndex++] = (byte) v;
                }
            }
        }
        return yuv;
    }
}
