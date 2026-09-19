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
