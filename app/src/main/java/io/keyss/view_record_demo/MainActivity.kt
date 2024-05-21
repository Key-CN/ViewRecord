package io.keyss.view_record_demo

import android.graphics.Bitmap
import android.media.MediaCodec
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.utils.widget.MotionButton
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import io.keyss.view_record.ISourceProvider
import io.keyss.view_record.RecordEncoder
import io.keyss.view_record.recording.RecordController
import io.keyss.view_record.recording.ViewRecorder
import io.keyss.view_record.utils.CodecUtil
import io.keyss.view_record.utils.RecordViewUtil
import io.keyss.view_record.utils.VRLogger
import io.keyss.view_record.video.EncoderErrorCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Arrays

class MainActivity : AppCompatActivity() {
    private val TAG = "MainTAG"
    private lateinit var previewView: PreviewView
    private lateinit var layoutRecordContentView: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var tvTime: androidx.appcompat.widget.AppCompatTextView
    private lateinit var btnStart: com.google.android.material.button.MaterialButton
    private lateinit var btnStop: MotionButton
    private lateinit var btnCapture: MotionButton
    private lateinit var ivCapture: AppCompatImageView
    private lateinit var videoView: VideoView

    private var mCamera: Camera? = null
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private var mTimerJob: Job? = null

    //private val mRecordEncoder = RecordAsyncEncoder()
    private val mRecordEncoder = RecordEncoder()

    private val viewRecord = ViewRecorder()

    private var mLastRecordFile: File? = null

    // 需要的权限列表
    private val permissionMap = mutableMapOf(
        android.Manifest.permission.CAMERA to false,
        android.Manifest.permission.RECORD_AUDIO to false,
    )
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view_main_activity)
        layoutRecordContentView = findViewById(R.id.layout_record_content_main_activity)
        tvTime = findViewById(R.id.tv_time_main_activity)
        btnStart = findViewById(R.id.btn_start_record_main_activity)
        btnStart.setOnClickListener {
            startRecord(layoutRecordContentView)
        }
        btnStop = findViewById(R.id.btn_stop_record_main_activity)
        btnStop.setOnClickListener {
            stopRecord()
        }
        videoView = findViewById(R.id.video_view_main_activity)
        videoView.setOnCompletionListener {
            videoView.isGone = true
            Toast.makeText(this, "播放结束", Toast.LENGTH_SHORT).show()
        }
        findViewById<MotionButton>(R.id.btn_play_main_activity).setOnClickListener {
            if (mLastRecordFile?.absolutePath.isNullOrBlank()) {
                Toast.makeText(this, "请先录制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            videoView.isVisible = true
            videoView.setVideoPath(mLastRecordFile?.absolutePath)
            videoView.start()
        }

        ivCapture = findViewById(R.id.iv_capture_main_activity)
        btnCapture = findViewById(R.id.btn_capture_record_main_activity)
        btnCapture.setOnClickListener {
            val startTime = System.currentTimeMillis()
            val frameBitmap = RecordViewUtil.getBitmapFromView(window, layoutRecordContentView, 540)
            //val frameBitmap = viewRecord.getFrameBitmap(640)
            Log.i(TAG, "OnClick getFrameBitmap cost: ${System.currentTimeMillis() - startTime}ms")
            ivCapture.setImageBitmap(frameBitmap)
        }
        initFunc()
        VRLogger.logLevel = Log.VERBOSE
        checkPermission()

        test2()
    }

    private fun test2() {
        val mime = "video/avc"
        //CodecUtil.getAllHardwareEncoders("video/avc")
        val allHardwareEncoders = CodecUtil.getAllHardwareEncoders(mime)
        for (mediaCodecInfo in allHardwareEncoders) {
            Log.i(TAG, "HardwareEncoders: " + mediaCodecInfo.name)
            Log.i(
                TAG,
                "Color supported by this encoder: " + Arrays.toString(mediaCodecInfo.getCapabilitiesForType(mime).colorFormats)
            )
        }
        val allSoftwareEncoders = CodecUtil.getAllSoftwareEncoders(mime)
        for (mediaCodecInfo in allSoftwareEncoders) {
            Log.i(TAG, "SoftwareEncoders: " + mediaCodecInfo.name)
            Log.i(
                TAG,
                "Color supported by this encoder: " + Arrays.toString(mediaCodecInfo.getCapabilitiesForType(mime).colorFormats)
            )
        }
    }

    private fun initFunc() {
        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                //
                Log.d(TAG, "requestPermissionLauncher request Permission result isGranted=$isGranted")
                /*if (isGranted) {
                    // Permission is granted. Continue the action or workflow in your app.
                    startPreview()
                } else {
                    // Explain to the user that the feature is unavailable because the
                    // feature requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                    // 向用户解释该功能不可用，因为该功能需要用户拒绝的权限。 同时尊重用户的决定。 不要链接到系统设置以说服用户改变他们的决定。
                    checkPermission()
                }*/
                // 需要再次检测，同时满足两个权限
                checkPermission()
            }

        // todo 补充一个一次请求多个权限的方法
        /*val requestMultiplePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){

        }
        requestMultiplePermissionLauncher.launch(permissionMap.mapTo(arrayOf(), {

        }))*/
    }


    private var mStartTime = 0L
    private fun startRecord(view: View) {
        if (isLackPermissions()) {
            checkPermission()
            return
        }
        tvTime.text = "开始"
        //method1(view)
        //method2(view)
        //method3(view)
        method4(view)
        //mRecordEncoder.setUp(sourceProvider, outputFile, 1024_000, true)
        //mRecordEncoder.start()
        mTimerJob = lifecycleScope.launch {
            var time = 0
            while (this.isActive) {
                tvTime.text = "${time++}"
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * 测试手动初始化
     */
    private fun init(view: View) {
        viewRecord.init(
            window = window,
            view = view,
            width = 555,
            fps = 30,
            videoBitRate = 4_000_000,
            iFrameInterval = 1,
            audioBitRate = 192_000,
            audioSampleRate = 44100,
            isStereo = true,
        )
        /*viewRecord.initJustVideo(
            window = window,
            view = view,
            width = 222,
            fps = 30,
            videoBitRate = 4_000_000,
            iFrameInterval = 1,
        )*/
    }

    private fun method4(view: View) {
        if (viewRecord.isStartRecord) {
            Toast.makeText(this, "正在录制中", Toast.LENGTH_SHORT).show()
            return
        }
        init(view)
        val outputFile = File(externalCacheDir, "record_${System.currentTimeMillis()}.mp4")
        mLastRecordFile = outputFile
        // 先check一下，最后对比下参数
        val width = view.width
        val height = view.height
        Log.i(TAG, "startRecord(): View: width=$width, height=$height, outputFile: ${outputFile.absolutePath}")
        mStartTime = System.currentTimeMillis()
        viewRecord.startRecord(outputFile.absolutePath, object : RecordController.Listener {
            override fun onStatusChange(status: RecordController.Status?) {
                Log.i(TAG, "onStatusChange() called with: status = $status")
            }
        }, object : EncoderErrorCallback {
            override fun onCodecError(type: String, e: MediaCodec.CodecException) {
                Log.e(TAG, "onCodecError() called with: type = $type", e)
            }
        })
    }

    private fun stopMethod4(): Unit {
        viewRecord.stopRecord()
    }

    private fun stopMethod3(): Unit {
    }

    private fun method3(view: View) {

    }

    private fun method2(view: View) {
        mRecordEncoder.start(
            window,
            view,
            isRecordAudio = false
        ) { isSuccessful: Boolean, result: String ->
            Log.w(TAG, "onResult() isSuccessful: $isSuccessful, result: $result")
        }
    }

    private fun method1(view: View) {
        val outputFile = File(externalCacheDir, "record_${System.currentTimeMillis()}.mp4")
        mLastRecordFile = outputFile
        Log.i(TAG, "startRecord() outputFile: ${outputFile.absolutePath}")
        val sourceProvider = object : ISourceProvider {
            override fun next(): Bitmap {
                return RecordViewUtil.getBitmapFromView(window, view, 540)
            }

            override fun onResult(isSuccessful: Boolean, result: String) {
                Log.w(TAG, "onResult() isSuccessful: $isSuccessful, result: $result")
            }
        }
        mRecordEncoder.start(sourceProvider, outputFile, 1024_000, true)
    }

    private fun stopRecord() {
        // 录制时长
        val duration = System.currentTimeMillis() - mStartTime
        // 输出转换成秒毫秒
        val durationStr = "${duration / 1000}.${duration % 1000}"
        //mRecordEncoder.stop()
        stopMethod4()
        mTimerJob?.cancel()
        mTimerJob = null
        Log.i(TAG, "stopRecord() duration=${durationStr}秒, RecordFile: ${mLastRecordFile?.absolutePath}")
    }

    private fun checkPermission() {
        permissionMap.keys.forEach {
            permissionMap[it] =
                ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        Log.i(TAG, "checkPermission(): $permissionMap")
        // 全部都是true有权限
        if (!isLackPermissions()) {
            startPreview()
            return
        }
        permissionMap.forEach {
            if (!it.value) {
                tryRequestOnePermission(it.key)
                // 只能一个一个来，两个一起来，另一个直接返回isGranted: false
                return
            }
        }
    }

    /**
     * 是否缺少权限
     */
    private fun isLackPermissions() = permissionMap.containsValue(false)

    /**
     * @param isExplained 是否已经解释过
     */
    private fun tryRequestOnePermission(permission: String, isExplained: Boolean = false) {
        val shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale(permission)
        Log.d(
            TAG,
            "请求单个权限(${permission}) 需要展示请求权限的理由吗？=$shouldShowRequestPermissionRationale，是否已展示过理由=$isExplained"
        )
        if (shouldShowRequestPermissionRationale) {
            // 向用户显示指导界面，在此界面中说明用户希望启用的功能为何需要特定权限。
            if (isExplained) {
                requestOnePermission(permission)
            } else {
                showWhy(permission)
            }
        } else {
            requestOnePermission(permission)
        }
    }

    private fun requestOnePermission(permission: String) {
        requestPermissionLauncher.launch(permission)
    }

    private fun showWhy(permission: String) {
        val message = when (permission) {
            android.Manifest.permission.CAMERA -> "需要相机权限，用于录像"
            android.Manifest.permission.RECORD_AUDIO -> "需要录音权限，用于录音"
            else -> "需要权限"
        }
        AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage(message)
            .setPositiveButton("确定") { dialog, which ->
                Log.i(TAG, "showWhy Dialog: current state = ${lifecycle.currentState}")
                tryRequestOnePermission(permission, true)
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, which ->
                // TODO: 2023/5/26 你的操作
                dialog.dismiss()
            }
            .show()
    }

    private fun startPreview() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder().build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        mCamera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview)
    }
}
