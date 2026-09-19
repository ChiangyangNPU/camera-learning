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
