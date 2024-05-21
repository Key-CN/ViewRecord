# Android 应用内录屏

## Description
Android View Record: Screen Record, Video Record, Audio Record
安卓录屏，或者说app内录屏，避开隐私问题，不会录到系统通知或者其他系统级弹窗或者各种类似下拉状态栏等会遮盖的之类的画面，当初也是为了实现这个需求造的这个轮子（苦于搜遍GitHub都没搜到，不然也懒得写了），View级别的录屏，你可以录整个RootContentView，也可以是只录某个ViewGroup或者View，支持同时录音

> 注释保留了很多，因为本身也是边学习边输出，出于交流分享共同学习的目的，保留了注释，方便大家理解和避开一些坑

## 2024/05/20
NOTE: 已知在就算支持的颜色格式中，也可能会出现oom，如420Planar在荣耀某款平板上，华为（确切的说是海思或者麒麟的芯片）在颜色格式上，support列表中是支持但是还是有很多不支持的问题，会导致OOM，网站这个问题遇到的也很多，一搜一大把，大致Log类似于：
```
 W  hw configLocalPlayBack err = -22
 W  do not know color format 0x7f000001 = 2130706433
 I  setupAVCEncoderParameters with [profile: Baseline] [level: Level3]
 I  [OMX.hisi.video.encoder.avc] cannot encode color aspects. Ignoring.
 I  [OMX.hisi.video.encoder.avc] cannot encode HDR static metadata. Ignoring.
 I  setupVideoEncoder succeeded
 I  [OMX.hisi.video.encoder.avc] got color aspects (R:0(Unspecified), P:0(Unspecified), M:0(Unspecified), T:0(Unspecified)) err=-1010(??)
```
展示解决方案只能是不使用19（COLOR_FormatYUV420Planar），21（COLOR_FormatYUV420SemiPlanar）没问题，所以我新增了一个优选列表，优先选择21
并且OMX.google.h264.encoder这个编码器在华为上也无法使用，编码的视频有问题无法播放，别的机型测了几款没问题。

## 2024/04/09 修复在Android14上的采集问题
主要是在PixelCopy.request中，不像前代api会阻塞返回了，正如之前在RecordViewUtil中134行注释中所说的，在这一代api中突然生效了

目前已知在Android7.1.1 API25上还有问题，Muxer相关

并且在非采用PixelCopy.request方案的录制中，采集摄像头View需使用TextureView，直接使用CameraX中的Texture无法采集到，会是黑屏

## Nov 23 2023 新增一个录制类 ViewRecorder
**高可用，比我之前写的那个兼容性要好，暂时项目中先用这个**
不过因为也是临时用，又紧急，所以一些可配置下项我暂时还没抽出来，只根据自己项目需要写死了值

借用了pedroSG94在他的RootEncoder推流项目中的录制编码部分类进行修改
感谢大佬的开源分享，项目地址：https://github.com/pedroSG94/RootEncoder
原版本在机型兼容性上存在一些问题，由于比较紧急，暂时没时间调试，所以先在该项目基础上写一版能用的，这个项目考虑的还是比较完整的。

他的代码已经写的很好了，不过很复杂不便于学习，我足足看了两天，interface很绕，我删除了部分他推流用的功能，做了些许精简
他的项目是流，所以使用的是ArrayBlockingQueue，而我是实时采集，所以Video这部分我加了判断但还没有删除，Audio部分则是删除了。
后面有时间继续学习他的编码部分，完善下我这个简单的单纯的录屏功能
中间还参考了两个项目，一个微软的，一个chromium的
https://github.com/microsoft/HydraLab/blob/b50bc6054d32a1b83d6c944ef9fbd393150922ab/android_client/app/src/main/java/com/microsoft/hydralab/android/client/BaseEncoder.java
https://github.com/chromium/chromium/blob/0ddb38eda131f19995ec537bc67b62d35170e2ab/media/base/android/java/src/org/chromium/media/MediaCodecUtil.java
能参考的项目基本上都是推流中使用的。
chromium中对硬编部分，做了CPU判断，从我的代码上线至今收集到的日志，应该是有点关系的。后续我也会跟进完善


## Usage
具体可以参照Demo，MainActivity中的调用

* 最新的方案参考method4(view: View)方法

**旧：**
1. 实现```ISourceProvider```接口
2. 指定输出路径
3. 设定参数
   1. 可配置项很多，可以看类中没有private的成员变量
   ```kotlin
   // 比如视频的格式类型 acv h264, hevc h265，但要注意，很多手机还不能硬解硬编h265
   videoMimeType
    ```
4. 开始录屏
```kotlin
val outputFile = File(externalCacheDir, "record_${System.currentTimeMillis()}.mp4")
val sourceProvider = object : ISourceProvider {
   override fun next(): Bitmap {
      return RecordViewUtil.getBitmapFromView(window, view, 800)
   }

   override fun onResult(isSuccessful: Boolean, result: String) {
      Log.w(TAG, "onResult() isSuccessful: $isSuccessful, result: $result")
   }
}
// 方案1：便捷方式
mRecordEncoder.start(sourceProvider, outputFile, 1024_000, true)
// 方案2: 先设定好参数，在业务处启动
mRecordEncoder.setUp(sourceProvider, outputFile, 1024_000, true)
mRecordEncoder.start()
// 方案3: 无需读写权限，等结束回调返回路径
mRecordEncoder.start(
   window,
   view,
   isRecordAudio = false
) { isSuccessful: Boolean, result: String ->
   Log.w(TAG, "onResult() isSuccessful: $isSuccessful, result: $result")
}
```
5. 要排查问题可以打开日志输出
```kotlin
VRLogger.logLevel = Log.VERBOSE 或者 Log.DEBUG 级别
```


## 优点：
1. 不需要权限，主要说得是录屏的那个敏感权限弹窗。如果需要录音，那录音权限还是需要的。
2. 解决隐私问题，常规录屏方案可能会录到用户的隐私信息，比如聊天记录、各类账号、短信验证码等。
3. 不会因为下拉状态栏等，需要录制的内容被遮盖。
4. 自定义源更灵活。

## 缺点：
1. 只能录制应用内的前台内容（录后台内容其实可以实现，自己控制下next的输入源就可以了，理论上扩展性很高）。
2. 不能录制SurfaceView的内容，原理问题，如果需要录摄像头或者Player需要使用TextureView。


## 难点
1. 帧率控制。(已解决)
2. 启动帧同步，目前采用视频帧启动后再输入音频。目前还是会丢启动时的部分帧，待解决
3. 不同机型的颜色格式适配，暂未完全解决。（大致解决，具体原因看5/20那条更新）
4. 视频输入源耗时问题，会导致实际帧率下降，大分辨率的情况下比较明显，如果异步采集合成，时间一长，积压的帧多了内存会爆炸，后续可能需要改算法方案来提高效率。


## To-Do List
- [ ] 代码优化抽象，方便后续升级扩展（第一个版本写的比较急）
- [ ] 上传到公共仓库
- [x] Bitmap - Pixels 算法效率提升
- [x] 机型适配
- [x] 帧率提升
- [x] 麦克风降噪
- [ ] 合入其他音频源
