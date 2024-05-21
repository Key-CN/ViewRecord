/*
 * Copyright (C) 2023 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.keyss.view_record.video;

import android.media.MediaCodecInfo;

import androidx.annotation.NonNull;

/**
 * Created by pedro on 21/01/17.
 * NOTE: 目前直接转换只支持21, 19, 39, 20这四种，所以其余的注释掉，如有需要自行查找转换方案，并且在chooseColorDynamically方法中添加转换函数
 */

public enum FormatVideoEncoder {
    YUV420_SEMI_PLANAR, YUV420_PLANAR, YUV420_PACKED_PLANAR, YUV420_PACKED_SEMI_PLANAR,
    /*
     * YUV420Flexible并不是一种确定的YUV420格式，而是包含COLOR_FormatYUV411Planar, COLOR_FormatYUV411PackedPlanar, COLOR_FormatYUV420Planar, COLOR_FormatYUV420PackedPlanar, COLOR_FormatYUV420SemiPlanar和COLOR_FormatYUV420PackedSemiPlanar。
     * 在API 21引入YUV420Flexible的同时，它所包含的这些格式都deprecated掉了
     * YUV420Flexible是一种灵活的格式，具体使用时依旧需要确定当前使用的子格式才能完成正确的转换，所以放这里没有意义
     */
    //YUV420_FLEXIBLE,
    //YUV422FLEXIBLE, YUV422PLANAR, YUV422SEMIPLANAR, YUV422PACKEDPLANAR, YUV422PACKEDSEMIPLANAR,
    //YUV444FLEXIBLE, YUV444INTERLEAVED,
    /**
     * SURFACE通常用于直接渲染，不需要将 Bitmap 转换为字节数组
     */
    SURFACE,
    /**
     * 用于动态获取
     */
    YUV420Dynamical;

    public int getFormatCodec() {
        return switch (this) {
            // = NV12 = 21
            case YUV420_SEMI_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            // = i420 = YV21 =19
            case YUV420_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            // = NV21 = 39
            case YUV420_PACKED_SEMI_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
            // = YV12 = 20
            case YUV420_PACKED_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
            //case SURFACE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
            // 动态格式：2135033992
            //case YUV420_FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            //case YUV422FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible;
            //case YUV422PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar;
            //case YUV422SEMIPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar;
            //case YUV422PACKEDPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar;
            //case YUV422PACKEDSEMIPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar;
            //case YUV444FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible;
            //case YUV444INTERLEAVED -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved;
            default -> -1;
        };
    }

    @NonNull
    @Override
    public String toString() {
        return name() + ", int code: " + getFormatCodec();
    }
}
