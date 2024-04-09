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
     * 有待研究
     */
    //YUV420_FLEXIBLE,
    //YUV422FLEXIBLE, YUV422PLANAR, YUV422SEMIPLANAR, YUV422PACKEDPLANAR, YUV422PACKEDSEMIPLANAR,
    //YUV444FLEXIBLE, YUV444INTERLEAVED,
    SURFACE,
    //take first valid color for encoder (YUV420PLANAR, YUV420SEMIPLANAR or YUV420PACKEDPLANAR)
    YUV420Dynamical;

    public int getFormatCodec() {
        return switch (this) {
            case YUV420_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            case YUV420_SEMI_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            case YUV420_PACKED_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
            case YUV420_PACKED_SEMI_PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
            //case YUV420_FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            //case YUV422FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Flexible;
            //case YUV422PLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422Planar;
            //case YUV422SEMIPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422SemiPlanar;
            //case YUV422PACKEDPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedPlanar;
            //case YUV422PACKEDSEMIPLANAR -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar;
            //case YUV444FLEXIBLE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible;
            //case YUV444INTERLEAVED -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Interleaved;
            //case SURFACE -> MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
            default -> -1;
        };
    }

    @NonNull
    @Override
    public String toString() {
        return name() + ", int code: " + getFormatCodec();
    }
}
