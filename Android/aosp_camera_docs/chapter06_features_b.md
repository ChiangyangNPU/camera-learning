# 第 6 章 相机功能（中）

> 本章对应 AOSP 官方文档"相机功能"组的中段五篇：外接 USB 摄像头、高动态范围（HDR）模式、HEIF 图片处理、单色相机、运动追踪。内容面向希望系统学习 Android Camera 框架的工程师，重点讲清"每个功能是什么、解决什么问题、如何在 HAL/元数据层面实现、OEM 需要做什么"。

## 6.1 外接 USB 摄像头

> 来源：[外接 USB 摄像头](https://source.android.google.cn/docs/core/camera/external-usb-cameras?hl=zh-cn)

### 6.1.1 功能定位

Android 平台支持**即插即用（plug-and-play）的 USB 网络摄像头（webcam）**，前提是这些摄像头通过标准的 Android Camera2 API 和相机 HAL 接口接入。网络摄像头通常支持 **USB 视频类（UVC，USB Video Class）** 驱动；在 Linux 内核侧，UVC 摄像头由标准的 **Video4Linux（V4L）** 驱动控制。

它解决的问题是：让设备可以低成本接入通用 USB 摄像头，用于**视频聊天、照片冲印机**等轻量级用例。官方文档明确指出，此功能**不能替代**手机上典型的内置相机 HAL，也不是为高分辨率高速流式传输、AR、手动 ISP/传感器控制等性能敏感的复杂任务设计的。

### 6.1.2 架构与实现

- USB 相机 HAL 进程是**外接摄像头提供程序（external camera provider）**的一部分，该提供程序监听 USB 设备的可用性并相应地枚举外接摄像头设备；其权限和 SE 策略与内置相机 HAL 进程类似。
- 参考实现位于 `ExternalCameraProvider`，外接摄像头设备与会话实现分别在 `ExternalCameraDevice` 和 `ExternalCameraDeviceSession` 中。
- 从 **API 级别 28** 开始，Java 客户端 API 引入了 `EXTERNAL` 硬件级别（hardware level）。
- 第三方网络相机应用直接访问 UVC 设备时所需的相机权限，与所有常规相机应用相同。

### 6.1.3 对 OEM 的要求

1. 系统必须支持 `android.hardware.usb.host` 系统功能。
2. 内核必须启用 UVC 支持，在对应的 `defconfig` 中添加：
   - `CONFIG_USB_VIDEO_CLASS=y`
   - `CONFIG_MEDIA_USB_SUPPORT=y`
3. 在设备 build 中启用外接摄像头提供程序：
   - 在 `device.mk` 中加入 `android.hardware.camera.provider-V1-external-service`，并复制 `external_camera_config.xml` 到 vendor 分区；
   - 在 Treble HAL 清单中为 `android.hardware.camera.provider`（AIDL）添加 `external/0` 实例；
   - 若设备运行在 Treble 直通（passthrough）模式，需更新 `sepolicy`，允许 `cameraserver` 访问 `device`、`video_device` 目录与字符设备。

### 6.1.4 external_camera_config.xml 关键配置

| 配置项 | 含义 |
| --- | --- |
| `Provider/ignore/id` | 需要被外接相机 HAL 忽略的内部视频节点编号 |
| `MaxJpegBufferSize` | JPEG 缓冲区最大字节数（示例为 3MB，约等于 1080p YUV420） |
| `NumVideoBuffers` | 流式传输 ≥ 30fps 时 v4l2 缓冲队列长度（越大请求缓存越多、越流畅，但内存占用更高） |
| `NumStillBuffers` | 流式传输 < 30fps 时 v4l2 缓冲队列长度 |
| `FpsList/Limit` | 各输出尺寸的最大帧率上限：尺寸小于某行 `width/height` 的图片可上报最高 `fpsBound`；宽高需递增、fpsBound 需递减；超过最后一行的尺寸不受支持 |

示例中的帧率上限：640x480 → 30fps，1280x720 → 15fps，1920x1080 → 10fps。

### 6.1.5 自定义与优化

- **常规自定义**（改 `external_camera_config.xml`）：排除内部摄像头的视频节点、支持的图片尺寸与帧率上限、inflight 缓冲区数量（流畅度与内存的权衡）。
- **设备专用优化**：
  - 缓冲区复制/缩放及 JPEG 编解码：通用实现用 CPU（libyuv/libjpeg），可替换为设备专用加速；
  - HAL 输出格式：通用实现视频 `IMPLEMENTATION_DEFINED` 缓冲区用 `YUV_420_888`、其余用 `YUV12`，可替换为设备高效格式并支持更多格式。

### 6.1.6 验证与限制

- 必须通过相机 CTS；整个测试期间外接 USB 摄像头必须始终插在设备上，否则部分用例会失败。
- 注意：`media_profiles` 条目不适用于外接 USB 网络摄像头，因此**没有 camcorder（摄像机）配置文件**。

### 6.1.7 关键术语

| 中文 | 英文 |
| --- | --- |
| 外接 USB 摄像头 | External USB camera |
| USB 视频类 | UVC (USB Video Class) |
| 视频4Linux 驱动 | V4L (Video4Linux) |
| 外接摄像头提供程序 | External camera provider |
| 外接硬件级别 | EXTERNAL hardware level |
| HAL 清单 | Treble HAL manifest |

## 6.2 高动态范围（HDR）模式

> 来源：[高动态范围（HDR）模式](https://source.android.google.cn/docs/core/camera/hdr-modes?hl=zh-cn)

### 6.2.1 功能定位

Camera2 API 中提供了多种**高动态范围（HDR，High Dynamic Range）**拍摄形式。HDR 解决的核心问题是：普通 8 位成像在明暗对比强烈（高对比度）场景下会丢失高光或阴影细节。本页将 HDR 分为两大类：**HDR 静态拍摄**与 **HDR 视频录制**。

### 6.2.2 HDR 静态拍摄

HDR 静态拍摄封装了各种用于**改善移动相机动态范围**的算法。按 Android 版本分为两条技术路线：

| 路线 | 适用版本 | 说明 |
| --- | --- | --- |
| 10 位相机输出 | Android 13 及以上 | 通过 10 位相机输出 `capability` 与 HDR 动态范围配置文件实现，生成真实 10 位像素格式和对应 10 位传递函数（transfer function）的帧；仅支持扩展后的物理位深 |
| 多帧融合 | Android 12 及以下 | 捕获多个不同曝光的帧并融合各图片生成最终 HDR 结果，有时会压缩到标准 8 位动态范围 |

Android 13+ 的 10 位相机输出（使用 HDR 动态范围配置文件 `DynamicRangeProfiles` 类配置）可与 HDR 场景模式结合，支持：

- 使用 **P010** 像素格式的 10 位未压缩静态拍摄；
- 按 **Ultra HDR** 规范、使用 **`JPEG_R`** 像素格式的 HDR 压缩静态拍摄。

Android 12 及以下的多帧融合 HDR 有两种方法：

1. **HDR 场景模式（HDR scene mode）**：在**相机 HAL 层**实现。如果受支持，相机客户端可在常规相机拍摄请求中设置此模式（即 `android.control.sceneMode` 中的 HDR 模式）。
2. **HDR 扩展（Camera Extensions）**：建议用于高对比度场景；可使用功能受限的拍摄会话（与常规拍摄会话相比）。在同一设备上，相机扩展程序生成的图片质量可能高于常规拍摄请求。

### 6.2.3 HDR 视频录制

与 HDR 静态拍摄相比，**HDR 视频**仅指 HDR 视频拍摄，即 **10 位视频录制**（同样基于 10 位相机输出 / `DynamicRangeProfiles` 机制）。相关细节可延伸阅读"10 位相机输出"与"Ultra HDR"章节。

### 6.2.4 对工程师的意义

- 学习时应区分三个层次：HAL 内实现的 HDR 场景模式（对客户端透明、一次请求生效）、框架/应用层的多帧融合扩展（Camera Extensions，质量可超出常规请求）、以及 Android 13 起原生 10 位管线（P010 / JPEG_R + 传递函数）。
- OEM 若宣称支持 HDR，需要明确自己走的是哪条路线，并在静态元数据（capability、scene mode、`DynamicRangeProfiles`）中如实通告。

### 6.2.5 关键术语

| 中文 | 英文 |
| --- | --- |
| 高动态范围 | HDR (High Dynamic Range) |
| HDR 场景模式 | HDR scene mode |
| 相机扩展程序 | Camera Extensions |
| HDR 动态范围配置文件 | DynamicRangeProfiles |
| 传递函数 | Transfer function |
| 10 位相机输出 | 10-bit camera output |
| P010 像素格式 | P010 pixel format |
| HDR 压缩静态拍摄 | JPEG_R (HDR compressed still capture) |
| 多帧融合 | Multi-frame fusion |

## 6.3 HEIF 图片处理

> 来源：[HEIF 图片处理](https://source.android.google.cn/docs/core/camera/heif?hl=zh-cn)

### 6.3.1 功能定位

搭载 **Android 10** 的设备支持 **HEIC** 压缩图片格式——它是 ISO/IEC 23008-12 规定的**高效图片文件格式（HEIF，High Efficiency Image File Format）**的 HEVC（高效视频编码）特定品牌。相比 JPEG，HEIC 图片"质量更好且文件更小"，解决的是静态图片压缩效率不足的问题。

### 6.3.2 生成流程

HEIC 图片由相机框架生成：向相机 HAL 请求**未压缩图片**，然后送入媒体子系统，由 HEIC 或 HEVC 编码器编码。因此它不是由相机 HAL 直接输出 HEIC 文件，而是"HAL 出原始帧 + 媒体编码器出 HEIC"的协作产物。

### 6.3.3 硬件前提

设备必须拥有支持以下之一的硬件编码器：

- `MIMETYPE_IMAGE_ANDROID_HEIC`，或
- `MIMETYPE_VIDEO_HEVC` 且具有**恒定质量模式**（`BITRATE_MODE_CQ`）。

### 6.3.4 实现方式

**媒体（编码器）侧**：

- HEVC 类型编解码器：使用带 `GRALLOC_USAGE_HW_VIDEO_ENCODER` 用法的 IMPLEMENTATION_DEFINED 格式或 `HAL_PIXEL_FORMAT_YCBCR_420_888` 格式（取决于图片大小）；
- HEIC 类型编解码器：使用带 `GRALLOC_USAGE_HW_IMAGE_ENCODER` 用法的 IMPLEMENTATION_DEFINED 格式。

**相机 HAL 侧**：

- 静态元数据：`ANDROID_HEIC_INFO_SUPPORTED = true`；`ANDROID_HEIC_INFO_MAX_JPEG_APP_SEGMENTS_COUNT` 取 [1, 16] 区间内的值。
- 流组合：对每个必要的流组合（mandatory stream combinations），设备必须支持用**相同大小的 HEIC 流替换 JPEG 流**。
- 对公共 API 上的 HEIC 输出流（`ImageFormat.HEIC`），相机服务会创建**两个 HAL 内部流**：
  1. 带 `JPEG_APPS_SEGMENT` 使用标志的 BLOB 流，存储应用细分（app segments，含 EXIF 与缩略图细分）；
  2. 根据目标编解码器（IMPLEMENTATION_DEFINED 或 YCBCR_420_888）与 HEIC 流大小确定的流。
- 框架根据 `ANDROID_HEIC_INFO_MAX_JPEG_APP_SEGMENTS_COUNT` 为 HAL 分配足够大的缓冲区以填充 JPEG 应用细分；**APP1 细分为必填**，APP2 及以上为可选。
- 框架可覆盖 APP1 中的 EXIF 标记（可派生自捕获结果元数据或与主图比特流相关），并发送至 **MediaMuxer**。

**方向（orientation）规则**：媒体编码器将方向嵌入输出图片的元数据，以保证主图与缩略图方向一致；因此**相机 HAL 不得根据 `android.jpeg.orientation` 旋转缩略图**；框架将方向写入 EXIF 元数据和 HEIC 容器。

**元数据复用**：与 JPEG 相关的静态、控制和动态元数据同样适用于 HEIC——例如捕获请求中的 `android.jpeg.orientation` 和 `android.jpeg.quality` 同样控制 HEIC 的方向和质量。

### 6.3.5 限制事项

- **无法同时配置 JPEG 和 HEIC 流**；
- HEIC 流替换 JPEG 流要求大小一致；
- HAL 不得自行旋转缩略图；应用细分数量上限为 16（APP1 必填）。

### 6.3.6 验证

- 使用 TestingCamera2 测试应用；
- CTS：`NativeImageReaderTest#testHeic`、`ImageReaderTest#testHeic`、`ImageReaderTest#testRepeatingHeic`、`ReprocessCaptureTest#testBasicYuvToHeicReprocessing`、`ReprocessCaptureTest#testBasicOpaqueToHeicReprocessing`、`RobustnessTest#testMandatoryOutputCombinations`、`StillCaptureTest#testHeicExif`；
- VTS：`VtsHalCameraProviderV2_4TargetTest.cpp`。

### 6.3.7 关键术语

| 中文 | 英文 |
| --- | --- |
| 高效图片文件格式 | HEIF (High Efficiency Image File Format) |
| HEIF 的 HEVC 特定品牌 | HEIC |
| 高效视频编码 | HEVC (High Efficiency Video Coding) |
| 恒定质量模式 | Constant Quality (CQ) mode |
| 应用细分 | JPEG app segments (APP1/APP2...) |
| 媒体复用器 | MediaMuxer |

## 6.4 单色相机

> 来源：[单色相机](https://source.android.google.cn/docs/core/camera/monochrome?hl=zh-cn)

### 6.4.1 功能定位

自 **Android 9** 起，设备可以支持**单色相机（monochrome camera）**。**Android 10** 进一步增加了：

- **Y8** 流格式支持（相比 YUV_420_888 每像素 1.5 字节，Y8 每像素仅 1 字节，**减少内存使用**）；
- 单色和**近红外（NIR，Near-Infrared）色彩滤波阵列（CFA）**静态元数据；
- 单色摄像头的 `DngCreator`（DNG 文件生成）支持。

它解决的问题是：让 OEM 能实现真正的单色或 NIR 摄像头设备（去掉色彩滤波阵列的传感器，感光效率更高、低光噪声更好），并可作为**逻辑多摄像头（logical multi-camera）**设备中的物理摄像头参与变焦/低光融合。

### 6.4.2 硬件要求

设备必须配备单色摄像头传感器，以及处理传感器输出的**图像信号处理器（ISP）**。

### 6.4.3 HAL 元数据实现要求

要将相机设备播发为单色相机，HAL 须满足：

1. `android.sensor.info.colorFilterArray` 设为 **MONO** 或 **NIR**；
2. 支持 BACKWARD_COMPATIBLE 必需键，**不支持** MANUAL_POST_PROCESSING；
3. `android.control.awbAvailableModes` 只包含 AUTO，且 `android.control.awbState` 为 CONVERTED 或 LOCKED（取决于 `android.control.awbLock`）；
4. `android.colorCorrection.mode`、`android.colorCorrection.transform`、`android.colorCorrection.gains` 不在可用请求和结果键中 → 因此单色相机设备最高只能是 **LIMITED** 硬件级别；
5. 不得存在以下颜色相关静态元数据键：`android.sensor.referenceIlluminant*`、`android.sensor.calibrationTransform*`、`android.sensor.colorTransform*`、`android.sensor.forwardMatrix*`、`android.sensor.neutralColorPoint`、`android.sensor.greenSplit`；
6. 以下键所有颜色通道的值必须相同：`android.sensor.blackLevelPattern`、`android.sensor.dynamicBlackLevel`、`android.statistics.lensShadingMap`、`android.tonemap.curve`；
7. `android.sensor.noiseProfile` 只有一个颜色通道；
8. 支持 Y8 的单色设备：HAL 必须支持在强制性流组合中（含重新处理 reprocessing）**以 Y8 替代 YUV_420_888** 格式。

### 6.4.4 公共 API

| API | 说明 |
| --- | --- |
| `ImageFormat.Y8` | Y8 图片格式（Android 10） |
| `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO` | 单色 CFA 通告 |
| `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR` | 近红外 CFA 通告（Android 10） |
| `REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME` | 单色相机能力（Android 9 引入） |

### 6.4.5 验证与限制

- CTS：`testMonochromeCharacteristics`、`CaptureRequestTest`、`CaptureResultTest`、`StillCaptureTest`、`DngCreatorTest`；VTS：`getCameraCharacteristics`、`processMultiCaptureRequestPreview`。
- 限制小结：最高 LIMITED 硬件级别、无 MANUAL_POST_PROCESSING、无颜色校正/色温类元数据键、AWB 仅 AUTO。

### 6.4.6 关键术语

| 中文 | 英文 |
| --- | --- |
| 单色相机 | Monochrome camera |
| 近红外 | NIR (Near-Infrared) |
| 色彩滤波阵列 | CFA (Color Filter Array) |
| 图像信号处理器 | ISP |
| 逻辑多摄像头 | Logical multi-camera |
| 重新处理 | Reprocessing |
| 强制性流组合 | Mandatory stream combinations |

## 6.5 运动追踪

> 来源：[运动追踪](https://source.android.google.cn/docs/core/camera/motion-tracking?hl=zh-cn)

### 6.5.1 功能定位

自 **Android 9** 起，相机设备可以通告**运动追踪（Motion Tracking）**功能。需要特别理解其定位：**相机本身不生成运动追踪数据**，而是作为数据源供下游使用方进行场景分析，典型使用方包括：

- **ARCore**（增强现实）；
- **图像稳定算法**；
- 与**其他传感器**（如 IMU）配合使用。

换言之，相机在此角色中提供的是"带精确几何标定、短曝光"的视频流，追踪运算由上层完成。

### 6.5.2 关键行为约束

当捕获请求包含 motion tracking 捕获意图（capture intent）时，相机必须**将曝光时间限制在 20 毫秒以内**，以减少运动模糊——这是该功能最明确的一条行为级限制，保证 AR 场景下帧间匹配的质量。

### 6.5.3 OEM 实现要求

1. **能力通告**：启用 `ANDROID_REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING`；
2. **捕获意图支持**：支持 `ANDROID_CONTROL_CAPTURE_INTENT_MOTION_TRACKING`（对应 API 层的 `CONTROL_CAPTURE_INTENT_MOTION_TRACKING`），且该 intent 出现在捕获请求中时将曝光时间限制为 ≤ 20ms；
3. **镜头校准数据**：必须在静态信息和逐帧（动态）元数据字段中准确报告：
   - `ANDROID_LENS_POSE_ROTATION`（镜头姿态旋转）
   - `ANDROID_LENS_POSE_TRANSLATION`（镜头姿态平移）
   - `ANDROID_LENS_INTRINSIC_CALIBRATION`（镜头内参校准）
   - `ANDROID_LENS_RADIAL_DISTORTION`（镜头径向畸变）
   - `ANDROID_LENS_POSE_REFERENCE`（镜头姿态参考）

这些标定字段使相机帧可以与世界坐标系及其他传感器数据对齐，是 AR/图像稳定算法正确工作的前提。文档未要求使用 vendor tag——校准数据全部通过**标准 Camera 元数据字段**报告。

### 6.5.4 参考实现与验证

- HAL 参考实现：高通相机 HAL `QCamera3HWI.cpp`（msm8998，`hardware/qcom/camera` 仓库）；
- 元数据定义：`hardware/interfaces/camera/metadata/` 下 `types.hal`（3.2 与 3.3 版本）；
- 支持此功能的相机设备必须通过**相机 CTS** 测试。

### 6.5.5 关键术语

| 中文 | 英文 |
| --- | --- |
| 运动追踪 | Motion Tracking |
| 捕获意图 | Capture Intent |
| 曝光时间 | Exposure time |
| 镜头姿态旋转 / 平移 | Lens Pose Rotation / Translation |
| 镜头内参校准 | Lens Intrinsic Calibration |
| 镜头径向畸变 | Lens Radial Distortion |
| 镜头姿态参考 | Lens Pose Reference |

---

> 学习提示：本章五个功能的共同脉络是"能力通告（capability/静态元数据）+ 行为约束（控制/动态元数据）+ CTS/VTS 验证"。建议结合第 3 章（相机 HAL 与元数据）与"相机功能（上/下）"章节对照阅读，理解框架如何通过元数据契约把 OEM 能力暴露给应用层。
