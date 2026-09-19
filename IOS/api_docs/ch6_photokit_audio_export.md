# 第 6 章 PhotoKit、音频会话与导出接口速查

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 补齐"拍照之后"的接口面：照片库（PhotoKit）、音频会话（AVAudioSession）与导出编辑三件套。机制讲解见《学习文档》第 7~9 章。出处：[PhotoKit](https://developer.apple.com/documentation/photokit)、[AVFAudio](https://developer.apple.com/documentation/avfaudio)、[AVAssetExportSession](https://developer.apple.com/documentation/avfoundation/avassetexportsession)。

## 6.1 PhotoKit 接口表

**授权与查询**：

| 接口 | 说明 | Android 对应 |
|---|---|---|
| `PHPhotoLibrary.requestAuthorization(for: .addOnly/.readWrite)` | 分级授权申请 | READ_MEDIA_IMAGES / WRITE |
| `PHPhotoLibrary.authorizationStatus(for:)` | 状态查询（含 `.limited`） | READ_MEDIA_VISUAL_USER_SELECTED |
| `PHAsset.fetchAssets(with:options:)` | 惰性查询返回 `PHFetchResult` | MediaStore query |
| `PHAsset.mediaType` / `creationDate` / `location` | 条目属性 | MediaStore 列 |
| `PHPhotoLibraryChangeObserver` | 增量变更回调 | ContentObserver |
| `PHAssetResource.assetResources(for:)` | 原始文件级资源（原图/配对视频/gain map 等） | 无直接对应（ContentResolver 文件流） |

**保存**：

```swift
try await PHPhotoLibrary.shared().performChanges {
    let req = PHAssetCreationRequest.forAsset()
    req.addResource(with: .photo, data: imageData, options: nil)
    req.creationDate = Date(); req.location = loc
}
```

| 成员 | 说明 |
|---|---|
| `PHAssetCreationRequest.forAsset()` | 新建条目（相册归属另用 `PHAssetCollectionChangeRequest`） |
| `addResource(with: .photo/.video/.pairedImage/.pairedVideo, data/fileURL:options:)` | 各类型资源写入（Live Photo 必须成对） |
| `PHAssetResourceCreationOptions.originalFilename` | 原始文件名保留 |

**读取**：

| 接口 | 说明 |
|---|---|
| `PHImageManager.requestImage(for:targetSize:contentMode:options:)` | 显示级（含 iCloud 网络下载选项） |
| `requestImageDataAndOrientation(for:options:)` | 完整原始数据 + 方向（HDR/gain map 随原始数据交付） |
| `PHAssetResourceManager.requestData(...)` | 按资源提取原始文件（`isNetworkAccessAllowed` 处理 iCloud） |
| `PHContentEditingInput/Output` | 编辑闭环（原图 + 渲染结果回写） |

**PHPickerViewController**：

| 成员 | 说明 |
|---|---|
| `PHPickerConfiguration(filter: .images/.videos/.livePhotos, selectionLimit:)` | 0 = 多选；**无需任何权限** |
| `PHPickerViewControllerDelegate.picker(_:didFinishPicking:)` | 返回 `NSItemProvider` 数组 |
| `NSItemProvider.loadDataRepresentation(for: UTType.image.identifier)` | 拿二进制（dataRepresentation） |

## 6.2 AVAudioSession 接口表

| 接口 | 说明 | Android 对应 |
|---|---|---|
| `AVAudioSession.sharedInstance()` | 进程单例 | AudioManager（部分） |
| `setCategory(_:mode:options:)` | `.playAndRecord` + `.videoRecording` + `[.defaultToSpeaker, .allowBluetooth]` 为录视频标准配置 | setMode + setSpeakerphoneOn |
| `setActive(_:options:)` | 激活/归还（`.notifyOthersOnDeactivation`） | requestAudioFocus / abandon |
| `currentRoute` / `routeChangeNotification` | 设备路由与切换监听 | AudioDeviceCallback |
| `interruptionNotification` | 打断（began/ended + shouldResume） | AudioFocus loss |
| `overrideOutputAudioPort(.speaker)` | 输出强制扬声器 | setSpeakerphoneOn(true) |
| `isOtherAudioPlaying` | 其他 App 在放音（录制前判断） | getStreamVolume 组合判断 |

## 6.3 导出与编辑接口表

| 接口 | 说明 | Android 对应 |
|---|---|---|
| `AVAssetExportSession(asset:presetName:)` | 声明式转码（`.presetHighestQuality`、`.presetHEVCHighestQuality`、`.passthrough`） | Media3 Transformer |
| `export(to:as:)` /（async 版本随新 SDK） | 执行导出 + 状态回调 | export() |
| `AVMutableComposition()` / `insertTimeRange(_:of:at:)` | 时间线拼接（引用不复制） | Transformer 序列 |
| `AVMutableVideoComposition(propertiesOf:)` | 视频合成（含 `AVVideoCompositionCoreAnimationTool`/自定义 compositor） | Effect/Overlay |
| `AVAssetImageGenerator(asset:)` + `appliesPreferredTrackTransform` | 指定时间出帧（封面） | MediaMetadataRetriever |
| `AVAssetWriter.metadata` / `AVMetadataItem` | 视频元数据写入（位置/时间/方向） | MediaMuxer 缺失项，iOS 完整 |
| `AVAssetReader + AVAssetWriter` | 逐帧读改写（精确控制） | MediaExtractor→Codec→Muxer |

导出兼容性检查：`AVAssetExportSession.exportPresets(compatibleWith:)` 先查目标 asset 支持的 preset 列表（HDR/ProRes 素材的可导出集与普通素材不同）。
