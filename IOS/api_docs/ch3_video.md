# 第 3 章 视频捕获接口（MovieFileOutput / VideoDataOutput / AssetWriter）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 出处：[AVCaptureMovieFileOutput](https://developer.apple.com/documentation/avfoundation/avcapturemoviefileoutput)、[AVCaptureVideoDataOutput](https://developer.apple.com/documentation/avfoundation/avcapturevideooutput)、[AVAssetWriter](https://developer.apple.com/documentation/avfoundation/avassetwriter)。

## 3.1 两条路线的选择

| | MovieFileOutput（托管） | VideoDataOutput + AVAssetWriter（自管） |
|---|---|---|
| 产出 | 完整 MOV 文件 | 逐帧 sample buffer，App 自己编码封装 |
| 定制点 | 时长/大小上限、防抖、旋转 | 像素格式、滤镜、编码参数、自定义容器 |
| Android 对应 | MediaRecorder | ImageReader + MediaCodec + MediaMuxer |
| 适用 | 普通录制、视频笔记 | 美颜/特效、直播推流、帧级算法 |

## 3.2 AVCaptureMovieFileOutput 接口表

| 成员 | 说明 |
|---|---|
| `startRecording(to:recordingDelegate:)` | 开始（异步完成回调在 delegate） |
| `stopRecording()` | 停止并收尾文件 |
| `maxRecordedDuration` / `maxRecordedFileSize` | 自动停止条件 |
| `isRecordingPaused` / `pauseRecording()` / `resumeRecording()` | 录制暂停（iOS 16+） |
| `recordsVideoOrientationAndMirroringChangesAsMetadataStream` | 方向变化写为元数据流（避免文件中途旋转） |
| `availableVideoCodecTypes` / `setVideoCodec?` | 编码能力（经 `videoSettings`/connection 输出设置） |
| delegate：`fileOutput(_:didStartRecordingTo:from:)`、`…didFinishRecordingTo…WithError` | 生命周期回调 |

文件输出方向/元数据由 connection 承担（第 1 章 1.3）；出片后处理常用 `AVAssetExportSession` 转码。

## 3.3 AVCaptureVideoDataOutput 接口表

| 成员 | 说明 | Android 对应 |
|---|---|---|
| `videoSettings` | `[kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]` 等 | ImageReader 格式选择 |
| `alwaysDiscardsLateVideoFrames` | 丢帧策略（true=实时优先） | `TEMPORAL_PATTERN`/丢帧行为 |
| `setSampleBufferDelegate(_, queue:)` | 指定**串行队列**收帧 | ImageReader.OnImageAvailableListener |
| `isVideoMirrored` 等 | 经 connection | — |

收帧回调核心类型链：`CMSampleBuffer` → `CVPixelBuffer`（可 zero-copy 交给 Core Image/Metal）→ `CMSampleTimingInfo.duration/pts`。

```swift
func captureOutput(_ output: AVCaptureOutput,
                   didOutput sampleBuffer: CMSampleBuffer,
                   from connection: AVCaptureConnection) {
    guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    // CIImage(cmvSampleBuffer:) / CVMetalTextureCache 均可零拷贝接管
}
```

帧率控制（设备级，见 1.2）：`activeVideoMinFrameDuration/MaxFrameDuration`（CMTime），锁 30fps 的标准写法是两者都设 `CMTime(value: 1, timescale: 30)`。

## 3.4 AVAssetWriter 落盘接口速查

| 成员 | 说明 |
|---|---|
| `AVAssetWriter(outputURL:fileType:)` | `.mov`（ProRes 必须 mov；HEVC 也可 `.mov`） |
| `addInput(AVAssetWriterInput)` / `addInput(AVAssetWriterInputPixelBufferAdaptor)` | 音/视频轨；PixelBufferAdaptor 直收 CVPixelBuffer |
| `input.mediaType` / `outputSettings` | `[AVVideoCodecKey: .hevc]` + 分辨率/码率/颜色属性 |
| `startWriting()` / `startSession(atSourceTime:)` | 时间轴起点（首个视频帧 PTS） |
| `input.isReadyForMoreMediaData` | 背压控制（对应 MediaCodec 输入缓冲满） |
| `finishWriting(completionHandler:)` | 收尾 |

多轨同步约定：视频与音频各自 input，按 PTS 写入；`startSession(atSourceTime:)` 用**最小 PTS**（一般是视频首帧），否则音画漂移——与 Android MediaMuxer 的 start 怕坑一致。

## 3.5 专业视频编码参数（ProRes / Apple Log）

| 目标 | 设置要点 | 版本/机型 |
|---|---|---|
| ProRes 422（HQ/LT/4444） | `AVVideoCodecType.proRes422/.proRes422HQ/.proRes4444` 写入 writer 的 `outputSettings`；容器 `.mov` | 第三方 API iOS 17+（iPhone 15 Pro 起） |
| Apple Log | 设备格式能力中查询 Apple Log 支持（随色彩空间能力交付），配合 ProRes 使用 | iPhone 15 Pro / iOS 17 |
| Apple Log 2 / ProRes RAW / Genlock | AVFoundation 新能力（WWDC25 起）；Genlock 用于多机位帧级同步 | iPhone 17 Pro / iOS 26 |

> 提示：ProRes 码率极高（4K30 ProRes 422HQ ≈ 数百 Mbps），必须直写本地高速存储并关注 `systemPressureState`；直播场景用 HEVC + 外挂 Log LUT 的组合更常见。具体可用性以 `availableVideoCodecTypes`/格式能力查询为准，机型矩阵见 Apple 产品页（A 层）。

## 3.6 音频捕获接口速查

| 成员 | 说明 |
|---|---|
| `AVCaptureDevice.default(for: .audio)` | 麦克风设备 |
| `AVCaptureAudioDataOutput` | 逐帧音频（`CMSampleBuffer`，PCM） |
| `AVCaptureDeviceInput`（audio） | 加入 session 即可被 MovieFileOutput 托管收音 |
| 音频格式设置 | `audioSettings`（AVFormatIDKey 等）或 AudioQueue 自管 |
| 双路对齐 | 视频/音频 delegate 分开串行队列，按 PTS 混流 |
