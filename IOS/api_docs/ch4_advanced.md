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
