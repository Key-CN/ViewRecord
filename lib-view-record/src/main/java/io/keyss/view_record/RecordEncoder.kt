package io.keyss.view_record

import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.View
import android.view.Window
import androidx.annotation.RequiresPermission
import io.keyss.view_record.utils.EncoderTools
import io.keyss.view_record.utils.RecordViewUtil
import io.keyss.view_record.utils.VRLogger
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * @author Key
 * Time: 2022/09/01 20:40
 * Description: 录制及编码
 * 该方案中音频的Mic启动需要时间，而视频当前帧的获取是瞬间的，所以优先启动音频线程。
 * 总的来说录制可以视为视频是主轴，音频是辅轴
 */
class RecordEncoder {
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
    private var mRecordStartTime = -1L
    private var mLastFrameTime = -1L

    ////// Video
    private lateinit var mVideoRecordConfig: VideoRecordConfig

    // acv h264, hevc h265, 根据需要求改
    var videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC

    // 可以复用，传进去写参数，这种方式有点像C的惯用写法
    private val mVideoBufferInfo = MediaCodec.BufferInfo()

    /**
     * 默认帧率(最大帧率)采用电视级的24帧每秒，大部分fps都采用的不是整数
     * 为了让参数利于计算，且缩小文件尺寸，改为20
     * 实际视频是动态帧率
     */
    var frameRate: Float = 20f
        set(value) {
            field = value
            fpsMs = 1000.0 / value
        }

    /** 每帧时间，仅为方便计算使用 */
    var fpsMs: Double = 1000.0 / frameRate
        private set

    /**
     * I帧间隔：秒
     */
    var iFrameInterval = 1f

    /** 视频比特率 */
    private var mVideoBitRate = 512_000

    ////// Audio
    private var isRecordAudio: Boolean = false
    private lateinit var mAudioRecord: AudioRecord
    private var isAudioRecordReleased = false
    private lateinit var mAudioMediaCodec: MediaCodec
    private val mAudioBufferInfo = MediaCodec.BufferInfo()
    private var mAudioTrackIndex = -1
    var audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC

    //private lateinit var mAudioBuffer: ByteBuffer，录不进去，暂未查到原因
    private lateinit var mAudioBuffer: ByteArray
    private var mAudioBufferSize: Int = 0
    var audioBitRate: Int = 192_000 // 音频比特率
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

    /** 输入流Buffer超时，微秒 */
    var defaultTimeOutUs: Long = 0

    /**
     * 前置配置，可以不从start加参数
     */
    fun setUp(provider: ISourceProvider, outputFile: File, minBitRate: Int, isRecordAudio: Boolean = true) {
        if (isVideoStarted || isRunning) {
            return
        }
        mOutputFile = outputFile
        mSourceProvider = provider
        mVideoBitRate = minBitRate
        // TODO: 2023/6/6 判断没有权限的话再改成false或者抛出异常
        this.isRecordAudio = isRecordAudio
    }

    @Synchronized
    fun start() {
        if (isVideoStarted || isRunning) {
            return
        }
        if (::mSourceProvider.isInitialized.not() || ::mOutputFile.isInitialized.not()) {
            onError("请先调用setUp()方法")
            return
        }
        // 可能同个对象第二次启动，而前一次又是异常中断，所以参数都要重置
        mAudioTrackIndex = -1
        isResulted = false
        isVideoStarted = false
        isAudioStarted = false
        isMuxerStarted = false
        ////////////////////////////////////////////////
        isRunning = true
        thread {
            val bitmap = try {
                mSourceProvider.next()
            } catch (e: Exception) {
                VRLogger.e("初始化错误: 第一次取bitmap异常", e)
                onError("初始化错误：无法获取到屏幕图像或者超过内存大小")
                return@thread
            }
            try {
                init(bitmap.width, bitmap.height, mVideoBitRate)
            } catch (e: Exception) {
                onError("初始化错误：${e.message}")
                return@thread
            }
            if (isRecordAudio) {
                runAudio()
            } else {
                runVideo()
            }
        }
    }

    /**
     * 自定义源更灵活
     */
    fun start(provider: ISourceProvider, outputFile: File, minBitRate: Int, isRecordAudio: Boolean = true) {
        VRLogger.d("start() called with: isStarted = $isVideoStarted, isRunning = $isRunning, outputFile=$outputFile, minBitRate=$minBitRate")
        setUp(provider, outputFile, minBitRate, isRecordAudio)
        start()
    }

    /**
     * 更轻便
     * @param width 为null时，使用view的原始宽高
     * @param onResult 最简洁的方式下可以用lambda表达式拿结果
     */
    fun start(
        window: Window,
        view: View,
        outputFile: File? = null,
        width: Int? = null,
        minBitRate: Int = 1024_000,
        isRecordAudio: Boolean = true,
        onResult: (isSuccessful: Boolean, result: String) -> Unit,
    ) {
        VRLogger.d("start2() called with: isStarted = $isVideoStarted, isRunning = $isRunning, outputFile=$outputFile, minBitRate=$minBitRate")
        val provider = object : ISourceProvider {
            override fun next(): Bitmap {
                return RecordViewUtil.getBitmapFromView(window, view, width)
            }

            override fun onResult(isSuccessful: Boolean, result: String) {
                VRLogger.i("start2 onResult() isSuccessful: $isSuccessful, result: $result")
                onResult.invoke(isSuccessful, result)
            }
        }
        // 确认文件路径可用性
        var finalOutputFile =
            outputFile ?: File(view.context.externalCacheDir, "record_${System.currentTimeMillis()}.mp4")
        try {
            if (finalOutputFile.exists()) {
                if (finalOutputFile.isFile) {
                    if (!finalOutputFile.delete()) {
                        finalOutputFile = File(view.context.externalCacheDir, "record_${System.nanoTime()}.mp4")
                    }
                } else {
                    finalOutputFile = File(view.context.externalCacheDir, "record_${System.nanoTime()}.mp4")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setUp(
            provider = provider,
            outputFile = finalOutputFile,
            minBitRate = minBitRate,
            isRecordAudio = isRecordAudio,
        )
        start()
    }

    /**
     * 此处stop只是为了停止循环，真正的结束需要在循环的末尾，写入end标识到文件
     */
    fun stop() {
        if (!isVideoStarted) {
            VRLogger.d("stop() called 未启动，不用停止")
            return
        }
        VRLogger.i("stop() called")
        isRunning = false
    }

    @Synchronized
    private fun finish() {
        try {
            if (isMuxerStarted) {
                isMuxerStarted = false
                if (::mMediaMuxer.isInitialized) {
                    mMediaMuxer.stop()
                    mMediaMuxer.release()
                }
                VRLogger.i("finish() called, MediaMuxer release")
            }
            onResult(true, mOutputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(false, "录制结束失败：${e.message}")
        } finally {
            isRunning = false
        }
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO, conditional = true)
    @Throws
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
        VRLogger.i("初始化完成, 最终参数, outputFile=${mOutputFile.absolutePath}, can write=[${mOutputFile.canWrite()}]")
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initAudioConfig() {
        // 计算缓冲区大小
        mAudioBufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, audioFormat)
        //mAudioBuffer = ByteBuffer.allocateDirect(mAudioBufferSize)
        mAudioBuffer = ByteArray(mAudioBufferSize)
        // 初始化AudioRecord实例
        mAudioRecord = AudioRecord(audioSource, audioSampleRate, channelConfig, audioFormat, mAudioBufferSize)
        isAudioRecordReleased = false
        // codec
        val audioFormat = MediaFormat.createAudioFormat(audioMimeType, audioSampleRate, audioChannelCount)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate) // 音频比特率
        audioFormat.setString(MediaFormat.KEY_MIME, audioMimeType)
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) // 音频配置
        //audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mAudioBufferSize) // 最大输入大小
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mAudioBufferSize)
        mAudioMediaCodec = MediaCodec.createEncoderByType(audioMimeType)
        mAudioMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mAudioMediaCodec.start()
        VRLogger.d("initAudioConfig outputFormat=${mAudioMediaCodec.outputFormat}, mAudioBufferSize=$mAudioBufferSize")
    }

    private fun initVideoConfig(width: Int, height: Int, minBitRate: Int) {
        setColorFormat()
        mVideoRecordConfig = VideoRecordConfig(
            videoMimeType = videoMimeType,
            colorFormat = mColorFormat,
            outWidth = width,
            outHeight = height,
            bitRate = minBitRate,
            frameRate = frameRate,
            iFrameInterval = iFrameInterval
        )
    }

    private fun runVideo() {
        thread {
            try {
                recordVideo()
            } catch (e: Exception) {
                VRLogger.e("视频录制错误", e)
                onError("视频录制失败：${e.message}")
            } finally {
                mVideoRecordConfig.destroy()
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
            // 从队列中去一个可用的buffer的index
            val inputBufferIndex = mVideoRecordConfig.videoMediaCodec.dequeueInputBuffer(defaultTimeOutUs)
            //VRLogger.i( "视频: inputBufferIndex=$inputBufferIndex, 第${mVideoRecordConfig.mGenerateVideoFrameIndex}帧数据")
            // 无可用缓冲区，丢掉（这个模式下实为等待，不取下一帧图像数据），下一帧
            if (inputBufferIndex < 0) {
                // 等下一帧
                SystemClock.sleep(fpsMs.toLong() + 1L)
                continue
            }
            // 取个输入buffer
            val inputBuffer = mVideoRecordConfig.videoMediaCodec.getInputBuffer(inputBufferIndex) ?: continue
            inputBuffer.clear()
            // 计算pts，取帧的时间（getCurrentPixelsData）
            val ptsUsec = (System.nanoTime() - mRecordStartTime) / 1000
            VRLogger.v("视频pts=${ptsUsec}us")
            // 录制
            val inputData: ByteArray = getCurrentPixelsData()
            inputBuffer.put(inputData)
            // Put the data on the encoding queue, 把buffer传给codec
            // 所以目前这里不可能出现false
            val inputFlags = if (isRunning) {
                0
            } else {
                VRLogger.i("视频录制准备结束")
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            }
            // 压入缓冲区，准备编码
            mVideoRecordConfig.videoMediaCodec.queueInputBuffer(
                inputBufferIndex,
                0,
                inputData.size,
                ptsUsec,
                inputFlags
            )
            while (true) {
                // Returns the index of an output buffer that has been successfully decoded or one of the INFO_* constants.
                val outputBufferIndex =
                    mVideoRecordConfig.videoMediaCodec.dequeueOutputBuffer(mVideoBufferInfo, defaultTimeOutUs)
                VRLogger.v(
                    "输出: 第${mVideoRecordConfig.generateVideoFrameIndex}帧数据, outputBufferIndex=[$outputBufferIndex], inputFlags=[$inputFlags], buffer.flags=[${mVideoBufferInfo.flags}], buffer.size=[${mVideoBufferInfo.size}], isVideoStarted=$isVideoStarted"
                )

                // 有数据
                if (outputBufferIndex >= 0) {
                    val currentTimeMillis = System.currentTimeMillis()
                    if (mLastFrameTime > 0L) {
                        VRLogger.d("View 距离上一帧${currentTimeMillis - mLastFrameTime}ms")
                    }
                    mLastFrameTime = currentTimeMillis
                    // 取数据
                    val outputBuffer = (mVideoRecordConfig.videoMediaCodec.getOutputBuffer(outputBufferIndex))
                        ?: continue // 取失败的话先丢一帧试试，不直接抛异常结束

                    // 这表明标记为此类的缓冲区包含编解码器初始化/编解码器特定数据而不是媒体数据。只会伴随着index=-2出现: 0 != 2 , 2 != 2(2是配置帧), 1 != 2(1是Key frame)
                    VRLogger.v(
                        "and = ${mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0}, != = ${mVideoBufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG}"
                    )
                    if (mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        VRLogger.i("视频: 配置帧, 原size=${mVideoBufferInfo.size}")
                        mVideoBufferInfo.size = 0
                    }

                    if (mVideoBufferInfo.size != 0) {
                        try {
                            outputBuffer.position(mVideoBufferInfo.offset)
                            outputBuffer.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size)
                            // isMuxerStarted应该包在这里，把数据取出来丢掉
                            if (isMuxerStarted) {
                                mMediaMuxer.writeSampleData(
                                    mVideoRecordConfig.videoTrackIndex,
                                    outputBuffer,
                                    mVideoBufferInfo
                                )
                            }
                            mVideoRecordConfig.generateVideoFrameIndex++
                            // 输入渲染，释放
                            mVideoRecordConfig.videoMediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (mVideoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isVideoStarted = false
                        VRLogger.i("收到视频结尾符, BUFFER_FLAG_END_OF_STREAM, 结束")
                        /*if (isVideoStarted) {
                            VRLogger.e("结尾？reached end of stream unexpectedly")
                            onResult(false, "结尾？reached end of stream unexpectedly")
                        } else {
                            VRLogger.e("结尾？end of stream reached")
                        }*/
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
                        mVideoRecordConfig.videoTrackIndex =
                            mMediaMuxer.addTrack(mVideoRecordConfig.videoMediaCodec.outputFormat)
                        startMuxer()
                    }

                    // -3
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // The output buffers have changed, the client must refer to the new set of output buffers returned by getOutputBuffers from this point on.
                        // 输出缓冲区已更改，从此时起，客户端必须引用 getOutputBuffers 返回的新输出缓冲区集。
                        // 所以，mVideoRecordConfig.videoMediaCodec.outputBuffers，直接跳过吧
                        VRLogger.w("recordVideo INFO_OUTPUT_BUFFERS_CHANGED, 看见需要排查！")
                    }
                }
            }
        }
        // 在这里的回调里删除文件，会导致MediaMuxer停止释放失败，已改到finish中MediaMuxer释放之后
        // 但这在前段事件会有延迟，但不明显，如果明显，需要自己加loading UI或者其他处理
        // 外面会调finish，防止异常
    }

    /**
     * 从源提取像素数据
     */
    private fun getCurrentPixelsData(): ByteArray {
        val start = System.currentTimeMillis()
        // 这一步10ms左右
        val bitmap = mSourceProvider.next()
        VRLogger.v("提取完bitmap, size=${bitmap.byteCount / 1024}KB, 耗时=${System.currentTimeMillis() - start}ms")
        // 需要时间，400宽的都要10ms左右，1024*1024 S9耗时50ms左右，如果异步按帧率取，内存可能会爆炸， 800*800耗时21ms
        val inputData: ByteArray = EncoderTools.getPixels(
            mVideoRecordConfig.colorFormat,
            mVideoRecordConfig.outWidth,
            mVideoRecordConfig.outHeight,
            bitmap
        )
        VRLogger.v("从bitmap提取像素 ${System.currentTimeMillis() - start}ms")
        bitmap.recycle()
        return inputData
    }

    private fun runAudio() {
        thread {
            try {
                recordAudio()
            } catch (e: Exception) {
                VRLogger.e("音频录制失败", e)
                // onResult(false, "音频录制失败")，音频失败就算了，主要已视频为主，只要视频没问题就行
            } finally {
                mAudioRecord.stop()
                mAudioRecord.release()
                isAudioRecordReleased = true
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
        // 如果AudioRecord被错误释放，则没必要再继续录制
        while (isAudioStarted && !isAudioRecordReleased) {
            val start = System.currentTimeMillis()
            val sampleSize = mAudioRecord.read(mAudioBuffer, 0, mAudioBufferSize)
            VRLogger.v("sampleSize = ${sampleSize}, 每次read耗时：${System.currentTimeMillis() - start}ms")
            onAudioBufferChanged(sampleSize)
        }
    }

    private fun onAudioBufferChanged(size: Int) {
        // 第一帧数据，第一次read会比较久，所以在音频启动后开始录制视频
        if (isRunning && !isVideoStarted) {
            runVideo()
        }
        // If this buffer is not a direct buffer, this method will always return 0
        // so 0怎么处理？应该不会出现
        // fixme 出现了，可能是mAudioRecord被释放了，但是还在录制，所以这里会一直返回0，也可能是其他情况
        if (size <= 0) {
            VRLogger.e("audio read size小于0: [$size]")
            return
        }
        val inputBufferIndex = mAudioMediaCodec.dequeueInputBuffer(defaultTimeOutUs)
        if (inputBufferIndex < 0) {
            // 等下一帧
            SystemClock.sleep(fpsMs.toLong() + 1L)
            return
        }
        val inputBuffer = mAudioMediaCodec.getInputBuffer(inputBufferIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(mAudioBuffer)
        val ptsUsec = (System.nanoTime() - mRecordStartTime) / 1000
        VRLogger.v("音频pts=${ptsUsec}us")
        val inputFlags = if (isRunning) {
            0
        } else {
            VRLogger.i("音频录制准备结束")
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        }
        mAudioMediaCodec.queueInputBuffer(inputBufferIndex, 0, size, ptsUsec, inputFlags)
        while (true) {
            val outputBufferIndex = mAudioMediaCodec.dequeueOutputBuffer(mAudioBufferInfo, defaultTimeOutUs)
            VRLogger.v(
                "音频: 原始Size=[${size}], outputBufferIndex=$outputBufferIndex, " +
                        "inputBufferIndex=$inputBufferIndex, AudioBufferInfo.size=[${mAudioBufferInfo.size}], isMuxerStarted=$isMuxerStarted"
            )
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    VRLogger.w("runAudio() INFO_OUTPUT_BUFFERS_CHANGED, 看见需要排查！")
                    // 再读一次，一般会到TRY_AGAIN_LATER
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isMuxerStarted) {
                        // 等于二次开始，第二次开始应该是需要flush然后再重开的
                        error { "format changed twice" }
                    }
                    mAudioTrackIndex = mMediaMuxer.addTrack(mAudioMediaCodec.outputFormat)
                    startMuxer()
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    //VRLogger.v("runAudio() INFO_TRY_AGAIN_LATER")
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
                            VRLogger.i( "音频: 配置帧, 原size=${mAudioBufferInfo.size}")
                            mAudioBufferInfo.size = 0
                        }*/
                        // 为了和视频同步，音频先开始，所以判断isVideoStarted
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
                        VRLogger.i("收到音频截止符: BUFFER_FLAG_END_OF_STREAM, 结束")
                        isAudioStarted = false
                        break
                    }
                }
            }
        }
    }

    private fun startMuxer() {
        if (!isMuxerStarted && min(
                mVideoRecordConfig.videoTrackIndex,
                if (isRecordAudio) mAudioTrackIndex else 1
            ) >= 0
        ) {
            mMediaMuxer.start()
            isMuxerStarted = true
            VRLogger.i("MediaMuxer start, VideoTrackIndex=${mVideoRecordConfig.videoTrackIndex}, AudioTrackIndex=${mAudioTrackIndex}")
        }
    }

    private fun setColorFormat() {
        mColorFormat = EncoderTools.getColorFormat()
    }

    private fun onError(message: String) {
        stop()
        onResult(false, message)
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
