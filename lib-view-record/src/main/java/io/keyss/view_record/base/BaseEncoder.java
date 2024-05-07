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

package io.keyss.view_record.base;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.keyss.view_record.utils.CodecUtil;
import io.keyss.view_record.video.EncoderCallback;
import io.keyss.view_record.video.EncoderErrorCallback;
import io.keyss.view_record.video.IFrameDataGetter;

/**
 * Created by pedro on 18/09/19.
 */
public abstract class BaseEncoder implements EncoderCallback {
    protected String TAG = "BaseEncoder";
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private HandlerThread handlerThread;
    // 持续输入数据的队列
    protected BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(80);
    // 实时型
    protected boolean isRealTime = false;
    // 获取当前数据帧接口
    protected IFrameDataGetter iFrameDataGetter;
    protected MediaCodec codec;
    protected static long presentTimeUs;
    protected volatile boolean running = false;
    protected CodecUtil.Force force = CodecUtil.Force.FIRST_COMPATIBLE_FOUND;
    private MediaCodec.Callback callback;
    private long oldTimeStamp = 0L;
    protected boolean shouldReset = true;
    protected boolean prepared = false;
    private Handler handler;
    private EncoderErrorCallback encoderErrorCallback;

    public void setEncoderErrorCallback(EncoderErrorCallback encoderErrorCallback) {
        this.encoderErrorCallback = encoderErrorCallback;
    }

    public void setFrameDataGetter(IFrameDataGetter iFrameDataGetter) {
        isRealTime = true;
        this.iFrameDataGetter = iFrameDataGetter;
    }

    public void setRealTime(boolean realTime) {
        isRealTime = realTime;
    }

    public void restart() {
        start(false);
        initCodec();
    }

    public void start() {
        if (!prepared)
            throw new IllegalStateException(TAG + " not prepared yet. You must call prepare method before start it");
        if (presentTimeUs == 0) {
            presentTimeUs = System.nanoTime() / 1000;
        }
        start(true);
        initCodec();
    }

    protected void setCallback() {
        handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        createAsyncCallback();
        codec.setCallback(callback, handler);
    }

    private void initCodec() {
        // reset的时候可能出现
        if (codec == null) {
            throw new IllegalStateException("codec未初始化");
        }
        codec.start();
        running = true;
    }

    public abstract void reset();

    public abstract void start(boolean resetTs);

    protected abstract void stopImp();

    protected void fixTimeStamp(MediaCodec.BufferInfo info) {
        if (oldTimeStamp > info.presentationTimeUs) {
            info.presentationTimeUs = oldTimeStamp;
        } else {
            oldTimeStamp = info.presentationTimeUs;
        }
    }

    private void reloadCodec(IllegalStateException e) {
        //Sometimes encoder crash, we will try recover it. Reset encoder a time if crash
        EncoderErrorCallback callback = encoderErrorCallback;
        if (callback != null) {
            shouldReset = callback.onEncodeError(TAG, e);
        }
        if (shouldReset) {
            Log.e(TAG, "Encoder crashed, trying to recover it", e);
            reset();
        }
    }

    public void stop() {
        stop(true);
    }

    public void stop(boolean resetTs) {
        if (resetTs) {
            presentTimeUs = 0;
        }
        running = false;
        stopImp();
        if (handlerThread != null) {
            if (handlerThread.getLooper() != null) {
                if (handlerThread.getLooper().getThread() != null) {
                    handlerThread.getLooper().getThread().interrupt();
                }
                handlerThread.getLooper().quit();
            }
            handlerThread.quit();
            if (codec != null) {
                try {
                    codec.flush();
                } catch (IllegalStateException ignored) {
                }
            }
            //wait for thread to die for 500ms.
            try {
                handlerThread.getLooper().getThread().join(500);
            } catch (Exception ignored) {
            }
        }
        queue.clear();
        queue = new ArrayBlockingQueue<>(80);
        try {
            codec.stop();
            codec.release();
            codec = null;
        } catch (Exception e) {
            Log.e(TAG, "Error stopping codec", e);
        } finally {
            codec = null;
        }
        prepared = false;
        oldTimeStamp = 0L;
    }

    protected abstract MediaCodecInfo chooseEncoder(String mime);

    protected abstract Frame getInputFrame() throws InterruptedException;

    protected abstract long calculatePts(Frame frame, long presentTimeUs);

    /**
     * 这个方法里多处耗时的地方，都要处理终止状态，否则会报个不大不小，毫无影响的异常，就是这个byteBuffer已经不可用了
     */
    private void processInput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
                              int inBufferIndex) throws IllegalStateException {
        byteBuffer.clear();
        // 试试，防止偷跑
        if (!running) {
            Log.d(TAG, "processInput1: not running");
            return;
        }
        try {
            Frame frame = getInputFrame();
            // 如果停止的时候返回null，那这里就会死循环，所以上一层不可以给null
            while (frame == null && running) frame = getInputFrame();
            // 在这里终止掉
            if (!running) {
                Log.d(TAG, "processInput3: not running");
                return;
            }
            int size = Math.max(0, Math.min(frame.getSize(), byteBuffer.remaining()) - frame.getOffset());
            byteBuffer.put(frame.getBuffer(), frame.getOffset(), size);
            long pts = calculatePts(frame, presentTimeUs);
            mediaCodec.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (NullPointerException | IndexOutOfBoundsException e) {
            Log.i(TAG, "Encoding error", e);
        }
    }

    protected abstract void checkBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo);

    protected abstract void sendBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo);

    private void processOutput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
                               int outBufferIndex, @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
        checkBuffer(byteBuffer, bufferInfo);
        sendBuffer(byteBuffer, bufferInfo);
        mediaCodec.releaseOutputBuffer(outBufferIndex, false);
    }

    public void setForce(CodecUtil.Force force) {
        this.force = force;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void inputAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) throws IllegalStateException {
        // 试试，防止偷跑
        if (!running) {
            Log.d(TAG, "inputAvailable: not running");
            return;
        }
        ByteBuffer byteBuffer = mediaCodec.getInputBuffer(inBufferIndex);
        processInput(byteBuffer, mediaCodec, inBufferIndex);
    }

    @Override
    public void outputAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
                                @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
        ByteBuffer byteBuffer = mediaCodec.getOutputBuffer(outBufferIndex);
        processOutput(byteBuffer, mediaCodec, outBufferIndex, bufferInfo);
    }

    private void createAsyncCallback() {
        callback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) {
                try {
                    inputAvailable(mediaCodec, inBufferIndex);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaCodec.Callback.onInputBufferAvailable Encoding error", e);
                    reloadCodec(e);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
                try {
                    outputAvailable(mediaCodec, outBufferIndex, bufferInfo);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaCodec.Callback.onOutputBufferAvailable Encoding error", e);
                    reloadCodec(e);
                }
            }

            @Override
            public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
                EncoderErrorCallback callback = encoderErrorCallback;
                if (callback != null) callback.onCodecError(TAG, e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
                                              @NonNull MediaFormat mediaFormat) {
                formatChanged(mediaCodec, mediaFormat);
            }
        };
    }
}
