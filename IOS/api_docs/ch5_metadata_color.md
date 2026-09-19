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
