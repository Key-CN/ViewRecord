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
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.List;

import io.keyss.view_record.base.BaseEncoder;
import io.keyss.view_record.base.Frame;
import io.keyss.view_record.utils.CodecUtil;
import io.keyss.view_record.utils.yuv.YUVUtil;

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

    public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate,
                                       int iFrameInterval, FormatVideoEncoder formatVideoEncoder) {
        return prepareVideoEncoder(width, height, fps, bitRate,
                iFrameInterval, formatVideoEncoder, -1, -1);
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
        isBufferMode = true;
        MediaCodecInfo encoder = chooseEncoder(type);
        try {
            if (encoder != null) {
                // 返回不为null，说明设置的颜色和格式都支持
                Log.i(TAG, "Encoder selected " + encoder.getName());
                codec = MediaCodec.createByCodecName(encoder.getName());
                if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
                    this.formatVideoEncoder = chooseColorDynamically(encoder);
                    if (this.formatVideoEncoder == null) {
                        Log.e(TAG, "YUV420 dynamical choose failed");
                        return false;
                    }
                }
            } else {
                Log.e(TAG, "Valid encoder not found");
                return false;
            }
            MediaFormat videoFormat = MediaFormat.createVideoFormat(type, width, height);
            String resolution = width + "x" + height;
            Log.i(TAG, "Prepare video info: " + this.formatVideoEncoder.name() + ", " + resolution);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    this.formatVideoEncoder.getFormatCodec());
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
            //Set CBR mode if supported by encoder.
            if (CodecUtil.isCBRModeSupported(encoder, type)) {
                Log.i(TAG, "set bitrate mode CBR");
                videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            } else {
                Log.i(TAG, "bitrate mode CBR not supported using default mode");
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
                isBufferMode = false;
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
        if (formatVideoEncoder != FormatVideoEncoder.SURFACE) {
            YUVUtil.preAllocateBuffers(width * height * 3 / 2);
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

    private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
        for (int color : mediaCodecInfo.getCapabilitiesForType(type).colorFormats) {
            if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
                return FormatVideoEncoder.YUV420PLANAR;
            } else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
                return FormatVideoEncoder.YUV420SEMIPLANAR;
            }
        }
        return null;
    }

    /**
     * Prepare encoder with default parameters
     */
    public boolean prepareVideoEncoder() {
        return prepareVideoEncoder(width, height, fps, bitRate, iFrameInterval,
                formatVideoEncoder, avcProfile, avcProfileLevel);
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

        Log.i(TAG, mediaCodecInfoList.size() + " encoders found");
        for (MediaCodecInfo mci : mediaCodecInfoList) {
            Log.i(TAG, "Encoder " + mci.getName());
            MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
            // 获取支持的颜色格式
            for (int color : codecCapabilities.colorFormats) {
                Log.i(TAG, "Color supported: " + color);
                if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
                    if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci;
                } else {
                    //check if encoder support any yuv420 color
                    if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
                            || color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
                        return mci;
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected Frame getInputFrame() throws InterruptedException {
        // 这里耗时了
        Frame frame = isRealTime ? iFrameDataGetter.getFrameData() : queue.take();
        // 所以这里可能会刚好已经停止了
        if (frame == null) return null;
        // todo 要改，这个fpsLimiter有问题
        if (fpsLimiter.limitFPS() && running) {
            //long sleepTime = fpsLimiter.getSleepTime();
            //SystemClock.sleep(sleepTime);
            //VRLogger.v("frame limit discarded, sleepTime=" + sleepTime + "ms");
            return getInputFrame();
        }
        return frame;
    }

    @Override
    protected long calculatePts(Frame frame, long presentTimeUs) {
        return Math.max(0, frame.getTimeStamp() - presentTimeUs);
    }

    @Override
    public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
        getVideoData.onVideoFormat(mediaFormat);
    }

    @Override
    protected void checkBuffer(@NonNull ByteBuffer byteBuffer,
                               @NonNull MediaCodec.BufferInfo bufferInfo) {
        fixTimeStamp(bufferInfo);
        if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
            bufferInfo.presentationTimeUs = System.nanoTime() / 1000 - presentTimeUs;
        }
    }

    @Override
    protected void sendBuffer(@NonNull ByteBuffer byteBuffer,
                              @NonNull MediaCodec.BufferInfo bufferInfo) {
        getVideoData.getVideoData(byteBuffer, bufferInfo);
    }
}
