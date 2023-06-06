package io.keyss.view_record

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * @author Key
 * Time: 2022/09/01 20:40
 * Description: 录制及编码
 * 该方案中音频的Mic启动需要时间，而视频当前帧的获取是瞬间的，所以优先启动音频线程。
 * 总的来说录制可以视为视频是主轴，音频是辅轴
 */
class RecordEncoder {
    companion object {
        private const val TAG = "RecordEncoder"
    }

    // acv h264, hevc h265, 根据需要求改
    var videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    var audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC

    // 从我打印的capabilitiesForType.colorFormats来看，确实全部都支持：2135033992，另外出现的较多的是COLOR_FormatSurface
    // 从测试结果看 全部采用COLOR_FormatYUV420Flexible在某些机型上会导致花屏
    //private var mColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var mColorFormat = 0

    @Volatile
    private var isVideoStarted = false

    @Volatile
    private var isAudioStarted = false

    /**
     * running是运行完，因为stop之后最后一帧还需要时间来保存
     */
    @Volatile
    private var isRunning = false

    /**
     * 只回调一次
     */
    @Volatile
    private var isResulted = false

    private lateinit var mMediaMuxer: MediaMuxer

    @Volatile
    private var isMuxerStarted = false
    private var mRecordStartTime = 0L
    private var mLastFrameTime = 0L

    ////// Video
    private lateinit var mVideoMediaCodec: MediaCodec
    private val mVideoBufferInfo = MediaCodec.BufferInfo()
    private var mVideoTrackIndex = -1
    private var mGenerateVideoFrameIndex: Long = 0
    private var mOutWidth = 0
    private var mOutHeight = 0

    /**
     * 默认帧率(最大帧率)采用电视级的24帧每秒，大部分fps都采用的不是整数
     * 为了让参数利于计算，且缩小文件尺寸，改为20
     * 实际视频是动态帧率
     */
    private var mFrameRate: Float = 20f

    /** 每帧时间，仅为方便计算使用 */
    private var mFpsMs: Double = 1000.0 / mFrameRate

    /** 比特率， 默认至少256kbps */
    private var mBitRate = 256_000

    /**
     * I帧间隔：秒
     */
    var iFrameInterval = 1f

    ////// Audio
    private var isRecordAudio: Boolean = false
    private lateinit var mAudioMediaCodec: MediaCodec
    private val mAudioBufferInfo = MediaCodec.BufferInfo()
    private var mAudioTrackIndex = -1
    private lateinit var mAudioRecord: AudioRecord

    //private lateinit var mAudioBuffer: ByteBuffer
    private lateinit var mAudioBuffer: ByteArray
    private var mAudioBufferSize: Int = 0
    var audioSampleRate: Int = 44100 // 采样率, 默认：44100
    var audioChannelCount: Int = 1 // 声道数, 默认：1
    var channelConfig = AudioFormat.CHANNEL_IN_MONO // 默认：单声道
    var audioFormat = AudioFormat.ENCODING_PCM_16BIT // 默认：16位PCM编码
    var audioSource = MediaRecorder.AudioSource.MIC // 默认：音频源为麦克风


    /**
     * 数据源
     */
    private lateinit var mSourceProvider: ISourceProvider

    /**
     * 输出文件，存在则覆盖
     */
    private lateinit var mOutputFile: File


    // 输入流Buffer超时，微秒
    private val defaultTimeOutUs: Long = 0

    /**
     * 到时候加个前置配置，不从start加参数
     */
    fun setUp(provider: ISourceProvider, outputFile: File, minBitRate: Int, isRecordAudio: Boolean = true) {
        if (isVideoStarted || isRunning) {
            return
        }
        mOutputFile = outputFile
        mSourceProvider = provider
        mBitRate = minBitRate
        // TODO: 2023/6/6 判断没有权限的话再改成false或者抛出异常
        this.isRecordAudio = isRecordAudio
    }

    @Synchronized
    fun start() {
        if (isVideoStarted || isRunning) {
            return
        }
        isRunning = true
        mGenerateVideoFrameIndex = 0
        thread {
            val bitmap = mSourceProvider.next()
            init(bitmap.width, bitmap.height, mBitRate)
            if (isRecordAudio) {
                runAudio()
            } else {
                runVideo()
            }
        }
    }

    fun start(provider: ISourceProvider, outputFile: File, minBitRate: Int, isRecordAudio: Boolean = true) {
        Log.d(TAG, "start() called with: isStarted = $isVideoStarted, isRunning = $isRunning, outputFile=$outputFile, minBitRate=$minBitRate")
        setUp(provider, outputFile, minBitRate, isRecordAudio)
        start()
    }

    fun onError() {
        isVideoStarted = false
        isAudioStarted = false
    }

    /**
     * 此处stop只是为了停止循环，真正的结束需要在循环的末尾，写入end标识到文件
     */
    fun stop() {
        if (!isVideoStarted) {
            Log.w(TAG, "stop() called 未启动，不用停止")
            return
        }
        Log.i(TAG, "stop() called")
        isRunning = false
        isVideoStarted = false
        isAudioStarted = false
    }

    @Synchronized
    private fun finish() {
        try {
            if (::mMediaMuxer.isInitialized) {
                if (isMuxerStarted) {
                    isMuxerStarted = false
                    mMediaMuxer.stop()
                }
                mMediaMuxer.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(false, "结束失败：${e.message}")
        } finally {
            isRunning = false
        }
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO, conditional = true)
    private fun init(width: Int, height: Int, minBitRate: Int) {
        if (isRecordAudio) {
            initAudioConfig()
        }
        initVideoConfig(width, height, minBitRate)

        // Create the generated MP4 initialization object
        if (mOutputFile.exists()) {
            mOutputFile.delete()
        }
        mOutputFile.createNewFile()
        mMediaMuxer = MediaMuxer(mOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)


        // 报错
        // Missing codec specific data
        // Dumping Video track's last 10 frames timestamp and frame type
        // (3451328us, 3451328us Non-Key frame) (3520755us, 3520755us Non-Key frame) (3586903us, 3586903us Non-Key frame) (3652671us, 3652671us Non-Key frame) (3722708us, 3722708us Non-Key frame) (3796290us, 3796290us Non-Key frame) (3861818us, 3861818us Non-Key frame) (3927824us, 3927824us Non-Key frame) (3992905us, 3992905us Non-Key frame) (4064337us, 4064337us Non-Key frame)
        /*mTrackIndex = mMediaMuxer.addTrack(mMediaCodec.outputFormat)
        mMediaMuxer.start()
        mMuxerStarted = true*/
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initAudioConfig() {
        // 计算缓冲区大小
        mAudioBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, audioFormat)
        //mAudioBuffer = ByteBuffer.allocateDirect(mAudioBufferSize)
        mAudioBuffer = ByteArray(mAudioBufferSize)
        // 初始化AudioRecord实例
        mAudioRecord = AudioRecord(audioSource, audioSampleRate, channelConfig, audioFormat, mAudioBufferSize)
        // codec
        val audioFormat = MediaFormat.createAudioFormat(audioMimeType, audioSampleRate, audioChannelCount)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000) // 音频比特率
        audioFormat.setString(MediaFormat.KEY_MIME, audioMimeType)
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) // 音频配置
        //audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mAudioBufferSize) // 最大输入大小
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mAudioBufferSize)
        mAudioMediaCodec = MediaCodec.createEncoderByType(audioMimeType)
        mAudioMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mAudioMediaCodec.start()
        Log.d(TAG, "initAudioConfig outputFormat=${mAudioMediaCodec.outputFormat}, mAudioBufferSize=$mAudioBufferSize")
    }

    private fun initVideoConfig(width: Int, height: Int, minBitRate: Int) {
        setColorFormat()
        mOutWidth = width
        if (mOutWidth % 2 != 0) {
            mOutWidth -= 1
        }
        mOutHeight = height
        if (mOutHeight % 2 != 0) {
            mOutHeight -= 1
        }

        // config
        // acv h264
        //val mediaFormat = MediaFormat.createVideoFormat(mRecordMediaFormat, mOutWidth, mOutHeight)
        val mediaFormat = MediaFormat.createVideoFormat(videoMimeType, mOutWidth, mOutHeight)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat)
        // 码率至少给个256Kbps吧
        mBitRate = max(mOutWidth * mOutHeight, minBitRate)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate)
        mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, mFrameRate)
        // 关键帧，单位居然是秒，25开始可以float
        mediaFormat.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        mVideoMediaCodec = MediaCodec.createEncoderByType(videoMimeType)
        mVideoMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mVideoMediaCodec.start()
        Log.d(
            TAG,
            "initVideoConfig: fps=$mFrameRate, fpsMs=$mFpsMs, BitRate=$mBitRate, outputFormat=${mVideoMediaCodec.outputFormat}, 输入的width = $width, height = $height"
        )
    }

    private fun runVideo() {
        thread {
            try {
                recordVideo()
            } catch (e: Exception) {
                Log.e(TAG, "视频录制错误", e)
            } finally {
                if (::mVideoMediaCodec.isInitialized) {
                    /*try {
                        mVideoMediaCodec.signalEndOfInputStream()
                    } catch (_: Exception) {
                    }*/
                    mVideoMediaCodec.stop()
                    mVideoMediaCodec.release()
                }
                finish()
            }
        }
    }

    /**
     * 干脆就丢掉第一帧，没多大影响，可以简化代码流程
     */
    private fun recordVideo() {
        isVideoStarted = true
        mRecordStartTime = System.nanoTime()
        while (isVideoStarted) {
            val start = System.currentTimeMillis()
            // 从队列中去一个可用的buffer的index
            val inputBufferIndex = mVideoMediaCodec.dequeueInputBuffer(defaultTimeOutUs)
            //Log.i(TAG, "视频: inputBufferIndex=$inputBufferIndex, 第${mGenerateVideoFrameIndex}帧数据")
            // 无可用缓冲区，丢掉（这个模式下实为等待，不取下一帧图像数据），下一帧
            if (inputBufferIndex < 0) {
                // 等下一帧
                SystemClock.sleep(mFpsMs.toLong() + 1L)
                continue
            }
            // 取个输入buffer
            val inputBuffer = mVideoMediaCodec.getInputBuffer(inputBufferIndex) ?: continue
            val ptsUsec = (System.nanoTime() - mRecordStartTime) / 1000
            Log.i(TAG, "视频pts=${ptsUsec}us")
            // 录制
            val inputData: ByteArray = getCurrentPixelsData()
            inputBuffer.clear()
            inputBuffer.put(inputData)
            // Put the data on the encoding queue, 把buffer传给codec
            // 所以目前这里不可能出现false
            val inputFlags = if (isVideoStarted) {
                0
            } else {
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            }
            // 压入缓冲区，准备编码
            mVideoMediaCodec.queueInputBuffer(inputBufferIndex, 0, inputData.size, ptsUsec, inputFlags)
            while (true) {
                // Returns the index of an output buffer that has been successfully decoded or one of the INFO_* constants.
                val outputBufferIndex = mVideoMediaCodec.dequeueOutputBuffer(mVideoBufferInfo, defaultTimeOutUs)
                Log.i(
                    TAG,
                    "输出: 第${mGenerateVideoFrameIndex}帧数据, outputBufferIndex=[$outputBufferIndex], inputFlags=[$inputFlags], buffer.flags=[${mVideoBufferInfo.flags}], buffer.size=[${mVideoBufferInfo.size}], isVideoStarted=$isVideoStarted"
                )

                // 有数据
                if (outputBufferIndex >= 0) {
                    val currentTimeMillis = System.currentTimeMillis()
                    if (mLastFrameTime > 0L) {
                        Log.i(TAG, "距离上一帧${currentTimeMillis - mLastFrameTime}ms")
                    }
                    mLastFrameTime = currentTimeMillis
                    // 取数据
                    val outputBuffer = (mVideoMediaCodec.getOutputBuffer(outputBufferIndex)) ?: continue // 取失败的话先丢一帧试试，不直接抛异常结束

                    // 这表明标记为此类的缓冲区包含编解码器初始化/编解码器特定数据而不是媒体数据。只会伴随着index=-2出现: 0 != 2 , 2 != 2(2是配置帧), 1 != 2(1是Key frame)
                    Log.i(
                        TAG,
                        "and = ${mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0}, != = ${mVideoBufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG}"
                    )
                    if (mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        Log.i(TAG, "视频: 配置帧, 原size=${mVideoBufferInfo.size}")
                        mVideoBufferInfo.size = 0
                    }

                    if (mVideoBufferInfo.size != 0) {
                        try {
                            outputBuffer.position(mVideoBufferInfo.offset)
                            outputBuffer.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size)
                            // isMuxerStarted应该包在这里，把数据取出来丢掉
                            if (isMuxerStarted) {
                                mMediaMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, mVideoBufferInfo)
                            }
                            mGenerateVideoFrameIndex++
                            // 输入渲染，释放
                            mVideoMediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (isVideoStarted) {
                            Log.e(TAG, "结尾？reached end of stream unexpectedly")
                            onResult(false, "结尾？reached end of stream unexpectedly")
                        } else {
                            Log.e(TAG, "结尾？end of stream reached")
                        }
                        // 含有结束符，跳出结束
                        break
                    }
                    // 下一次
                    continue
                }

                // 信息类flag，OutputBufferInfo的IntDef一共三个值
                when (outputBufferIndex) {
                    // -1
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 数据取完，相当于缓冲区里没数据了，目前就第0帧的时候因为进来太快，没有数据，所以第1帧会看到的取了两次
                        // 因为这里的模式是同一条线程取和处理，所以跳出循环取下一帧，如果异步处理，超过帧率的话，内存会持续增长，直到爆炸
                        // FIXME: 2023/5/30 所以后续要看看大像素的情况下，如何提升处理速度
                        // 压缩肯定是要的，系统的录屏方案，三星4K屏，录下来的视频实际尺寸为720*1480
                        break
                    }

                    // -2
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (isMuxerStarted) {
                            // 等于二次开始，第二次开始应该是需要flush然后再重开的
                            error { "format changed twice" }
                        }
                        mVideoTrackIndex = mMediaMuxer.addTrack(mVideoMediaCodec.outputFormat)
                        if (min(mVideoTrackIndex, mAudioTrackIndex) >= 0 && !isMuxerStarted) {
                            mMediaMuxer.start()
                            isMuxerStarted = true
                            Log.i(
                                TAG,
                                "视频mMediaMuxer.start() TrackIndex=${mVideoTrackIndex}, outputFormat=${mVideoMediaCodec.outputFormat}"
                            )
                        }
                    }

                    // -3
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // The output buffers have changed, the client must refer to the new set of output buffers returned by getOutputBuffers from this point on.
                        // 输出缓冲区已更改，从此时起，客户端必须引用 getOutputBuffers 返回的新输出缓冲区集。
                        // 所以，mVideoMediaCodec.outputBuffers，直接跳过吧
                        Log.w(TAG, "recordVideo INFO_OUTPUT_BUFFERS_CHANGED")
                    }
                }
            }

            val end = System.currentTimeMillis() - start
            Log.w("Time", "从头到尾${end}ms")
        }
        onResult(true, mOutputFile.absolutePath)
        // 外面会调finish
    }

    /**
     * 从源提取像素数据
     */
    private fun getCurrentPixelsData(): ByteArray {
        val start = System.currentTimeMillis()
        // 这一步10ms左右
        val bitmap = mSourceProvider.next()
        Log.i("Video", "提取完bitmap, size=${bitmap.byteCount / 1024}KB, 耗时=${System.currentTimeMillis() - start}ms")
        // 需要时间，400宽的都要10ms左右，1024*1024 S9耗时50ms左右，如果异步按帧率取，内存可能会爆炸， 800*800耗时21ms
        val inputData: ByteArray = EncoderTools.getPixels(mColorFormat, mOutWidth, mOutHeight, bitmap)
        Log.i("Video", "从bitmap提取像素 ${System.currentTimeMillis() - start}ms")
        bitmap.recycle()
        return inputData
    }

    private fun runAudio() {
        thread {
            try {
                recordAudio()
            } catch (e: Exception) {
                Log.e(TAG, "音频录制失败", e)
            } finally {
                if (::mAudioMediaCodec.isInitialized) {
                    /*try {
                        mAudioMediaCodec.signalEndOfInputStream()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }*/
                    mAudioMediaCodec.stop()
                    mAudioMediaCodec.release()
                }
            }
        }
    }

    private fun recordAudio() {
        isAudioStarted = true
        // 启动在20ms左右
        mAudioRecord.startRecording()
        while (isAudioStarted) {
            val start = System.currentTimeMillis()
            val sampleSize = mAudioRecord.read(mAudioBuffer, 0, mAudioBufferSize)
            Log.i(TAG, "sampleSize = ${sampleSize}, 每次read耗时：${System.currentTimeMillis() - start}ms")
            onAudioBufferChanged(sampleSize)
        }
        mAudioRecord.stop()
        mAudioRecord.release()
    }

    private fun onAudioBufferChanged(size: Int) {
        // 第一帧数据，第一次read会比较久，所以在音频启动后开始录制视频
        if (isRunning && !isVideoStarted) {
            runVideo()
        }
        // If this buffer is not a direct buffer, this method will always return 0
        // so 0怎么处理？应该不会出现
        if (size <= 0) {
            Log.e(TAG, "audio read size小于0: [$size]")
            return
        }
        val inputBufferIndex = mAudioMediaCodec.dequeueInputBuffer(defaultTimeOutUs)
        if (inputBufferIndex < 0) {
            // 等下一帧
            SystemClock.sleep(mFpsMs.toLong() + 1L)
            return
        }
        val inputBuffer = mAudioMediaCodec.getInputBuffer(inputBufferIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(mAudioBuffer)
        val ptsUsec = (System.nanoTime() - mRecordStartTime) / 1000
        Log.i(TAG, "音频pts=${ptsUsec}us")
        val inputFlags = if (isAudioStarted) {
            0
        } else {
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        }
        mAudioMediaCodec.queueInputBuffer(inputBufferIndex, 0, size, ptsUsec, inputFlags)
        while (true) {
            val outputBufferIndex = mAudioMediaCodec.dequeueOutputBuffer(mAudioBufferInfo, defaultTimeOutUs)
            Log.i(
                TAG,
                "音频: 原始Size=[${size}], outputBufferIndex=$outputBufferIndex, " +
                        "inputBufferIndex=$inputBufferIndex, AudioBufferInfo.size=[${mAudioBufferInfo.size}], isMuxerStarted=$isMuxerStarted"
            )
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    Log.w(TAG, "runAudio() INFO_OUTPUT_BUFFERS_CHANGED")
                    // 再读一次，一般会到TRY_AGAIN_LATER
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isMuxerStarted) {
                        // 等于二次开始，第二次开始应该是需要flush然后再重开的
                        error { "format changed twice" }
                    }
                    mAudioTrackIndex = mMediaMuxer.addTrack(mAudioMediaCodec.outputFormat)
                    Log.i(
                        TAG,
                        "音频: TrackIndex=${mAudioTrackIndex}, outputFormat=${mAudioMediaCodec.outputFormat}"
                    )
                    if (min(mVideoTrackIndex, mAudioTrackIndex) >= 0 && !isMuxerStarted) {
                        mMediaMuxer.start()
                        isMuxerStarted = true
                        Log.i(TAG, "音频mMediaMuxer.start()")
                    }
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Log.d(TAG, "runAudio() INFO_TRY_AGAIN_LATER")
                    break
                }

                else -> {
                    if (outputBufferIndex < 0) {
                        // 理论上不存在其他负数
                        continue
                    }
                    try {
                        val outputBuffer = mAudioMediaCodec.getOutputBuffer(outputBufferIndex) ?: continue
                        /*if (mAudioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            Log.i(TAG, "音频: 配置帧, 原size=${mAudioBufferInfo.size}")
                            mAudioBufferInfo.size = 0
                        }*/
                        if (isMuxerStarted && isVideoStarted) {
                            // 拿到buffer开始操作
                            outputBuffer.position(mAudioBufferInfo.offset)
                            outputBuffer.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size)
                            mMediaMuxer.writeSampleData(mAudioTrackIndex, outputBuffer, mAudioBufferInfo)
                        }
                        mAudioMediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (mAudioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.e(TAG, "audio: end of stream reached")
                        break
                    }
                }
            }
        }
    }

    private fun setColorFormat() {
        mColorFormat = EncoderTools.getColorFormat()
    }

    @Synchronized
    private fun onResult(isSuccessful: Boolean, result: String) {
        if (isResulted) {
            return
        }
        isResulted = true
        mSourceProvider.onResult(isSuccessful, result)
    }
}
