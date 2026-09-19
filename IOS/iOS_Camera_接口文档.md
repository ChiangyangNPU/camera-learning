# iOS Camera 接口速查文档

> 本文档是相机学习系列的第六份，与《Android_Camera_接口文档》同构：**写代码时查方法、查字段、查对应关系**。所有签名以 Swift 5 / iOS 17+ SDK 为准，逐章附 Apple 官方文档链接。
>
> 官方文档入口：<https://developer.apple.com/documentation/avfoundation>
> 整理日期：2026-09-19。iOS 无公开 HAL，故不设 Android 版第 4 章（HAL）的对应章，改为"元数据与色彩管理对照"（第 5 章）。

## 文档结构

| 章节 | 内容 | Android 版对应 |
|---|---|---|
| 第 1 章 核心捕获类接口 | Session/Device/Input/Connection/Format 方法与属性表 | 第 1 章 Camera2 核心类 |
| 第 2 章 照片捕获接口 | AVCapturePhotoOutput/PhotoSettings/Photo 全字段表、48MP 路径 | 第 1、5 章（拍照与元数据） |
| 第 3 章 视频捕获接口 | 文件输出与逐帧输出双路线、AVAssetWriter、ProRes/Log 参数 | 第 1、2 章（录制部分） |
| 第 4 章 高级能力接口 | MultiCam、深度、MetadataOutput、事件 API、Cinematic、外接 | 第 2 章（能力部分） |
| 第 5 章 元数据与色彩管理对照 | 3A 控制语义总表、输出流对照、颜色空间、迁移检查清单 | 第 5 章 camera_metadata |

## 使用建议

- 从 Android 迁移：先读第 5 章总表建立映射，再按需查第 1~4 章的签名；
- 新学 iOS：先通读《iOS_Camera_学习文档》第 2~4 章（机制），再回本册查表；
- 表中"Android 对应"列是**语义对照**（近似），行为差异以《学习文档》各章说明为准。

---

# 第 1 章 核心捕获类接口速查（Session / Device / Input / Connection）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 本章对应《Android_Camera_接口文档》第 1 章（Camera2 核心类）的 iOS 版。所有签名以 Swift 5 / iOS 17+ SDK 为准，官方出处：[AVCaptureSession](https://developer.apple.com/documentation/avfoundation/avcapturesession)、[AVCaptureDevice](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、[AVCaptureConnection](https://developer.apple.com/documentation/avfoundation/avcaptureconnection)。

## 1.1 AVCaptureSession 方法/属性表

| 成员 | 签名要点 | 说明 | Android 对应 |
|---|---|---|---|
| `sessionPreset` | 属性，`AVCaptureSession.Preset` | `.photo` / `.high` / `.hd1920x1080` / `.hd4K3840x2160` / `.inputPriority` / `.qvga` 等 | 流组合整体声明 |
| `canSetSessionPreset(_:)` | 查询 | preset 兼容性预检 | 无（逐流判断） |
| `beginConfiguration()` / `commitConfiguration()` | 原子配置区间 | 运行中改结构不中断 | createCaptureSession（需重建） |
| `addInput(_:)` / `addOutput(_:)` | 增删 | 配对 `canAddInput/canAddOutput` 预检 | addSurface |
| `removeInput/Output`、`inputs`/`outputs` | 增删/查询 | — | — |
| `startRunning()` / `stopRunning()` | 同步阻塞 | 别在主线程 | openCamera+setRepeatingRequest |
| `isRunning` | 只读 | — | — |
| `interruptionNotification` / `interruptionEndedNotification` / `errorNotification` | 通知 | 中断 reason 在 userInfo | onDisconnected/AvailabilityCallback |
| `connections` | 只读 | 全部连接（见 1.3） | — |

常用 preset 速查：

| preset | 用途 | 典型实际输出 |
|---|---|---|
| `.photo` | 拍照 + 预览（静态最优） | 预览随屏、照片全分辨率 |
| `.high` | 高质量视频录制 | 设备最高视频分辨率 |
| `.hd1920x1080` / `.hd4K3840x2160` | 固定视频分辨率 | 1080p / 4K |
| `.inputPriority` | 逐帧处理自定格式 | 由 device.activeFormat 决定 |
| `.vga640x480` / `.cif352x288` / `.qvga` | 低功耗/低延迟 | 对应分辨率 |

## 1.2 AVCaptureDevice 关键属性表（控制面）

> 完整机制讲解见《学习文档》第 3 章；本表为查签名用。

| 组 | 成员 | 类型/取值 |
|---|---|---|
| 发现 | `AVCaptureDevice.DiscoverySession(deviceTypes:mediaType:position:)` | 类方法，返回按优先级排序设备 |
| 标识 | `uniqueID` / `modelID` / `localizedName` | String |
| 姿态 | `position` | `.front / .back / .unspecified` |
| 能力 | `isFocusPointOfInterestSupported` / `isExposurePointOfInterestSupported` / `isFlashAvailable` / `isTorchAvailable` / `isLowLightBoostSupported` | Bool |
| 格式 | `formats: [AVCaptureDevice.Format]` / `activeFormat` | Format 列表（见 1.4） |
| 对焦 | `focusMode` / `focusPointOfInterest` / `lensPosition`（只读）/ `minimumFocusDistance` | 三态模式 + 归一化坐标 |
| 曝光 | `exposureMode` / `exposurePointOfInterest` / `exposureTargetBias`（±min/max）/ `exposureDuration`（min/max）/ `ISO`（min/max） | 四态模式 + 手动参数 |
| 白平衡 | `whiteBalanceMode` / `deviceWhiteBalanceGains` / `maxWhiteBalanceGain` | 两态 + RGB 增益 |
| 变焦 | `videoZoomFactor` / `displayVideoZoomUpFactor` / `videoMaxZoomFactor`（activeFormat）/ `virtualDeviceSwitchOverVideoZoomFactors` | 倍率与切换锚点 |
| 闪光/手电 | `torchMode` / `torchLevel` / `hasFlash` | 设备级属性 |
| 帧率 | `activeVideoMinFrameDuration` / `activeVideoMaxFrameDuration` | CMTime（需 lock 后写在 activeFormat 约束内） |
| 多摄 | `isVirtualDevice` / `constituentDevices` / `activePrimaryConstituentDevice` | 虚拟设备三元组 |
| 压力 | `systemPressureState`（KVO） | level + factors |

控制流程模板（iOS 一切设备写入的固定形态）：

```swift
try device.lockForConfiguration()
device.focusMode = .continuousAutoFocus
device.exposureTargetBias = 1.0
device.videoZoomFactor = 2.0
device.unlockForConfiguration()
```

> 对应 Android 的心智模型：`lockForConfiguration` 区间 ≈ 一次 `CaptureRequest.Builder` 构建后 `setRepeatingRequest` 提交；区别是 iOS 写入后**持续生效**，Android 每帧请求独立。

## 1.3 AVCaptureConnection 关键属性表

| 成员 | 说明 | Android 对应 |
|---|---|---|
| `videoRotationAngle`（iOS 17+，替代 `videoOrientation`） | 0/90/180/270，目标旋转角 | CaptureRequest `JPEG_ORIENTATION` / `SENSOR_ORIENTATION` 合成 |
| `isVideoMirrored` | 前摄镜像 | OEM 行为差异点，iOS 显式 |
| `preferredVideoStabilizationMode` | off/standard/cinematic/cinematicExtended/previewAuto | `CONTROL_VIDEO_STABILIZATION_MODE` |
| `preferredVideoStabilizationMode` 支持查询 | `activeVideoStabilizationMode` 实际生效 | — |
| `videoScaleAndCropFactor` | 连接级数字缩放（≤ `videoMaxScaleAndCropFactor`） | 与 zoomRatio 二选一用，避免叠加 |
| `isVideoFieldOfViewSupported` / `videoFieldOfView` | FOV 控制（部分连接） | 无直接对应 |
| `cameraCalibrationData` | 标定获取入口 | `LENS_CALIBRATION` 族 |

方向换算的实践约定（官方示例 AVCam 的做法）：UI 旋转（`UIDevice.orientation`）→ 映射到每个 connection 的 `videoRotationAngle`；照片方向另由 `AVCapturePhoto` 的 metadata EXIF 承载，写盘时不要二次旋转。

## 1.4 AVCaptureDevice.Format 速查表

| 成员 | 说明 | Android 对应 |
|---|---|---|
| `dimensions`（CMVideoDimensions） | 该格式视频尺寸 | StreamConfigurationMap |
| `videoSupportedFrameRateRanges` | `[AVFrameRateRange]`（min/max duration） | `AE_TARGET_FPS_RANGE` 列表 |
| `videoFieldOfView` | 视场角 | `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 近似 |
| `supportedMaxPhotoDimensions` | 48MP 等照片尺寸列表（iOS 16+） | `SENSOR_PIXEL_MODE` 关联行为 |
| `isVideoStabilizationSupported` / 防抖各模式支持 | 能力查询 | `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` |
| `autoFocusSystem` | phaseD Detection / contrastDetection / none | `AF_MODE` 能力来源 |
| `highestPhotoQualitySupported` | 照片质量能力 | 无直接对应 |
| `supportedColorSpaces` | sRGB / Display P3 / HLG | `COLOR_SPACE` 能力 |

切格式的固定写法：`lockForConfiguration` → `device.activeFormat = 想要的 format`（仅 `.inputPriority` preset 下对逐帧输出生效）→ 配 `activeVideoMin/MaxFrameDuration` 锁帧率 → `unlockForConfiguration`。

## 1.5 权限与设备监视接口

| 接口 | 用途 |
|---|---|
| `AVCaptureDevice.authorizationStatus(for: .video/.audio)` | 查询授权态 |
| `AVCaptureDevice.requestAccess(for:completionHandler:)` | 弹窗申请 |
| `AVCaptureDevice.wasConnectedNotification` / `wasDisconnectedNotification` | 外设热插拔 |
| `AVCaptureDevice.systemPressureState`（KVO） | 热压力降级 |
| `subjectAreaDidChangeNotification` | 场景变化（重启 3A 提示） |
# 第 2 章 照片捕获接口（AVCapturePhotoOutput / PhotoSettings）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 出处：[AVCapturePhotoOutput](https://developer.apple.com/documentation/avfoundation/avcapturephotooutput)、[AVCapturePhotoSettings](https://developer.apple.com/documentation/avfoundation/avcapturephotosettings)、[AVCapturePhoto](https://developer.apple.com/documentation/avfoundation/avcapturephoto)。

## 2.1 拍照最小代码骨架

```swift
let settings: AVCapturePhotoSettings
if photoOutput.availablePhotoCodecTypes.contains(.hevc) {
    settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])
} else {
    settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
}
settings.flashMode = .auto
settings.photoQualityPrioritization = .quality
photoOutput.capturePhoto(with: settings, delegate: self)

// delegate 关键回调
func photoOutput(_ output: AVCapturePhotoOutput,
                 didFinishProcessingPhoto photo: AVCapturePhoto,
                 error: Error?) {
    let data = photo.fileDataRepresentation()!   // 含 EXIF/色彩配置的成品
}
```

## 2.2 AVCapturePhotoOutput 属性表（能力查询区）

| 成员 | 说明 |
|---|---|
| `availablePhotoCodecTypes` | `[AVVideoCodecType]`（hevc/jpeg，jpegXL 自 iOS 17 起） |
| `availableRawPhotoPixelFormatTypes` | 可用 RAW FourCC |
| `isAppleProRAWSupported` | ProRAW 能力（iOS 14.3+） |
| `isLivePhotoCaptureSupported` → `isLivePhotoCaptureEnabled` | Live Photo 两段式：先查能力再开 |
| `isDepthDataDeliverySupported` → `isDepthDataDeliveryEnabled` | 照片深度 |
| `isPortraitEffectsMatteDeliverySupported/Enabled` | 人像蒙版（iOS 13+） |
| `maxPhotoDimensions`（iOS 16+） | 输出尺寸上限（48MP 需显式抬） |
| `isResponsiveCaptureSupported` / `isFastCaptureSupported` | 快门响应能力 |
| `isVirtualDeviceConstituentPhotoDeliverySupported/Enabled` | 虚拟设备逐镜头照片（多镜头同拍） |
| `isDualPhotoDeliverySupported/Enabled` | 双摄同拍（前后同拍等） |

> 通用模式：iOS 的能力几乎全是 `isXxxSupported`（会话配置完成后查询）→ `isXxxEnabled`（写入开启）两段式。对应 Android 是 `CameraCharacteristics` 查能力 + request 开启，iOS 把两步放在同一个对象上。

## 2.3 AVCapturePhotoSettings 字段表

| 组 | 字段 | 取值/说明 | Android 对应 |
|---|---|---|---|
| 格式 | `format` | `[AVVideoCodecKey: .hevc/.jpeg]` | ImageFormat.JPEG/HEIC |
| RAW | `rawPhotoPixelFormatType` | 从 availableRaw… 选；RAW 与主格式可同时交付 | RAW_SENSOR ImageReader |
| ProRAW | `rawPhotoPixelFormatType`（DNG 容器）+ output 的 ProRAW 支持 | iOS 14.3+；交付为 Apple 处理链线性 DNG | 无 |
| 闪光 | `flashMode` | .off/.on/.auto | `FLASH_MODE` |
| 质量 | `photoQualityPrioritization` | .speed/.balanced/.quality | OEM 私有 HDR 档位 |
| 尺寸 | `maxPhotoDimensions` | 单张收窄（iOS 16+） | — |
| 高分辨率 | `isHighResolutionCaptureEnabled` | （旧版高分辨率开关，iOS 16 起由 dimensions 体系覆盖） | — |
| 虚拟融合 | `isAutoVirtualDeviceFusionEnabled` | 跨镜头融合开关 | logical camera 行为 |
| Live Photo | `livePhotoVideoCodecType` | 配对视频编码 | Motion Photo |
| 深度 | `isDepthDataDeliveryEnabled` / `embedsDepthDataInPhoto` | 深度随照片 | OEM 分化 |
| 蒙版 | `isPortraitEffectsMatteDeliveryEnabled` | 人像蒙版 | 无标准对应 |
| 红眼 | `isAutoRedEyeReductionEnabled` | — | — |
| 方向 | `embedsPortraitEffectsMatteInPhoto`、EXIF 方向随 `AVCapturePhoto` metadata | — | `JPEG_ORIENTATION` |
| 缩略图 | `embeddedThumbnailPixelFormats` | DNG 内嵌缩略 | — |
| 自定义 | `processedFileType`、RAW 处理回调 `photoOutput(_:didFinishProcessingRawPhoto…)` | RAW 需单独回调接收 | — |

## 2.4 AVCapturePhoto 交付物速查

| 成员 | 说明 |
|---|---|
| `fileDataRepresentation()` | 完整成品数据（含 EXIF/颜色配置/DNG 容器），直接写盘 |
| `cgImageRepresentation()` | 已解码位图 |
| `metadata` | EXIF/TIFF/辅助字典 |
| `resolvedSettings` | 实际生效的快门/ISO/闪光/对焦（**iOS 版 CaptureResult**） |
| `depthData` / `portraitEffectsMatte` | 附属交付 |
| `cameraCalibrationData` | 内参/畸变/外参 |
| `livePhotoCompanionMovieFileURL`? | Live Photo 配对视频（`AVCaptureLivePhoto`） |
| `embeddedThumbnailPhotoFormat` | 缩略信息 |

## 2.5 高分辨率（48MP）拍照标准代码路径

```swift
// 会话配置完成后
if let fmt = device.activeFormat,
   let dims = fmt.supportedMaxPhotoDimensions.max(by: { $0.width * $0.height < $1.width * $1.height }) {
    photoOutput.maxPhotoDimensions = dims
}
// 每张照片
settings.maxPhotoDimensions = photoOutput.maxPhotoDimensions
settings.photoQualityPrioritization = .quality   // 48MP + 深处理组合，耗时显著上升
```

要点：48MP 与 `.quality` 优先级叠加时出片延迟明显（Photonic Engine 全链处理），UI 需按"秒级"预期设计；`.speed` 下 48MP 意味着跳过大部分合并降噪——两档差异要真机验证。
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
# 第 4 章 高级能力接口（MultiCam / Depth / Metadata / 事件 / Cinematic）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

## 4.1 AVCaptureMultiCamSession（iOS 13+）

> 出处：[AVCaptureMultiCamSession](https://developer.apple.com/documentation/avfoundation/avcapturemulticamsession)、[AVCamMulti 示例](https://developer.apple.com/documentation/avfoundation/capture_setup/avcammulticam_capturing_photos_and_video_on_multiple_cameras_simultaneously)。

| 成员 | 说明 |
|---|---|
| `isMultiCamSupported` | 类属性，硬件预检 |
| 用法差异 | 与普通 session 同 API；并发组合限制见官方 Hardware Compatibility 表 |
| 输出建议 | 多路逐帧用小分辨率；同录+高分辨率照片组合限制最多 |

虚拟设备相关（挂在 AVCaptureDevice 上）：

| 成员 | 说明 |
|---|---|
| `isVirtualDevice` | 是否逻辑多摄 |
| `constituentDevices` | 成员物理镜头数组 |
| `activePrimaryConstituentDevice` | 当前主镜头 |
| `virtualDeviceSwitchOverVideoZoomFactors` | 换镜锚点倍率 |
| `AVCapturePhotoOutput.isVirtualDeviceConstituentPhotoDeliveryEnabled` | 多镜头同拍照片 |

## 4.2 深度管线接口（AVCaptureDepthDataOutput / AVDepthData）

> 出处：[AVCaptureDepthDataOutput](https://developer.apple.com/documentation/avfoundation/avcapturedepthdataoutput)、[AVDepthData](https://developer.apple.com/documentation/avfoundation/avdepthdata)。

| 成员 | 说明 |
|---|---|
| `isDepthDataDeliveryEnabled`（device 级） | 打开深度流前置条件 |
| `AVCaptureDepthDataOutput.setDelegate(_, callbackQueue:)` | 深度帧回调（频率低于视频） |
| `depthDataFilteringEnabled` / `depthDataAlwaysAvailable`? | 平滑与持续输出选项 |
| `AVDepthData.converting(toDepthDataClass:)` | disparity ↔ depth 互转 |
| `AVDepthData.converting(toFilterType:)` | 精度转换（Float16/32） |
| `depthDataMap` | CVPixelBuffer 深度图本体 |
| `isDepthDataFiltered` / `cameraCalibrationData` | 质量与标定 |
| 照片深度 | `AVCapturePhotoSettings.isDepthDataDeliveryEnabled` → `AVCapturePhoto.depthData` |

深度↔视频对齐：深度帧 PTS 与视频帧 PTS 独立漂移，用 `AVCaptureDepthDataOutput` 的 `depthDataCalibrated`? 与时间戳最近邻配对（官方示例做法）。

## 4.3 AVCaptureMetadataOutput（人脸/码检测）

| 成员 | 说明 | Android 对应 |
|---|---|---|
| `metadataObjectTypes` | `[.face, .qr, ...]`（先查 `availableMetadataObjectTypes`） | Face Detection / ML Kit |
| `setDelegate(_, queue:)` | `AVCaptureMetadataOutputObjectsDelegate` 回调 `AVMetadataObject` 数组 | — |
| `rectOfInterest` | 检测区域（归一化，配合 `metadataOutputRectConverted` 换算） | MeteringRectangle 思路 |
| `AVMetadataObject` → `AVMetadataMachineReadableCodeObject.stringValue` | 码内容 | — |

> 实践提示：人脸检测在 iOS 26 部分 beta 版曾有回归（社区报告），生产代码建议用 Vision 框架（`VNDetectFaceRectanglesRequest`）做主力检测，MetadataOutput 做轻量预览标注。

## 4.4 事件与控制 API（AVCaptureEventInteraction / Center Stage）

> 出处：[AVCaptureEventInteraction](https://developer.apple.com/documentation/avfoundation/avcaptureeventinteraction)、[WWDC25 Session 253](https://developer.apple.com/videos/play/wwdc2025/253)。

| 接口 | 版本 | 用途 |
|---|---|---|
| `AVCaptureEventInteraction(eventHandler:)` + `AVCaptureEvent.pressPhase/endPhase` | iOS 18（Camera Control） | 两段式硬件快门：轻按对焦/重按拍摄 |
| AirPods 远程事件（同 API 体系扩展） | iOS 26 | 远程触发捕获 |
| `AVCaptureDevice.centerStageControlMode` 等 Center Stage 属性 | iOS 16+/26 增强 | 人像居中跟随开关与宽高比 |
| Cinematic capture API | iOS 26 | 电影效果捕获会话（见 4.5） |

## 4.5 Cinematic 捕获 API（iOS 26）

> 出处：[Capture cinematic video in your app（WWDC25 Session 319）](https://developer.apple.com/videos/play/wwdc2025/319)。

- 会话层：电影效果捕获会话（`AVCaptureSession` 体系内的专用配置路径），要求多摄虚拟设备；
- 能力：系统主体识别与焦点跟人、录制中切换焦点主体、焦点过渡速率控制；
- 与播放侧（`AVCaptureCinematicVideoStabilizationMode`? 播放端 Cinematic 支持类）解耦，捕获 API 全新命名空间；
- 适用：访谈/短视频类 App 的"焦点叙事"功能，此前只能靠系统相机拍摄现成素材。

> 本节为概述级（A 层：WWDC25 session 319 公开内容）；具体类名以 iOS 26 SDK 头文件与官方文档为准（新 API 命名在撰写时仍随版本演进）。

## 4.6 外接摄像头接口

| 接口 | 说明 |
|---|---|
| `AVCaptureDeviceType.external` | iOS 17+（合并自 16 的 continuityCamera） |
| `AVCaptureDevice.wasConnected/wasDisconnectedNotification` | 热插拔 |
| 格式与控制 | 走同一套 Format/控制 API，能力受设备 UVC 描述符限制 |
| 预览 | 同 previewLayer，无特殊路径 |
# 第 5 章 元数据与色彩管理对照（iOS ↔ Android 元数据体系）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> Android 相机的一切控制/结果都走 `CaptureRequest`/`TotalCaptureResult` 键值元数据；iOS 没有统一元数据表，控制散落在设备属性、settings 字段、connection 属性里，结果集中在 `AVCapturePhoto.resolvedSettings`/`metadata` 与各 `CMSampleBuffer` 附件。本章给一张"语义对齐"总表，把两套体系接起来——查 Android 某个键在 iOS 的落点，或反向迁移，都用这张表。

## 5.1 3A 控制与结果语义对照总表

| 语义 | Android 元数据（request/result） | iOS 落点 |
|---|---|---|
| AE 模式 | `android.control.aeMode` | `AVCaptureDevice.exposureMode`（custom ≈ AE OFF） |
| 曝光时间 | `android.sensor.exposureTime`（ns） | `setExposureModeCustom(duration:iso:)`（CMTime 秒） |
| 感光度 | `android.sensor.sensitivity` | `ISO` 参数（ISO 值语义相同） |
| AE 补偿 | `android.control.aeExposureCompensation`（步进） | `exposureTargetBias`（连续 EV） |
| AE 区域 | `android.control.aeRegions` | `exposurePointOfInterest`（单点） |
| AE 状态 | `android.control.aeState`（搜索/收敛/锁定/闪烁） | `adjustingExposure`（KVO）+ 无状态机 |
| AF 模式 | `android.control.afMode` | `focusMode` 三态 |
| AF 触发 | `android.control.afTrigger` | 模式切换/`setFocusModeLocked`（无显式 trigger） |
| AF 距离 | `android.lens.focusDistance`（屈光度） | `lensPosition`（0~1，无标定单位） |
| AF 状态 | `android.control.afState` | `adjustingFocus`（KVO） |
| AWB 模式 | `android.control.awbMode`（色温枚举） | `whiteBalanceMode` 两态 |
| AWB 增益 | `android.colorCorrection.gains` | `deviceWhiteBalanceGains`（RGB 1~max） |
| CCM | `android.colorCorrection.transform`（3×3 R） | 不可控（系统内部） |
| 色温手动 | （需 OEM 扩展或自算） | `captureDeviceWhiteBalanceGains(for: temperatureAndTint)` 系统换算 |
| 闪光 | `android.flash.mode` | `AVCapturePhotoSettings.flashMode` / `torchMode` |
| precapture 序列 | `android.control.aePrecaptureTrigger` | 无（系统内部处理） |
| 变焦 | `android.control.zoomRatio` | `videoZoomFactor` |
| 裁剪 | `android.scaler.cropRegion` | preset + connection 缩放（无逐帧裁剪） |
| 防抖 | `android.control.videoStabilizationMode` | `connection.preferredVideoStabilizationMode` |
| 帧率 | `android.control.aeTargetFpsRange` | `activeVideoMin/MaxFrameDuration` |
| 3A 场景变化回调 | result 状态比对 | `subjectAreaDidChangeNotification` |

## 5.2 输出/流语义对照

| 语义 | Android | iOS |
|---|---|---|
| 会话 | `CameraCaptureSession` | `AVCaptureSession` |
| 流 | `OutputConfiguration(Surface)` | `AVCaptureXxxOutput` 对象 |
| 流格式 | ImageFormat（YUV_420_888/RAW/JPEG…） | `kCVPixelFormatType_*`（VideoDataOutput）/ codec 枚举 |
| 能力查询 | `CameraCharacteristics` | `AVCaptureDevice.Format` + output 的 `isXxxSupported` |
| 多摄并发能力 | 显式 characteristics | 官方表格 + 运行时失败 |
| 逐帧结果元数据 | `TotalCaptureResult` | `AVCapturePhoto.resolvedSettings`（照片）/ sample buffer attachments（视频） |
| 传感器方向 | `android.sensor.orientation` | 固定 landscape sensor + connection 旋转（`videoRotationAngle`） |
| 时间戳 | `SensorTimestamp`（ns，单调） | `CMSampleBuffer` PTS（CMTime，host 时间基准） |

## 5.3 静态图像元数据与色彩管理

> 出处：[AVCapturePhoto.metadata](https://developer.apple.com/documentation/avfoundation/avcapturephoto/2875824-metadata)、[Display P3 与颜色管理](https://developer.apple.com/documentation/coregraphics/cgcolorspace)（CoreGraphics 颜色空间文档）。

iOS 照片成品的三层元数据（`fileDataRepresentation()` 内嵌）：

1. **EXIF/TIFF**：快门、ISO、焦距、方向、时间——`AVCapturePhoto.metadata` 字典直接可读；
2. **色彩配置**：ICC profile（Display P3 或 sRGB）；`AVCapturePhotoSettings.embedsDepthDataInPhoto` 等附属按需内嵌；
3. **Apple 专项**：Live Photo 配对引用、ProRAW 的 DNG 处理标记（LinearizationTable/Orientation 等 DNG 标签）。

颜色空间速查：

| 名称 | 用途 | 代码落点 |
|---|---|---|
| sRGB | 默认交付 | HEIC/JPEG 内嵌 ICC |
| Display P3 | 广色域（iPhone 7+ 全系） | `CGColorSpace(name: .displayP3)`；Core Image `workingColorSpace` |
| HLG / PQ | HDR 视频（Dolby Vision 部分机型） | 视频色彩属性（writer `outputSettings`） |
| Apple Log | 专业调色 | ProRes 配套（见第 3 章 3.5） |

> 与 Android 对照：Android 的广色域与 HDR 交付受 OEM 支持度制约（`REQUEST_AVAILABLE_COLOR_SPACE_P3` 等），iOS 上 Display P3 是全系默认能力——写"广色域直出"类功能，iOS 是低阻力路线。

## 5.4 迁移检查清单（Android → iOS 写码前过一遍）

1. 逐帧元数据习惯 → 换成"设备属性 + KVO 观察"；
2. `CameraCharacteristics` 全量能力表 → 拆到 `Format` 与各 output 的 `isXxxSupported`；
3. 每帧改参数（如变焦推拉）→ 设备属性写入（可 `ramp` 动画）或 MultiCam 路线；
4. ImageReader 拿 JPEG → photoOutput delegate 拿成品 `AVCapturePhoto`；
5. 逐帧 YUV 处理 → VideoDataOutput（CVPixelBuffer），格式名从 ImageFormat 换成 kCVPixelFormatType；
6. 时长/大小限制的录制 → maxRecordedDuration/Size（无需自实现）；
7. 权限被拒的可重试路径 → 引导设置页（iOS 无二次运行时弹窗）。
