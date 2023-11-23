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

package io.keyss.view_record.recording;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by pedro on 08/03/19.
 * <p>
 * Class to control video recording with MediaMuxer.
 */
public class AndroidMuxerRecordController extends BaseRecordController {

    private static final String TAG = "AndroidRecordController";
    private MediaMuxer mediaMuxer;
    private MediaFormat videoFormat, audioFormat;

    @Override
    public void startRecord(@NonNull String path, @Nullable Listener listener) throws IOException {
        mediaMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.listener = listener;
        status = Status.STARTED;
        if (listener != null) listener.onStatusChange(status);
        if (isOnlyAudio && audioFormat != null) init();
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void startRecord(@NonNull FileDescriptor fd, @Nullable Listener listener) throws IOException {
        mediaMuxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.listener = listener;
        status = Status.STARTED;
        if (listener != null) listener.onStatusChange(status);
        if (isOnlyAudio && audioFormat != null) init();
    }

    @Override
    public void stopRecord() {
        videoTrack = -1;
        audioTrack = -1;
        status = Status.STOPPED;
        if (mediaMuxer != null) {
            try {
                mediaMuxer.stop();
                mediaMuxer.release();
            } catch (Exception ignored) {
            }
        }
        mediaMuxer = null;
        pauseMoment = 0;
        pauseTime = 0;
        if (listener != null) listener.onStatusChange(status);
    }

    @Override
    public void recordVideo(ByteBuffer videoBuffer, MediaCodec.BufferInfo videoInfo) {
        if (status == Status.STARTED && videoFormat != null && (audioFormat != null || isOnlyVideo)) {
            if (videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME || isKeyFrame(videoBuffer)) {
                videoTrack = mediaMuxer.addTrack(videoFormat);
                init();
            }
        } else if (status == Status.RESUMED
                && (videoInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME || isKeyFrame(videoBuffer))) {
            status = Status.RECORDING;
            if (listener != null) listener.onStatusChange(status);
        }
        if (status == Status.RECORDING) {
            updateFormat(this.videoInfo, videoInfo);
            write(videoTrack, videoBuffer, this.videoInfo);
        }
    }

    @Override
    public void recordAudio(ByteBuffer audioBuffer, MediaCodec.BufferInfo audioInfo) {
        if (status == Status.RECORDING) {
            updateFormat(this.audioInfo, audioInfo);
            write(audioTrack, audioBuffer, this.audioInfo);
        }
    }

    @Override
    public void setVideoFormat(MediaFormat videoFormat, boolean isOnlyVideo) {
        this.videoFormat = videoFormat;
        this.isOnlyVideo = isOnlyVideo;
    }

    @Override
    public void setAudioFormat(MediaFormat audioFormat, boolean isOnlyAudio) {
        this.audioFormat = audioFormat;
        this.isOnlyAudio = isOnlyAudio;
        if (isOnlyAudio && status == Status.STARTED) {
            init();
        }
    }

    @Override
    public void resetFormats() {
        videoFormat = null;
        audioFormat = null;
    }

    private void init() {
        if (!isOnlyVideo) audioTrack = mediaMuxer.addTrack(audioFormat);
        mediaMuxer.start();
        status = Status.RECORDING;
        if (listener != null) listener.onStatusChange(status);
    }

    private void write(int track, ByteBuffer byteBuffer, MediaCodec.BufferInfo info) {
        try {
            mediaMuxer.writeSampleData(track, byteBuffer, info);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.i(TAG, "Write error", e);
        }
    }
}
