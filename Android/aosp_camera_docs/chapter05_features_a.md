# 第 5 章 相机功能（上）

> 本章内容整理自 AOSP 官方文档（中文版）"相机功能"组页面的上半部分，面向希望学习 Android Camera 框架的工程师。涵盖：10 位相机输出、相机焦外成像（Bokeh）、并发相机流式传输、相机扩展（CameraX/Camera2 Vendor Extensions）、相机扩展验证工具、相机预览防抖共 6 个功能主题。

## 5.1 10 位相机输出

> 来源：[10 位相机输出](https://source.android.google.cn/docs/core/camera/10-bit-camera-output?hl=zh-cn)

### 5.1.1 功能概述

Android 13 及更高版本通过**动态范围配置文件（Dynamic Range Profiles）**支持 10 位相机输出。相机客户端在配置输出流时指定所需的动态范围配置文件，从而让第三方应用（Camera2 API 客户端）能够录制接近原生相机应用效果的 HDR 10 位视频——更亮的高光、更大的动态范围。

支持的配置文件包括：

- **HLG10**（HLG 10-bit，**强制支持**）
- HDR 10
- HDR 10+
- 杜比视界（Dolby Vision）

### 5.1.2 客户端 API 与使用流程

1. **发现**：调用 `getSupportedProfiles()` 获取 `DynamicRangeProfiles` 实例，其中包含设备支持的配置文件及对应的拍摄请求限制条件。
2. **推荐配置**：框架通过 `REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE` 字段返回推荐的动态范围配置文件。
3. **配置输出流**：调用 `OutputConfiguration.setDynamicRangeProfile(long)` 设置输出流组合。强制性的输出流组合见 `CameraDevice` 文档中"常规拍摄（regular capture）"的"10 位输出的其他有保证配置"表。

### 5.1.3 实现（相机 AIDL HAL / 元数据）

设备制造商需要完成如下相机 AIDL HAL 集成：

1. 在相机功能中添加 `ANDROID_REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT`。
2. 用所有支持的动态范围配置文件及其限制条件的位图填充 `ANDROID_REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP`；其中 **HLG10 必须支持**，且必须添加一个推荐的动态范围配置文件。
3. 确保在输出流配置期间，采用 **P010** 格式（`ImageFormat.YCBCR_P010`）或实现定义格式（`ImageFormat.PRIVATE`）的输出流支持该动态范围配置文件值。
4. 根据动态范围配置文件，在通知相机服务之前，为已处理的 **Gralloc 4** 缓冲区设置静态或动态元数据缓冲区。

相关元数据定义（`metadata_definitions.xml`）：

| 元数据/标签 | 说明 |
| --- | --- |
| `DYNAMIC_RANGE_TEN_BIT` | 10 位输出能力定义 |
| `availableDynamicRangeProfilesMap` | 支持的动态范围配置文件位图 |
| `recommendedTenBitDynamicRangeProfile` | 推荐的 10 位动态范围配置文件 |
| `10BIT_OUTPUT` | 10 位输出相关定义 |

参考实现在 `hardware/google/camera/devices/EmulatedCamera/hwl`（Cuttlefish 模拟相机）。

### 5.1.4 对 OEM 的要求

- **硬件前提**：设备必须具备 10 位或更高色深的相机传感器，以及相应的 ISP 支持。
- **合规**：需满足 CDD（兼容性定义文档）中"7.5 摄像头"章节的兼容性要求。
- **强制**：HLG10 配置文件必须支持。

### 5.1.5 验证（三个阶段）

**1. 测试 API 功能正确性**

- VTS：`hardware/interfaces/camera/provider/aidl/vts/`，测试基本发现、配置和流式传输，按需检查 HDR 元数据。
- CTS：`cts/tests/camera/src/android/hardware/camera2/cts/`，确认相机行为符合 AOSP API 规范。
- Camera ITS：`cts/apps/CameraITS`，确认使用 HDR 配置文件时常规视频行为一致，具体测试为 `tests/scene4/test_video_aspect_ratio_and_crop.py`。

**2. 比较原生相机与第三方应用**

使用 GitHub 上的 Camera2Video 示例应用。建议场景：

- 中等到弱光场景，含蜡烛或明亮小灯（验证自动曝光和动态范围）。
- 明亮户外场景，色彩鲜艳并有反光物体如铬合金保险杠（验证明亮高光呈现）。
- 普通低动态范围室内场景（验证非极端光照下的行为）。
- 所有场景建议包含人物和人脸，验证曝光、色彩和肤色处理。

**3. 比较 SDR 与 HDR**

视觉验证前提：设备支持 HDR 显示（1000 尼特以上屏幕），且视频应用（如 Google 相册）支持 HDR 播放。关键验证点（数值仅为示例）：

- **弱光场景**：HDR 片段中明亮高光可达屏幕最大亮度（可能高达 1000 尼特），SDR 片段约为 100 尼特，SDR 应显得反差较小、亮度较低。
- **明亮户外**：HDR 片段整体亮度更高（例如高达 800 尼特），高光部位接近屏幕最大亮度。
- **普通 SDR 室内场景**：HDR 和 SDR 色彩、色调相似，HDR 亮度不应低于 SDR；若因微调选择做不到，应确保第三方应用与原生相机行为一致。

### 5.1.6 关键术语

| 中文 | 英文 |
| --- | --- |
| 动态范围配置文件 | Dynamic Range Profile |
| 10 位相机输出 | 10-bit Camera Output |
| HLG10（强制支持） | HLG10 |
| HDR 10 / HDR 10+ / 杜比视界 | HDR10 / HDR10+ / Dolby Vision |
| P010 格式 | `ImageFormat.YCBCR_P010` |
| 实现定义格式 | `ImageFormat.PRIVATE` |
| 相机 AIDL HAL | Camera AIDL HAL |
| Gralloc 4 缓冲区 | Gralloc 4 buffer |
| 推荐的动态范围配置文件 | `REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE` |
| 兼容性定义文档 | CDD (Compatibility Definition Document) |
| 标准动态范围 / 高动态范围 | SDR / HDR |
| 尼特 | Nit（亮度单位） |

## 5.2 相机焦外成像（Bokeh）

> 来源：[相机焦外成像（Bokeh）](https://source.android.google.cn/docs/core/camera/bokeh?hl=zh-cn)

### 5.2.1 功能概述

**焦外成像（Bokeh）**是一种浅景深（shallow depth of field）效果，通过对场景中非聚焦部分进行模糊处理实现。移动设备上的深度信息来源主要有两种：

- **双摄立体视觉（Stereo Vision）**
- **单摄像头双光电二极管（Dual PD, photodiode）**

从 **Android 11** 开始，平台原生支持焦外成像并提供 API，使该功能可被第三方应用调用（此前由各厂商私有实现）。

### 5.2.2 实现（相机 HAL 静态元数据）

需在相机 HAL 中播发（advertise）以下三项静态元数据：

**1. `ANDROID_CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_MAX_SIZES`**

- 格式：三整数元组数组 `{mode, maxWidth, maxHeight}`。
- 除 `{ANDROID_CONTROL_EXTENDED_SCENE_MODE_DISABLED, 0, 0}` 外，HAL 必须列出 `ANDROID_CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE`（静态拍照焦外模式）和/或 `ANDROID_CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS`（连续焦外模式）及其对应的最大流尺寸。

**2. `ANDROID_CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_ZOOM_RATIO_RANGES`**

- 格式：`{minZoomRatio, maxZoomRatio}` 数组，顺序须与上一条元数据对应；`[1.0, 1.0]` 表示不支持缩放。

**3. `ANDROID_CONTROL_AVAILABLE_MODES`** 中填入 `ANDROID_CONTROL_USE_EXTENDED_SCENE_MODE`。

### 5.2.3 触发方式与会话参数优化

**应用侧触发**：将 `ANDROID_CONTROL_MODE` 设为 `ANDROID_CONTROL_USE_EXTENDED_SCENE_MODE`，并将 `ANDROID_CONTROL_EXTENDED_SCENE_MODE` 设为受支持的扩展取景模式之一。注意：由于需要立体视觉计算，此实现会消耗额外内存。

**会话参数优化（避免重新配置）**：若该模式不能逐帧应用、启用/停用时会出现意外延迟，应将 `ANDROID_CONTROL_EXTENDED_SCENE_MODE` 加入 `ANDROID_REQUEST_AVAILABLE_SESSION_KEYS`，并实现 `ICameraDeviceSession::isReconfigurationRequired()`，避免对无需重新配置的模式重复重配。

### 5.2.4 对 OEM 的要求与验证

| 要求 | 内容 |
| --- | --- |
| 静态元数据 | 播发上述两个 `EXTENDED_SCENE_MODE` 元数据标记及 `USE_EXTENDED_SCENE_MODE` 模式 |
| 会话处理 | 视需要把 `EXTENDED_SCENE_MODE` 声明为会话参数，并实现 `isReconfigurationRequired()` |
| 验证测试 | 通过 `CtsCameraTestCases`、`VtsHalCameraProviderV2_4TargetTest`、CTS 验证程序中的 `CameraBokehTest` |

### 5.2.5 关键术语

| 中文 | 英文 |
| --- | --- |
| 相机焦外成像 | Camera Bokeh |
| 浅景深 | shallow depth of field |
| 立体视觉 | stereo vision |
| 双光电二极管 | Dual PD (photodiode) |
| 扩展取景模式 | Extended Scene Mode |
| 静态拍照焦外 | `BOKEH_STILL_CAPTURE` |
| 连续焦外 | `BOKEH_CONTINUOUS` |
| 缩放比例范围 | zoom ratio range |
| 重新配置 | reconfiguration |

## 5.3 并发相机流式传输

> 来源：[并发相机流式传输](https://source.android.google.cn/docs/core/camera/concurrent-streaming?hl=zh-cn)

### 5.3.1 功能概述

从 **Android 11** 开始，Android 允许设备支持摄像头设备的并发流式传输，例如同时运行前置和后置摄像头。Camera2 API 提供两个查询方法：

- **`getConcurrentCameraIds()`**：获取一组当前连接的摄像头设备标识符组合，这些标识符支持同时配置摄像头设备会话。
- **`isConcurrentSessionConfigurationSupported()`**：检查能否同时配置给定的摄像头设备集合及其相应会话配置。

### 5.3.2 解决的问题：ISP 等硬件资源有限时的分配冲突

**问题示例**：设备有两个 ISP；摄像头 ID 0 是由广角+超广角组成的逻辑摄像头（各占一个 ISP），摄像头 ID 1 占一个 ISP。若单独打开 ID 0，HAL 可能预留两个 ISP，导致前置摄像头（ID 1）无法配置任何数据流。

**解决方案**：

- 相机框架须在配置会话**之前**打开所有摄像头设备（`@3.2::ICameraDevice::open`），向 HAL 提供并发操作提示，以便正确分配资源。
- 并发使用时，应用只能将 `ZOOM_RATIO` 控制设为 **1x 到 MAX_DIGITAL_ZOOM**，而非完整 `ZOOM_RATIO_RANGE`（避免内部切换物理摄像头需要更多 ISP 资源）。

**向后兼容问题**：`MultiViewTest.java#testDualCameraPreview` 允许在 `openCamera` 时出现 `ERROR_MAX_CAMERAS_IN_USE` 失败，第三方应用可能依赖此行为。因此，HAL 在无法支持所有摄像头并行运行的完整流配置时，应使 `openCamera` 失败并返回 `ERROR_MAX_CAMERAS_IN_USE`。

**约束**：通告的摄像头 ID 组合中各 ID 不得冲突。例如通告 `{0,1}`、`{2,1}` 时，0 与 1、1 与 2 不能同时出现冲突组合。

### 5.3.3 实现（HAL 接口）

- 实现 **`ICameraProvider@2.6`** HAL 接口，包含两个方法：
  - `getConcurrentStreamingCameraIds`
  - `isConcurrentStreamCombinationSupported`
- 强制流组合通过摄像头特性属性 **`SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS`** 通告。
- 参考实现：模拟相机 HAL 库 `EmulatedCameraProviderHWLImpl.cpp`。

### 5.3.4 强制并发流组合

若通告支持并发操作，则每个摄像头组合必须支持以下强制性流配置：

| 目标 1 类型 | 目标 1 最大大小 | 目标 2 类型 | 目标 2 最大大小 | 用例示例 |
| --- | --- | --- | --- | --- |
| YUV | s1440p | — | — | 应用内视频或图片处理 |
| PRIV | s1440p | — | — | 应用内取景器分析 |
| JPEG | s1440p | — | — | 无取景器静态图片拍摄 |
| YUV / PRIV | s720p | JPEG | s1440p | 标准静态成像 |
| YUV / PRIV | s720p | YUV / PRIV | s1440p | 应用内视频或使用预览进行视频处理 |

**附加要求**：

- 具有 MONOCHROME 能力（`REQUEST_AVAILABLE_CAPABILITIES` 包含 `REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME`）且支持 Y8 的设备，须在所有保证组合中将 YUV 流替换为 Y8 流。
- 不具备 `ANDROID_REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE` 能力的设备，并发操作期间须至少支持一个 Y16 流（`Dataspace::DEPTH`），分辨率为 sVGA（即给定格式最大输出分辨率与 640x480 中的较小者）。

**分辨率定义**：

- **s720p** = 720p (1280x720) 或 `StreamConfigurationMap.getOutputSizes()` 对该格式返回的最大分辨率。
- **s1440p** = 1440p (1920x1440) 或上述方法返回的最大分辨率。

### 5.3.5 对 OEM 的要求与验证

1. 如通告支持并发操作，须实现 `ICameraProvider@2.6` 的两个查询方法。
2. 通告的每个摄像头组合须满足上述强制性流配置表。
3. 须支持 `SCALER_MANDATORY_CONCURRENT_STREAM_COMBINATIONS` 元数据属性。
4. HAL 须保持向后兼容，在资源不足时通过 `ERROR_MAX_CAMERAS_IN_USE` 使 `openCamera` 失败。
5. 并发模式下缩放范围限制在 1x – MAX_DIGITAL_ZOOM。
6. 使用 CTS 测试 `ConcurrentCameraTest.java` 验证，并使用能同时打开并运行多个摄像头的应用进行实测。

### 5.3.6 关键术语

| 中文 | 英文 |
| --- | --- |
| 并发相机流式传输 | Concurrent camera streaming |
| 摄像头提供程序接口 | ICameraProvider@2.6 HAL |
| 强制并发流组合 | Mandatory concurrent stream combinations |
| 图像信号处理器 | ISP (Image Signal Processor) |
| 逻辑摄像头 | Logical camera |
| 物理摄像头切换 | Internal camera ID switching |
| 最大摄像头占用错误 | `ERROR_MAX_CAMERAS_IN_USE` |
| 缩放比范围 | `ZOOM_RATIO_RANGE` |
| 最大数字变焦 | MAX_DIGITAL_ZOOM |
| 相机会话配置 | Session configuration |
| 向后兼容能力 | BACKWARD_COMPATIBLE capability |
| 单色能力 | MONOCHROME capability |
| 兼容性测试套件 | CTS (Compatibility Test Suite) |

## 5.4 相机扩展（CameraX/Camera2 厂商扩展）

> 来源：[相机扩展（CameraX/Camera2 厂商扩展）](https://source.android.google.cn/docs/core/camera/camerax-vendor-extensions?hl=zh-cn)

### 5.4.1 功能概述

设备制造商（OEM）可以通过 **OEM 供应商库（OEM vendor library）**提供的相机扩展接口，向第三方开发者提供**焦外成像、夜间模式、HDR、自动、脸部照片修复**等扩展效果。开发者可以使用 **Camera2 Extensions API** 和 **CameraX Extensions API** 访问在 OEM 供应商库中实现的扩展（两者的支持扩展列表一致）。扩展接口本身称为 `extensions-interface`。

### 5.4.2 架构

- OEM 供应商库实现 `extensions-interface` 接口。
- CameraX Extensions API 与 Camera2 Extensions API 分别供 CameraX 应用和 Camera2 应用访问供应商扩展。
- 供应商库**不内置在应用中**，而是在运行时由 Camera2/X 从设备上加载：CameraX 通过 `<uses-library>` 声明依赖 `androidx.camera.extensions.impl` 库；Camera2 中由框架加载的扩展服务做同样声明。OEM 库被标记为可选，应用可以在没有该库的设备上正常运行。

![图：相机扩展程序架构（extensions-interface 与 OEM 供应商库）](../images/ch5-extensions-1.png)

### 5.4.3 实现 OEM 供应商库

以 `camera-extensions-stub` 中的文件为基础：

- **基本接口文件（请勿修改）**：`PreviewExtenderImpl`、`ImageCaptureExtenderImpl`、`ExtenderStateListener`、`ProcessorImpl`、`PreviewImageProcessorImpl`、`CaptureProcessorImpl`、`CaptureStageImpl`、`RequestUpdateProcessorImpl`、`ProcessResultImpl`，以及 `advanced/` 包下的 `AdvancedExtenderImpl`、`SessionProcessorImpl`、`RequestProcessorImpl`、各种 `Camera2OutputConfigImpl` 等。
- **强制性实现**：`ExtensionVersionImpl`（版本验证）、`InitializerImpl`（库初始化）。
- **各扩展的扩展器类**（按需实现）：焦外成像 `Bokeh*ExtenderImpl`、夜间模式 `Night*ExtenderImpl`、自动 `Auto*ExtenderImpl`、HDR `Hdr*ExtenderImpl`、脸部照片修复 `Beauty*ExtenderImpl`（含 Preview/ImageCapture/Advanced 三类变体）。
- 不实现某个扩展时，将 `isExtensionAvailable()` 返回 `false` 或移除相应扩展器类，Camera2/X 会向应用报告该扩展不可用。

### 5.4.4 端到端流程（以夜间模式为例）

![图：夜间模式扩展程序实现的端到端流程](../images/ch5-extensions-2.png)

1. **版本验证**：Camera2/X 调用 `ExtensionVersionImpl.checkApiVersion()`，确保 OEM 实现的 `extensions-interface` 版本与 Camera2/X 支持的版本兼容。
2. **供应商库初始化**：`InitializerImpl.init()` 初始化供应商库；在回调 `OnExtensionsInitializedCallback.onSuccess()` 之前，Camera2/X 不会进行其他调用（版本检查除外）。从 `extensions-interface` 1.1.0 起必须实现 `InitializerImpl`；1.0.0 实现会跳过此步骤。
3. **实例化扩展器类**：扩展器分**基本扩展器**和**高级扩展器**两种类型，每种扩展程序须实现其中一种。Camera2/X 可能多次实例化扩展器类，因此不要在构造函数或 `init()` 中做繁重初始化，而应在 `onInit()`（基本）或 `initSession()`（高级）时进行。
4. **检查可用性**：通过 `isExtensionAvailable()` 检查扩展对指定相机 ID 是否可用（基本扩展器要求 Preview 与 ImageCapture 两个扩展器类都返回 `true`）。
5. **用相机信息初始化扩展器**：传入相机 ID 和 `CameraCharacteristics`。
6. **查询信息**：支持的分辨率、预计静态拍摄延迟时间、支持的拍摄请求密钥/结果密钥等。
7. **启用扩展**：基本扩展器通过钩子将 OEM 实现接入 Camera2 管道（注入拍摄请求参数、启用后期处理处理器）；高级扩展器通过 `SessionProcessorImpl` 启用。

**版本兼容性规则**：

- **主要版本不同** → 视为不兼容，停用扩展。
- **向后兼容**：主要版本相同时，Camera2/X 向后兼容旧版供应商库（例如支持 1.3.0 的 Camera2/X 可兼容实现 1.0.0/1.1.0/1.2.0 的库）。
- **向前兼容**：取决于 OEM 自身；若 Camera2/X 版本不满足要求，可返回不兼容版本（如 99.0.0）以停用扩展。

### 5.4.5 基本扩展器与高级扩展器对比

|  | 基本扩展器 | 高级扩展器 |
| --- | --- | --- |
| 数据流配置 | **固定**：预览 `PRIVATE` 或 `YUV_420_888`（有处理器时）；静态拍摄 `JPEG` 或 `YUV_420_888`（有处理器时） | **可由 OEM 自定义** |
| 发送拍摄请求 | 只有 Camera2/X 发送请求，OEM 可为其设置参数；提供处理器时 Camera2/X 可发送多个请求并交给处理器 | OEM 获得 `RequestProcessorImpl` 实例，可自行执行 Camera2 拍摄请求并获取结果和图片；Camera2/X 通过 `startRepeating`/`startCapture` 指示 OEM 发起请求 |
| 相机管道中的钩子 | `onPresetSession`（会话参数）、`onEnableSession`（会话配置后发单个请求）、`onDisableSession`（会话关闭前发单个请求） | `initSession`（返回自定义会话配置）、`onCaptureSessionStart`、`onCaptureSessionEnd` |
| 适用情形 | 在相机 HAL 或处理 YUV 图片的处理器中实现的扩展 | 基于 Camera2 的实现；需要自定义数据流配置（如 RAW 流）；需要交互式拍摄序列 |
| 支持的 API 版本 | Camera2 扩展：Android 13+；CameraX 扩展：`camera-extensions` 1.1.0+ | Camera2 扩展：Android 12L+；CameraX 扩展：1.2.0-alpha03+ |

**基本扩展器的三个应用流程**：

![图：基本扩展器中的应用流程 1——检查扩展可用性](../images/ch5-extensions-3.png)

![图：基本扩展器中的应用流程 2——查询相关信息](../images/ch5-extensions-4.png)

![图：基本扩展器中的应用流程 3——启用扩展进行预览/静态拍摄（HAL 实现）](../images/ch5-extensions-5.png)

**处理器类型（基本扩展器）**：通过 `PreviewExtenderImpl.getProcessorType` 指定——

- `PROCESSOR_TYPE_NONE`：无处理器，图片在相机 HAL 中处理。
- `PROCESSOR_TYPE_REQUEST_UPDATE_ONLY`：根据最新 `TotalCaptureResult` 更新重复请求参数（`RequestUpdateProcessorImpl`）。
- `PROCESSOR_TYPE_IMAGE_PROCESSOR`：处理 `YUV_420_888` 图片并输出到 `PRIVATE` surface（`PreviewImageProcessorImpl`）。

静态拍摄可通过 `ImageCaptureExtenderImpl.getCaptureProcessor` 返回 `CaptureProcessorImpl`：`getCaptureStages()` 返回的每个 `CaptureStageImpl` 对应一个拍摄请求（如 3 个则通过 `captureBurst` 发送 3 个请求），图片与 `TotalCaptureResult` 配对送入处理器，结果写入 `YUV_420_888` surface，由 Camera2/X 按需转成 JPEG。

![图：采用 PreviewImageProcessorImpl 的预览流程](../images/ch5-extensions-6.png)

![图：采用 CaptureProcessorImpl 的静态拍摄流程](../images/ch5-extensions-7.png)

### 5.4.6 支持拍摄请求/结果密钥（1.3.0 起）

应用可能设置缩放、点按对焦、闪光灯、曝光补偿等参数，但 OEM 实现未必兼容。`extensions-interface` 1.3.0 起可通过 `getAvailableCaptureRequestKeys()` / `getAvailableCaptureResultKeys()` 公开自己支持的参数；CameraX/Camera2 只支持返回列表中的密钥。建议至少支持：

| 操作 | 建议支持的密钥 |
| --- | --- |
| 缩放 | `CONTROL_ZOOM_RATIO`、`SCALER_CROP_REGION` |
| 点按对焦 | `CONTROL_AF_MODE`、`CONTROL_AF_TRIGGER`、`CONTROL_AF_REGIONS`、`CONTROL_AE_REGIONS`、`CONTROL_AWB_REGIONS` |
| 闪光灯 | `CONTROL_AE_MODE`、`CONTROL_AE_PRECAPTURE_TRIGGER`、`FLASH_MODE` |
| 曝光补偿 | `CONTROL_AE_EXPOSURE_COMPENSATION` |

### 5.4.7 高级扩展器要点

- `ExtensionVersionImpl.isAdvancedExtenderImplemented()` 返回 `true` 时启用高级扩展器。
- 每种扩展类型实现 `advanced/*AdvancedExtenderImpl`；核心在 `SessionProcessorImpl`：
  - `initSession()`：分配资源，返回 `Camera2SessionConfigImpl`（含 `Camera2OutputConfigImpl` 列表与会话参数）。输出可选方式：直接添加输出 surface（`SurfaceOutputConfigImpl`，由 HAL 处理）、添加中间 `ImageReader` surface（`ImageReaderOutputConfigImpl`，自行处理后写入输出）、或使用 Camera2 surface 共享。
  - `onCaptureSessionStart()`：获得 `RequestProcessorImpl`，可执行拍摄请求并检索图片（需 `setImageProcessor()` 注册回调）。
  - `startRepeating()` / `startCapture()` / `setParameters()`：发起预览/拍照、设置请求参数（必须至少支持 `JPEG_ORIENTATION` 和 `JPEG_QUALITY`）。
  - `startTrigger()`（1.3.0 起）：支持 `CONTROL_AF_TRIGGER`、`CONTROL_AE_PRECAPTURE_TRIGGER` 等触发请求，实现点按对焦和闪光灯。
- 分辨率要求：预览必须至少支持 `PRIVATE`；静态拍摄必须同时支持 `JPEG` 与 `YUV_420_888`；图片分析 YUV 流不支持时 `getSupportedYuvAnalysisResolutions()` 返回空列表/null。
- **应用流程（三种）**：查询扩展可用性 → 查询信息（延迟范围、分辨率、请求/结果密钥）→ 启用扩展进行预览与静态拍摄。

![图：高级扩展器中的应用流程 1——检查扩展可用性](../images/ch5-extensions-8.png)

![图：高级扩展器中的应用流程 2——查询相关信息](../images/ch5-extensions-9.png)

![图：高级扩展器中的应用流程 3——启用扩展进行预览/静态拍摄](../images/ch5-extensions-10.png)
- **支持预览、静态拍摄和图片分析**：若实现处理器，必须支持 3 个 `YUV_420_888` 流的组合；高级扩展器需保证预览与拍摄输出有效，图片分析输出仅在非 null 时工作。
- **视频拍摄**：当前架构仅支持预览与静态拍摄用例，不支持在 `MediaCodec`/`MediaRecorder` surface 上启用扩展（应用可录制预览输出）。

### 5.4.8 Android 14 新特性（extensions-interface 1.4.0）

- **特定于扩展的元数据**：
  - `EXTENSION_STRENGTH` 拍摄请求参数（0–100）控制后期处理强度：BOKEH 控制模糊程度；HDR/NIGHT 控制融合程度与亮度；FACE_RETOUCH 控制美颜程度。
  - `EXTENSION_CURRENT_TYPE` 拍摄结果指明当前启用的扩展类型（AUTO 扩展会在 HDR/NIGHT 等间动态切换）。
- **实时预估静态拍摄延迟**：`getRealtimeStillCaptureLatency()`（基本扩展器实现 `getRealtimeCaptureLatency()`，高级扩展器在 `SessionProcessorImpl` 实现），返回 `captureLatency` 与 `processingLatency`，比静态的 `getEstimatedCaptureLatencyRangeMillis()` 更准确。
- **拍摄处理进度回调**：`onCaptureProcessProgressed()`（0–100），基本扩展器经 `ProcessResultImpl`、高级扩展器经 `CaptureCallback` 上报。
- **postview 静态拍摄**：处理延迟较长时先显示 postview 占位图，最终图片可用后替换。基本扩展器实现 `CaptureProcessorImpl.onPostviewOutputSurface` 与 `processWithPostview`；高级扩展器实现 `SessionProcessorImpl.startCaptureWithPostview`。
- **SurfaceView 输出**：重复请求预览输出注册 `SurfaceView`，走功耗与性能优化的预览渲染路径。
- **特定于供应商的会话类型**：基本扩展器经 `ExtenderStateListener.onSessionType()`、高级扩展器经 `Camera2SessionConfigImpl.getSessionType()` 选择内部会话类型，对客户端 API 无影响。

### 5.4.9 扩展程序接口版本记录

| 版本 | 添加的功能 |
| --- | --- |
| 1.0.0 | 版本验证（`ExtensionVersionImpl`）；基本扩展器（`PreviewExtenderImpl`、`ImageCaptureExtenderImpl`）；处理器（`PreviewImageProcessorImpl`、`CaptureProcessorImpl`、`RequestUpdateProcessorImpl`） |
| 1.1.0 | 库初始化（`InitializerImpl`）；公开支持的分辨率（`getSupportedResolutions`） |
| 1.2.0 | 高级扩展器（`AdvancedExtenderImpl`、`SessionProcessorImpl`）；获取预计拍摄延迟（`getEstimatedCaptureLatencyRange`） |
| 1.3.0 | 公开支持的拍摄请求/结果密钥；带 `ProcessResultImpl` 的新 `process()` 调用；触发器请求（`startTrigger`） |
| 1.4.0 | 特定于扩展的元数据；动态预估静态拍摄延迟；拍摄处理进度回调；postview 静态拍摄；支持 `SurfaceView` 输出；特定于供应商的会话类型 |

### 5.4.10 在设备上部署供应商库

1. 添加权限文件（如 `/etc/permissions/camera_extensions.xml`），将 `<uses-library>` 指定的库映射到设备上的实际文件路径：

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <permissions>
       <library name="androidx.camera.extensions.impl"
                file="OEM_IMPLEMENTED_JAR" />
   </permissions>
   ```

   其中 `name` 必须为 `androidx.camera.extensions.impl`（CameraX 搜索该库名），`file` 为实现 jar 的绝对路径（如 `/system/framework/androidx.camera.extensions.impl.jar`）。
2. 在 **Android 12 及以上**支持 CameraX 扩展的设备，必须在设备 makefile 中设置系统属性：

   ```make
   PRODUCT_VENDOR_PROPERTIES += \
       ro.camerax.extensions.enabled=true \
   ```

3. 参考实现位于 `frameworks/ex`：`sample`（基本扩展器）、`advancedSample`（高级扩展器）、`service_based_sample`（在 Service 中承载相机扩展，含 `oem_library` 直通库与 `extensions_service` 扩展服务示例）。

### 5.4.11 扩展取景模式与相机扩展的关系

对于焦外成像，既可以通过相机扩展公开，也可以通过扩展场景模式（`CONTROL_EXTENDED_SCENE_MODE`，见 5.2 节）公开：

- **扩展取景模式限制更少**：可在支持灵活数据流组合和请求参数的常规 `CameraCaptureSession` 中启用；但只能在相机 HAL 中实现，且必须对应用可用的所有正交控件进行验证。
- **相机扩展**：仅支持一组固定的数据流类型，对拍摄请求参数支持有限；但适合无法在 HAL 中实现（需在应用层用后处理处理器处理图片）的场景。
- **官方建议**：同时使用扩展取景模式和相机扩展来公开焦外成像，因为应用可能倾向于使用特定 API。

### 5.4.12 关键术语

| 中文 | 英文 |
| --- | --- |
| 相机扩展 | Camera Extensions |
| OEM 供应商库 | OEM vendor library |
| 扩展程序接口 | `extensions-interface` |
| 基本扩展器 | Basic Extender |
| 高级扩展器 | Advanced Extender |
| 会话处理器 | `SessionProcessorImpl` |
| 请求处理器 | `RequestProcessorImpl` |
| 拍摄阶段 | `CaptureStageImpl` |
| 扩展强度 | `EXTENSION_STRENGTH` |
| 当前扩展类型 | `EXTENSION_CURRENT_TYPE` |
| postview 静态拍摄 | Postview still capture |
| 静态拍摄延迟 | Still capture latency |

## 5.5 相机扩展验证工具

> 来源：[相机扩展验证工具](https://source.android.google.cn/docs/core/camera/camerax-vendor-extensions-validation-tool?hl=zh-cn)

### 5.5.1 工具定位与解决的问题

相机扩展验证工具（Camera Extensions Validation Tool）供设备制造商（OEM）验证其"相机扩展 OEM 供应商库"实现是否正确。包含两类测试：

- **自动验证测试**：验证供应商库接口实现是否正确。例如，若图片拍摄需要 CaptureProcessor，测试会验证 `ImageCaptureExtenderImpl#getCaptureStages()` 是否返回所需的 `CaptureStage` 实例。
- **手动验证测试**：验证预览与拍摄图片的效果和画质，如脸部照片修复是否正确应用、焦外成像（bokeh）强度是否足够。

源代码位于 Android Jetpack 代码库中的扩展测试应用（`androidx-main/camera/integration-tests/extensionstestapp/`）。

### 5.5.2 构建

```bash
# 1. 下载 Android Jetpack 库源代码（参考 Jetpack README 的检出代码部分）
cd path/to/checkout/frameworks/support/

# 2. 构建测试用 APK（支持手动验证）
./gradlew camera:integration-tests:camera-testapp-extensions:assembleDebug
# 输出：.../out/androidx/camera/integration-tests/camera-testapp-extensions/build/outputs/apk/debug/camera-testapp-extensions-debug.apk

# 3. 构建 androidTest APK（支持自动验证）
./gradlew camera:integration-tests:camera-testapp-extensions:assembleAndroidTest
# 输出：.../build/outputs/apk/androidTest/debug/camera-testapp-extensions-debug-androidTest.apk
```

### 5.5.3 运行自动测试

先安装两个 APK（`adb install -r <apk路径>`），然后：

```bash
# 运行全部自动化测试（全部通过返回 OK，否则输出失败报告）
adb shell am instrument -w -r \
  androidx.camera.integration.extensions.test/androidx.test.runner.AndroidJUnitRunner

# 针对特定类运行（以 ImageCaptureTest 为例）
adb shell am instrument -w -r \
  -e class androidx.camera.integration.extensions.ImageCaptureTest \
  androidx.camera.integration.extensions.test/androidx.test.runner.AndroidJUnitRunner
```

![图：自动化测试结果 OK（全部通过）](../images/ch5-validation-1.png)

![图：存在失败情况的自动化测试结果](../images/ch5-validation-2.png)

### 5.5.4 运行手动测试

- 安装并启动扩展测试应用后，点按右上角菜单项**切换到验证工具模式**。
- 首页列出所有含 `REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE` 功能的摄像头；不支持任何扩展模式的相机显示为灰色。
- 选择相机后可见其可测试的扩展模式（不支持的显示为灰色）。

![图：验证工具模式首页——列出所有摄像头](../images/ch5-validation-3.png)

![图：适用于某个摄像头的扩展程序模式列表](../images/ch5-validation-4.png)

**验证预览**：点按扩展模式进入图片拍摄 activity，支持缩放、点按对焦、闪光灯模式切换、增/减曝光值、启用/停用扩展切换按钮；需验证这些功能在预览中正常工作。

![图：启用了焦外成像的预览图片](../images/ch5-validation-5.png)

**验证拍摄图片**：点按拍摄按钮后进入图片验证 activity，支持双指缩放、左右滑动切换图片、重新拍摄、保存图片；需验证图片正确且与拍摄时的缩放/对焦/闪光灯/曝光设置相符。结果正确点 PASS（对勾），否则点失败按钮（感叹号）。

![图：启用了焦外成像时拍摄的图片](../images/ch5-validation-6.png)

**测试结果颜色指示**：

| 背景颜色 | 含义 |
| --- | --- |
| 白色 | 相机至少支持一种扩展模式，尚未全部验证 |
| 绿色 | 所有受支持的扩展模式均已验证且全部通过 |
| 红色 | 所有模式已验证，但至少一种失败 |
| 灰色 | 该功能不可用 |

![图：摄像头测试结果的背景颜色指示（相机列表页）](../images/ch5-validation-7.png)

![图：扩展程序模式测试结果的背景颜色指示（扩展模式页）](../images/ch5-validation-8.png)

**其他功能**：

- **导出测试结果**：以 CSV 文件导出到 `Documents/ExtensionsValidation` 文件夹。
- **重置**：清除所有缓存的测试结果。
- **扩展程序示例应用**：切换回示例应用模式。

若发现问题并发布新版供应商库，应先重置结果，再对全部摄像头重新运行所有受支持的扩展模式以确认修复。

### 5.5.5 对 OEM 的要求

- 正确实现相机扩展供应商库接口（如 `ImageCaptureExtenderImpl`、`CaptureStage`、`CaptureProcessor` 等）。
- 通过自动与手动测试确认效果质量（如人像修复、焦外成像强度）。
- 可导出 CSV 结果作为验证记录。

### 5.5.6 关键术语

| 中文 | 英文 |
| --- | --- |
| 相机扩展验证工具 | Camera Extensions Validation Tool |
| 相机扩展 OEM 供应商库 | Camera Extensions OEM vendor library |
| 自动/手动验证测试 | Automatic / Manual validation tests |
| 图片拍摄 activity / 图片验证 activity | Image capture activity / Image validation activity |
| 验证工具模式 | Validation tool mode |
| 向后兼容能力 | `REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE` |
| 焦外成像 | Bokeh |

## 5.6 相机预览防抖

> 来源：[相机预览防抖](https://source.android.google.cn/docs/core/camera/camera-preview-stabilization?hl=zh-cn)

### 5.6.1 功能概述

面向 **Android 13 及以上**设备，相机框架对捕获会话中的**预览流及其他非 RAW 流**提供视频防抖支持。此功能让第三方应用在对比相机预览与录制内容时，实现所见即所得（WYSIWYG）的体验。

### 5.6.2 解决的问题

传统上预览与录制（视频）的防抖处理可能不一致，导致用户在预览中看到的效果与最终录制结果不同。此功能让预览流也应用视频防抖，从而使预览效果与录制内容一致，实现 WYSIWYG。

### 5.6.3 实现（HAL / 元数据）

设备制造商需要在相机 HAL 中通告支持并实现防抖算法，涉及两个 Camera2 元数据键：

| 键（Camera2 元数据） | 作用 |
| --- | --- |
| `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` | 在相机特性（`CameraCharacteristics`）中通告支持 |
| `CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION` | 表示预览防抖模式（`CameraMetadata`） |

- 如需修改默认设置：在通过 `createCaptureRequest` 创建拍摄请求时，在拍摄请求模板中分配默认值。
- 更多细节参见 `CONTROL_VIDEO_STABILIZATION_MODE` 文档。
- 参考实现：Cuttlefish 虚拟设备中的 EmulatedCamera 代码，位于 `hardware/google/camera/devices/EmulatedCamera/hwl/EmulatedSensor.cpp`。

### 5.6.4 对 OEM 的要求与验证

1. 在相机 HAL 中通告上述两个元数据键的支持。
2. 实现视频防抖算法本身。
3. 通过以下验证测试：
   - **CTS**：`RobustnessTest.java#testMandatoryPreviewStabilizationOutputCombinations`
   - **ITS（测试视野范围 FOV 与防抖效果）**：
     - `scene4/test_preview_Stabilization_fov.py`
     - `sensor_fusion/test_preview_stabilization.py`

### 5.6.5 关键术语

| 中文 | 英文 |
| --- | --- |
| 相机预览防抖 | Camera Preview Stabilization |
| 视频防抖 | Video Stabilization |
| 预览流 | Preview stream |
| 非 RAW 流 | Non-RAW stream |
| 所见即所得 | WYSIWYG (What You See Is What You Get) |
| 捕获会话 | Capture session |
| 拍摄请求 / 拍摄请求模板 | Capture request / Capture request template |
| 视野范围 | FOV (Field of View) |
| 传感器融合 | Sensor fusion |
| 兼容性测试套件 / 图像测试套件 | CTS / ITS |
