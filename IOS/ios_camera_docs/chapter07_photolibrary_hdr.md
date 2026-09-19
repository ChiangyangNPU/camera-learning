# 第 7 章 照片库与 HDR 交付闭环（PhotoKit / Adaptive HDR）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 本章补齐"拍照之后"的半条链路：照片存进哪里、怎么读回来、隐私授权怎么管，以及 HDR 照片（Adaptive HDR / Gain Map）的交付形态。对应 Android 的 MediaStore/MediaProvider 与 Ultra HDR 专题。出处：[PhotoKit](https://developer.apple.com/documentation/photokit)、[PHPickerViewController](https://developer.apple.com/documentation/photokit/phpickerviewcontroller)、WWDC24 HDR 图像 session（见 7.5）。

## 7.1 PhotoKit 对象模型

> 出处：[PHAsset](https://developer.apple.com/documentation/photokit/phasset)、[PHAssetCollection](https://developer.apple.com/documentation/photokit/phassetcollection)。

| 概念 | PhotoKit | Android 对应 |
|---|---|---|
| 媒体条目 | `PHAsset`（photo/video/live photo 统一） | MediaStore 的 Images/Video 表行 |
| 容器 | `PHAssetCollection`（相册/时刻）+ `PHFetchResult` 惰性集合 | MediaStore bucket/album |
| 查询 | `PHFetchOptions`（谓词+排序） | MediaStore query |
| 内容读取 | `PHImageManager` / `PHAssetResource`（原始文件级访问） | ContentResolver.openInputStream |
| 变更观察 | `PHPhotoLibraryChangeObserver`（细粒度增量变更） | ContentObserver |

与 MediaStore 最大的体感差异：PhotoKit 是**授权后全库统一访问**（授权即读全库或受限子集），Android 13+ 的 Photo Picker 与部分授权模式在 iOS 的对应物是 PHPicker 与 limited library（7.3/7.4）。

## 7.2 保存与读取闭环

> 出处：[Saving created or edited assets](https://developer.apple.com/documentation/photokit/saving_created_or_edited_assets)。

保存（异步事务 + 变更请求）：

```swift
PHPhotoLibrary.shared().performChanges {
    let request = PHAssetCreationRequest.forAsset()
    request.addResource(with: .photo, data: imageData, options: nil)
    // 视频: .video(data/fileURL)；Live Photo: .pairedImage + .pairedVideo 成对
} completionHandler: { success, error in /* ... */ }
```

要点：

- `PHAssetCreationRequest` 支持直接写入**原始文件**（HEIC/DNG/MOV）并附位置、拍摄时间等元数据（`creationRequest?.location = CLLocation(...)`）——RAW/ProRAW App 的标准入库路径，等价 Android 上 MediaStore 的 RELATIVE_PATH + IS_PENDING 流程但更直接；
- 读取原始数据用 `PHAssetResource`（比 `PHImageManager` 更底层，能拿到入库时的原文件与配对资源），iCloud 未下载的资产需允许网络（`PHAssetResourceManagerRequestOptions.isNetworkAccessAllowed`）；
- 显示级读取用 `PHImageManager.requestImage/DataAndOrientation`，处理 iCloud 按需下载（networkAccessAllowed + progress handler）。

## 7.3 权限：addOnly / readWrite / limited

> 出处：[Requesting authorization to access photos](https://developer.apple.com/documentation/photokit/requesting_authorization_to_access_photos)。

| 授权级别 | 场景 | Android 对应 |
|---|---|---|
| `.addOnly` | 只写不读（相机直存类 App） | READ/WRITE_EXTERNAL_STORAGE 分离的写入侧 |
| `.readWrite` | 全功能 | READ_MEDIA_IMAGES/VIDEO |
| limited（受限选择） | 用户只授权部分照片 | Android 14+ 的部分授权/Photo Picker 选中集 |

iOS 14 起的 limited 模式是**系统级 UI**：用户在授权弹窗选"选择照片…"后，App 只能访问选中集；App 应监听 `PHPhotoLibrary` 的授权变更并引导用户调整（系统会在状态栏给持续提示）。Info.plist 需 `NSPhotoLibraryUsageDescription`（读）与 `NSPhotoLibraryAddUsageDescription`（仅添加，addOnly 路径用）。

与相机 App 的组合：拍照 → 只存不读的 App 用 `.addOnly`（体验最轻）；要相册内选图再编辑的 App，优先考虑 PHPicker 而不是申请读权限。

## 7.4 PHPickerViewController：无需授权的选择器

> 出处：[PHPickerViewController](https://developer.apple.com/documentation/photokit/phpickerviewcontroller)。

系统托管的全库选择器（iOS 14+），**App 不获得任何照片库权限**，只拿到用户勾选的条目（`NSItemProvider` 形式交付，可请求 image/data 表示）：

- 配置：`PHPickerConfiguration(photoLibrary: nil)` + `selectionLimit`（1 为单选，0 为多选）+ `filter`（.images/.videos/.livePhotos）；
- 交付：delegate `picker(_:didFinishPicking:)` → `NSItemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier)`；
- 优势：零权限 + 系统级多选/搜索体验；局限：拿不到 PHAsset 引用（无法做"全库浏览/同步"类功能）——与 Android Photo Picker 的定位完全同构。

## 7.5 HDR 照片体系：Adaptive HDR 与 Gain Map

> 出处：WWDC24 [Use HDR for dynamic image experiences in your app](https://developer.apple.com/videos/play/wwdc2024/10603)、ImageIO/CGImageDestination 的 gain map 支持（文档站 HDR 图像交付主题）。

iPhone 15 起，系统相机默认输出 **HDR 照片**；iOS 18 将其官方化为 **Adaptive HDR** 概念，并给第三方完整的读写 API。理解三个层：

1. **载体**：单文件 HEIC/JPEG 内嵌 **Gain Map**（增益图）——主图是 SDR 显示域，增益图记录 HDR 亮度比，HDR 显示器上按增益图重建高亮；**不是** Android Ultra HDR 的 JPEG_R 双文件思路，但语义同构（Apple 官方在 WWDC 中亦说明其格式遵循 ISO 21496-1 gain map 标准）；
2. **Adaptive HDR**：同一文件同时携带 SDR + HDR 表示，随显示能力自适应——SDR 屏、HDR 屏、分享到不支持的平台都各自取合适版本；
3. **管线入口**：捕获侧（`AVCapturePhotoOutput` 的 HDR 交付随版本开放，具体能力查询以官方文档为准）、写入侧（`CGImageDestination` 支持 gain map 属性）、读取侧（ImageIO 解码出主图 + gain map）、显示侧（UIKit/NSImage 自动按显示器能力渲染 HDR）。

与 Android 的对照（对齐 Android 系列的 Ultra HDR 专题）：

| | iOS（Adaptive HDR） | Android（Ultra HDR / JPEG_R） |
|---|---|---|
| 载体 | HEIC/JPEG + ISO 21496-1 gain map（单文件） | JPEG_R（JPEG + XMP `#gainmap`） |
| 系统相机默认 | iPhone 15 起默认 HDR 照片 | 逐 OEM 推进 |
| 第三方读写 | ImageIO/CGImageDestination 官方 API（iOS 18 完整化） | Gainmap 相关 API（API 34+，OEM 支持度分化） |
| 显示自适应 | 系统级（SDR/HDR 自动） | 系统级 |

工程要点：自研拍照 App 想输出 HDR 照片，iOS 18+ 的推荐路径是 **gain map 交付 + Adaptive HDR**（而非全 HDR-HEIC，兼容性差）；编辑 App 注意——**裁剪/滤镜若走普通解码重编码会丢 gain map**，需用 ImageIO 的 gain map 感知 API 全链路透传，这与 Android 上 Ultra HDR 图被误处理的坑完全同型。

## 7.6 本章小结

拍照 App 的完整闭环 = 捕获（第 4 章）→ 处理（图像管线文档第 3 章）→ **交付与库（本章）**。iOS 的闭环特点是：授权模型细（addOnly/limited）、选择器零权限化（PHPicker）、HDR 交付官方化（Adaptive HDR gain map）——三条都比 Android 的对应机制更早、更系统化。
