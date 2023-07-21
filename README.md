# Android 应用内录屏

### Description
Android View Record: Screen Record, Video Record, Audio Record
安卓录屏，或者说app内录屏，避开隐私问题，不会录到系统通知或者其他系统级弹窗或者各种类似下拉状态栏等会遮盖的之类的画面，当初也是为了实现这个需求造的这个轮子（苦于搜遍GitHub都没搜到，不然也懒得写了），View级别的录屏，你可以录整个RootContentView，也可以是只录某个ViewGroup或者View，支持同时录音

> 注释保留了很多，因为本身也是边学习边输出，出于交流分享共同学习的目的，保留了注释，方便大家理解和避开一些坑

### Usage
具体可以参照Demo，MainActivity中的调用
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


### 优点：
1. 不需要权限，主要说得是录屏的那个敏感权限弹窗。如果需要录音，那录音权限还是需要的。
2. 解决隐私问题，常规录屏方案可能会录到用户的隐私信息，比如聊天记录、各类账号、短信验证码等。
3. 不会因为下拉状态栏等，需要录制的内容被遮盖。
4. 自定义源更灵活。

### 缺点：
1. 只能录制应用内的前台内容（录后台内容其实可以实现，自己控制下next的输入源就可以了，理论上扩展性很高）。
2. 不能录制SurfaceView的内容，原理问题，如果需要录摄像头或者Player需要使用TextureView。


## 难点
1. 帧率控制。
2. 启动帧同步，目前采用视频帧启动后再输入音频。
3. 不同机型的颜色格式适配，暂未完全解决。
4. 视频输入源耗时问题，会导致实际帧率下降，大分辨率的情况下比较明显，如果异步采集合成，时间一长，积压的帧多了内存会爆炸，后续可能需要改算法方案来提高效率。


## To-Do List
- [ ] 代码优化抽象，方便后续升级扩展（第一个版本写的比较急）
- [ ] 上传到公共仓库
- [ ] Bitmap - Pixels 算法效率提升
- [ ] 机型适配
- [ ] 帧率提升
- [ ] 麦克风降噪
- [ ] 合入其他音频源
