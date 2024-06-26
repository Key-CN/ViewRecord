package io.keyss.view_record.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import android.view.Window
import io.keyss.view_record.audio.AudioEncoder
import io.keyss.view_record.audio.GetAacData
import io.keyss.view_record.audio.GetMicrophoneData
import io.keyss.view_record.audio.MicrophoneManager
import io.keyss.view_record.base.Frame
import io.keyss.view_record.recording.RecordController.Listener
import io.keyss.view_record.utils.RecordViewUtil
import io.keyss.view_record.utils.yuv.ConvertUtil
import io.keyss.view_record.video.EncoderErrorCallback
import io.keyss.view_record.video.FormatVideoEncoder
import io.keyss.view_record.video.GetVideoData
import io.keyss.view_record.video.IFrameDataGetter
import io.keyss.view_record.video.VideoEncoder
import java.nio.ByteBuffer

/**
 * Description: 采用pedro版本的编码类进行录制的一个版本
 *
 * Time: 2023/11/21 18:52
 * @author Key
 */
class ViewRecorder {
    companion object {
        private const val TAG = "ViewRecordEncoder"
    }

    private lateinit var view: View
    private lateinit var window: Window

    /**
     * 是否已启动
     */
    @Volatile
    var isStartRecord = false
        private set

    /** 视频编码器 */
    private lateinit var videoEncoder: VideoEncoder
    private var videoInitSuccess = false

    private var audioInitSuccess = false
    private lateinit var audioEncoder: AudioEncoder
    private lateinit var microphoneManager: MicrophoneManager

    private lateinit var recordController: AndroidMuxerRecordController

    /**
     * 只录视频时只初始化视频编码器
     */
    @Throws
    fun initJustVideo(
        window: Window,
        view: View,
        width: Int,
        fps: Int = 24,
        videoBitRate: Int = 4_000_000,
        iFrameInterval: Int = 1,
    ) {
        if (isStartRecord) {
            throw IllegalStateException("recording is running")
        }
        this.window = window
        this.view = view
        recordController = AndroidMuxerRecordController()
        videoEncoder = VideoEncoder(object : GetVideoData {
            override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                //Log.d(TAG, "getVideoData() called with: h264Buffer = $h264Buffer, info = $info")
                //fpsListener.calculateFps()
                recordController.recordVideo(h264Buffer, info)
            }

            override fun onVideoFormat(mediaFormat: MediaFormat) {
                Log.d(TAG, "onVideoFormat() called with: mediaFormat = $mediaFormat")
                recordController.setVideoFormat(mediaFormat, !audioInitSuccess)
            }
        })
        // 设置获取帧的方法
        videoEncoder.setFrameDataGetter(object : IFrameDataGetter {
            override fun getFrameData(): Frame {
                return Frame(getFrameBytes())
            }
        })
        // 通过获取一帧来初始化视频参数
        val frameBitmap = getFrameBitmap(width)
        videoInitSuccess = videoEncoder.prepareVideoEncoder(
            frameBitmap.width,
            frameBitmap.height,
            fps,
            videoBitRate,
            iFrameInterval,
            FormatVideoEncoder.YUV420Dynamical
            // NOTE: 已知在就算支持的颜色格式中，也可能会出现oom，如YUV420_PLANAR在荣耀某款平板上
            //FormatVideoEncoder.YUV420_SEMI_PLANAR//21 V
            //FormatVideoEncoder.YUV420_PLANAR//19 V
            //FormatVideoEncoder.YUV420_PACKED_SEMI_PLANAR//39 V
            //FormatVideoEncoder.YUV420_PACKED_PLANAR//20 V
        )
    }

    /**
     * 初始化录制：view视频+mic音频
     */
    @Throws
    fun init(
        window: Window,
        view: View,
        width: Int,
        fps: Int = 24,
        videoBitRate: Int = 4_000_000,
        iFrameInterval: Int = 1,
        audioBitRate: Int = 192_000,
        audioSampleRate: Int = 44_100,
        isStereo: Boolean = true,
    ) {
        // 视频设置
        initJustVideo(window, view, width, fps, videoBitRate, iFrameInterval)

        // 音频设置
        audioEncoder = AudioEncoder(object : GetAacData {
            override fun getAacData(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                recordController.recordAudio(aacBuffer, info)
            }

            override fun onAudioFormat(mediaFormat: MediaFormat) {
                recordController.setAudioFormat(mediaFormat)
            }
        })
        audioEncoder.setRealTime(true)
        //audioEncoder.setForce(CodecUtil.Force.SOFTWARE)

        microphoneManager = MicrophoneManager(object : GetMicrophoneData {
            override fun inputPCMData(frame: Frame) {
                audioEncoder.inputPCMData(frame)
            }
        })

        audioInitSuccess = microphoneManager.createMicrophone(
            MediaRecorder.AudioSource.DEFAULT,
            audioSampleRate,
            isStereo,
            false,
            false
        )
        if (!audioInitSuccess) {
            // 已失败，不再初始化音频编码器
            return
        }
        audioInitSuccess = audioEncoder.prepareAudioEncoder(
            audioBitRate,
            microphoneManager.sampleRate,
            microphoneManager.channel == AudioFormat.CHANNEL_IN_STEREO,
            microphoneManager.inputBufferSize
        )
    }

    @Throws
    fun startRecord(path: String, statusListener: Listener, errorListener: EncoderErrorCallback) {
        // 判断下是否已经初始化，及初始化是否成功
        if (!this::videoEncoder.isInitialized || !videoInitSuccess) {
            throw IllegalStateException("videoEncoder is not initialized, videoInitSuccess=$videoInitSuccess")
        }
        if (isStartRecord) {
            return
        }
        isStartRecord = true
        // 设置错误回调
        videoEncoder.setEncoderErrorCallback(errorListener)
        // 启动并设置正确的回调
        recordController.startRecord(path, statusListener)
        if (audioInitSuccess) {
            microphoneManager.start()
            audioEncoder.start()
        }
        videoEncoder.start()
    }

    fun stopRecord() {
        if (!isStartRecord) {
            return
        }
        isStartRecord = false
        recordController.stopRecord()
        if (!recordController.isRecording) {
            Log.i(TAG, "stopRecord() called not isRecording")
            videoEncoder.stop()
            if (audioInitSuccess) {
                audioEncoder.stop()
                microphoneManager.stop()
            }
            recordController.resetFormats()
        }
        videoInitSuccess = false
        audioInitSuccess = false
    }

    private fun getFrameBytes(): ByteArray {
        if (!this::view.isInitialized || !this::window.isInitialized) {
            throw IllegalStateException("view or window is not initialized")
        }
        //val start = System.currentTimeMillis()
        val bitmap = getFrameBitmap(videoEncoder.width)
        //val getBitmapCost = System.currentTimeMillis() - start
        val inputData: ByteArray = ConvertUtil.convertBitmapToYUVByteArray(
            bitmap,
            videoEncoder.formatVideoEncoder.formatCodec
        )
        //VRLogger.v("getFrameBytes() bitmap width=${videoEncoder.width}, colorFormat: ${videoEncoder.formatVideoEncoder.formatCodec}, getBitmapCost: ${getBitmapCost}ms, total cost: ${System.currentTimeMillis() - start}ms")
        return inputData
    }

    private fun getFrameBitmap(width: Int) = RecordViewUtil.getBitmapFromView(window, view, width)
}
