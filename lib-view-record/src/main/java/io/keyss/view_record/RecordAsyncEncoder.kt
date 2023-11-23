package io.keyss.view_record

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.View
import android.view.Window
import androidx.annotation.RequiresPermission
import io.keyss.view_record.utils.EncoderTools
import io.keyss.view_record.utils.RecordViewUtil
import io.keyss.view_record.utils.VRLogger
import java.io.File
import java.nio.ByteBuffer


/**
 * @author Key
 * Time: 2023/11/20 20:40
 * Description: 录制及编码
 * 改良，未完成
 */
class RecordAsyncEncoder {
    // 从我打印的capabilitiesForType.colorFormats来看，确实全部都支持：2135033992，另外出现的较多的是COLOR_FormatSurface
    // 从测试结果看 全部采用COLOR_FormatYUV420Flexible在某些机型上会导致花屏
    //private var mColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var mColorFormat = 0

    @Volatile
    private var isVideoStarted = false

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
        prepare()
    }

    private fun prepare() {
        val bitmap = try {
            mSourceProvider.next()
        } catch (e: Exception) {
            VRLogger.e("初始化错误: 第一次取bitmap异常", e)
            onError("初始化错误：无法获取到屏幕图像或者超过内存大小")
            return
        }
        try {
            init(bitmap.width, bitmap.height, mVideoBitRate)
        } catch (e: Exception) {
            onError("初始化错误：${e.message}")
            return
        }
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
        isResulted = false
        isVideoStarted = false
        isMuxerStarted = false
        ////////////////////////////////////////////////
        isRunning = true
        runVideo()
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
        initVideoConfig(width, height, minBitRate)
        // Create the generated MP4 initialization object
        if (mOutputFile.exists()) {
            mOutputFile.delete()
        }
        mOutputFile.createNewFile()
        mMediaMuxer = MediaMuxer(mOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        VRLogger.i("初始化完成, 最终参数, outputFile=${mOutputFile.absolutePath}, can write=[${mOutputFile.canWrite()}]")
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
        mVideoRecordConfig.videoMediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, inputBufferId: Int) {
                VRLogger.d("视频Codec: onInputBufferAvailable: inputBufferId=$inputBufferId")
                try {
                    processInput(codec, inputBufferId)
                } catch (e: Exception) {
                    VRLogger.e("processInput错误", e)
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, outputBufferId: Int, info: MediaCodec.BufferInfo) {
                VRLogger.d("视频Codec: onOutputBufferAvailable: outputBufferId=$outputBufferId, info=$info")
                try {
                    processOutput(codec, outputBufferId, info)
                } catch (e: Exception) {
                    VRLogger.e("processOutput错误", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                VRLogger.e("视频Codec错误", e)
                //onError("视频录制失败：${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // 打印信息日志
                VRLogger.d("视频Codec: onOutputFormatChanged: $format")
                processOutputFormatChanged(codec, format)
            }
        })
        mVideoRecordConfig.videoMediaCodec.start()
    }

    /**
     * 处理格式变动
     */
    private fun processOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        // 从format中获取trackIndex
        val trackIndex = mMediaMuxer.addTrack(format)
        if (trackIndex >= 0) {
            mVideoRecordConfig.videoTrackIndex = trackIndex
            startMuxer()
        }
    }

    /**
     * 处理输入的帧
     */
    @Throws
    private fun processInput(codec: MediaCodec, inputBufferId: Int) {
        val inputBuffer = codec.getInputBuffer(inputBufferId) ?: return
        VRLogger.d("inputBuffer isDirect=${inputBuffer.isDirect}")
        inputBuffer.clear()
        // 计算pts，取帧的时间（getCurrentPixelsData）
        val ptsUsec = (System.nanoTime() - mRecordStartTime) / 1000
        VRLogger.v("视频pts=${ptsUsec}us")
        // 获取屏幕数据
        val inputData: ByteArray = getCurrentPixelsData()
        // todo 第一次changed 待修改 buffer is inaccessible
        inputBuffer.put(inputData)
        // 压入缓冲区，准备编码
        codec.queueInputBuffer(inputBufferId, 0, inputData.size, ptsUsec, 0)
    }

    /**
     * 处理输出帧到文件
     */
    @Throws
    private fun processOutput(codec: MediaCodec, outputBufferId: Int, info: MediaCodec.BufferInfo) {
        val outputBuffer = codec.getOutputBuffer(outputBufferId) ?: return
        val bufferFormat = codec.getOutputFormat(outputBufferId)
        try {
            outputBuffer.position(info.offset)
            outputBuffer.limit(info.offset + info.size)
            write(mVideoRecordConfig.videoTrackIndex, outputBuffer, info)
            // 用作记录
            mVideoRecordConfig.generateVideoFrameIndex++
            // 释放
            codec.releaseOutputBuffer(outputBufferId, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 写入文件
     */
    private fun write(track: Int, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        try {
            mMediaMuxer.writeSampleData(track, byteBuffer, info)
        } catch (e: IllegalStateException) {
            VRLogger.w("Write error", e)
        } catch (e: IllegalArgumentException) {
            VRLogger.w("Write error", e)
        }
    }

    private fun runVideo() {
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

    /**
     * 干脆就丢掉第一帧，没多大影响，可以简化代码流程
     */
    private fun recordVideo() {

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

    }

    private fun recordAudio() {

    }

    private fun startMuxer() {
        if (!isMuxerStarted) {
            mMediaMuxer.start()
            isMuxerStarted = true
            VRLogger.i("MediaMuxer start, VideoTrackIndex=${mVideoRecordConfig.videoTrackIndex}")
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
