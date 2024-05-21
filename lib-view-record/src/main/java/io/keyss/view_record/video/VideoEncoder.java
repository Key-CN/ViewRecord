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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import io.keyss.view_record.base.BaseEncoder;
import io.keyss.view_record.base.Frame;
import io.keyss.view_record.utils.CodecUtil;

/**
 * Created by pedro on 19/01/17.
 * This class need use same resolution, fps and imageFormat that Camera1ApiManagerGl
 */

public class VideoEncoder extends BaseEncoder {
    private final GetVideoData getVideoData;
    //surface to buffer encoder
    private Surface inputSurface;
    private int width = 640;
    private int height = 480;
    private int fps = 24;
    private int bitRate = 1280 * 1024; //in kbps
    // I帧间隔：秒
    private int iFrameInterval = 1;
    //for disable video
    private final FpsLimiter fpsLimiter = new FpsLimiter();
    private String type = CodecUtil.H264_MIME;
    private FormatVideoEncoder formatVideoEncoder = FormatVideoEncoder.YUV420Dynamical;
    private int avcProfile = -1;
    private int avcProfileLevel = -1;

    public VideoEncoder(GetVideoData getVideoData) {
        this.getVideoData = getVideoData;
        TAG = "VideoEncoder";
    }

    public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int iFrameInterval, FormatVideoEncoder formatVideoEncoder) {
        return prepareVideoEncoder(width, height, fps, bitRate, iFrameInterval, formatVideoEncoder, -1, -1);
    }

    /**
     * Prepare encoder with custom parameters
     */
    public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate,
                                       int iFrameInterval, FormatVideoEncoder formatVideoEncoder,
                                       int avcProfile, int avcProfileLevel) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
        this.iFrameInterval = iFrameInterval;
        this.formatVideoEncoder = formatVideoEncoder;
        this.avcProfile = avcProfile;
        this.avcProfileLevel = avcProfileLevel;
        MediaCodecInfo encoder = chooseEncoder(type);
        try {
            if (encoder != null) {
                // 返回不为null，说明设置的颜色和格式都支持
                Log.i(TAG, "Video Encoder selected " + encoder.getName());
                codec = MediaCodec.createByCodecName(encoder.getName());
                if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
                    this.formatVideoEncoder = chooseColorDynamically(encoder);
                    if (this.formatVideoEncoder == null) {
                        Log.e(TAG, "YUV420 dynamical choose failed");
                        return false;
                    }
                } else {
                    // 验证选择的颜色是否在颜色支持列表
                    //Arrays.stream(encoder.getCapabilitiesForType(type).colorFormats).anyMatch(element -> element == formatVideoEncoder.getFormatCodec());
                    boolean contains = false;
                    for (int colorFormat : encoder.getCapabilitiesForType(type).colorFormats) {
                        if (colorFormat == formatVideoEncoder.getFormatCodec()) {
                            contains = true;
                            break;
                        }
                    }
                    if (!contains) {
                        Log.e(TAG, "非动态, 手动选择的Color format [" + formatVideoEncoder + "] not supported");
                        return false;
                    }
                }
                Log.i(TAG, "YUV420 choose: " + this.formatVideoEncoder.toString() + ", 传入: " + formatVideoEncoder);
            } else {
                Log.e(TAG, "Valid encoder not found");
                return false;
            }
            MediaFormat videoFormat = MediaFormat.createVideoFormat(type, width, height);
            String resolution = width + "x" + height;
            Log.i(TAG, "Prepare video info: " + this.formatVideoEncoder.toString() + ", resolution=" + resolution);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, this.formatVideoEncoder.getFormatCodec());
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
            //Set CBR mode if supported by encoder.
            if (CodecUtil.isCBRModeSupported(encoder, type)) {
                // 定码率
                Log.i(TAG, "set bitrate mode CBR");
                videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            } else if (CodecUtil.isVBRModeSupported(encoder, type)) {
                // 变码率
                Log.i(TAG, "set bitrate mode VBR");
                videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } else {
                Log.i(TAG, "bitrate mode CBR and VBR all not supported using default mode");
            }
            if (this.avcProfile > 0) {
                // MediaFormat.KEY_PROFILE, API > 21
                videoFormat.setInteger("profile", this.avcProfile);
            }
            if (this.avcProfileLevel > 0) {
                // MediaFormat.KEY_LEVEL, API > 23
                videoFormat.setInteger("level", this.avcProfileLevel);
            }
            setCallback();
            codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            running = false;
            if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
                inputSurface = codec.createInputSurface();
            }
            Log.i(TAG, "prepared");
            prepared = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Create VideoEncoder failed.", e);
            this.stop();
            return false;
        }
    }

    @Override
    public void start(boolean resetTs) {
        shouldReset = resetTs;
        if (resetTs) {
            fpsLimiter.setFPS(fps);
        }
        Log.i(TAG, "started");
    }

    @Override
    protected void stopImp() {
        if (inputSurface != null) inputSurface.release();
        inputSurface = null;
        Log.i(TAG, "stopped");
    }

    @Override
    public void reset() {
        stop(false);
        prepareVideoEncoder(width, height, fps, bitRate, iFrameInterval, formatVideoEncoder, avcProfile, avcProfileLevel);
        restart();
    }

    /**
     * mediaCodecList: [2135033992, 19, 21, 20, 39, 2130708361(COLOR_FormatSurface)]
     * 一般2135033992(COLOR_FormatYUV420Flexible)都是支持的，但我目前没有找到合适的转换方法
     * 21对应的是{@link android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar}
     * 2135033992对应的是{@link android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible}
     * 有限选择21、19，很多设备只有[]两种，另外两种转换可能会花屏
     * 虽然很多设备看起来支持很多颜色，但是最终转换的时候可能会花屏或者直接卡死
     */
    private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
        int[] colorFormats = mediaCodecInfo.getCapabilitiesForType(type).colorFormats;
        Log.i(TAG, "Color supported by this encoder: " + Arrays.toString(colorFormats));
        // 做一个优先选择排序
        FormatVideoEncoder[] preferredOrder = {
                FormatVideoEncoder.YUV420_SEMI_PLANAR,
                FormatVideoEncoder.YUV420_PLANAR,
                FormatVideoEncoder.YUV420_PACKED_SEMI_PLANAR,
                FormatVideoEncoder.YUV420_PACKED_PLANAR,
        };
        for (FormatVideoEncoder format : preferredOrder) {
            for (int color : colorFormats) {
                if (color == format.getFormatCodec()) {
                    return format;
                }
            }
        }
        return null;
    }

    /**
     * Prepare encoder with default parameters
     */
    public boolean prepareVideoEncoder() {
        return prepareVideoEncoder(width, height, fps, bitRate, iFrameInterval, formatVideoEncoder, avcProfile, avcProfileLevel);
    }

    public void setVideoBitrateOnFly(int bitrate) {
        if (isRunning()) {
            this.bitRate = bitrate;
            Bundle bundle = new Bundle();
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            try {
                codec.setParameters(bundle);
            } catch (IllegalStateException e) {
                Log.e(TAG, "encoder need be running", e);
            }
        }
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void setInputSurface(Surface inputSurface) {
        this.inputSurface = inputSurface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setFps(int fps) {
        this.fps = fps;
    }

    public int getFps() {
        return fps;
    }

    public int getBitRate() {
        return bitRate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FormatVideoEncoder getFormatVideoEncoder() {
        return formatVideoEncoder;
    }

    /**
     * choose the video encoder by mime.
     */
    @Override
    protected MediaCodecInfo chooseEncoder(String mime) {
        List<MediaCodecInfo> mediaCodecInfoList;
        if (force == CodecUtil.Force.HARDWARE) {
            mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(mime, true);
        } else if (force == CodecUtil.Force.SOFTWARE) {
            mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(mime, true);
        } else {
            //Priority: hardware CBR > hardware > software CBR > software
            mediaCodecInfoList = CodecUtil.getAllEncoders(mime, true, true);
        }

        Log.i(TAG, force + ", " + mediaCodecInfoList.size() + " video encoders found");
        for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
            Log.i(TAG, "Encoder: " + mediaCodecInfo.getName());
            Log.i(TAG, "Color supported by this encoder: " + Arrays.toString(mediaCodecInfo.getCapabilitiesForType(mime).colorFormats));
        }

        /*int[] preferredColorOrder = {
                FormatVideoEncoder.YUV420_SEMI_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PACKED_SEMI_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PACKED_PLANAR.getFormatCodec(),
        };*/
        List<Integer> preferredColorOrder = Arrays.asList(
                FormatVideoEncoder.YUV420_SEMI_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PACKED_SEMI_PLANAR.getFormatCodec(),
                FormatVideoEncoder.YUV420_PACKED_PLANAR.getFormatCodec()
        );
        // 如果指定颜色的话需要搜索多个编码器
        for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
            MediaCodecInfo.CodecCapabilities codecCapabilities = mediaCodecInfo.getCapabilitiesForType(mime);
            // 是否有支持的颜色格式
            for (int color : codecCapabilities.colorFormats) {
                if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
                    if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mediaCodecInfo;
                } else {
                    // 大于0为指定了颜色格式，需要找到支持该颜色的编码器
                    if (formatVideoEncoder.getFormatCodec() > 0) {
                        if (color == formatVideoEncoder.getFormatCodec()) {
                            return mediaCodecInfo;
                        }
                    } else {
                        // check if encoder support any yuv420 color
                        // note: 一般存在多种编码器，目前没做优先选择某种，未指定的情况下返回第一种
                        if (preferredColorOrder.contains(color)) {
                            return mediaCodecInfo;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected Frame getInputFrame() throws InterruptedException {
        // 这里耗时了
        long start = System.currentTimeMillis();
        Frame frame = isRealTime && null != iFrameDataGetter ? iFrameDataGetter.getFrameData() : queue.take();
        long sinceGetFrame = System.currentTimeMillis() - start;
        //VRLogger.v("取帧耗时: " + sinceGetFrame + "ms, frame=" + frame);
        // 所以这里可能会刚好已经停止了
        if (frame == null) return null;
        // 跟当前帧理应的时间差，再减去一个处理时间
        long diffTime = fpsLimiter.limitFPS() - sinceGetFrame;
        if (diffTime > 0 && running) {
            SystemClock.sleep(diffTime);
            //VRLogger.v("frame limit discarded, sleepTime=" + diffTime + "ms");
            return getInputFrame();
        }
        // 上一帧时间应为取帧时的时间
        fpsLimiter.setLastFrameTime(start);
        return frame;
    }

    @Override
    protected long calculatePts(Frame frame, long presentTimeUs) {
        return Math.max(0, frame.getTimeStamp() - presentTimeUs);
//        return frame.getTimeStamp();
    }

    @Override
    public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
        getVideoData.onVideoFormat(mediaFormat);
    }

    @Override
    protected void checkBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo) {
        fixTimeStamp(bufferInfo);
        if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
            // 感觉surface的方式可以理解为和实时的模式是一样的
            bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - presentTimeUs;
        }
    }

    @Override
    protected void sendBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo) {
        getVideoData.getVideoData(byteBuffer, bufferInfo);
    }
}
