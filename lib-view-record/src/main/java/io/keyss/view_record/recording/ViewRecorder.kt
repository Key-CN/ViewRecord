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
import io.keyss.view_record.utils.EncoderTools
import io.keyss.view_record.utils.RecordViewUtil
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
    private var audioInitialized = false

    private lateinit var videoEncoder: VideoEncoder

    private lateinit var audioEncoder: AudioEncoder
    private lateinit var microphoneManager: MicrophoneManager


    //private var audioEncoder: AudioEncoder? = null
    private lateinit var recordController: AndroidMuxerRecordController

    fun init(window: Window, view: View, width: Int, fps: Int, bitRate: Int, iFrameInterval: Int) {
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
                recordController.setVideoFormat(mediaFormat, !audioInitialized)
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
        videoEncoder.prepareVideoEncoder(
            frameBitmap.width,
            frameBitmap.height,
            fps,
            bitRate,
            iFrameInterval,
            //FormatVideoEncoder.YUV420SEMIPLANAR
            FormatVideoEncoder.YUV420Dynamical
        )

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
        audioInitialized = microphoneManager.createMicrophone(
            MediaRecorder.AudioSource.DEFAULT,
            44100,
            true,
            false,
            false
        )
        audioInitialized = audioInitialized && audioEncoder.prepareAudioEncoder(
            192_000,
            microphoneManager.sampleRate,
            microphoneManager.channel == AudioFormat.CHANNEL_IN_STEREO,
            microphoneManager.inputBufferSize
        )
        if (audioInitialized) {
            audioEncoder.start()
            microphoneManager.start()
        }
    }


    fun startRecord(path: String, statusListener: Listener, errorListener: EncoderErrorCallback) {
        if (isStartRecord) {
            return
        }
        isStartRecord = true
        // 设置错误回调
        videoEncoder.setEncoderErrorCallback(errorListener)
        // 启动并设置正确的回调
        recordController.startRecord(path, statusListener)
        videoEncoder.start()
    }

    fun stopRecord() {
        if (!isStartRecord) {
            return
        }
        isStartRecord = false
        recordController.stopRecord()
        if (!recordController.isRecording) {
            videoEncoder.stop()
            if (audioInitialized) {
                microphoneManager.stop()
                audioEncoder.stop()
            }
            recordController.resetFormats()
        }
    }

    private fun getFrameBytes(): ByteArray {
        if (!this::view.isInitialized || !this::window.isInitialized) {
            throw IllegalStateException("view or window is not initialized")
        }
        //val start = System.currentTimeMillis()
        val bitmap = getFrameBitmap(videoEncoder.width)
        //val getBitmapCost = System.currentTimeMillis() - start
        val inputData: ByteArray = EncoderTools.getPixels(
            videoEncoder.formatVideoEncoder.formatCodec,
            bitmap.width,
            bitmap.height,
            bitmap
        )
        // 21 = COLOR_FormatYUV420SemiPlanar
        //VRLogger.v("getFrameBytes() colorFormat: ${videoEncoder.formatVideoEncoder.formatCodec}, getBitmapCost: ${getBitmapCost}ms, total cost: ${System.currentTimeMillis() - start}ms")
        return inputData
    }

    private fun getFrameBitmap(width: Int) = RecordViewUtil.getBitmapFromView(window, view, width)
}
