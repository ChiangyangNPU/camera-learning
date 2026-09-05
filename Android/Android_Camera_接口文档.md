# Android Camera 接口速查学习文档（应用层 + HAL 层）

> 本文档是《Android Camera 框架学习文档》的姊妹篇：前一份按 AOSP 官方文档讲**框架与机制**（[Android_Camera_学习文档.md](../Android_Camera_学习文档.md)），本份按**接口层**整理——从应用层 API（Camera2 / CameraX / Camera1 / NDK）到 HAL 层接口（camera3.h / AIDL HAL / 元数据），每节均附官方来源链接。
>
> 整理日期：2026-09-05。接口说明以官方参考文档与 AOSP 源码（main 分支）为准，方法做了精选摘要而非全量罗列。

## 文档结构

| 章节 | 内容 | 接口层次 |
|---|---|---|
| 第 1 章 Camera2 API | 包架构、CameraManager/CameraDevice/CameraCaptureSession 等核心类方法表、三类元数据 Key、典型调用流程 | 应用层（Java/Kotlin） |
| 第 2 章 CameraX | Use Case 模型、Preview/ImageCapture/ImageAnalysis/VideoCapture、ProcessCameraProvider、典型流程 | 应用层（Jetpack 封装） |
| 第 3 章 Camera1 与 NDK | 旧版 Camera 状态机与方法表、Parameters、回调体系、NDK libcamera2 函数分组、三代 API 对比 | 应用层（旧版 + NDK） |
| 第 4 章 HAL 层接口 | camera_common.h 模块接口、camera3.h 全部 ops 与结构体、AIDL HAL（Provider/Device/Session/Callback）、C↔AIDL 对照表 | HAL 层（C/HIDL/AIDL） |
| 第 5 章 元数据体系 | camera_metadata C API、tag 命名空间全表、metadata_properties 统计、vendor tag 机制、查阅指南 | 框架↔HAL 数据协议 |

## 建议使用方式

- **写应用**：查第 1、2 章的方法表与流程；需要更细参数时循着每节"来源"链接进官方参考页。
- **调 HAL / 看框架源码**：查第 4 章接口表与第 5 章元数据 API；第 4 章的 C↔AIDL 对照表适合从旧资料迁移时查阅。
- **跨层理解**：第 1 章的 Key（应用层）与第 5 章的 tag（HAL 层）是同一套元数据体系的两侧映射，对照阅读最有收获。

---

# 第 1 章 Camera2 API（android.hardware.camera2）

> 本章是 `android.hardware.camera2` 包的接口速查 + 学习总结，面向学习 Android Camera 的工程师。内容基于官方 API Reference 整理归纳，技术名词保留英文。

## 1.1 包概览与整体架构

> 来源：[android.hardware.camera2 包概览](https://developer.android.google.cn/reference/android/hardware/camera2/package-summary)

Camera2 将一个相机设备建模为一条**流水线（pipeline）**：它以 1:1 映射的方式接收单帧**请求（CaptureRequest）**，按请求配置捕获单帧图像，并输出单帧**结果（CaptureResult）**及对应的图像数据。它取代了已废弃的旧 `android.hardware.Camera` API。

### 核心心智模型：三层句柄 + 单向请求流

```
枚举/查询层                       操作层                          元数据层
────────────                    ────────────                   ────────────
CameraManager                   CameraDevice                   CameraCharacteristics   (静态: 设备能做什么)
 └─ getCameraIdList()            └─ createCaptureRequest()  ──▶ CaptureRequest        (动态输入: 这一帧要做什么)
 └─ getCameraCharacteristics()   └─ createCaptureSession()      CaptureResult         (动态输出: 这一帧实际做了什么)
 └─ openCamera()                     │                          TotalCaptureResult    (完整版结果)
                                     ▼
                                 CameraCaptureSession
                                  └─ setRepeatingRequest() ──▶ Surface(s) ──▶ Image/ImageReader
                                  └─ capture()                    (图像数据走 Surface，元数据走回调)
```

要点：

1. **枚举 → 打开 → 建会话 → 下发请求 → 收结果** 是所有 Camera2 应用的固定主线。
2. **控制流与数据流分离**：CaptureRequest 携带控制参数，图像数据只流向建会话时登记的 `Surface`，元数据（CaptureResult）通过 `CaptureCallback` 回调返回。
3. **异步无处不在**：openCamera、createCaptureSession 都是异步回调；同步方法的异常（如 `CameraAccessException`）只表示"调用本身不合法"（相机被占用、权限缺失、参数错误等）。
4. **API 33 起**，会话级旧方法被废弃并改名：`capture → captureSingleRequest`、`captureBurst → captureBurstRequests`、`setRepeatingRequest → setSingleRepeatingRequest`、`stopRepeating → stopRepeating`（保留）等，语义一致，建议新代码直接用新名。
5. 包内主要成员：`CameraManager`、`CameraCharacteristics`、`CameraDevice`、`CameraCaptureSession`（子类 `CameraConstrainedHighSpeedCaptureSession`、`CameraOfflineSession`）、`CaptureRequest`、`CaptureResult`/`TotalCaptureResult`、`CameraMetadata`（三者的公共基类，承载 Key 与枚举常量）。
6. 包内常用异常：`CameraAccessException`（含 `CAMERA_IN_USE`、`MAX_CAMERAS_IN_USE`、`CAMERA_DISABLED`、`CAMERA_ERROR`、`CAMERA_DISCONNECTED` 等原因码）、`IllegalArgumentException`、`IllegalStateException`（会话/设备关闭后继续调用）。

---

## 1.2 CameraManager

> 来源：[CameraManager](https://developer.android.google.cn/reference/android/hardware/camera2/CameraManager)

**职责**：系统服务（`Context.CAMERA_SERVICE`），负责**检测、枚举、描述相机设备并建立连接**。应用与相机硬件之间的第一个入口，本身不产生图像。

**协作关系**：通过 `getCameraIdList()` 枚举相机 → 用 `getCameraCharacteristics()` 拿静态元数据（CameraCharacteristics）→ 用 `openCamera()` 异步创建 CameraDevice。还管理手电筒（torch）、相机可用性监听、并发相机等系统能力。调用任何相机 API 均需 `Manifest.permission.CAMERA` 权限。

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `String[] getCameraIdList()` | 返回当前可连接的相机 ID 列表 | 可能不含被设备策略屏蔽的相机；ID 非有序语义（"0"不一定在后置），永远用 `LENS_FACING` 判断前后置 |
| `CameraCharacteristics getCameraCharacteristics(String cameraId)` | 查询某相机的静态属性 | 可在 openCamera 前调用；无 CAMERA 权限时部分 Key 会被过滤 |
| `void openCamera(String cameraId, CameraDevice.StateCallback callback, Handler handler)` | 异步打开相机，结果经 StateCallback 回调 | 需 CAMERA 权限（SecurityException）；可能抛 `CameraAccessException`；也可用 `(String, Executor, StateCallback)` 重载（API 28+）指定线程 |
| `void setTorchMode(String cameraId, boolean enabled)` | 不打开相机的情况下开关闪光灯 | 不会使设备进入"被占用"状态；需 `FLASHLIGHT` 特性 |
| `void registerAvailabilityCallback(AvailabilityCallback, Handler)` | 监听相机可用/不可用（被别的客户端占用） | 注册时立即回调一次当前状态；有 `Executor` 重载（API 28+） |
| `void registerTorchCallback(TorchCallback, Handler)` | 监听手电筒开关状态 | 同样注册即回调当前状态 |
| `CameraExtensionCharacteristics getCameraExtensionCharacteristics(String cameraId)` | 查询设备厂商扩展（HDR、夜景、美颜等） | API 33+；与 `CameraDevice.createExtensionSession()` 配套 |
| `Set<String> getConcurrentCameraIds()` | 查询可保证并发打开的相机组合 | API 30+；多摄同开（如双预览）前先查 |
| `boolean isConcurrentSessionConfigurationSupported(Map<...>)` | 判断多相机并发会话配置是否支持 | API 30+ |
| `void turnOnTorchWithStrengthLevel(String, int)` / `int getTorchStrengthLevel(String)` | 调节手电筒亮度档位 | API 33+ |
| `void unregisterAvailabilityCallback(...)` / `unregisterTorchCallback(...)` | 反注册回调 | 防内存泄漏，Activity 生命周期中成对使用 |

**嵌套回调类**：`AvailabilityCallback`（`onCameraAvailable` / `onCameraUnavailable` / `onCameraAccessPrioritiesChanged`）、`TorchCallback`（`onTorchModeChanged`）。

---

## 1.3 CameraCharacteristics

> 来源：[CameraCharacteristics](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCharacteristics)

**职责**：描述一个 CameraDevice 的**静态属性**——这台相机"有什么能力、参数取值范围是什么"。继承自 `CameraMetadata<CameraCharacteristics.Key<?>>`，**不可变（immutable）**（API 32 起 `SENSOR_ORIENTATION` 等极少数 Key 可能随设备状态动态变化，文档在各自 Key 下注明）。

**协作关系**：由 `CameraManager.getCameraCharacteristics()` 获得，是建会话前做能力检查的依据（选尺寸、判断前后置、判断是否支持 RAW 等）。它、CaptureRequest、CaptureResult 三者共用 `CameraMetadata` 的 Key 体系（详见 1.9 节）。

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `<T> T get(CameraCharacteristics.Key<T> key)` | 按 Key 查询一项静态属性 | 返回可能为 `null`（设备不支持该能力），必须判空 |
| `List<Key<?>> getKeys()` | 返回该设备支持的全部 Key | 结果是"此设备有值"的子集，不是全部定义的 Key |
| `List<Key<?>> getKeysNeedingPermission()` | 列出需要 CAMERA 权限才返回的 Key | API 32+；无权限客户端会拿不到这些值 |
| `List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys()` | 本设备支持的请求 Key 集合 | 做自定义控制面板前先查，避免下发无效控制 |
| `List<CaptureResult.Key<?>> getAvailableCaptureResultKeys()` | 本设备支持的结果 Key 集合 | 同上 |
| `List<Key<?>> getAvailableSessionKeys()` | 会话级参数 Key（建会话时生效，改了要重建会话） | API 28+，配合 `SessionConfiguration.setSessionParameters()` |
| `static Size getMaxSize(Size... sizes)` | 小工具：从候选尺寸中取最大者 | 常与 `getOutputSizes()` 搭配选预览/拍照尺寸 |

### 常用 Key 速查（静态元数据）

| Key | 类型 | 含义 / 注意点 |
|---|---|---|
| `LENS_FACING` | int | 镜头朝向（`LENS_FACING_BACK` / `FRONT` / `EXTERNAL`）；选相机 ID 的标准依据 |
| `SENSOR_ORIENTATION` | int | 传感器安装角度（0/90/180/270）；做预览旋转、JPEG 方向补偿必用 |
| `SCALER_STREAM_CONFIGURATION_MAP` | StreamConfigurationMap | 各输出格式支持的尺寸/帧率；`getOutputSizes(class or format)` 选尺寸必查 |
| `INFO_SUPPORTED_HARDWARE_LEVEL` | int | 能力等级：`LEGACY`（兼容模式，仅旧 Camera API 能力）< `LIMITED` ≈ 旧 API+ < `FULL`（手动控制、高速连拍等）< `LEVEL_3`（再加 RAW/YUV reprocess）；另有 `EXTERNAL`（USB 等外置，介于 LIMITED 之下） |
| `REQUEST_AVAILABLE_CAPABILITIES` | int[] | 能力标签集：`RAW`、`MANUAL_SENSOR`、`MANUAL_POST_PROCESSING`、`BURST_CAPTURE`、`LOGICAL_MULTI_CAMERA`、`CONSTRAINED_HIGH_SPEED_VIDEO`、`DYNAMIC_RANGE_TEN_BIT`、`ULTRA_HIGH_RESOLUTION_SENSOR` 等 |
| `FLASH_INFO_AVAILABLE` | boolean | 是否有闪光灯 |
| `SENSOR_INFO_ACTIVE_ARRAY_SIZE` | Rect | 传感器有效成像区域；做 crop/变焦坐标换算的基准 |
| `SENSOR_INFO_PIXEL_ARRAY_SIZE` | Size | 传感器总像素阵列（含黑边，大于 active array） |
| `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` | float[] | 可用焦距；判断光学变焦能力（物理多摄另看 `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` 等） |
| `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` | float | 最大数码变焦倍数（配合 `SCALER_CROP_REGION` 实现） |
| `CONTROL_MAX_REGIONS_AF/AE/AWB` | int | 3A 支持的测区数量；为 0 则 `CONTROL_AF_REGIONS` 等设置无效 |

---

## 1.4 CameraDevice

> 来源：[CameraDevice](https://developer.android.google.cn/reference/android/hardware/camera2/CameraDevice)

**职责**：表示**一个已连接的相机设备**，提供对图像捕获与后处理的细粒度控制、高帧率操作。继承 `AutoCloseable`。是"相机硬件本身"的应用层句柄。

**协作关系**：由 `CameraManager.openCamera()` 异步获得；负责 `createCaptureRequest()` 生成请求模板、`createCaptureSession()` 创建会话；生命周期结束调用 `close()`。所有状态变化通过 `StateCallback` 通知。

### StateCallback 与错误码

| 回调 | 触发时机 |
|---|---|
| `onOpened(CameraDevice)` | 相机打开成功，此后才能建会话 |
| `onDisconnected(CameraDevice)` | 相机被更高优先级客户端抢占；此后任何调用抛 `IllegalStateException`，应尽快 `close()` |
| `onError(CameraDevice, int error)` | 打开失败或设备致命错误；回调后设备不可再用，需 `close()` 后重开 |
| `onClosed(CameraDevice)` | `close()` 完成的最终确认，可在此释放相关资源 |

`onError` 的错误码（定义在 `CameraDevice.StateCallback`）：

| 错误码 | 含义 |
|---|---|
| `ERROR_CAMERA_IN_USE` (1) | 相机已被更高优先级的客户端占用 |
| `ERROR_MAX_CAMERAS_IN_USE` (2) | 打开的相机数超上限（已废弃语义，实际很少回调） |
| `ERROR_CAMERA_DISABLED` (3) | 被设备策略禁用（DevicePolicyManager） |
| `ERROR_CAMERA_DEVICE` (4) | 相机硬件致命错误；必须重新 open 才能再用 |
| `ERROR_CAMERA_SERVICE` (5) | 相机服务崩溃等致命错误；通常需要重启设备 |

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `CaptureRequest.Builder createCaptureRequest(int templateType)` | 按用途模板创建请求 Builder | 模板已预置一组合理的默认参数（见下表），再按需覆盖 |
| `void createCaptureSession(SessionConfiguration config)` | 创建 CameraCaptureSession（API 30+ 推荐形式） | 通过 `SessionConfiguration` 聚合输出 `OutputConfiguration` 列表、`Executor`、StateCallback、session 参数、输入配置（reprocess） |
| `void createCaptureSession(List<Surface>, StateCallback, Handler)` | 旧版建会话 | **API 30 起废弃**，功能是新版子集，仅老代码兼容用 |
| `void createConstrainedHighSpeedCaptureSession(...)` | 创建高帧率（慢动作/高速录像）会话 | API 30 起废弃，改用 `SessionConfiguration` + `SESSION_HIGH_SPEED`，返回 `CameraConstrainedHighSpeedCaptureSession` |
| `void createReprocessableCaptureSession(InputConfiguration, ...)` | 创建可 reprocess 的会话 | API 30 起废弃；新版用 `SessionConfiguration.setInputConfiguration()`，须先 `createReprocessCaptureRequest()` |
| `void createExtensionSession(ExtensionSessionConfiguration)` | 创建厂商扩展会话（HDR/夜景/_bokeh 等） | API 33+；输出仍走 Surface，能力查询走 `CameraExtensionCharacteristics` |
| `CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult)` | 基于某帧的结果创建 reprocess 请求 | 只能提交给 reprocessable 会话；典型用于 ZSL 二次处理 |
| `void close()` | 尽快断开相机 | 幂等；会话会被关闭、未完成请求被丢弃；务必在 onClosed 后才算彻底释放 |

### Template 一览（createCaptureRequest 参数）

| Template | 用途 | 特点 |
|---|---|---|
| `TEMPLATE_PREVIEW` (1) | 预览 | 低延迟、AE/AF 连续 |
| `TEMPLATE_RECORD` (2) | 录像 | 稳定帧率、连续对焦适合视频 |
| `TEMPLATE_STILL_CAPTURE` (3) | 拍照 | 倾向高质量输出（更高画质参数） |
| `TEMPLATE_VIDEO_SNAPSHOT` (4) | 录像中拍静帧 | 不打断视频流，画质偏录像模式 |
| `TEMPLATE_ZERO_SHUTTER_LAG` (5) | ZSL 拍照 | 配合 reprocess 使用 |
| `TEMPLATE_MANUAL` (6) | 全手动 | 仅 `MANUAL_SENSOR`/`MANUAL_POST_PROCESSING` 能力设备有意义 |

---

## 1.5 CameraCaptureSession

> 来源：[CameraCaptureSession](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCaptureSession)

**职责**：表示一个**已配置好的捕获会话**——相机流水线到一组目标 `Surface` 的绑定。所有 `capture` / `setRepeatingRequest` 都通过它提交；一个会话在其被新会话取代或设备关闭前一直有效。

**协作关系**：由 `CameraDevice.createCaptureSession()` 异步创建（配置耗时可达数百毫秒，成功回调 `StateCallback.onConfigured`，失败回调 `onConfigureFailed`）。请求对象来自 `CaptureRequest.Builder`，其 `addTarget()` 的 Surface 必须是**建会话时登记过的子集**。子类：`CameraConstrainedHighSpeedCaptureSession`（高速录像）、`CameraOfflineSession`（离线模式，API 33+）。已知子类之外的扩展由 `CameraExtensionSession`（API 33）承担厂商扩展处理。

**重要语义**：
- 会话是昂贵资源：建会话要重建相机内部 pipeline 并分配 buffer；切换会话前最好先 `abortCaptures()`。
- 旧会话被新会话顶替时回调 `onClosed`，并自动清空 repeating 请求。
- 会话关闭后调用其方法抛 `IllegalStateException`。

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `int capture(CaptureRequest, CaptureCallback, Handler)` | 提交单次捕获请求 | 每个请求产出一帧图像 + 一条 CaptureResult；**优先级高于 repeating**，会在当前 repeating 帧完成后尽快插入处理；API 33 起推荐用 `captureSingleRequest()` |
| `int captureBurst(List<CaptureRequest>, CaptureCallback, Handler)` | 一次性提交一组请求，按最小间隔连续捕获 | burst 内部不会被其他请求交错；API 33 起推荐 `captureBurstRequests()` |
| `int setRepeatingRequest(CaptureRequest, CaptureCallback, Handler)` | 设置持续重复的请求（预览/录像主循环） | 新的 repeating 会**替换**旧的；没有"队列累积"，预览主循环就是它；API 33 起推荐 `setSingleRepeatingRequest()` |
| `int setRepeatingBurst(List<CaptureRequest>, CaptureCallback, Handler)` | 循环重复一组请求 | 同上，替换式生效 |
| `void stopRepeating()` | 停止 repeating，但不影响已提交的单次请求 | 想暂停预览用它，而不是 abortCaptures |
| `void abortCaptures()` | 尽快丢弃所有 pending 和 in-flight 请求 | repeating 也会被清空；部分请求会正常完成、部分走 `onCaptureFailed`；会引入数据流短暂停顿；**切换新会话前必须调用** |
| `void close()` | 关闭会话 | 一般不必显式调用，`CameraDevice.close()` 会连带关闭 |
| `CameraDevice getDevice()` | 获取所属 CameraDevice | — |
| `boolean isReprocessable()` | 判断是否为可 reprocess 会话 | reprocess 请求只能提交到此类会话 |
| `void finalizeOutputConfigurations(List<OutputConfiguration>)` | 应用延迟创建的 Surface（共享 Surface 场景） | API 27+；配合 `OutputConfiguration.setPhysicalCameraId()` 等多摄配置 |
| `SessionState getState()` / `boolean isReprocessable()` | 查询会话状态 / 能力 | `getState()` API 30+，`CameraCaptureSessionState` 枚举 |

### 两个回调类

- `StateCallback`：`onConfigured`（会话就绪，保存引用开始下发请求）、`onConfigureFailed`、`onActive`（开始有数据）、`onReady`（队列空）、`onClosed`、`onSurfacePrepared`（Surface buffer 就绪，API 23+）。
- `CaptureCallback`：`onCaptureStarted`（曝光开始，可带时间戳）、`onCaptureProgressed`（**partial result**）、`onCaptureCompleted`（**total result**，参数是 `TotalCaptureResult`）、`onCaptureFailed`（配对 `CaptureFailure`）、`onCaptureSequenceCompleted` / `onCaptureSequenceAborted`、`onCaptureBufferLost`。

---

## 1.6 CaptureRequest

> 来源：[CaptureRequest](https://developer.android.google.cn/reference/android/hardware/camera2/CaptureRequest)

**职责**：一次捕获所需的**不可变参数包**：传感器/镜头/闪光灯配置、处理 pipeline、控制算法参数、目标 Surface 列表。它是应用向相机表达"这一帧要怎么拍"的唯一方式。

**协作关系**：通过 `CameraDevice.createCaptureRequest(template)` 得到 `CaptureRequest.Builder`，`set()` 覆盖参数、`addTarget()` 挂输出 Surface、`build()` 生成不可变请求，再交给 `CameraCaptureSession` 提交。每个请求可指定不同的目标 Surface 子集（如预览请求只挂预览 Surface，拍照请求挂 JPEG 的 ImageReader Surface）。继承自 `CameraMetadata<CaptureRequest.Key<?>>`。

### Builder 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `<T> Builder set(CaptureRequest.Key<T>, T)` | 设置一项控制参数 | 类型必须与 Key 泛型一致；值不在支持范围会被忽略或降级，可从对应 Result Key 反查实际生效值 |
| `<T> T get(CaptureRequest.Key<T>)` | 读取当前 Builder 中该参数值 | 含模板预置值 |
| `Builder addTarget(Surface)` | 添加该请求的输出 Surface | 必须是建会话时登记的 Surface；同一 Surface 可出现在多个请求里 |
| `Builder removeTarget(Surface)` | 移除输出目标 | — |
| `CaptureRequest build()` | 生成不可变请求 | 一个 Builder 可多次 build 生成不同参数的请求 |
| `Builder setPhysicalCameraKey(Key, Object, String)` | 对逻辑多摄中的某个物理相机单独设置参数 | 仅 `LOGICAL_MULTI_CAMERA` 且多摄请求时相关 |

### 常用 Key 速查（动态控制项）

| Key | 类型 | 含义 / 注意点 |
|---|---|---|
| `CONTROL_MODE` | int | 3A 总开关：`OFF` / `AUTO` / `USE_SCENE_MODE`；用场景模式时 AE/AF/AWB 单项设置多数失效 |
| `CONTROL_AE_MODE` | int | 曝光模式：`ON` / `ON_AUTO_FLASH` / `ON_ALWAYS_FLASH` / `OFF`（手动曝光前提）等 |
| `CONTROL_AF_MODE` | int | 对焦模式：`OFF` / `AUTO`（配 AF_TRIGGER）/ `CONTINUOUS_PICTURE` / `CONTINUOUS_VIDEO` / `MACRO` / `EDOF` |
| `CONTROL_AF_TRIGGER` | int | 触发一次自动对焦：`START` / `CANCEL`；单次对焦必须显式触发 |
| `CONTROL_AWB_MODE` | int | 白平衡：`AUTO` / `INCANDESCENT` / `FLUORESCENT` / `DAYLIGHT` / `CLOUDY_DAYLIGHT` / `SHADE` / `TWILIGHT` / `OFF` |
| `CONTROL_CAPTURE_INTENT` | int | 用途声明（预览/录像/拍照/ZSL 等），帮助 3A 调优；与 Template 强相关 |
| `CONTROL_AE_EXPOSURE_COMPENSATION` | int | 曝光补偿（EV 步数，范围查 `CONTROL_AE_COMPENSATION_RANGE`） |
| `FLASH_MODE` | int | `OFF` / `SINGLE` / `TORCH`；仅在 AE 模式为 `OFF` 时由应用直接控制闪光 |
| `SCALER_CROP_REGION` | Rect | 裁剪区域，用于数码变焦/EIS；坐标基于 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`，宽高应保持与传感器一致的长宽比 |
| `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY` / `SENSOR_FRAME_DURATION` | long / int / long | 手动曝光三件套，需 `CONTROL_AE_MODE=OFF` 且设备支持 `MANUAL_SENSOR` |
| `LENS_FOCUS_DISTANCE` | float | 手动对焦距离（单位 diopters，=1/米）；需 `CONTROL_AF_MODE=OFF` |
| `JPEG_ORIENTATION` / `JPEG_QUALITY` / `JPEG_THUMBNAIL_QUALITY` | int | JPEG 元数据方向与压缩质量；方向只影响 EXIF，不做像素旋转 |
| `STATISTICS_FACE_DETECT_MODE` | int | 人脸检测开关（`SIMPLE`/`FULL`），结果从 `STATISTICS_FACES` 读取 |

---

## 1.7 CaptureResult（与 TotalCaptureResult）

> 来源：[CaptureResult](https://developer.android.google.cn/reference/android/hardware/camera2/CaptureResult)

**职责**：一帧图像捕获完成后的**结果元数据**，"相机实际做了什么"的动态输出。继承自 `CameraMetadata<CaptureResult.Key<?>>`，不可变。

**协作关系**：相机处理完一个 CaptureRequest 后由 `CaptureCallback` 逐帧送回。**请求里能设置的所有 Key 都能在结果里查到最终生效值**；结果还额外携带相机状态信息（AF 状态、镜头状态、时间戳等）。子类 `TotalCaptureResult` 是"完整汇集"的结果；`onCaptureProgressed` 给到的 `CaptureResult` 可能是**部分结果（partial）**，不保证所有 Key 有值，只有 total 结果保证启用过的 Key 齐全。所以**业务逻辑应依赖 `onCaptureCompleted` 的 TotalCaptureResult**。

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `<T> T get(CaptureResult.Key<T> key)` | 按 Key 查询结果值 | partial 结果中可能为 null；`CameraMetadata` 相关 Key 缺失时按不支持处理 |
| `CaptureRequest getRequest()` | 获取产生本结果的请求 | 可用于把回调与请求参数对上（如区分拍照请求和预览请求） |
| `long getFrameNumber()` | 本结果对应帧号 | 同一帧的 partial/total 帧号相同 |
| `int getSequenceId()` | 提交批次序号 | 与 `onCaptureSequenceCompleted` 的 sequenceId 对应，用于知道整批何时结束 |
| `List<Key<?>> getKeys()` | 本结果包含的 Key 列表 | 遍历诊断用 |
| `CameraMetadataNative getPartialResults()`（TotalCaptureResult） | 访问原始 partial 数据 | 高级用法，一般用不到 |

### 常用 Key 速查（动态状态项）

| Key | 类型 | 含义 / 注意点 |
|---|---|---|
| `CONTROL_AE_STATE` | int | AE 状态机：`INACTIVE` / `SEARCHING` / `CONVERGED` / `LOCKED` / `FLASH_REQUIRED` / `PRECAPTURE`；判断曝光是否稳定 |
| `CONTROL_AF_STATE` | int | AF 状态机：`INACTIVE` / `ACTIVE_SCAN` / `FOCUSED_LOCKED` / `NOT_FOCUSED_LOCKED` / `PASSIVE_SCAN` / `PASSIVE_FOCUSED` / `PASSIVE_UNFOCUSED`；配合 `CONTROL_AF_TRIGGER` 实现"对焦成功才拍照" |
| `CONTROL_AWB_STATE` | int | 白平衡状态机，同上风格 |
| `FLASH_STATE` | int | `READY` / `FIRED` / `CHARGING` 等；连拍时防止闪光灯没充好电 |
| `LENS_STATE` | int | `STATIONARY` / `MOVING`；判断对焦马达是否已停 |
| `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY` | long / int | 该帧**实际**使用的曝光参数（与请求设置的可能不同） |
| `SENSOR_TIMESTAMP` | long | 该帧曝光开始的时间戳（ns）；与 `onCaptureStarted`、Image 时间戳同一时钟 |
| `SCALER_CROP_REGION` | Rect | 该帧实际裁剪区域（回读数码变焦/EIS 的实际效果） |
| `JPEG_ORIENTATION` / `JPEG_QUALITY` | int | 实际写入 JPEG 的方向与质量 |
| `COLOR_CORRECTION_GAINS` / `COLOR_CORRECTION_TRANSFORM` | RggbChannelVector / ColorSpaceTransform | 实际应用的白平衡增益/色变换矩阵 |
| `STATISTICS_FACES` | Face[] | 检测到的人脸（需请求里打开 `STATISTICS_FACE_DETECT_MODE`） |
| `NOISE_REDUCTION_MODE` / `EDGE_MODE` / `SHADING_MODE` | int | 各处理模块实际使用的模式（`FAST`/`HIGH_QUALITY`/`OFF` 等） |

> reprocess 流程中，`TotalCaptureResult` 还是 `CameraDevice.createReprocessCaptureRequest()` 的输入。

---

## 1.8 ImageReader

> 来源：[ImageReader](https://developer.android.google.cn/reference/android/media/ImageReader)（注意：ImageReader 位于 **android.media** 包，是 Camera2 的标准搭档）

**职责**：让应用**直接访问渲染到 Surface 的图像数据**。Camera2 会话的输出 Surface 可以是 ImageReader 的 Surface，这样每帧（或每次拍照）图像会以 `Image` 对象形式排队，由应用通过 `acquireLatestImage()` / `acquireNextImage()` 取走。

**协作关系**：`getSurface()` 挂进 `CaptureRequest.Builder.addTarget()` 与建会话的输出列表；每有新帧回调 `OnImageAvailableListener.onImageAvailable()`；拿到 `Image` 后按平面读取数据（`getPlanes()[i].getBuffer()`），用完必须 `Image.close()` 归还。

### 常用方法表

| 方法签名简写 | 作用 | 注意点 |
|---|---|---|
| `static ImageReader newInstance(int w, int h, int format, int maxImages)` | 创建 ImageReader | `maxImages` 是同时持有的 Image 上限；太小丢帧、太大占内存 |
| `Surface getSurface()` | 获取可作为相机输出的 Surface | 必须在建会话时登记 |
| `Image acquireLatestImage()` | 取最新一帧并丢弃积压的旧帧 | 预览场景首选；返回 null 表示当前无可用帧；会 close 掉被跳过的帧 |
| `Image acquireNextImage()` | 按队列顺序取下一帧 | 适合逐帧处理（连拍、逐帧分析）；不丢弃旧帧 |
| `void setOnImageAvailableListener(OnImageAvailableListener, Handler)` | 新帧可用回调 | 回调线程由 handler/executor 决定；不要在回调里做重活 |
| `int getWidth()` / `getHeight()` / `int getFormat()` | 图像尺寸与格式 | 常用格式：`ImageFormat.JPEG`、`YUV_420_888`、`RAW_SENSOR`、`PRIVATE`（不透传给 CPU 的队列用，如给 codec） |
| `int getMaxImages()` | 可同时持有的 Image 上限 | 持有数超过该值时 acquire 会失败 |
| `void close()` | 释放 ImageReader | 先 close 所有未释放的 Image；注意与相机关闭顺序避免 crash |

**使用注意**：产速 > 消速时，数据源会丢帧或停摆；Image 不及时 close 很快会打满 maxImages 造成卡死。`acquireLatestImage()` 内部对被跳过帧做了 close，但两方法都要对取到的 Image 负责。API 33+ 还可用 `ImageReader.Builder` 设定 usage 等属性。

---

## 1.9 三类元数据的关系：CameraCharacteristics / CaptureRequest / CaptureResult

三者都继承 `CameraMetadata<T>`，都用 `Key<T>` 做类型安全的字段读写，但生命周期与读写方向完全不同——这是 Camera2 最核心的设计：

| 维度 | CameraCharacteristics | CaptureRequest | CaptureResult |
|---|---|---|---|
| 回答的问题 | 这台设备**能**做什么 | 这一帧**想要**怎么做 | 这一帧**实际**做了什么 |
| 性质 | 静态、不可变、设备级 | 动态、应用构造、逐帧/逐批 | 动态、相机生成、逐帧 |
| 方向 | 只读 | 应用 → 相机（输入） | 相机 → 应用（输出） |
| 获取时机 | openCamera 之前 | 任何时刻（会话中反复构造） | 每帧回调返回 |
| 生命周期 | 打开相机前后都稳定 | 单次提交 | 单帧 |
| Key 来源 | `CameraCharacteristics.Key` | `CaptureRequest.Key` | `CaptureResult.Key` |

关键联系：

1. **同名共轭**：大多数 request Key 在 result 中有对应项（如 `CONTROL_AF_MODE` ↔ `CONTROL_AF_STATE`、`SENSOR_EXPOSURE_TIME` 请求/结果同 Key），文档明确"所有请求属性都可在结果中查询最终值"。请求写"意图"，结果读"实效"。
2. **能力先行**：请求能设置什么、结果会有什么，都由 characteristics 限定——可用 request keys 用 `getAvailableCaptureRequestKeys()` 查，可用 result keys 用 `getAvailableCaptureResultKeys()` 查；无权限时部分 Key 被 `getKeysNeedingPermission()` 所列过滤。
3. **partial vs total**：结果可能分多次回调（partial 先行、total 收尾），编程上以 `onCaptureCompleted(TotalCaptureResult)` 为准。
4. **静态 → 请求的换算关系**：例如 `SCALER_CROP_REGION` 的合法坐标基于 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`；曝光范围查 `CONTROL_AE_COMPENSATION_RANGE`；3A 区域设置前查 `CONTROL_MAX_REGIONS_*`。
5. 记忆口诀：**Characteristics 问能力，Request 下意图，Result 验实效**。

---

## 1.10 典型使用流程（Kotlin 伪代码）

完整主线：**枚举 → 打开 → 建会话 → 下发 repeating 请求 → 单发拍照请求 → 收结果/收图像 → 关闭**。

```kotlin
// 0. 准备：权限已授予（Manifest.permission.CAMERA）
val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

// 1. 枚举 + 能力查询：选一个后置相机
val cameraId = cameraManager.cameraIdList.first { id ->
    val c = cameraManager.getCameraCharacteristics(id)
    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
}
val chars = cameraManager.getCameraCharacteristics(cameraId)
val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
val previewSize = streamMap.getOutputSizes(SurfaceTexture::class.java)
    .maxByOrNull { it.width * it.height }!!

// 2. 准备输出 Surface：预览 + 拍照用的 ImageReader（android.media）
val previewSurface = Surface(textureView.surfaceTexture!!.apply { setDefaultBufferSize(previewSize.width, previewSize.height) })
val jpegReader = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 2)
jpegReader.setOnImageAvailableListener({ reader ->
    reader.acquireLatestImage()?.use { img -> saveJpeg(img) }   // Image 用完必须 close
}, backgroundHandler)

// 3. 打开相机（异步）
cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
    override fun onOpened(device: CameraDevice) { buildSession(device) }
    override fun onDisconnected(device: CameraDevice) { device.close() }          // 被抢占
    override fun onError(device: CameraDevice, error: Int) { device.close() }     // ERROR_CAMERA_* 见 1.4
}, backgroundHandler)

fun buildSession(device: CameraDevice) {
    // 3.1 由模板构造请求 Builder
    val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewSurface)                                              // 数据出口 1
        addTarget(jpegReader.surface)                                          // 数据出口 2（可选：预览同时喂分析流）
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    // 3.2 建会话（异步；API 30+ 推荐 SessionConfiguration）
    val config = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(OutputConfiguration(previewSurface), OutputConfiguration(jpegReader.surface)),
        executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                // 4. 下发 repeating 请求：预览主循环
                session.setRepeatingRequest(previewBuilder.build(), previewCallback, backgroundHandler)
                this@MainActivity.session = session
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { /* 提示失败 */ }
        }
    )
    device.createCaptureSession(config)
}

// 5. 拍照：在预览之外插入一条更高优先级的单发请求（走 TEMPLATE_STILL_CAPTURE）
fun takePicture() {
    val stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
        addTarget(jpegReader.surface)                                          // JPEG 出口
        set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation())         // 方向写 EXIF
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START) // 可选：单次对焦
    }
    session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session, request, timestamp, frameNumber) { /* 快门反馈 */ }
        override fun onCaptureCompleted(session, request, result: TotalCaptureResult) {
            // 6. 收结果：result.get(CONTROL_AF_STATE) 等元数据；图像数据则从 ImageReader 回调取
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        }
        override fun onCaptureFailed(session, request, failure: CaptureFailure) { /* 重试或提示 */ }
    }, backgroundHandler)
}

// 7. 关闭：先停 repeating / abort，再关设备（onClosed 后才算释放干净）
fun releaseCamera() {
    session?.run { stopRepeating(); abortCaptures() }
    camera?.close()
    jpegReader.close()
}
```

流程要点回顾：

1. **顺序不可乱**：拿到 `onOpened` 才能建会话，拿到 `onConfigured` 才能提交请求。
2. **请求的 target ⊆ 会话的输出集合**，否则提交失败。
3. **预览走 repeating，拍照走单发 capture**（优先级更高），拍照完成预览自动恢复。
4. **元数据与图像数据分开收**：结果元数据在 `CaptureCallback`，图像内容在 `ImageReader` 回调（或 MediaCodec/MediaRecorder 的 Surface）。
5. **异常与回调都要处理**：`CameraAccessException`（打开/配置阶段）、`StateCallback.onError`（设备错误）、`CaptureCallback.onCaptureFailed`（单帧失败）是三条独立的失败路径。



# 第 2 章 CameraX（androidx.camera）

## 2.1 CameraX 概览

> 来源：[CameraX | Android Developers（Jetpack Release 页）](https://developer.android.google.cn/jetpack/androidx/releases/camera)

**定位**：CameraX 是 Jetpack 的相机库，官方描述为"利用该库，可以更轻松地向应用添加相机功能，并提供了很多兼容性修复和解决方法，有助于在众多设备上打造一致的开发者体验"。它不是新的底层框架，而是**对 Camera2 API 的封装**：默认实现由 `androidx.camera:camera-camera2` 提供（入口类 `Camera2Config`），最终仍通过 `android.hardware.camera2` 走 HAL。CameraX 带来的核心价值：

- **生命周期感知**：camera 与 `LifecycleOwner` 绑定，自动处理 open/start/stop/close，无需手写 Resume/Pause 逻辑；
- **Use Case 抽象**：把"预览、拍照、分析、录像"建模为四个 Use Case 类，应用声明需求即可，分辨率协商、stream 配置由 CameraX 决定；
- **设备兼容性**：内置大量 OEM 机型 workaround，官方维护设备实验室做兼容性测试；
- **向后兼容**：最低支持到 API 21，比直接使用 Camera2（API 21+ 但行为碎片化）省心。

**四大 artifact 与周边库**（截至文档编写时稳定版为 1.6.x，另处于 1.7.0-alpha 阶段）：

| Artifact | 包名 | 核心内容 | 职责 |
|---|---|---|---|
| `camera-core` | `androidx.camera.core`（含 `resolutionselector`、`featuregroup` 子包） | `Preview`、`ImageCapture`、`ImageAnalysis`、`CameraSelector`、`CameraXConfig`、`UseCase`/`Camera`/`CameraControl`/`CameraInfo`、`SurfaceRequest`、`ViewPort` | Use Case 与公共基础设施 |
| `camera-camera2` | `androidx.camera.camera2`（含 `interop`） | `Camera2Config`、`Camera2Interop`、`Camera2CameraControl`、`Camera2CameraInfo` | Camera2 默认实现 + 互操作层 |
| `camera-lifecycle` | `androidx.camera.lifecycle` | `ProcessCameraProvider`、`LifecycleCameraController` | 绑定 lifecycle 的 provider |
| `camera-video` | `androidx.camera.video` | `VideoCapture`、`Recorder`、`Recording`、`PendingRecording`、`FileOutputOptions`/`MediaStoreOutputOptions`、`QualitySelector`、`VideoRecordEvent` | 视频录制 |
| `camera-view` | `androidx.camera.view` | `PreviewView`、`CameraController` | UI 层封装 |
| 其他（按需） | — | `camera-effects`、`camera-extensions`（滤镜/夜视等厂商扩展）、`camera-mlkit-vision`（MLKit 集成）、`camera-compose`（Compose 支持）、`camera-feature-combination-query`（能力查询） | 扩展能力 |

**Use Case 模型**：应用不直接创建 `CaptureSession`，而是构建若干 Use Case 实例，通过 `ProcessCameraProvider.bindToLifecycle()` 一次性绑定。同一 camera 同时最多绑定 3 个 Use Case；单次 bind 多个 Use Case 时，分辨率协商优先级为 **ImageCapture > Preview > ImageAnalysis**（VideoCapture 参与各自的组合协商）。常用组合：预览+拍照（Preview + ImageCapture）、预览+分析（Preview + ImageAnalysis）、预览+录像（Preview + VideoCapture）。

**与 Camera2 的关系和互操作（Camera2Interop）**：
- 正常使用无需接触 Camera2；需要 Camera2 独有参数时走 `androidx.camera.camera2.interop` 包（需 `@ExperimentalCamera2Interop` 注解）；
- `Camera2Interop.Extender`：挂在各 Use Case 的 `Builder` 上，往 `CaptureRequest` 里塞 Camera2 key（如 `CaptureRequest.CONTROL_AE_MODE`）；
- `Camera2CameraControl.from(cameraControl).setCaptureRequestOptions(...)`：运行时动态下发/合并 CaptureRequest options；
- `Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(CameraCharacteristics.XXX)`：读取底层 `CameraCharacteristics`；
- 反向：`Camera2Config.defaultConfig()` 即 CameraX 的默认 Camera2 实现，用于 `CameraXConfig.Builder.fromConfig(...)`。

---

## 2.2 Preview

> 来源：[Preview | API reference | Android Developers](https://developer.android.google.cn/reference/androidx/camera/core/Preview)

**职责**：官方定义 "A use case that provides a camera preview stream for displaying on-screen."——向外提供一块可渲染预览流的 `Surface`。本身不落盘、不输出图像数据给应用消费，只负责把帧送进你提供的 Surface（典型消费者是 `camera-view` 的 `PreviewView`）。

**Preview.Builder 关键配置方法**：

| 方法 | 说明 |
|---|---|
| `setSurfaceProvider(SurfaceProvider)` | 阻塞版：设置接收 `SurfaceRequest` 的回调，回调在主线程执行，要求 `onSurfaceRequested()` 内**立即**调用 `request.provideSurface(...)` 并快速返回 |
| `setSurfaceProvider(Executor, SurfaceProvider)` | 非阻塞版（推荐）：回调在指定 Executor 上执行，`onSurfaceRequested` 可稍后异步 provide Surface |
| `setTargetRotation(int)` | 目标旋转，`ROTATION_0/90/180/270`；绑定后可随时调用更新 |
| `setResolutionSelector(ResolutionSelector)` | 1.3+ 的分辨率/宽高比选择入口（取代已废弃的 `setTargetResolution`/`setTargetAspectRatio`） |
| `setTargetFrameRate(Range<Integer>)` | 目标帧率区间（不保证生效，是协商参考值） |
| `setDynamicRange(DynamicRange)` | 动态范围，默认 SDR，可设 HDR（如 `HDR_HLG_10_BIT`） |
| `setPreviewStabilizationEnabled(boolean)` | 1.4+ 预览防抖；设备是否支持需查 `PreviewCapabilities.isPreviewStabilizationSupported()` |

**Preview.SurfaceProvider 与 SurfaceRequest**（Preview 的核心回调机制）：

```kotlin
preview.setSurfaceProvider { request ->          // onSurfaceRequested(SurfaceRequest)
    val surface = ...                            // 例如由 PreviewView/SurfaceTexture 提供
    surface.provideSurface(request.resolution,   // provideSurface(Surface, Executor, ResultListener)
        request.executor) { result -> request.close() }
    // 不打算提供 Surface 时也应调用 request.close()，否则 camera 会一直等待
}
```

- `SurfaceRequest` 关键成员：`getResolution()`（协商出的分辨率）、`getExecutor()`、`provideSurface(Surface, Executor, ResultListener)`、`willProvideSurface()`（承诺稍后提供，避免超时失效）、`addRequestCancellationListener()`（Surface 被废弃时清理资源）、`isAborted()`。
- 使用 `PreviewView` 时无需手写：`previewView.surfaceProvider` 直接传给 `setSurfaceProvider`。

**输出/查询**：`getResolutionInfo()` 返回 `ResolutionInfo`（`getResolution()` / `getRotationDegrees()` / `getCropRect()`），用于查询当前实际生效的分辨率与旋转裁剪信息。

---

## 2.3 ImageCapture

> 来源：[ImageCapture | API reference | Android Developers](https://developer.android.google.cn/reference/androidx/camera/core/ImageCapture)

**职责**：官方定义 "A use case that allows the application to take a picture and save image to file."——提供拍照能力与简化的相机控制，管理底层 camera 与 capture session，把 JPEG（或 RAW/JPEG_R）数据落盘或以内存形式回调。是三个 Use Case 中分辨率协商优先级最高的。

**关键常量**：

| 常量 | 含义 |
|---|---|
| `CAPTURE_MODE_MAXIMIZE_QUALITY`（默认） | 最大化画质，可能引入额外帧以优化曝光，延迟较高 |
| `CAPTURE_MODE_MINIMIZE_LATENCY` | 最小化快门延迟（配合 ZSL 思路，牺牲少量画质） |
| `FLASH_MODE_OFF` / `FLASH_MODE_ON` / `FLASH_MODE_AUTO` | 闪光灯模式；注意与 `CameraControl.enableTorch()`（持续常亮）区分 |
| `OUTPUT_FORMAT_JPEG`（默认）/ `OUTPUT_FORMAT_JPEG_R` / `OUTPUT_FORMAT_RAW_SENSOR` | 1.5+ 输出格式 |

**ImageCapture.Builder 关键配置方法**：

| 方法 | 说明 |
|---|---|
| `setCaptureMode(int)` | 画质优先 / 延迟优先 |
| `setFlashMode(int)` | 闪光灯模式 |
| `setTargetRotation(int)` | 目标旋转，决定照片 Exif 旋转元数据，绑定后可更新 |
| `setJpegQuality(int)` | JPEG 压缩质量 1~100 |
| `setResolutionSelector(ResolutionSelector)` | 分辨率/宽高比选择 |
| `setOutputFormat(int)` | 1.5+ 选择 JPEG / JPEG_R / RAW_SENSOR |
| `setSensorToBufferTransformMatrix(Matrix)` | 传感器到缓冲区变换（特殊拼接场景用） |
| `setViewPort(ViewPort)` | 与其他 Use Case 共享同一裁剪视口 |

**takePicture 两个重载（核心 API）**：

```kotlin
// 方式一：落盘（File / MediaStore / Uri），最常用
imageCapture.takePicture(
    ImageCapture.OutputFileOptions.Builder(file).build(),
    executor,
    object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(results: ImageCapture.OutputFileResults) { /* results.savedUri */ }
        override fun onError(exception: ImageCaptureException) { /* 按错误码处理 */ }
    })

// 方式二：内存回调，返回 ImageProxy（应用自行处理、必须 close()）
imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) { image.use { /* JPEG buffer */ } }
    override fun onError(exception: ImageCaptureException) { }
})
```

**关键回调 / 输出类**：

| 类型 | 关键成员 | 说明 |
|---|---|---|
| `OnImageSavedCallback` | `onImageSaved(OutputFileResults)`、`onError(ImageCaptureException)` | 落盘回调；`OutputFileResults.getSavedUri()` 返回保存位置 |
| `OnImageCapturedCallback` | `onCaptureSuccess(ImageProxy)`、`onError(ImageCaptureException)` | 内存回调；拿到的 `ImageProxy` 用完必须 `close()` |
| `ImageCaptureException` | `getCode()`、`getImageProxy()`、`getCause()` | 错误码（如 `ERROR_CAMERA_CLOSED`、`ERROR_FILE_IO`）+ 原因 |
| `OutputFileOptions.Builder` | 构造参数分别接受 `File`、`MediaStoreOutputOptions`、`(ContentResolver, Uri, ContentValues)`；`setMetadata(Metadata)`、`build()` | Metadata 可写入 GPS（`setLocation`）、水平镜像标记等 |
| `ImageProxy` | `getPlanes()`、`getImageInfo().rotationDegrees`、`getFormat()` | 照片数据包装；旋转信息主要靠 Exif + rotationDegrees |

**运行时控制**：`getTargetRotation()` / `setTargetRotation(int)` 可在绑定后随时更新（典型做法是监听 `OrientationEventListener`）；`getResolutionInfo()` 查询实际生效分辨率；`setFlashMode` / `setCaptureMode` 等也提供了运行时 setter/getter。

---

## 2.4 ImageAnalysis

> 来源：[ImageAnalysis | API reference | Android Developers](https://developer.android.google.cn/reference/androidx/camera/core/ImageAnalysis)

**职责**：官方定义 "A use case that provides CPU accessible images for the application to perform image analysis on, by default in YUV_420_888 format."——把相机帧以可编程访问的图像流交给应用做分析（机器学习、扫码、实时滤镜输入等）。默认分辨率 640x480（分析场景通常不需要高分辨率）。

**关键常量（背压与格式）**：

| 常量 | 含义 |
|---|---|
| `BackpressureStrategy.KEEP_ONLY_LATEST`（默认） | 处理不过来时只保留最新一帧，旧帧丢弃（推荐，实时性最好） |
| `BackpressureStrategy.BLOCK_PRODUCER` | 处理不过来时阻塞上游产出（保证不丢帧，但延迟会累积） |
| `OutputImageFormat.OUTPUT_IMAGE_FORMAT_YUV_420_888`（默认） | 输出 YUV_420_888（标准分析格式） |
| `OutputImageFormat.OUTPUT_IMAGE_FORMAT_RGBA_8888` | 输出 RGBA，可直接转 Bitmap（部分推理引擎/ML Kit 更友好） |

**ImageAnalysis.Builder 关键配置方法**：

| 方法 | 说明 |
|---|---|
| `setBackpressureStrategy(int)` | 背压策略 |
| `setOutputImageFormat(int)` | YUV 或 RGBA |
| `setTargetRotation(int)` | 目标旋转（影响 `ImageInfo.rotationDegrees`） |
| `setResolutionSelector(ResolutionSelector)` | 分辨率/宽高比选择 |
| `setOutputImageRotationEnabled(boolean)` | 1.2+ 由 CameraX 直接旋转图像缓冲（`rotationDegrees` 变为 0），省去应用侧旋转 |

**setAnalyzer 与 Analyzer（核心回调）**：

```kotlin
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
analysis.setAnalyzer(executor) { imageProxy ->      // Analyzer.analyze(ImageProxy)
    try {
        val rotation = imageProxy.imageInfo.rotationDegrees
        // ... 分析 planes / buffer（例如送入 ML 模型）
    } finally {
        imageProxy.close()                          // 必须关闭，否则帧流会停止
    }
}
```

- `setAnalyzer(Executor, Analyzer)`：在指定 Executor 上回调（推荐）；`setAnalyzer(Analyzer)` 单参数版本使用主线程执行回调，已不推荐。
- `ImageAnalysis.Analyzer` 接口只有一个方法 `analyze(ImageProxy imageProxy)`。
- **必须**在处理完成后调用 `imageProxy.close()`；否则按 KEEP_ONLY_LATEST 策略后续帧无法送达，表现为"分析流卡死"。

**ImageProxy 常用方法**：`getWidth()` / `getHeight()`、`getPlanes()`（各平面 buffer、rowStride、pixelStride）、`getFormat()`、`getImageInfo()`（`getRotationDegrees()`、`getTimestamp()`、`getTag()`），以及 `close()`。`getImage()`（直接拿 `android.media.Image`）在 1.3+ 已废弃，建议统一走 `ImageProxy` 抽象。

---

## 2.5 VideoCapture

> 来源：[VideoCapture | API reference | Android Developers](https://developer.android.google.cn/reference/androidx/camera/video/VideoCapture)

**职责**：官方定义 "A use case that provides camera stream suitable for video application."——为录像提供相机流。它是一个泛型类 `VideoCapture<T extends VideoOutput>`，本身只负责"流"，**录制/编码逻辑委托给 `VideoOutput` 实现**（标准实现是 `androidx.camera.video.Recorder`），因此录像的输出选项、暂停恢复、事件监听都在 `Recorder`/`Recording` 一侧。

**创建方式（静态工厂）**：

```kotlin
val videoCapture = VideoCapture.withOutput(Recorder.Builder().build())  // 返回 VideoCapture<Recorder>
val recorder: Recorder = videoCapture.output                            // getOutput() 取回 VideoOutput
```

**VideoCapture.Builder 关键配置方法**：

| 方法 | 说明 |
|---|---|
| `setTargetRotation(int)` | 目标旋转；对 Recorder 的最终旋转策略：写入视频元数据、直接旋转内容或两者结合 |
| `setDynamicRange(DynamicRange)` | 动态范围，默认 SDR；HDR 录制用 `HDR_UNSPECIFIED_10_BIT`，支持范围可经 `VideoCapabilities.getSupportedDynamicRanges()` 查询 |
| `setMirrorMode(int)` | 镜像模式：`MIRROR_MODE_OFF` / `MIRROR_MODE_ON` / `MIRROR_MODE_ON_FRONT_ONLY`（推荐后者：与 Preview 默认镜像行为一致） |
| `setTargetFrameRate(Range<Integer>)` | 目标帧率区间（协商参考值，不保证生效） |
| `setVideoStabilizationEnabled(boolean)` | 1.4+ 视频防抖；启用前建议查 `isStabilizationSupported()` |

注意：录像输出质量不在这里配置，而是由 `Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD))` 控制；`getSelectedQuality()` 可查询实际选中的 `VideoQuality`。

**录制流程（Recorder / PendingRecording / Recording）**：

```kotlin
// 1. 准备输出选项：存到 MediaStore 或 File
val outputOptions = MediaStoreOutputOptions.Builder(context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI).build()
// 2. 准备录制（PendingRecording），带音频需 RECORD_AUDIO 权限
val pendingRecording = videoCapture.output
    .prepareRecording(context, outputOptions)
    .withAudioEnabled()
// 3. 启动，监听 VideoRecordEvent
val recording: Recording = pendingRecording.start(executor) { event: VideoRecordEvent ->
    when (event) {
        is VideoRecordEvent.Start -> { /* 录制开始 */ }
        is VideoRecordEvent.Status -> { /* recordingStats: 时长/大小 */ }
        is VideoRecordEvent.Finalize -> {
            if (!event.hasError()) { /* event.outputResults.outputUri */ }
        }
    }
}
// 4. 运行时控制
recording.pause(); recording.resume()
recording.stop()        // 正常结束（触发 Finalize 事件并落盘）
```

**运行时控制**：`getTargetRotation()` / `setTargetRotation(int)` 绑定后可更新；`getResolutionInfo()` 查询当前分辨率（可能随所选 Quality 动态变化）。常用 bind 组合为 `Preview + VideoCapture`（可再加 `ImageCapture` 做录像中拍照）。

---

## 2.6 ProcessCameraProvider、CameraSelector 与 CameraXConfig

> 来源：[ProcessCameraProvider | API reference](https://developer.android.google.cn/reference/androidx/camera/lifecycle/ProcessCameraProvider)、[CameraSelector | API reference](https://developer.android.google.cn/reference/androidx/camera/core/CameraSelector)、[CameraXConfig | API reference](https://developer.android.google.cn/reference/androidx/camera/core/CameraXConfig)

### 2.6.1 ProcessCameraProvider

官方定义："A singleton which can be used to bind the lifecycle of cameras to any LifecycleOwner within an application's process."——进程级单例，把 camera 及 Use Case 的生命周期与任意 `LifecycleOwner` 绑定，是**应用标准入口**（实现自 `CameraProvider` 接口）。

| 方法 | 签名要点 | 说明 |
|---|---|---|
| `getInstance(Context)` | `static ListenableFuture<ProcessCameraProvider>` | 异步初始化并获取 provider；Kotlin 中常用 `.await()`（`ListenableFuture.await()`） |
| `bindToLifecycle(owner, selector, UseCase...)` | `@MainThread`，返回 `Camera` | 核心方法：把若干 Use Case 绑到 LifecycleOwner；owner 处于 `STARTED` 即开流，回退自动 stop/close |
| `bindToLifecycle(owner, selector, UseCaseGroup)` | `@MainThread` | Use Case 组绑定，组内共享 `ViewPort`/`CameraEffect` 等，保证取景一致 |
| `bindToLifecycle(List<SingleCameraConfig>)` | `@MainThread`，返回 `ConcurrentCamera` | 1.3+ 并发双摄（组合需匹配 `getAvailableConcurrentCameraInfos()`） |
| `unbind(UseCase...)` | `@MainThread` | 解绑指定 Use Case；未绑定的会被忽略 |
| `unbindAll()` | `@MainThread` | 解绑全部 Use Case 并关闭相机 |
| `isBound(UseCase)` | `boolean` | 判断 Use Case 是否仍在绑定 |
| `getAvailableCameraInfos()` | `List<CameraInfo>` | 当前可用相机信息；`CameraInfo.getCameraSelector()` 可反查选择器 |
| `hasCamera(CameraSelector)` | `boolean` | 是否存在满足条件的相机（绑定前检查） |
| `getCameraInfo(CameraSelector)` | `CameraInfo` | 按选择器解析出将要绑定的 `CameraInfo` |
| `configureInstance(CameraXConfig)` | `static`，1.4+ 实验性 | 不便继承 `Application` 时（如库代码）的一次性预配置 |

**绑定规则要点**：所有 bind/unbind 必须在**主线程**调用（否则 `IllegalStateException`）；同一 Use Case 不能同时绑定两个 Lifecycle；单个 camera 最多 3 个 Use Case；用不同 selector 解析到不同 camera 再绑同一 owner 会抛异常；重新 bind 同一 UseCase 即更新其配置。

### 2.6.2 CameraSelector

官方定义："A set of requirements and priorities used to select a camera or return a filtered set of cameras."——声明式选相机。

| 常量 / 方法 | 说明 |
|---|---|
| `DEFAULT_BACK_CAMERA` / `DEFAULT_FRONT_CAMERA` | 预置的后置 / 前置选择器（最常用） |
| `LENS_FACING_FRONT = 0` / `LENS_FACING_BACK = 1` | lensFacing 常量 |
| `LENS_FACING_EXTERNAL = 2` | 外置相机（实验性，`@ExperimentalLensFacing`，行为依赖厂商） |
| `Builder.requireLensFacing(int)` | 要求指定朝向；多次调用是叠加条件而非覆盖 |
| `Builder.addCameraFilter(CameraFilter)` | 自定义过滤器（可基于 Camera2 characteristics 挑相机）；多个 filter 依添加顺序生效，取首个命中结果 |
| `Builder.setPhysicalCameraId(String)` | 1.4+ 选择逻辑多摄中的某个物理摄像头（如超广角） |
| `filter(List<CameraInfo>)` | 对传入列表按 filter 过滤，例如"取出所有后置相机" |
| `of(CameraIdentifier...)` | 1.5+ 按优先级列表指定具体相机 |

### 2.6.3 CameraXConfig

官方定义："A configuration for adding implementation and user-specific behavior to CameraX."——初始化配置，作用于 provider 的整个生命周期。

**配置途径**（二选一，不配置则使用默认值）：
1. 继承 `Application` 并实现 `CameraXConfig.Provider`，返回 `getCameraXConfig()`；
2. 库代码场景：首次 `getInstance()` 前调用 `ProcessCameraProvider.configureInstance(config)`（实验性）。

| `CameraXConfig.Builder` 方法 | 说明 |
|---|---|
| `fromConfig(Camera2Config.defaultConfig())` | 以 Camera2 默认实现为基底（固定写法） |
| `setAvailableCamerasLimiter(CameraSelector)` | 限定可用相机（如只要后置可显著缩短初始化时间，省去前置枚举） |
| `setCameraExecutor(Executor)` | 驱动 camera 栈的线程池（不设则用内部默认） |
| `setSchedulerHandler(Handler)` | 内部任务调度 Handler（一般无需设置） |
| `setMinimumLoggingLevel(int)` | 最低日志级别（默认 DEBUG；release 建议设 ERROR） |
| `setCameraOpenRetryMaxTimeoutInMillisWhileResuming(long)` | 1.4+ 前台恢复时反复尝试打开相机的超时 |
| `build()` | 生成不可变 `CameraXConfig` |

```kotlin
class MyApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
}
```

---

## 2.7 典型使用流程

> 来源：[ProcessCameraProvider | API reference | Android Developers](https://developer.android.google.cn/reference/androidx/camera/lifecycle/ProcessCameraProvider)

标准流程：**加依赖 → 初始化 provider → 构建 Use Case → bindToLifecycle → 运行时操作（拍照/分析/录像）→ unbind（或交由 lifecycle 自动管理）**。

```kotlin
// ── 0. 依赖（build.gradle.kts，版本以官方 release 页为准）──
// val cameraxVersion = "1.6.1" // 示例：稳定版
// implementation("androidx.camera:camera-core:${cameraxVersion}")
// implementation("androidx.camera:camera-camera2:${cameraxVersion}")   // Camera2 实现
// implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
// implementation("androidx.camera:camera-view:${cameraxVersion}")      // PreviewView

class CameraFragment : Fragment() {
    private lateinit var imageCapture: ImageCapture

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ── 1. 初始化：异步获取 ProcessCameraProvider ──
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()   // 或 await()（suspend）

            // ── 2. 构建 Use Case ──
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)  // PreviewView 提供的 provider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            // ── 3. 绑定到 lifecycle（主线程；STARTED 即开流）──
            val camera = cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture
            )
            // camera.cameraControl 可做 zoom / focus / torch 等运行时操作

            // ── 4. 拍照（点击快门时调用）──
            takePhoto()

            // ── 5. 解绑（通常交给 lifecycle 自动处理，离开页面时调用更稳妥）──
            // cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val file = File(requireContext().filesDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    // results.savedUri
                }
                override fun onError(exception: ImageCaptureException) {
                    // 按 exception.errorCode 处理
                }
            })
    }
}
```

流程要点小结：
1. **初始化是异步的**：`getInstance()` 返回 `ListenableFuture`，完成前不可 bind；初始化成本可用 `setAvailableCamerasLimiter` 降低；
2. **bind 是幂等入口**：配置变化时直接重新 `bindToLifecycle` 即可更新，不必手动 unbind；
3. **生命周期自动管理**：Fragment/Activity 进入 `STARTED` 自动恢复、`DESTROYED` 自动关闭；主动停止（如切换相机）才调 `unbind`/`unbindAll`；
4. **目标旋转**：屏幕方向变化时应更新各 Use Case 的 `setTargetRotation`，否则照片/视频方向可能不对；
5. **回调线程自选**：`takePicture`、`startRecording`、`setAnalyzer` 均要求显式传 `Executor`。



# 第 3 章 Camera1（旧版）与 NDK 相机接口

> 本章为接口速查 + 学习总结。Camera1 指 `android.hardware.Camera`（API 1 引入，API 21 起 deprecated，被 Camera2 取代但仍长期维护）；NDK 相机接口指 libcamera2（`<camera/NdkCamera*.h>`，API 24 起可用），是 Java Camera2 的 C 封装。

## 3.1 Camera1 概览：定位、状态机与生命周期

> 来源：[Camera | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera)

### 3.1.1 定位

- `android.hardware.Camera` 自 **API 1** 起提供，是 Android 第一代相机 API，用于控制设备相机、拍照与录像。
- **API 21 起 deprecated**：官方推荐新应用使用 `android.hardware.camera2`。Camera1 至今仍可用（backward compatible），大量存量代码与教程基于它，是理解相机框架演进的起点。
- 权限要求：`Manifest.permission.CAMERA`；建议配合 `<uses-feature android:name="android.hardware.camera" />` 声明。
- **非线程安全**：绝大多数方法须在调用 `open()` 的线程上执行；各类回调投递到调用 `open()` 的线程的事件循环（Looper）。若该线程无 Looper，则回调投递到主线程事件循环。因此常见写法是在专属 HandlerThread 中 open 相机。

### 3.1.2 状态机与合法调用顺序

Camera1 没有显式的 State 对象，状态隐含在方法调用序列中。整理自官方文档给出的拍照/录像步骤，可归纳为以下状态与合法迁移：

```
Released/Uninitialized
   │ open()（拿到 Camera 实例，进入 Idle）
   ▼
Idle（可 setParameters / setPreviewDisplay / setPreviewTexture）
   │ setPreviewDisplay()/setPreviewTexture() + startPreview()
   ▼
Preview ──► takePicture() ──► Capture-in-progress（预览自动停止）
   │  ▲                            │ jpeg 回调返回后 startPreview() 恢复
   │  └────────────────────────────┘
   │ unlock() → 交给 MediaRecorder.setCamera()
   ▼
Recording（录像中；此期间不可再 unlock）
   │ MediaRecorder.stop() → reconnect()（重新拿回 Camera 控制权）
   ▼
Preview / Idle
   │ stopPreview()
   ▼
Stopped ──startPreview()──► Preview
   │ release()（任一非 Released 状态均可调用）
   ▼
Released（实例不可复用，需重新 open()）
```

合法调用顺序要点（摘自官方 javadoc 的拍照 10 步与录像说明）：

1. `open(int)` 打开相机；
2. `getParameters()` → 修改 → `setParameters()`；
3. `setDisplayOrientation(int)` 设置预览方向；
4. `setPreviewDisplay(SurfaceHolder)`（重要，须在 `startPreview()` 之前调用）；
5. `startPreview()`（重要，`takePicture()` 前必须已启动预览）；
6. `takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)`；
7. 拍照后预览会停止，JPEG 回调返回后须再次 `startPreview()` 恢复预览；
8. 录像流程：open → startPreview → `unlock()` → `MediaRecorder.setCamera(camera)` → 录制 → `reconnect()` → stopPreview → release；
9. `release()` 重要：应在 `Activity.onPause()` 中释放，`onResume()` 中重新 open。

非法调用的典型表现：未 startPreview 就 takePicture、在预览中途（API 14 前）调 setDisplayOrientation、录像中再次 unlock、对已 release 的实例调用任何方法（抛 `RuntimeException`）。

### 3.1.3 open() / release() 生命周期

- `open()` / `open(int cameraId)`：打开指定（或默认后置）相机。多摄像头时 id 取值 `0 .. getNumberOfCameras()-1`。**必须与 release() 成对出现**，否则相机服务被独占，其他应用无法使用。
- `reconnect()`：录像结束后从 MediaRecorder 手中重新接管相机（API 14 起 MediaRecorder.stop 后 Camera 通常已可复用，显式 reconnect 更稳妥）。
- `lock()` / `unlock()`：相机锁迁移给 MediaRecorder 前后使用。API 14 起录像后不再需要显式 `lock()`。
- 典型 Activity 生命周期配合：`onResume()` → open + startPreview；`onPause()` → stopPreview + release。相机是全局竞争资源，失去焦点即应释放。

## 3.2 Camera 类方法速查表

> 来源：[Camera | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera)

`public class Camera extends Object`。内部类包括 `CameraInfo`、`Parameters`、`Size`、`Area`、`Face` 及各回调接口（见 3.4）。常用方法如下：

| 方法 | 签名 / 版本 | 说明 |
|---|---|---|
| `open` | `static Camera open()` / `open(int cameraId)` | 打开默认（后置）或指定 id 的相机；失败抛 `RuntimeException` |
| `getNumberOfCameras` | `static int getNumberOfCameras()` | 返回可用相机数（含外接 USB 相机，可动态变化） |
| `getCameraInfo` | `static void getCameraInfo(int, CameraInfo)` | 读取指定相机的朝向、旋转角等 |
| `setPreviewDisplay` | `void setPreviewDisplay(SurfaceHolder)` | 设置 SurfaceView 预览；须在 surfaceCreated 后、startPreview 前调用 |
| `setPreviewTexture` | `API 11` `void setPreviewTexture(SurfaceTexture)` | 设置 GL 纹理预览（用于自定义渲染/离屏处理） |
| `setDisplayOrientation` | `void setDisplayOrientation(int degrees)` | 顺时针旋转预览（0/90/180/270）；不影响 PreviewCallback 数据、JPEG 照片与录像；API 14 起可在预览中调用 |
| `setParameters` | `void setParameters(Parameters)` | 应用参数（须先 getParameters 修改再整体回写） |
| `getParameters` | `Parameters getParameters()` | 取当前参数快照 |
| `startPreview` | `void startPreview()` | 启动预览；takePicture 的前置条件 |
| `stopPreview` | `void stopPreview()` | 停止预览 |
| `takePicture` | `void takePicture(ShutterCallback, PictureCallback raw, PictureCallback postview, PictureCallback jpeg)` | 四阶段回调拍照；raw 可能为 null（无 raw buffer 或 buffer 不够大），postview 并非所有设备支持；JPEG 回调返回前不得再 startPreview/再拍照 |
| `autoFocus` | `void autoFocus(AutoFocusCallback)` | 发起单次对焦（仅预览中有效）；闪光模式非 OFF 时对焦过程可能闪灯 |
| `cancelAutoFocus` | `void cancelAutoFocus()` | 取消进行中的对焦 |
| `setPreviewCallback` | `void setPreviewCallback(PreviewCallback)` | 每帧回调 `onPreviewFrame(byte[], Camera)`，默认 NV21 |
| `setPreviewCallbackWithBuffer` | `void setPreviewCallbackWithBuffer(PreviewCallback)` | 带 buffer 回调，须用 `addCallbackBuffer` 提供 buffer，回调后 buffer 被消耗、需重新 add，目的是复用内存 |
| `setOneShotPreviewCallback` | `void setOneShotPreviewCallback(PreviewCallback)` | 只回调一帧 |
| `addCallbackBuffer` | `void addCallbackBuffer(byte[])` | 为 withBuffer 模式补充预览 buffer |
| `startSmoothZoom` | `void startSmoothZoom(int value)` | 平滑变焦到目标值（0..`getMaxZoom()`），须 `isSmoothZoomSupported()` |
| `stopSmoothZoom` | `void stopSmoothZoom()` | 停止平滑变焦 |
| `setZoomChangeListener` | `void setZoomChangeListener(OnZoomChangeListener)` | 变焦进度回调 |
| `startFaceDetection` / `stopFaceDetection` | `API 14` | 开/关人脸检测，回调经 `setFaceDetectionListener` |
| `enableShutterSound` | `API 17` `boolean enableShutterSound(boolean)` | 开/关快门音；`CameraInfo.canDisableShutterSound == false` 时传 false 无效 |
| `lock` / `unlock` | `void lock()` / `void unlock()` | 相机锁：unlock 后可交给 MediaRecorder，重新接管用 lock/reconnect |
| `reconnect` | `void reconnect()` | 从 MediaRecorder 处重新接管相机 |
| `release` | `void release()` | 释放相机资源；之后实例不可复用 |
| `setErrorCallback` | `void setErrorCallback(ErrorCallback)` | 设置错误回调（见 3.4） |
| `setAutoFocusMoveCallback` | `API 16` | 连续对焦启停回调 |

类常量（错误码，见 3.4）：`CAMERA_ERROR_UNKNOWN(1)`、`CAMERA_ERROR_SERVER_DIED(100)`、`CAMERA_ERROR_EVICTED(2)`；另有广播常量 `ACTION_NEW_PICTURE`、`ACTION_NEW_VIDEO`。

## 3.3 Camera.Parameters 与 Camera.CameraInfo

### 3.3.1 Parameters：关键方法

> 来源：[Camera.Parameters | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera.Parameters)

Camera1 参数模型：`getParameters()` 取快照 → 修改字段 → `setParameters()` 整体生效；各能力须先查 `getSupportedXxx()`（不支持时返回 null），不能盲设。

| 方法 | 说明 |
|---|---|
| `setPreviewFormat(String)` / `getSupportedPreviewFormats()` | 预览帧格式，默认 **NV21**（NV21 必支持）；YV12 自 API 12 起为必支持项。该格式决定 `onPreviewFrame` 的 byte[] 布局 |
| `setPreviewSize(int w, int h)` / `getSupportedPreviewSizes()` | 预览分辨率；须从支持列表中选，并考虑显示方向与屏幕比例 |
| `setPictureSize(int w, int h)` / `getSupportedPictureSizes()` | 拍照 JPEG 分辨率 |
| `setPictureFormat(String)` / `getSupportedPictureFormats()` | 拍照格式，通常 ImageFormat.JPEG |
| `setFocusMode(String)` / `getSupportedFocusModes()` | 对焦模式，见下表 |
| `setFlashMode(String)` / `getSupportedFlashModes()` | 闪光模式 |
| `setSceneMode(String)` / `getSupportedSceneModes()` | 场景模式；**设置 scene mode 可能覆盖其他参数**（如 flash mode），应最后设置并复核 |
| `setWhiteBalance(String)` / `getSupportedWhiteBalance()` | 白平衡 |
| `setExposureCompensation(int)` / `getMinExposureCompensation()` / `getMaxExposureCompensation()` / `getExposureCompensationStep()` | 曝光补偿：以 step 为单位、按 index 设置（EV = index × step），合法范围 [min, max] |
| `setZoom(int)` / `getMaxZoom()` / `getZoomRatios()` / `isZoomSupported()` / `isSmoothZoomSupported()` | 数码变焦（1x..max，按 ZoomRatios 表格） |
| `setJpegQuality(int)` | JPEG 压缩质量 1..100 |
| `setRotation(int)` | 拍照旋转角（0/90/180/270，写入 EXIF）。推荐配合 `OrientationEventListener`：后置 `rotation = (info.orientation + degrees) % 360`，前置 `rotation = (info.orientation - degrees + 360) % 360` |
| `setFocusAreas(List<Area>)` / `setMeteringAreas(List<Area>)` | 对焦/测光区域：坐标 -1000..1000（左上为 -1000），weight 1..1000；数量上限 `getMaxNumFocusAreas()` / `getMaxNumMeteringAreas()`；仅部分 focus mode 下生效 |
| `setAutoExposureLock(boolean)` / `setAutoWhiteBalanceLock(boolean)` | 锁定 AE / AWB（连续模式下才有意义） |
| `setRecordingHint(boolean)` | 提示相机将进入录像用途，可加快 startPreview/录制启动 |
| `setVideoStabilization(boolean)` / `isVideoStabilizationSupported()` | 电子防抖 |
| `setGpsLatitude/Longitude/Altitude/Timestamp/ProcessingMethod`、`removeGpsData()` | 给 JPEG 注入 GPS EXIF |
| `getSupportedVideoSizes()` / `getPreferredPreviewSizeForVideo()` | 录像支持的分辨率；不设时用预览尺寸 |
| `getFocalLength()` / `getHorizontalViewAngle()` / `getVerticalViewAngle()` / `getFocusDistances(float[])` | 焦距（mm）、视场角、对焦距离（NEAR/OPTIMAL/FAR 三档，米） |
| `flatten()` / `unflatten(String)` | 参数与字符串互转（调试、进程间传递用） |

### 3.3.2 Parameters 常用模式常量

| 类别 | 常量（字符串值） |
|---|---|
| FocusMode | `FOCUS_MODE_AUTO`("auto")、`CONTINUOUS_PICTURE`("continuous-picture")、`CONTINUOUS_VIDEO`("continuous-video")、`MACRO`("macro")、`INFINITY`("infinity")、`FIXED`("fixed")、`EDOF`("edof") |
| FlashMode | `FLASH_MODE_OFF`、`ON`、`AUTO`、`RED_EYE`、`TORCH`（常亮手电筒） |
| SceneMode | `AUTO`、`ACTION`、`PORTRAIT`、`LANDSCAPE`、`NIGHT`、`NIGHT_PORTRAIT`、`THEATRE`、`BEACH`、`SNOW`、`SUNSET`、`STEADYPHOTO`、`FIREWORKS`、`SPORTS`、`PARTY`、`CANDLELIGHT`、`BARCODE`、`HDR`(API 17) |
| WhiteBalance | `AUTO`、`INCANDESCENT`、`FLUORESCENT`、`WARM_FLUORESCENT`、`DAYLIGHT`、`CLOUDY_DAYLIGHT`、`TWILIGHT`、`SHADE` |
| Effect（色彩效果） | `NONE`、`MONO`、`NEGATIVE`、`SOLARIZE`、`SEPIA`、`POSTERIZE`、`WHITEBOARD`、`BLACKBOARD`、`AQUA` |
| Antibanding（防频闪） | `ANTIBANDING_AUTO`、`50HZ`、`60HZ`、`OFF` |

配套取值方法：`getFocusMode()`、`getFlashMode()`、`getSceneMode()`、`getWhiteBalance()`、`getColorEffect()`、`getAntibanding()`、`getExposureCompensation()`、`getZoom()`、`getJpegQuality()`、`getRotation()`、`getPreviewSize()`、`getPictureSize()`、`getPreviewFormat()` 等。

### 3.3.3 Camera.CameraInfo

> 来源：[Camera.CameraInfo | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera.CameraInfo)

通过 `Camera.getCameraInfo(int cameraId, CameraInfo info)` 填充，描述某颗相机硬件信息：

| 字段 | 类型 | 说明 |
|---|---|---|
| `facing` | `int` | 朝向：`CAMERA_FACING_BACK = 0`（后置，朝向与屏幕相反）、`CAMERA_FACING_FRONT = 1`（前置，朝向与屏幕相同） |
| `orientation` | `int` | 相机图像需顺时针旋转的角度才能与自然方向一致显示，取值 0/90/180/270 |
| `canDisableShutterSound` | `int`（API 17） | 是否允许关闭快门音（法规要求地区为 false）；为 0 时 `enableShutterSound(false)` 失败，takePicture 仍会发声 |

配套常量即 `CAMERA_FACING_BACK` / `CAMERA_FACING_FRONT`。多摄像头设备中 Camera1 的逻辑是"每个朝向暴露一个 id"，由 HAL 聚合。

## 3.4 回调体系与错误码

> 来源：[Camera.ErrorCallback | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera.ErrorCallback)

Camera1 的异步事件全部通过回调接口表达，均在 `open()` 线程（或主线程）的事件循环上回调：

| 回调接口 | 方法签名 | 触发时机 |
|---|---|---|
| `ShutterCallback` | `onShutter()` | 拍照快门时刻（用于播放快门音/动画；无参数） |
| `PictureCallback` | `onPictureTaken(byte[] data, Camera camera)` | 拍照数据就绪：raw（可能 null）、postview（不一定支持）、jpeg 三个阶段各回调一次 |
| `PreviewCallback` | `onPreviewFrame(byte[] data, Camera camera)` | 预览帧数据（格式由 `setPreviewFormat` 决定，默认 NV21），经 setPreviewCallback / setOneShotPreviewCallback / setPreviewCallbackWithBuffer 三种方式注册 |
| `AutoFocusCallback` | `onAutoFocus(boolean success, Camera camera)` | `autoFocus()` 完成或失败（success=false 表示未对上） |
| `AutoFocusMoveCallback`（API 16） | `onAutoFocusMoving(boolean start, Camera camera)` | 连续对焦开始/停止 |
| `FaceDetectionListener`（API 14） | `onFaceDetection(Face[] faces, Camera camera)` | 人脸检测结果（faces 可能为空数组） |
| `OnZoomChangeListener` | `onZoomChange(int zoomValue, boolean stopped, Camera camera)` | 平滑变焦过程中每步回调，stopped=true 表示到位 |
| `ErrorCallback` | `onError(int error, Camera camera)` | 相机发生严重错误（见下表） |

ErrorCallback 错误码表：

| 错误码 | 值 | 含义与处理 |
|---|---|---|
| `CAMERA_ERROR_UNKNOWN` | 1 | 未指定错误；建议停止预览并 release，必要时重新 open |
| `CAMERA_ERROR_SERVER_DIED` | 100 | 相机服务进程死亡；**必须** release 当前实例并重新创建 Camera 对象 |
| `CAMERA_ERROR_EVICTED` | 2（API 23 增补） | 相机被更高优先级用户（如系统/其他应用）抢占断开；必须 release，之后可重试重新打开 |

处理约定：`onError` 返回后该 Camera 实例可能已不可用，标准做法是立即 `stopPreview()` + `release()`，并视错误类型决定是否重新 `open()`。

## 3.5 NDK libcamera2（`<camera/NdkCamera*.h>`）

> 来源：[Camera | Android NDK | Android Developers](https://developer.android.google.cn/ndk/reference/group/camera)

### 3.5.1 架构与头文件

- NDK 相机 API 是 **Java Camera2 的 C 语言封装**，自 **API 24（Android 7.0）起可用**；两者共享同一套 HAL 语义（Session / Request / Metadata）。
- 核心头文件：
  - `<camera/NdkCameraManager.h>` — 相机发现、可用性监听、打开设备；
  - `<camera/NdkCameraDevice.h>` — 设备对象、创建请求模板与 CaptureSession、关闭设备、错误/断开回调；
  - `<camera/NdkCameraCaptureSession.h>` — 会话：单次/重复请求、停止、中止；
  - `<camera/NdkCaptureRequest.h>` — `ACaptureRequest`：输出目标与 metadata 字段设置；
  - `<camera/NdkCameraMetadata.h>` / `<camera/NdkCameraMetadataTags.h>` — characteristics/results 元数据读写与标签枚举；
  - `<camera/NdkCameraError.h>` — `camera_status_t` 错误码；
  - `<media/NdkImageReader.h>`（`AImageReader`/`AImage`）— 取帧通道（API 24 起），对应 Java 侧 ImageReader。
- 所有句柄均为不透明指针：`ACameraManager`、`ACameraDevice`、`ACameraCaptureSession`、`ACaptureRequest`、`ACameraMetadata`、`ACameraOutputTarget`、`ACaptureSessionOutput`、`ACaptureSessionOutputContainer`、`ACameraIdList`。
- 返回值统一为 `camera_status_t`：`ACAMERA_OK`（0）为成功；错误如 `ACAMERA_ERROR_CAMERA_DISCONNECTED`、`ACAMERA_ERROR_CAMERA_IN_USE`、`ACAMERA_ERROR_MAX_CAMERA_IN_USE`、`ACAMERA_ERROR_CAMERA_DISABLED`、`ACAMERA_ERROR_PERMISSION_DENIED`、`ACAMERA_ERROR_INVALID_PARAMETER`、`ACAMERA_ERROR_STREAM_CONFIGURE_FAIL`、`ACAMERA_ERROR_SESSION_CLOSED` 等（基址 `ACAMERA_ERROR_BASE = -10000`）。

与 Java Camera2 的对应关系：

| NDK 类型 | Java Camera2 对应 |
|---|---|
| `ACameraManager` | `android.hardware.camera2.CameraManager` |
| `ACameraDevice` | `CameraDevice` |
| `ACameraCaptureSession` | `CameraCaptureSession` |
| `ACaptureRequest` | `CaptureRequest` / `CaptureRequest.Builder` |
| `ACameraMetadata` | `CameraCharacteristics` / `CaptureResult` / `CameraInfo` |
| `ACameraManager_AvailabilityCallbacks` | `AvailabilityCallback` |
| `ACameraDevice_StateCallbacks` | `CameraDevice.StateCallback` |
| `ACameraCaptureSession_stateCallbacks` | `StateCallback`(Session) |
| `AImageReader` / `AImage` | `ImageReader` / `Image` |

### 3.5.2 关键函数分组表

**ACameraManager（NdkCameraManager.h）**

| 函数 | 说明 |
|---|---|
| `ACameraManager_create()` / `ACameraManager_delete()` | 创建/销毁 manager（全局入口） |
| `ACameraManager_getCameraIdList(m, ACameraIdList**)` / `ACameraManager_deleteCameraIdList()` | 枚举相机 id 列表 |
| `ACameraManager_getCameraCharacteristics(m, id, ACameraMetadata**)` | 取该相机特性（只读 metadata） |
| `ACameraManager_registerAvailabilityCallback(m, cb)` / `unregister` | 设备可用/不可用回调（对应 AvailabilityCallback） |
| `ACameraManager_openCamera(m, id, callbacks, ACameraDevice**)` | 打开相机；`callbacks` 为 `ACameraDevice_StateCallbacks`（onDisconnected/onError） |

**ACameraDevice（NdkCameraDevice.h）**

| 函数 | 说明 |
|---|---|
| `ACameraDevice_createCaptureRequest(dev, template, ACaptureRequest**)` | 按模板创建请求：`TEMPLATE_PREVIEW(1)`、`TEMPLATE_STILL_CAPTURE(2)`、`TEMPLATE_RECORD(3)`、`TEMPLATE_VIDEO_SNAPSHOT(4)`、`TEMPLATE_ZERO_SHUTTER_LAG(5)`、`TEMPLATE_MANUAL(6)` |
| `ACameraDevice_createCaptureSession(dev, outputs(ACameraOutputTargets*), stateCb, session**)` | 用输出目标集合创建会话（配 SessionStateCallbacks：onClosed/onReady/onActive） |
| `ACameraDevice_getId(dev)` | 返回设备 id 字符串 |
| `ACameraDevice_close(dev)` | 关闭设备（回调仍可能触发一次 onClosed/onDisconnected） |
| 设备错误回调 | `onError(dev, int error, ctx)`，error 取值：`ERROR_CAMERA_IN_USE(1)`、`ERROR_MAX_CAMERAS_IN_USE(2)`、`ERROR_CAMERA_DISABLED(3)`、`ERROR_CAMERA_DEVICE(4)`、`ERROR_CAMERA_SERVICE(5)` |

**ACameraCaptureSession（NdkCameraCaptureSession.h）**

| 函数 | 说明 |
|---|---|
| `ACameraCaptureSession_capture(s, cb, n, requests[])` | 单次下发 1..n 个请求（对应 capture()） |
| `ACameraCaptureSession_setRepeatingRequest(s, cb, n, requests[])` | 重复下发（预览/录像流） |
| `ACameraCaptureSession_stopRepeating(s)` | 停止重复请求 |
| `ACameraCaptureSession_abortCaptures(s)` | 中止所有在途请求 |
| `ACameraCaptureSession_close(s)` | 关闭会话 |

**ACaptureRequest / ACameraOutputTarget（NdkCaptureRequest.h）**

| 函数 | 说明 |
|---|---|
| `ACaptureRequest_setEntry_u8/i32/i64/float/double/rational(req, tag, count, value)` | 按标签写入 metadata（控制曝光、对焦、ISO 等所有 Camera2 控制项） |
| `ACaptureRequest_getConstEntry(req, tag, ACameraMetadata_const_entry*)` | 读取请求中某标签当前值 |
| `ACaptureRequest_addTarget(req, target)` / `removeTarget` | 挂接/移除输出目标（如 AImageReader 的 ANativeWindow、SurfaceView） |
| `ACaptureRequest_copy(from, to)` / `ACaptureRequest_free(req)` | 复制/释放请求 |
| `ACameraOutputTarget_create(ANativeWindow*, target**)` / `free` | 用 ANativeWindow 造输出目标 |
| `ACaptureSessionOutput_create(ANativeWindow*, out**)`、`ACaptureSessionOutputContainer_create/add/free` | 组装会话输出容器 |

**ACameraMetadata（NdkCameraMetadata.h）**：`ACameraMetadata_getConstEntry(m, tag, entry*)`、`ACameraMetadata_getAllTags(m, count, tags**)`、`ACameraMetadata_copy`、`ACameraMetadata_free`；标签常量在 `NdkCameraMetadataTags.h`（如 `ACAMERA_SENSOR_EXPOSURE_TIME`、`ACAMERA_FLASH_MODE`、`ACAMERA_CONTROL_AF_MODE` 等），语义与 Java `CameraMetadata` 一致。

**AImageReader（media/NdkImageReader.h，简述）**：`AImageReader_new(w, h, format, maxImages, reader**)` 创建帧接收器（常用 `AIMAGE_FORMAT_YUV_420_888`、`AIMAGE_FORMAT_JPEG`、`AIMAGE_FORMAT_RAW16`），`AImageReader_getWindow(reader, ANativeWindow**)` 取出窗口喂给 `ACameraOutputTarget_create`；用 `AImageReader_setImageListener` 异步收帧，`AImage_getPlaneData` 读像素。NDK 相机没有 Camera1 式的 onPreviewFrame，取帧一律走 AImageReader 或 Surface/ANativeWindow。

### 3.5.3 典型调用流程

```
ACameraManager_create()
  → ACameraManager_getCameraIdList()            // 选 id
  → ACameraManager_getCameraCharacteristics()   // 读能力（如 ACAMERA_LENS_FACING）
  → ACameraManager_openCamera(id, stateCallbacks, &device)
  → AImageReader_new(...) → AImageReader_getWindow(&window)
  → ACameraOutputTarget_create(window, &target)
  → ACaptureSessionOutputContainer_create/add(...)
  → ACameraDevice_createCaptureSession(device, outputs, sessionCallbacks, &session)
  → ACameraDevice_createCaptureRequest(device, TEMPLATE_PREVIEW, &request)
  → ACaptureRequest_addTarget(request, target)
  → ACameraCaptureSession_setRepeatingRequest(session, captureCb, 1, &request)
  // 一次性抓拍用 ACameraCaptureSession_capture()；结束用 stopRepeating()
  → ACameraDevice_close(device) / ACaptureRequest_free / ACameraManager_delete
```

## 3.6 Camera1 与 Camera2 / NDK 对比小结

> 来源：[Camera | API reference | Android Developers](https://developer.android.google.cn/reference/android/hardware/Camera)、[Camera | Android NDK | Android Developers](https://developer.android.google.cn/ndk/reference/group/camera)

| 维度 | Camera1（android.hardware.Camera） | Camera2（java，API 21+） | NDK libcamera2（C，API 24+） |
|---|---|---|---|
| 定位 | 第一代 API，已 deprecated 但仍维护 | 当前官方主推 | Camera2 的 C 封装，供 native 代码使用 |
| 编程模型 | 命令式：open → setParameters → startPreview → takePicture | 会话/请求管道：CameraDevice → CaptureSession → RepeatingRequest | 与 Camera2 相同，纯 C 句柄 + 回调结构体 |
| 参数控制 | `Camera.Parameters` 字符串扁平模型，粗粒度 | `CaptureRequest` metadata，细粒度（逐帧可变） | `ACaptureRequest` + ACAMERA_* 标签，与 Java metadata 同一套语义 |
| 预览输出 | SurfaceHolder / SurfaceTexture | 多 Surface（同会话多路输出） | ANativeWindow 列表（ACaptureSessionOutputContainer） |
| 帧数据获取 | `onPreviewFrame`（NV21） | ImageReader / 各类 Surface | AImageReader / AImage（`media/NdkImageReader.h`） |
| RAW / 手动控制 | 基本不支持 | 支持（RAW_SENSOR、手动曝光/对焦、Burst） | 同 Camera2，支持 RAW（AIMAGE_FORMAT_RAW16） |
| 多相机/逻辑多摄 | 不支持（每朝向一个 id） | 支持（logical multi-camera） | 支持（getCameraCharacteristics 读逻辑信息） |
| 回调与状态 | 单一线程事件循环回调，状态机隐式 | 显式状态回调（onOpened/onDisconnected/onError、SessionState） | 结构体回调（StateCallbacks、SessionStateCallbacks、CameraCaptureCallbacks） |
| 典型场景 | 存量代码、极简拍照 | 新 Android 应用、专业拍照/录像 | 游戏引擎、跨平台 SDK、native 图像处理管线 |

迁移提示：从 Camera1 迁往 Camera2/NDK 时，"setPreviewDisplay+startPreview" 对应 "createCaptureSession+setRepeatingRequest"；"setParameters" 对应 "修改 CaptureRequest 并再次 setRepeatingRequest"；"onPreviewFrame" 对应 "AImageReader/ImageReader 回调"。



# 第 4 章 HAL 层接口（camera3.h 与 AIDL Camera HAL）

> 本章基于 AOSP main 分支源码整理（抓取日期 2026-09-05）：
> `hardware/libhardware/include_all/hardware/camera3.h`、`camera_common.h`，以及 `hardware/interfaces/camera/` 下的 AIDL 接口定义。
> 说明：main 分支上 `include/hardware/camera3.h` 已是指向 `include_all/hardware/camera3.h` 的符号链接，两者内容相同。

## 4.1 HAL 接口演进概览

> 来源：[camera_common.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera_common.h)、[hardware/interfaces/camera 目录](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/)

Android Camera HAL 的接口形态经历了三代演进，每一代都保持了"方法表 / 接口"的语义结构，但绑定方式与进程模型发生了根本变化：

| 阶段 | 接口形态 | 载体 | 绑定方式 | 引入版本 |
|---|---|---|---|---|
| Legacy | `camera_module_t` + `camera_device_t`（camera1/HAL1） | libhardware 动态库（`hw_get_module` dlopen） | 同进程直接函数调用 | Android 1.0 起 |
| HAL3（传统 C 接口） | `camera_module_t` + `camera3_device_t`（camera3.h） | libhardware 动态库 | 同进程直接函数调用（Android 8 起也可由 provider 包装成 HIDL 跑在独立进程） | HAL3.0 自 Android 4.2（ICS 之后）引入 |
| HIDL | `android.hardware.camera.provider@2.x` / `camera.device@3.x` | hardware/interfaces | Binderized HIDL（独立 provider 进程） | Android 8.0（O） |
| AIDL | `android.hardware.camera.provider` / `android.hardware.camera.device`（.aidl） | hardware/interfaces | AIDL Binder（独立 provider 进程） | **Android 13（Tiramisu）** |

C HAL 设备版本宏（`camera_common.h`，main 分支实际定义到 3_6）：

```c
#define CAMERA_DEVICE_API_VERSION_1_0   // DEPRECATED（HAL1）
#define CAMERA_DEVICE_API_VERSION_2_0   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_2_1   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_0   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_1   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_2   // 最低仍被支持的 HAL3 版本
#define CAMERA_DEVICE_API_VERSION_3_3
#define CAMERA_DEVICE_API_VERSION_3_4
#define CAMERA_DEVICE_API_VERSION_3_5
#define CAMERA_DEVICE_API_VERSION_3_6
#define CAMERA_DEVICE_API_VERSION_CURRENT  CAMERA_DEVICE_API_VERSION_3_5
// 模块版本：CAMERA_MODULE_API_VERSION_1_0 ~ 2_5，CURRENT = 2_5
```

版本与 Android 发行版的大致对应（学习参考，非严格规范）：

| C HAL 设备版本 | HIDL camera.device | HIDL camera.provider | Android |
|---|---|---|---|
| 3.2 | @3.2 | @2.4 | 8.0（O） |
| 3.3 | @3.3 | @2.4 | 8.1（O MR1） |
| 3.4 | @3.4 | @2.4 | 9（P） |
| 3.5 | @3.5 | @2.5 | 10（Q） |
| 3.6 | @3.6 | @2.6 | 11（R） |
| 3.6（C 侧无 3_7 宏） | @3.7 | @2.7 | 13（T，与 AIDL 同期） |
| —（被取代） | —（AIDL 接管） | `android.hardware.camera.provider`（AIDL） | **13（T）起新设备使用** |

演进要点：

1. **进程隔离**：HIDL/AIDL 时代，Camera HAL 运行在独立的 `camera.provider` 进程中，与 `cameraserver` 通过 Binder 通信，HAL 崩溃不再拖垮相机服务；C 接口时代 HAL 与 framework 同进程（legacy 直通）。
2. **发现模型变化**：C 接口由 `camera_module_t.get_number_of_cameras()` 静态枚举（内置摄像头 0..N-1）；provider 模型由 `ICameraProvider.getCameraIdList()` + `ICameraProviderCallback.cameraDeviceStatusChange()` 动态上报（支持 USB/外接摄像头热插拔）。
3. **能力演进**：HAL3.3 引入 reprocess 与 RAW 修正；3.4 引入部分结果/物理摄像头元数据完善与 output buffer 错误码；3.5 引入 `session_parameters`、物理摄像头设置、`is_reconfiguration_required` 前身（模块级查询）；3.6 引入 `signal_stream_flush`、`is_reconfiguration_required`、HAL buffer manager（`requestStreamBuffers/returnStreamBuffers`）。
4. **AIDL 取代 HIDL**：Android 13 起新设备要求实现 AIDL 接口；AIDL 版本化更轻量（无需 `.hal` 嵌套 import），支持 `ServiceSpecificException` 错误模型与可扩展枚举，框架（`cameraserver`/`Camera2Client`）对两种后端做了统一封装（`CameraProviderManager` 同时管理 HIDL 与 AIDL provider）。

`hardware/interfaces/camera/` 目录结构（main 分支）：

```
camera/
├── common/    # 公共类型（CameraDeviceStatus、TorchModeStatus、VendorTagSection、CameraResourceCost 等）
├── device/    # 设备接口：1.0、3.2~3.7（HIDL）与 aidl/（ICameraDevice*、Stream、CaptureRequest 等约 34 个文件）
├── metadata/  # android.hardware.camera.metadata（元数据 tag 枚举定义）
└── provider/  # provider 接口：2.4~2.7（HIDL）与 aidl/（ICameraProvider、ICameraProviderCallback）
```

## 4.2 camera_common.h 关键定义

> 来源：[camera_common.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera_common.h)

### 4.2.1 camera_module_t（模块方法表）

`camera_module_t` 是传统 C HAL 的模块入口。HAL so 导出 `HAL_MODULE_INFO_SYM`，framework 通过 `hw_get_module()` 加载后把 `hw_module_t` 强转为 `camera_module_t` 使用。

| 成员 | 方向 | 作用 | 引入的 module 版本 |
|---|---|---|---|
| `hw_module_t common` | — | 通用模块头，`common.methods->open` 即 **open()**（打开指定 camera id 的设备，返回 `hw_device_t*`，可强转为 `camera_device_t`）；返回值：0 / `-ENODEV` / `-EINVAL` / `-EBUSY`（已打开）/ `-EUSERS`（并发数上限） | 1.0 |
| `int (*get_number_of_cameras)(void)` | FW→HAL | 返回可访问的摄像头数量（id 为 0..N-1 的字符串）。module 2.4+ 只统计内置（BACK/FRONT）摄像头，外接摄像头通过状态回调上报 | 1.0 |
| `int (*get_camera_info)(int camera_id, struct camera_info *info)` | FW→HAL | 返回某摄像头的静态信息（`camera_info_t`）。2.4+ 对已断开设备应返回 `-EINVAL` | 1.0 |
| `int (*set_callbacks)(const camera_module_callbacks_t *callbacks)` | FW→HAL | 注册模块级异步回调（设备热插拔、torch 状态） | 2.1 |
| `void (*get_vendor_tag_ops)(vendor_tag_ops_t *ops)` | FW→HAL | 查询 vendor 自定义 metadata tag 的查询方法表 | 2.2 |
| `int (*open_legacy)(hw_module_t*, const char* id, uint32_t halVersion, hw_device_t**)` | FW→HAL | 以指定旧版本 HAL API 打开设备（如同一 id 同时支持 HAL1/HAL3），可选，不支持返回 `-ENOSYS` | 2.3 |
| `int (*set_torch_mode)(const char* camera_id, bool enabled)` | FW→HAL | 开关手电筒；成功后必须通过 `torch_mode_status_change()` 上报新状态 | 2.4 |
| `int (*init)(void)` | FW→HAL | HAL so 加载后、其他方法调用前的一次性初始化，可为 NULL | 2.4 |
| `int (*get_physical_camera_info)(int physical_camera_id, camera_metadata_t **static_metadata)` | FW→HAL | 获取逻辑多摄背后"未独立暴露"的物理摄像头的静态 metadata | 2.5 |
| `int (*is_stream_combination_supported)(int camera_id, const camera_stream_combination_t *streams)` | FW→HAL | 模块级流组合查询（不打开设备即可询问某组流是否支持） | 2.5 |
| `void (*notify_device_state_change)(uint64_t deviceState)` | FW→HAL | 通知整机物理状态变化（64 位掩码：低 32 位系统定义，高 32 位 vendor 定义）；在调用任何 `ICameraDevice::open` 之前至少被调用一次 | 2.5（配合 AIDL 时代语义） |
| `void* reserved[2]` | — | 保留 | — |

### 4.2.2 camera_info_t

```c
typedef struct camera_info {
    int facing;              // 朝向：CAMERA_FACING_BACK / FRONT /（2.4+）EXTERNAL
    int orientation;         // 图像需顺时针旋转的角度：0/90/180/270
    uint32_t device_version; // 即 camera_device_t.common.version（CAMERA_DEVICE_API_VERSION_3_x）
    const camera_metadata_t *static_camera_characteristics; // 静态特征 metadata（只读，模块生命周期内有效）
    int resource_cost;       // 2.4+：资源开销 [0,100]，多摄并发仲裁依据
    char** conflicting_devices;   // 2.4+：不能同时打开的冲突设备 id 列表
    size_t conflicting_devices_length;
} camera_info_t;
```

### 4.2.3 状态枚举

`camera_device_status_t`（通过 `camera_device_status_change` 上报；框架默认所有设备 PRESENT）：

| 枚举值 | 含义 |
|---|---|
| `CAMERA_DEVICE_STATUS_NOT_PRESENT = 0` | 设备未连接，open 会失败 |
| `CAMERA_DEVICE_STATUS_PRESENT = 1` | 已连接，可打开 |
| `CAMERA_DEVICE_STATUS_ENUMERATING = 2` | 已连接但正在枚举，open 返回 `-EBUSY` |

合法迁移：PRESENT↔NOT_PRESENT、NOT_PRESENT→ENUMERATING→PRESENT/NOT_PRESENT。

`torch_mode_status_t`（通过 `torch_mode_status_change` 上报；仅对 PRESENT 设备有意义）：

| 枚举值 | 含义 |
|---|---|
| `TORCH_MODE_STATUS_NOT_AVAILABLE = 0` | 闪光灯不可用（如 open() 占用），不能 set_torch_mode |
| `TORCH_MODE_STATUS_AVAILABLE_OFF = 1` | 手电筒关闭且可用 |
| `TORCH_MODE_STATUS_AVAILABLE_ON = 2` | 手电筒已开启 |

`device_state_t`（配合 `notify_device_state_change`）：`NORMAL=0`、`BACK_COVERED=1<<0`、`FRONT_COVERED=1<<1`、`FOLDED=1<<2`、`VENDOR_STATE_START=1LL<<32`。

### 4.2.4 camera_module_callbacks_t（HAL→框架回调）

| 回调 | module 版本 | 作用 |
|---|---|---|
| `void (*camera_device_status_change)(..., int camera_id, int new_status)` | 2.1 | 设备连接状态变化（外接摄像头插拔） |
| `void (*torch_mode_status_change)(..., const char* camera_id, int new_status)` | 2.4 | 手电筒可用状态变化 |

另有两个配套结构（2.5 引入，供 `is_stream_combination_supported` 使用）：`camera_stream_t`（简版流描述，字段与 camera3_stream_t 同名：`stream_type/width/height/format/usage/data_space/rotation/physical_camera_id`）与 `camera_stream_combination_t`（`num_streams/streams/operation_mode`）。

## 4.3 camera3.h 核心结构体与方法表

> 来源：[camera3.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h)

### 4.3.1 camera3_device_t

```c
typedef struct camera3_device {
    hw_device_t common;          // common.version 必须为 CAMERA_DEVICE_API_VERSION_3_x
    camera3_device_ops_t *ops;   // 设备方法表
} camera3_device_t;
```

framework 的 `open()` 拿到 `hw_device_t*` 后按 `common.version` 判断为 HAL3 设备，再通过 `ops` 调用。设备生命周期：`open → initialize → configure_streams → (construct_default_request_settings / process_capture_request ⇄ process_capture_result / notify) → flush → configure_streams… → close`。

### 4.3.2 camera3_device_ops_t（全部 op，方向均为 FW→HAL）

| 方法 | 引入版本 | 作用 |
|---|---|---|
| `int (*initialize)(const camera3_device*, const camera3_callback_ops_t *callback_ops)` | 3.0 | open 后首先调用一次，把回调函数表交给 HAL。非阻塞，应在 5ms 内返回（上限 10ms） |
| `int (*configure_streams)(const camera3_device*, camera3_stream_configuration_t *stream_list)` | 3.0 | 重置管线并配置新的输入/输出流。HAL 通过回写每个 `camera3_stream_t` 的 `usage/max_buffers` 等表达诉求。重负载调用（可到几百 ms） |
| `int (*register_stream_buffers)(const camera3_device*, const camera3_stream_buffer_set_t*)` | 3.0，**3.2 起废弃（必须置 NULL）** | 早期版本为某流预注册 gralloc buffer；3.2+ buffer 直接随 request 传入 |
| `const camera_metadata_t* (*construct_default_request_settings)(const camera3_device*, int type)` | 3.0 | 按 `CAMERA3_TEMPLATE_*` 用途生成默认请求设置（如 PREVIEW/STILL_CAPTURE）。非阻塞，应 1ms、上限 5ms |
| `int (*process_capture_request)(const camera3_device*, camera3_capture_request_t *request)` | 3.0 | 提交一次拍摄/重处理请求（核心路径）。HAL 异步通过 `process_capture_result()` 与 `notify()` 返回结果；提交出错时 buffer fence 归框架所有 |
| `void (*get_metadata_vendor_tag_ops)(const camera3_device*, vendor_tag_ops_t*)` | 3.0 | 填充设备级 vendor tag 查询方法 |
| `void (*dump)(const camera3_device*, int fd)` | 3.0 | dumpsys/bugreport 时输出调试文本（仅 ASCII） |
| `int (*flush)(const camera3_device*)` | 3.1 | 丢弃所有进行中的请求与 buffer，返回后设备静止，可安全 configure_streams 或提交新请求 |
| `void (*signal_stream_flush)(const camera3_device*, uint32_t num_streams, const uint32_t *stream_ids)` | 3.6 | 框架即将进入 `streamUseCases`/离线切换等"drain"流程时，通知 HAL 这些流不再有新 request，HAL 应尽快归还全部该流 buffer（必须非阻塞） |
| `int (*is_reconfiguration_required)(const camera3_device*, const camera_metadata_t *old_session_params, const camera_metadata_t *new_session_params)` | 3.6 | 会话参数变化时询问 HAL 是否需要完整重配置：返回 0 需要重配置，`-EINVAL` 可跳过 |
| `void* reserved[6]` | — | 保留 |

> 注：HAL3 方法表中不存在 `get_metadata_value`、`set_request_queue_size_fn` 这类 op（后者属于已废弃的 camera2 接口），实际 op 以上表 10 个为准。

### 4.3.3 camera3_callback_ops_t（HAL→FW 回调）

| 回调 | 引入版本 | 作用 |
|---|---|---|
| `void (*process_capture_result)(const camera3_callback_ops*, const camera3_capture_result_t *result)` | 3.0 | 返回一个请求的结果（metadata、output buffer、input buffer）。3.2 起允许同一 frame 分多次返回（partial result），同流 buffer 必须按 FIFO 顺序返回 |
| `void (*notify)(const camera3_callback_ops*, const camera3_notify_msg_t *msg)` | 3.0 | 异步事件：快门（SHUTTER）与错误（ERROR_DEVICE/REQUEST/RESULT/BUFFER）。SHUTTER 应尽早发出，buffer 要等 SHUTTER 时间戳才会派发给应用 |
| `camera3_buffer_request_status_t (*request_stream_buffers)(const camera3_callback_ops*, uint32_t num_buffer_reqs, const camera3_buffer_request_t*, uint32_t *num_stream_buffers_ret, camera3_stream_buffer_ret_t*)` | 3.6 | HAL buffer manager 模式下，HAL 主动向框架请求输出 buffer（同步阻塞调用） |
| `void (*return_stream_buffers)(const camera3_callback_ops*, uint32_t num_buffers, const camera3_stream_buffer_t*)` | 3.6 | HAL buffer manager 模式下，把不再使用的 buffer 归还框架 |

`camera3_notify_msg_t` 消息类型（`camera3_msg_type_t`）：`CAMERA3_MSG_ERROR=1`、`CAMERA3_MSG_SHUTTER=2`；错误码（`camera3_error_msg_code_t`）：`CAMERA3_MSG_ERROR_DEVICE=1`（设备级致命错误，之后只能 close）、`CAMERA3_MSG_ERROR_REQUEST=2`（整帧失败）、`CAMERA3_MSG_ERROR_RESULT=3`（metadata 生成失败）、`CAMERA3_MSG_ERROR_BUFFER=4`（单个 buffer 失败，ISM 处理流不能处理）。

请求模板 `camera3_request_template_t`：`CAMERA3_TEMPLATE_PREVIEW=1`、`STILL_CAPTURE=2`、`VIDEO_RECORD=3`、`VIDEO_SNAPSHOT=4`、`ZERO_SHUTTER_LAG=5`、`MANUAL=6`（vendor 可定义 `CAMERA3_VENDOR_TEMPLATE_*`，从 `CAMERA3_TEMPLATE_COUNT=7` 起）。

### 4.3.4 camera3_stream_t 关键字段

流（stream）是 framework 与 HAL 之间按固定格式/分辨率循环传递 buffer 的通道。字段按写入者分三类：

| 字段 | 设置者 | 说明 |
|---|---|---|
| `int stream_type` | FW | `CAMERA3_STREAM_OUTPUT=0` / `INPUT=1` / `BIDIRECTIONAL=2` |
| `uint32_t width, height` | FW | buffer 尺寸（像素） |
| `int format` | FW | `HAL_PIXEL_FORMAT_*`（graphics.h）；`IMPLEMENTATION_DEFINED` 时由 gralloc 依据 usage 决定 |
| `uint32_t usage` | **HAL** | gralloc usage；输出流为生产者 usage、输入流为消费者 usage；3.1+ 入参含消费者 usage 供 HAL 参考，HAL 必须覆写 |
| `uint32_t max_buffers` | **HAL** | 该流同时最多可被 HAL dequeue 的 buffer 数 |
| `void *priv` | **HAL** | HAL 私有句柄，框架不解析 |
| `android_dataspace_t data_space` | FW | buffer 内容语义（色彩空间/深度数据）；3.3 起有效，3.4 起用 V0 定义 |
| `int rotation` | FW | 输出旋转 `CAMERA3_STREAM_ROTATION_0/90/180/270`；3.3 起有效；HAL 必须至少支持 ROTATION_0 |
| `const char* physical_camera_id` | FW | 物理输出流所属物理摄像头 id；3.5 起有效，逻辑多摄用 |

### 4.3.5 camera3_stream_configuration_t

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t num_streams` | 3.0 | 流总数（含输入），至少 1 且至少 1 个输出流；最多 1 个输入流 |
| `camera3_stream_t **streams` | 3.0 | 流指针数组 |
| `uint32_t operation_mode` | 3.3 | `CAMERA3_STREAM_CONFIGURATION_NORMAL_MODE=0` / `CONSTRAINED_HIGH_SPEED_MODE=1`（慢动作）及 vendor 模式 |
| `const camera_metadata_t *session_parameters` | 3.5 | 会话参数（`ANDROID_REQUEST_AVAILABLE_SESSION_KEYS`）初值，可与 configure 一并下发 |

### 4.3.6 camera3_capture_request_t / camera3_capture_result_t

请求（FW→HAL）：

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t frame_number` | 3.0 | 框架递增的帧号，结果与 notify 以此对应 |
| `const camera_metadata_t *settings` | 3.0 | 本次拍摄参数；NULL 表示沿用最近一次请求（configure 后第一笔不得为 NULL） |
| `camera3_stream_buffer_t *input_buffer` | 3.0 | 输入 buffer（NULL=正常拍摄，非 NULL=reprocess） |
| `uint32_t num_output_buffers` | 3.0 | 输出 buffer 数（≥1） |
| `const camera3_stream_buffer_t *output_buffers` | 3.0 | 输出 buffer 数组 |
| `uint32_t num_physcam_settings; const char **physcam_id; const camera_metadata_t **physcam_settings` | 3.5 | 逻辑多摄逐物理摄像头的请求设置 |

结果（HAL→FW）：

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t frame_number` | 3.0 | 对应请求帧号 |
| `const camera_metadata_t *result` | 3.0 | 结果 metadata（同一 frame 仅一次携带；错误时返回空 buffer 并 notify ERROR_RESULT） |
| `uint32_t num_output_buffers; const camera3_stream_buffer_t *output_buffers` | 3.0 | 返回的输出 buffer（可先于全部填充完成返回，靠 release_fence 同步；3.2 起可先于 SHUTTER 返回 buffer） |
| `const camera3_stream_buffer_t *input_buffer` | 3.2 | 归还的输入 buffer |
| `uint32_t partial_result` | 3.2 | 部分结果序号，1..`android.request.partialResultCount`；仅含 buffer 时为 0 |
| `uint32_t num_physcam_metadata; const char **physcam_ids; const camera_metadata_t **physcam_metadata` | 3.5 | 逐物理摄像头结果 metadata |

### 4.3.7 camera3_stream_buffer_t

| 字段 | 说明 |
|---|---|
| `camera3_stream_t *stream` | 所属流 |
| `buffer_handle_t *buffer` | gralloc buffer 句柄 |
| `int status` | `CAMERA3_BUFFER_STATUS_OK=0` / `ERROR=1`（填充失败时置 ERROR 返回） |
| `int acquire_fence` | HAL 读写前必须等待的 sync fence fd（-1 表示无需等待）；返回结果时必须置 -1 |
| `int release_fence` | HAL 归还 buffer 时设置的 sync fence（-1 表示已就绪）；框架等待后方可复用 |

### 4.3.8 时序小结

```
framework                                HAL
   | open(id)  (hw_module_t.common.methods->open)
   | initialize(callback_ops)               ← 传入回调表
   | construct_default_request_settings(TEMPLATE_*)
   | configure_streams(stream_list)         ← HAL 回写 usage/max_buffers
   | process_capture_request(req) ────────► │ 3A/ISP 拍摄
   | ◄──────── notify(SHUTTER)              │
   | ◄──────── process_capture_result(...)  │ （可多次、partial）
   | flush()（丢帧、回收 buffer）
   | close()  (hw_device_t.common.close)
```

## 4.4 AIDL 接口

> 来源：[camera/device/aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/)、[camera/provider/aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/)（Android 13 引入；所有接口均 `@VintfStability`）

服务名格式：`android.hardware.camera.provider.ICameraProvider/<type>/<instance>`（如 `internal/0`）；设备名格式 `device@<major>.<minor>/<type>/<id>`。错误统一通过 `ServiceSpecificException` 抛出（`ILLEGAL_ARGUMENT`、`INTERNAL_ERROR`、`CAMERA_IN_USE`、`MAX_CAMERAS_IN_USE`、`CAMERA_DISCONNECTED`、`OPERATION_NOT_SUPPORTED` 等）。

### 4.4.1 ICameraProvider

> 来源：[ICameraProvider.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProvider.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void setCallback(ICameraProviderCallback callback)` | FW→HAL | 相机服务启动时注册回调（服务重启后需再次调用） |
| `VendorTagSection[] getVendorTags()` | FW→HAL | 返回该 provider 覆盖设备支持的 vendor tag 分组 |
| `String[] getCameraIdList()` | FW→HAL | 返回内置（BACK/FRONT）摄像头设备名列表；外接设备只经状态回调上报 |
| `ICameraDevice getCameraDeviceInterface(String cameraDeviceName)` | FW→HAL | 由设备名取 `ICameraDevice` 接口（不上电）；等价于旧模型"找到设备但未 open" |
| `void notifyDeviceStateChange(long deviceState)` | FW→HAL | 整机状态（折叠/遮盖）变化通知；首次 open 前至少调用一次 |
| `ConcurrentCameraIdCombination[] getConcurrentCameraIds()` | FW→HAL | 可同时并发出流的摄像头 id 组合（满足最小分辨率保证） |
| `boolean isConcurrentStreamCombinationSupported(in CameraIdAndStreamCombination[] configs)` | FW→HAL | 查询多摄并发流组合是否支持 |

常量：`DEVICE_STATE_NORMAL/BACK_COVERED/FRONT_COVERED/FOLDED`（与 C 接口 `device_state_t` 一致）。

### 4.4.2 ICameraProviderCallback

> 来源：[ICameraProviderCallback.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProviderCallback.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void cameraDeviceStatusChange(String cameraDeviceName, CameraDeviceStatus newStatus)` | HAL→FW | 设备连接状态变化（对应 C 接口 `camera_device_status_change`，枚举为 `CameraDeviceStatus.PRESENT/NOT_PRESENT/ENUMERATING`） |
| `void torchModeStatusChange(String cameraDeviceName, TorchModeStatus newStatus)` | HAL→FW | 手电筒状态变化（对应 `torch_mode_status_change`） |
| `void physicalCameraDeviceStatusChange(String cameraDeviceName, String physicalCameraDeviceName, CameraDeviceStatus newStatus)` | HAL→FW | 逻辑多摄背后物理设备的状态变化（C 接口无对应物，HIDL 2.5 起新增） |

### 4.4.3 ICameraDevice

> 来源：[ICameraDevice.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDevice.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `CameraMetadata getCameraCharacteristics()` | FW→HAL | 静态特征 metadata（对应旧 `get_camera_info` 的 `static_camera_characteristics`） |
| `CameraMetadata getPhysicalCameraCharacteristics(in String physicalCameraId)` | FW→HAL | 逻辑多摄中某物理摄像头的静态特征 |
| `CameraResourceCost getResourceCost()` | FW→HAL | 资源开销（对应 `camera_info_t.resource_cost` + 冲突设备） |
| `boolean isStreamCombinationSupported(in StreamConfiguration streams)` | FW→HAL | 流组合查询（对应 module 2.5 的 `is_stream_combination_supported`，此处下沉到设备级） |
| `ICameraDeviceSession open(in ICameraDeviceCallback callback)` | FW→HAL | 上电并打开设备，返回会话接口。错误：`CAMERA_IN_USE`（重复打开）、`MAX_CAMERAS_IN_USE`、`CAMERA_DISCONNECTED` 等 |
| `ICameraInjectionSession openInjectionSession(in ICameraDeviceCallback callback)` | FW→HAL | 打开"注入会话"（外部图像注入到 HAL，测试/虚拟相机用），不支持时 `OPERATION_NOT_SUPPORTED` |
| `void setTorchMode(boolean on)` | FW→HAL | 手电筒开关（对应 `camera_module_t.set_torch_mode`，此处位于设备级） |
| `void turnOnTorchWithStrengthLevel(int torchStrength)` | FW→HAL | 以指定亮度档位开手电筒（AIDL 新增能力，`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` 为上限） |
| `int getTorchStrengthLevel()` | FW→HAL | 查询当前亮度档位（AIDL 新增） |
| `CameraMetadata constructDefaultRequestSettings(in RequestTemplate type)` | FW→HAL | 与 Session 同名方法语义一致，但**可在 open 之前调用**（AIDL 新增） |
| `boolean isStreamCombinationWithSettingsSupported(in StreamConfiguration streams)` | FW→HAL | 带会话参数/request key 的流组合查询（AIDL 新增，服务 3.0 查询版本校验） |
| `CameraMetadata getSessionCharacteristics(in StreamConfiguration sessionConfig)` | FW→HAL | 返回随会话配置而变的特征（如 `ANDROID_CONTROL_ZOOM_RATIO_RANGE`，Android 15 起） |

> 注：需求清单中提到的 `ICameraDeviceInjectionCallback.aidl` 在 `hardware/interfaces` 中并不存在；注入相关接口为 `ICameraDevice.openInjectionSession()` 返回的 `ICameraInjectionSession`（方法 `configureInjectionStreams(in StreamConfiguration, in CameraMetadata)`，语义同 configureStreams 但不覆盖内部相机配置），以及 HIDL `device@3.7` 中的同名接口。

### 4.4.4 ICameraDeviceSession

> 来源：[ICameraDeviceSession.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDeviceSession.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `HalStream[] configureStreams(in StreamConfiguration requestedConfiguration)` | FW→HAL | 配置流；返回 HAL 期望的每流参数（`overrideFormat/producerUsage/consumerUsage/maxBuffers/overrideDataSpace/supportOffline/enableHalBufferManager`）。对应 C 接口 `configure_streams`（`camera3_stream_configuration_t` ↔ `StreamConfiguration`：`streams/sessionParams/streamConfigCounter`） |
| `CameraMetadata constructDefaultRequestSettings(in RequestTemplate type)` | FW→HAL | 默认请求模板（对应 `construct_default_request_settings`） |
| `int processCaptureRequest(in CaptureRequest[] requests, in BufferCache[] cachesToRemove)` | FW→HAL | 提交请求（**批量**：一次可带多个请求；`cachesToRemove` 清理 HAL 侧 buffer 缓存）。对应 `process_capture_request` |
| `void flush()` | FW→HAL | 丢弃全部进行中请求并归还 buffer（对应 `flush`） |
| `void close()` | FW→HAL | 关闭设备，之后所有调用抛 `INTERNAL_ERROR`（对应 `hw_device_t.common.close`） |
| `MQDescriptor<byte, SynchronizedReadWrite> getCaptureRequestMetadataQueue()` | FW→HAL | 获取请求 metadata 快速通道 FMQ（大 metadata 走共享内存，Binder 只传帧号） |
| `MQDescriptor<byte, SynchronizedReadWrite> getCaptureResultMetadataQueue()` | FW→HAL | 结果 metadata FMQ（同上） |
| `boolean isReconfigurationRequired(in CameraMetadata oldSessionParams, in CameraMetadata newSessionParams)` | FW→HAL | 会话参数变化是否需要完整重配置（对应 3.6 `is_reconfiguration_required`；返回 true 需要重配置） |
| `oneway void signalStreamFlush(in int[] streamIds, in int streamConfigCounter)` | FW→HAL | 通知 HAL 指定流即将不再有新请求、应归还 buffer（对应 3.6 `signal_stream_flush`；AIDL 中显式 `oneway` 非阻塞） |
| `ICameraOfflineSession switchToOffline(in int[] streamsToKeep, out CameraOfflineSessionInfo offlineSessionInfo)` | FW→HAL | 把未完成请求移交离线会话继续处理（离线模式，不占用传感器/相机资源），AIDL/HIDL 3.7 新增 |
| `void repeatingRequestEnd(in int frameNumber, in int[] streamIds)` | FW→HAL | 通知 HAL 某 repeating 请求在给定流上的最后一个 frame 号，便于 HAL 判定周期结束 |
| `ConfigureStreamsRet configureStreamsV2(in StreamConfiguration requestedConfiguration)` | FW→HAL | configureStreams 的新版本：返回 `ConfigureStreamsRet{HalStream[] halStreams, boolean enableHalBufferManager}`；仅当静态元数据 `ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION == SESSION_CONFIGURABLE` 时调用，与 configureStreams 互斥 |

### 4.4.5 ICameraDeviceCallback

> 来源：[ICameraDeviceCallback.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDeviceCallback.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void notify(in NotifyMsg[] msgs)` | HAL→FW | 异步通知（**批量**）：`NotifyMsg` 为 union `{ ShutterMsg shutter; ErrorMsg error; }`，对应 C 接口 `notify`（`CAMERA3_MSG_SHUTTER/ERROR`） |
| `void processCaptureResult(in CaptureResult[] results)` | HAL→FW | 返回结果（**批量**、可分次 partial）：`CaptureResult{frameNumber, result, outputBuffers, inputBuffer, partialResult, physicalCameraMetadata}`，对应 `process_capture_result` |
| `BufferRequestStatus requestStreamBuffers(in BufferRequest[] bufReqs, out StreamBufferRet[] buffers)` | HAL→FW | HAL buffer manager 模式下同步请求输出 buffer（返回 `OK/FAILED_PARTIAL/FAILED_CONFIGURING/FAILED_UNKNOWN`），对应 3.6 `request_stream_buffers` |
| `void returnStreamBuffers(in StreamBuffer[] buffers)` | HAL→FW | 归还输出 buffer，对应 3.6 `return_stream_buffers` |

`StreamBuffer`（对应 C `camera3_stream_buffer_t`）：`int streamId`、`long bufferId`（buffer 缓存句柄）、`NativeHandle buffer`、`BufferStatus status`、`NativeHandle acquireFence`、`NativeHandle releaseFence`——fence 由 fd 句柄传递，取代 C 接口的裸 fence fd。

### 4.4.6 ICameraOfflineSession（补充）

> 来源：[ICameraOfflineSession.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraOfflineSession.aidl)

由 `ICameraDeviceSession.switchToOffline()` 返回：`void close()`、`MQDescriptor<...> getCaptureResultMetadataQueue()`、`void setCallback(in ICameraDeviceCallback cb)`；结果仍通过 `ICameraDeviceCallback` 回传。HAL 必须在 switch 时拍完所需传感器帧，离线后不得再访问传感器。

### 4.4.7 AIDL 与 HIDL 的主要差异

| 维度 | HIDL（provider@2.x / device@3.x） | AIDL |
|---|---|---|
| 错误模型 | 每方法返回 `Status` + 可选结果 | `void`/直接返回值 + `ServiceSpecificException` |
| 方法扩展 | 只能通过新版本号（`configureStreams_3_5` 等带后缀方法） | 接口小版本演进，新增独立方法名（如 `configureStreamsV2`、`getSessionCharacteristics`） |
| 请求提交 | `processCaptureRequest(in CaptureRequest, in BufferCache[])`（3.x 亦为批量） | `int processCaptureRequest(in CaptureRequest[], in BufferCache[])`，显式批量并返回触发帧号（HAL 可据此提前返回错误） |
| 新增能力 | 3.7：`signalStreamFlush`、`isReconfigurationRequired`、switchToOffline | 保留全部 3.7 能力；另增 `turnOnTorchWithStrengthLevel`/`getTorchStrengthLevel`（闪光灯亮度）、`constructDefaultRequestSettings`（open 前可调用）、`isStreamCombinationWithSettingsSupported`、`getSessionCharacteristics`、`repeatingRequestEnd`、`configureStreamsV2`（buffer manager 会话级配置） |
| 枚举 | `types.hal` 固定枚举 | 独立 `.aidl` 枚举/parcelable（`StreamType`、`StreamRotation`、`RequestTemplate`、`ErrorCode` 等），可扩展 |
| 流定义 | `Stream`（HIDL 3.5+ 含 `sensorPixelModesUsed`、`dynamicRangeProfile`） | `Stream` 增加 `groupId`、`bufferSize`、`useCase`、`colorSpace`、`sensorPixelModesUsed`、`dynamicRangeProfile` |
| 命名风格 | 蛇形（`process_capture_request`） | 驼峰（`processCaptureRequest`），参数带 `in/out/oneway` 修饰 |

## 4.5 C HAL 与 AIDL 接口对应关系对照表

> 模块/设备/回调三层逐项对照；"—" 表示 C 接口无对应（AIDL 语义内聚到设备接口）。

| 传统 C HAL（libhardware） | AIDL | 说明 |
|---|---|---|
| `hw_get_module()` + `camera_module_t.common.methods->open(id)` | `ICameraProvider.getCameraDeviceInterface(name)` → `ICameraDevice.open(callback)` | AIDL 由 provider 管理设备发现，open 直接返回 session（不再需要后续 `initialize` 传回调——回调在 open 时一并传入） |
| `camera_module_t.get_number_of_cameras()` | `ICameraProvider.getCameraIdList()`（内置）+ `ICameraProviderCallback.cameraDeviceStatusChange()`（外接动态） | 静态枚举 → 动态上报 |
| `camera_module_t.get_camera_info()` | `ICameraDevice.getCameraCharacteristics()` | 静态特征 |
| `camera_module_t.get_physical_camera_info()` | `ICameraDevice.getPhysicalCameraCharacteristics()` | 物理摄像头特征 |
| `camera_info_t.resource_cost / conflicting_devices` | `ICameraDevice.getResourceCost()`（`CameraResourceCost`） | 多摄并发仲裁 |
| `camera_module_t.set_callbacks(camera_module_callbacks_t*)` | `ICameraProvider.setCallback(ICameraProviderCallback)` | 模块级回调注册 |
| `camera_module_callbacks_t.camera_device_status_change` | `ICameraProviderCallback.cameraDeviceStatusChange` | 设备插拔 |
| `camera_module_callbacks_t.torch_mode_status_change` | `ICameraProviderCallback.torchModeStatusChange` | 手电筒状态 |
| `camera_module_t.set_torch_mode(id, enabled)` | `ICameraDevice.setTorchMode(on)` | 移至设备级 |
| — | `ICameraDevice.turnOnTorchWithStrengthLevel()` / `getTorchStrengthLevel()` | AIDL 新增 |
| `camera_module_t.get_vendor_tag_ops()` | `ICameraProvider.getVendorTags()` | vendor tag 查询 |
| `camera_module_t.open_legacy()` | —（AIDL 无 HAL 版本并存概念） | 遗留能力 |
| `camera_module_t.init()` | —（provider 进程内自行初始化） | |
| `camera_module_t.notify_device_state_change(state)` | `ICameraProvider.notifyDeviceStateChange(deviceState)` | 折叠态等整机状态 |
| `camera_module_t.is_stream_combination_supported()` | `ICameraDevice.isStreamCombinationSupported()` / `isStreamCombinationWithSettingsSupported()` / `ICameraProvider.isConcurrentStreamCombinationSupported()` | 下沉到设备级并扩展 |
| `camera3_device_t`（open 返回的 `hw_device_t*`） | `ICameraDeviceSession` | 设备会话 |
| `camera3_device_ops_t.initialize(callback_ops)` | 回调在 `ICameraDevice.open(callback)` 中传入 | 合并进 open |
| `configure_streams(stream_list)` | `ICameraDeviceSession.configureStreams(StreamConfiguration)` → `HalStream[]` | HAL 诉求由返回值表达（不再回写入参结构体）；V2 版本返回 `ConfigureStreamsRet` |
| `construct_default_request_settings(template)` | `ICameraDeviceSession.constructDefaultRequestSettings(RequestTemplate)`；`ICameraDevice` 上另有 open 前可调用的版本 | |
| `process_capture_request(request)` | `ICameraDeviceSession.processCaptureRequest(CaptureRequest[], BufferCache[])` | 批量提交 + buffer 缓存清理 |
| `flush()` | `ICameraDeviceSession.flush()` | |
| `dump(fd)` | —（dumpsys 走 provider 进程实现，不在接口中） | |
| `register_stream_buffers(buffer_set)`（3.2 废弃） | —（buffer 随 request 传递）或 `ICameraDeviceCallback.requestStreamBuffers/returnStreamBuffers`（HAL buffer manager） | |
| `get_metadata_vendor_tag_ops()` | `ICameraProvider.getVendorTags()` | 移至 provider 级 |
| `signal_stream_flush(stream_ids)`（3.6） | `ICameraDeviceSession.signalStreamFlush(streamIds, streamConfigCounter)`（oneway） | 增加 streamConfigCounter 区分配置轮次 |
| `is_reconfiguration_required(old, new)`（3.6） | `ICameraDeviceSession.isReconfigurationRequired(oldSessionParams, newSessionParams)` | |
| — | `ICameraDeviceSession.switchToOffline()` → `ICameraOfflineSession` | 离线模式为 HIDL 3.7/AIDL 新增 |
| `camera3_callback_ops.process_capture_result(result)` | `ICameraDeviceCallback.processCaptureResult(CaptureResult[])` | 批量 |
| `camera3_callback_ops.notify(msg)` | `ICameraDeviceCallback.notify(NotifyMsg[])` | 批量 |
| `camera3_callback_ops.request_stream_buffers()`（3.6） | `ICameraDeviceCallback.requestStreamBuffers(BufferRequest[]) → BufferRequestStatus, StreamBufferRet[]` | |
| `camera3_callback_ops.return_stream_buffers()`（3.6） | `ICameraDeviceCallback.returnStreamBuffers(StreamBuffer[])` | |
| `camera3_stream_t` | `Stream`（FW→HAL 期望）+ `HalStream`（HAL 诉求） | 输入/输出拆成两个 parcelable |
| `camera3_stream_buffer_t` | `StreamBuffer` | fence fd → `NativeHandle`；新增 `bufferId` 缓存机制 |
| `camera3_capture_request_t` | `CaptureRequest` | 多摄设置 `physcam_settings` → `PhysicalCameraSetting[]` |
| `camera3_capture_result_t` | `CaptureResult` | 多摄 metadata → `PhysicalCameraMetadata[]` |
| `CAMERA3_TEMPLATE_*` | `RequestTemplate` 枚举 | 值相同（PREVIEW=1 … MANUAL=6） |

学习建议：先以 4.3 的 C 接口理解 HAL3 的核心模型（request/result、stream、fence、partial result），再对照 4.4/4.5 阅读 AIDL 定义——两者语义几乎一一对应，差异集中在进程模型、错误处理与批量/FMQ 传输细节上。阅读 framework 侧实现时，`frameworks/av/services/camera/libcameraservice/common/HalInterface.h`（`Camera3Device` 对两种后端的统一封装）与 `CameraProviderManager` 是印证对照关系的最佳入口。



# 第 5 章 元数据体系（camera_metadata）

Camera2/HAL3 的一切控制与结果传递都建立在 `camera_metadata` 之上：应用下发的是一份份元数据（CaptureRequest），HAL 返回的也是元数据（CaptureResult），设备能力描述同样是一份元数据（CameraCharacteristics）。本章基于 AOSP `system/media` 仓库的源码，梳理这套体系的 C API、tag 组织方式、条目总量统计与厂商扩展机制。

---

## 5.1 元数据体系概览

> 来源：[camera_metadata.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata.h)、[camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)、[Camera HAL3 元数据概念页（source.android.google.cn）](https://source.android.google.cn/docs/core/camera/camera3_metadata?hl=zh-cn)

### 5.1.1 为什么用元数据

Camera1 时代，应用通过 `Camera.Parameters` 以**扁平字符串协议**与 HAL 交互：`set("key=value;key2=value2")`。这种协议有明显缺陷：

- **弱类型**：所有值都是字符串，int/float/数组全靠手写序列化，解析容易出错；
- **无结构**：表达不了矩形、区域（metering region）、ration 等复合类型；
- **不可逐帧控制**：Camera1 的 parameters 是设备级状态，改一次要整包重新下发，难以做 HAL3 的"每帧一个请求"流水线；
- **扩展性差**：OEM 加自定义参数只能继续往字符串里塞 key，容易互相冲突、无版本约束。

Camera2 改用**强类型的二进制元数据缓冲区**：每个数据项由 `tag`（uint32 编号）标识，附带 `type`（6 种基本类型之一）和 `count`（元素个数），值按类型定长存放。请求、结果、设备特性全是同一种结构 `camera_metadata_t`，可整体 memcpy、逐帧下发，AOSP 官方概念页对此的概括是：静态特性通过大幅扩展的 `getCameraInfo()` 提供逐帧设置通过捕获请求传递（曝光时间、帧时长、感光度等），而结果元数据必须回报 HAL **实际生效**的值（例如应用请求帧时长 0，HAL 报告被限制后的最小值）。

### 5.1.2 缓冲区结构：camera_metadata_t

`camera_metadata.h` 中的核心注释可归纳为：

- 一份元数据是**一块连续内存**：头部 + 定长 entry 插槽数组 + 溢出数据区，字节数由 `get_camera_metadata_size()` 给出，因此可以安全地用 `memcpy()` 复制；
- 容量**固定**（entry_capacity / data_capacity），满了不会自动扩容；
- entry 默认**不排序**，且**允许同一 tag 出现多次**；`sort_camera_metadata()` 排序后可加速按 tag 查找，但再 add/append 会回到未排序状态；
- 每个条目在代码中以 `camera_metadata_entry_t`（可写引用）或 `camera_metadata_ro_entry_t`（只读引用，布局相同）暴露：

```c
typedef struct camera_metadata_entry {
    size_t   index;   // 在缓冲区中的位置
    uint32_t tag;     // tag 编号
    uint8_t  type;    // TYPE_BYTE ~ TYPE_RATIONAL
    size_t   count;   // 元素个数（不是字节数）
    union {           // 指向缓冲区内真实数据
        uint8_t *u8;  int32_t *i32;  float *f;
        int64_t *i64; double  *d;    camera_metadata_rational_t *r;
    } data;
} camera_metadata_entry_t;
```

### 5.1.3 三大类元数据

| 类别 | Java 层 | XML 中的 kind | 说明 |
|---|---|---|---|
| 静态特性 | `CameraCharacteristics` | `<static>` | 设备固有能力，**不打开设备即可查询**；`camera_metadata_tags.h` 注释说明 `*_INFO` section 专门存放这类"未开设备就能拿到"的静态信息 |
| 请求设置 | `CaptureRequest` | `<controls>` | 应用每帧下发、期望生效的控制值（曝光、对焦、裁剪等） |
| 结果 | `CaptureResult` | `<dynamic>` | HAL 每帧返回的实际生效值、状态与统计信息 |

### 5.1.4 tag 命名规则

- C 层：全大写蛇形命名 `ANDROID_<SECTION>_<NAME>`，如 `ANDROID_CONTROL_AE_MODE`；
- 字符串名：`android.<section>.<name>`，如 `android.control.aeMode`；
- Java 层 `Key.getName()` 返回的是以句点分隔的 `root.section[.subsections].name`，且标准 key 一律以 `android.` 开头，厂商 key 以 `com.` 等前缀区分；
- `camera_metadata_tags.h` 中每个 tag 枚举后都带一行机器生成注释，格式为 `// <类型> | <可见性> | <HAL 版本>`，例如：

```c
ANDROID_CONTROL_AE_MODE =             // enum         | public       | HIDL v3.2
    ANDROID_CONTROL_AE_MODE_OFF,      // HIDL v3.2
```

### 5.1.5 数据类型

`camera_metadata.h` 定义 6 种基本类型（`NUM_TYPES = 6`），并配有全局表 `camera_metadata_type_size[]`（每类型字节数）与 `camera_metadata_type_names[]`（类型名字符串）：

| 类型 | 枚举值 | C 类型 | 字节数 | 说明 |
|---|---|---|---|---|
| TYPE_BYTE | 0 | uint8_t | 1 | 也用于 boolean 和 8 位枚举 |
| TYPE_INT32 | 1 | int32_t | 4 | |
| TYPE_FLOAT | 2 | float | 4 | |
| TYPE_INT64 | 3 | int64_t | 8 | 常用于时间戳、曝光时长（ns） |
| TYPE_DOUBLE | 4 | double | 8 | 如 GPS 坐标 |
| TYPE_RATIONAL | 5 | camera_metadata_rational_t | 8 | 两个 int32：numerator / denominator |

### 5.1.6 可见性枚举

一个条目"谁能看见"由 `visibility` 属性决定。权威定义在 [`metadata_definitions.xsd`](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xsd) 中（沿源码实际取值列出，括号内为 XSD 原注释）：

| visibility | 含义 |
|---|---|
| `public` | Java 与 NDK 均 public，HAL 接口可见 |
| `java_public` | 仅 Java public SDK；不进 NDK |
| `ndk_public` | 仅 NDK public；Java 中是 @hide |
| `hidden` | Java 中 @hide；不进 NDK（旧文档常写作 "@hide"） |
| `system` | 不暴露给 Java/NDK，HAL 可见 |
| `extension` | Java @hide，但作为 public key 出现在 camera extensions 里 |
| `fwk_only` | Java @hide；不进 NDK，也**不进 HAL 接口**（纯框架内部） |
| `fwk_java_public` | Java public；不进 NDK、不进 HAL 接口 |
| `fwk_system_public` | Java system API（@SystemApi）；不进 NDK、不进 HAL 接口 |
| `fwk_public` | Java 与 NDK 均 public；不进 HAL 接口 |
| `fwk_ndk_public` | NDK public；不进 Java、不进 HAL 接口 |

注意：XML 中仍有一批**历史遗留条目未标注 visibility**（约 22 个），代码生成器将它们按 system/hidden 处理。

---

## 5.2 camera_metadata.h C API 函数表

> 来源：[camera_metadata.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata.h)

除非另有说明，返回 `int` 的函数 **0 表示成功、非 0 表示失败**；`find_camera_metadata_entry` 找不到时返回 `-ENOENT`。

### 分配、容量与复制

| 函数 | 作用 |
|---|---|
| `allocate_camera_metadata(entry_capacity, data_capacity)` | 分配新元数据：entry_capacity 为条目个数，data_capacity 为溢出数据字节数；用 `free_camera_metadata()` 释放 |
| `free_camera_metadata(metadata)` | 释放 `allocate_camera_metadata` 分配的结构 |
| `calculate_camera_metadata_size(entry_count, data_count)` | 计算容纳 entry_count 个条目、data_count 字节数据所需的缓冲区大小 |
| `calculate_camera_metadata_entry_data_size(type, data_count)` | 计算某条目需要的溢出数据字节数（小数据直接内联在 entry 里，返回 0） |
| `place_camera_metadata(dst, dst_size, ...)` | 在**已有缓冲区**头部放置元数据结构（调用方自己管理内存） |
| `copy_camera_metadata(dst, dst_size, src)` | 复制到已有缓冲区并**压实**（容量裁剪到实际用量） |
| `clone_camera_metadata(src)` | 按 src 实际用量分配最小新缓冲并复制（内部即"分配+append"），最常用 |
| `allocate_copy_camera_metadata_checked(src, src_size)` | 按给定大小分配并复制，**复制后做结构校验**，失败则返回 NULL；用于接收不可信来源的二进制元数据 |
| `get_camera_metadata_alignment()` | 返回整包元数据所需的对齐字节数 |
| `get_camera_metadata_size(m)` / `get_camera_metadata_compact_size(m)` | 总大小（含预留空间）/ 压实后大小 |
| `get_camera_metadata_entry_count(m)` / `_entry_capacity(m)` | 当前条目数 / 最大条目容量 |
| `get_camera_metadata_data_count(m)` / `_data_capacity(m)` | 已用溢出数据字节数 / 容量 |
| `validate_camera_metadata_structure(m, expected_size)` | 结构校验（防越界），返回 0 / `CAMERA_METADATA_VALIDATION_ERROR` / `CAMERA_METADATA_VALIDATION_SHIFTED`（未对齐但可被 clone 修复）；反序列化不可信数据前必调 |

### 读取、写入与遍历

| 函数 | 作用 |
|---|---|
| `add_camera_metadata_entry(dst, tag, data, data_count)` | 按当前最高 index 追加一个条目；未知 tag 或容量不足报错；vendor tag 需先设置查询回调（见 5.5） |
| `update_camera_metadata_entry(dst, index, data, data_count, updated_entry)` | 按下标更新数据；大小不变 O(1)，变长 O(N)；保持排序；会使旧的 entry.data 指针失效 |
| `find_camera_metadata_entry(src, tag, entry)` | **按 tag 查找**；同 tag 多条时不保证返回哪个；先 sort 可加速查找 |
| `find_camera_metadata_ro_entry(src, tag, entry)` | 同上，但返回只读引用 |
| `get_camera_metadata_entry(src, index, entry)` | 按**下标**取条目（可写引用） |
| `get_camera_metadata_ro_entry(src, index, entry)` | 按下标取条目（只读引用） |
| `delete_camera_metadata_entry(dst, index)` | 删除条目；需重排 entry 与数据，代价较高；保持排序 |
| `append_camera_metadata(dst, src)` | 把 src 的所有条目追加进 dst（不自动扩容）；结果变为未排序 |
| `sort_camera_metadata(dst)` | 排序以加速按 tag 查找；add/append 之后会退回未排序 |

关于"iterator"：当前 `camera_metadata.h` 的公开 API 中**没有** `get_camera_metadata_iterator` 之类的迭代器对象，遍历的标准写法是下标循环：

```c
size_t n = get_camera_metadata_entry_count(meta);
for (size_t i = 0; i < n; i++) {
    camera_metadata_ro_entry_t e;
    get_camera_metadata_ro_entry(meta, i, &e);
    // e.tag / e.type / e.count / e.data...
}
```

### tag 元信息查询

| 函数 | 作用 |
|---|---|
| `get_camera_metadata_section_name(tag)` | 返回 tag 所属 section 名（如 `"control"`）；vendor tag 需先注册查询回调，否则返回 NULL |
| `get_camera_metadata_tag_name(tag)` | 返回 tag 名（不含 section，如 `"aeMode"`） |
| `get_camera_metadata_tag_type(tag)` | 返回 tag 类型枚举，未知返回 -1 |
| `get_local_camera_metadata_section_name/_tag_name/_tag_type(tag, meta)` | 同上三个，但会**结合该缓冲区的 vendor tag 回调**解析 vendor tag |
| `camera_metadata_section_bounds[ANDROID_SECTION_COUNT][2]` | 全局表：每个 section 的 tag 起止范围 |
| `camera_metadata_section_names[ANDROID_SECTION_COUNT]` | 全局表：section 名字符串 |
| `camera_metadata_type_size[]` / `camera_metadata_type_names[]` | 类型大小 / 类型名全局表 |
| `camera_metadata_enum_snprint(tag, value, dst, size)` | 把**枚举类 tag** 的值打印成可读字符串 |
| `camera_metadata_enum_value(tag, name, size, value)` | 枚举名反查数值（与上一函数互逆） |

### 调试

| 函数 | 作用 |
|---|---|
| `dump_camera_metadata(m, fd, verbosity)` | 打印元数据；verbosity 0=仅条目，1=加 16 个数据值，2=全量 |
| `dump_indented_camera_metadata(m, fd, verbosity, indentation)` | 同上，带缩进参数（便于嵌套打印） |

### 旧版 vendor 查询（已废弃）

| 函数 / 类型 | 作用 |
|---|---|
| `vendor_tag_query_ops_t` + `set_camera_metadata_vendor_tag_ops(query_ops)` | 旧机制：向元数据库注册厂商 tag 的名字/类型查询回调；头文件标注 **DEPRECATED**，应改用 `camera_vendor_tags.h` 的 `vendor_tag_ops`（见 5.5） |

---

## 5.3 tag 组织与命名空间

> 来源：[camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)

### 5.3.1 tag 编号编码

- tag 是 32 位整数：**高 16 位 = section 编号，低 16 位 = section 内偏移**（`camera_metadata_section_start_t` 用 `section << 16` 表达起始值）；
- 主枚举 `camera_metadata_section_t` 当前有 **36 个 section**（`ANDROID_SECTION_COUNT = 36`），另设 `VENDOR_SECTION = 0x8000`，故厂商 tag 的编号全部 ≥ `0x80000000`（与 `camera_vendor_tags.h` 的 `CAMERA_METADATA_VENDOR_TAG_BOUNDARY` 一致）；
- 每个 section 枚举后有对应的 `*_START` 边界；`camera_metadata_tags.h` 的 tag 枚举中每个 section 还有 `*_END` 边界标记；
- 头文件注释强调：**新 section 只能加在 ANDROID_SECTION_COUNT 之前**，以保证既有枚举值不变（ABI 稳定）。

### 5.3.2 section 一览（按 tags.h 枚举顺序）

| Section | 用途（一句话） | 主要 tag 举例 |
|---|---|---|
| `COLOR_CORRECTION` | 3A 之后的色彩精调（色温增益、色彩矩阵、色差校正） | `mode`, `gains`, `transform`, `aberrationMode` |
| `CONTROL` | 3A 与整机拍摄控制的**核心 section** | `mode`, `aeMode`, `afMode`, `awbMode`, `aeTargetFpsRange`, `postRawSensitivityBoost` |
| `DEMOSAIC` | RAW 去马赛克（插值成 RGB） | `mode` |
| `EDGE` | 边缘增强（锐化） | `mode`, `strength`, `availableEdgeModes` |
| `FLASH` | 闪光灯控制 | `mode`, `firingPower`, `firingTime` |
| `FLASH_INFO` | 闪光灯静态能力 | `available`, `chargeDuration` |
| `HOT_PIXEL` | 热点/坏点校正 | `mode`, `availableHotPixelModes` |
| `JPEG` | JPEG 压缩参数与 EXIF/GPS 写入 | `quality`, `thumbnailSize`, `orientation`, `gpsCoordinates` |
| `LENS` | 镜头物理控制（光圈、变焦、对焦、防抖） | `aperture`, `focalLength`, `focusDistance`, `opticalStabilizationMode` |
| `LENS_INFO` | 镜头静态信息 | `facing`, `availableFocalLengths`, `poseRotation`, `intrinsicCalibration` |
| `NOISE_REDUCTION` | 降噪 | `mode`, `strength`, `availableNoiseReductionModes` |
| `QUIRKS` | HAL 实现的兼容性/特殊行为标记 | `meteringCropRegion`, `triggerAfWithAuto`, `useZslFormat` |
| `REQUEST` | 请求自身信息与输出流能力 | `frameCount`, `id`, `maxNumOutputStreams`, `availableCapabilities`；动态侧 `pipelineDepth` |
| `SCALER` | 裁剪、旋转、镜像与流配置（分辨率/格式/帧率） | `cropRegion`, `rotateAndCrop`, `availableStreamConfigurations`, `availableFormats` |
| `SENSOR` | 传感器曝光、时序与 RAW 校准 | `exposureTime`, `frameDuration`, `sensitivity`, `testPatternMode` |
| `SENSOR_INFO` | 传感器静态信息 | `activeArraySize`, `colorFilterArrangement`, `sensitivityRange`, `orientation` |
| `SHADING` | 镜头阴影（暗角）校正 | `mode`, `strength`, `availableModes` |
| `STATISTICS` | 3A 统计与人脸信息输出 | `faceDetectMode`, `lensShadingMapMode`, `hotPixelMapMode`, `faceRectangles` |
| `STATISTICS_INFO` | 统计能力静态信息 | `availableFaceDetectModes`, `maxFaceCount`, `histogramBucketCount` |
| `TONEMAP` | 色调映射（Gamma/HDR 曲线） | `mode`, `curveBlue`, `curveGreen`, `curveRed`, `gamma` |
| `LED` | 前置 LED 通知灯 | `transmit`, `availableLeds` |
| `INFO` | 设备整体等级与版本 | `supportedHardwareLevel`, `version`, `supportedBufferManagementVersion` |
| `BLACK_LEVEL` | 黑电平补偿锁定 | `lock` |
| `SYNC` | 请求与结果的帧同步 | `maxLatency`（静态），`frameNumber`（动态） |
| `REPROCESS` | 重处理（YUV/RAW 二次处理） | `effectiveExposureFactor`, `maxCaptureStall` |
| `DEPTH` | 深度数据流（DepthXXX） | `maxDepthSamples`, `availableDepthStreamConfigurations`, `availableDepthMinFrameDurations` |
| `LOGICAL_MULTI_CAMERA` | 逻辑多摄像头 | `physicalIds`, `sensorSyncType`, `activePhysicalId`, `activePhysicalSensorCropRegion` |
| `DISTORTION_CORRECTION` | 镜头畸变校正 | `mode`, `availableModes` |
| `HEIC` | HEIF 编码拍照 | `availableHeicStreamConfigurations`, `availableHeicStallDurations`, `availableHeicUltraHdrStreamConfigurations` |
| `HEIC_INFO` | HEIC 能力静态信息 | `supported`, `maxJpegAppMarkersCount` |
| `AUTOMOTIVE` | 车载摄像头 | `location`，以及（lens 子命名空间）`AUTOMOTIVE_LENS_FACING` |
| `AUTOMOTIVE_LENS` | 车载镜头朝向（HIDL v3.8 起拆分出的 section） | `facing` |
| `EXTENSION` | 相机扩展（夜视、人像等 extensions 接口） | `strength`, `currentType`, `nightModeIndicator` |
| `JPEGR` | UltraHDR（JPEG_R） | `availableJpegRStreamConfigurations`, `availableJpegRMinFrameDurations`, `availableJpegRStallDurations` |
| `SHARED_SESSION` | 共享会话配置 | `colorSpace`, `outputConfigurations`, `configuration` |
| `DESKTOP_EFFECTS` | 桌面模式背景虚化等人像效果 | `backgroundBlurMode`, `faceRetouchMode`, `faceRetouchStrength`, `capabilities` |

> 注意 `*_INFO` 拆分的来源：在 `metadata_definitions.xml` 源定义里并没有独立的 `flashInfo/lensInfo/sensorInfo/statisticsInfo/heicInfo` section，而是写在对应主 section 的 `<static>` 内嵌 `<info>` 子块中，代码生成时拆成独立 section。`AUTOMOTIVE_LENS` 同理，源自 `automotive` section 内嵌的 `<namespace name="lens">` 子块。

---

## 5.4 metadata_properties.xml 统计（现名 metadata_definitions.xml）

> 来源：[metadata_definitions.xml](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml)（main 分支上已由旧文件名 `metadata_properties.xml` 改名而来，结构不变：`namespace → section → controls/static/dynamic → entry`）

用 python（xml.etree）解析统计，**30 个 section、共 371 个 entry**（条目）。分 kind 统计如下（controls=可下发请求项，static=静态能力，dynamic=结果项；同一 tag 常同时出现在多个 kind 中，此处按声明处计数）：

| Section | controls | static | dynamic | 合计 |
|---|---|---|---|---|
| colorCorrection | 6 | 3 | 0 | 9 |
| control | 29 | 29 | 9 | 67 |
| demosaic | 1 | 0 | 0 | 1 |
| edge | 2 | 1 | 0 | 3 |
| flash | 4 | 10 | 1 | 15 |
| hotPixel | 1 | 1 | 0 | 2 |
| jpeg | 8 | 2 | 1 | 11 |
| lens | 5 | 17 | 2 | 24 |
| noiseReduction | 2 | 1 | 0 | 3 |
| quirks | 0 | 4 | 1 | 5 |
| request | 6 | 20 | 2 | 28 |
| scaler | 3 | 33 | 1 | 37 |
| sensor | 6 | 33 | 11 | 50 |
| shading | 2 | 1 | 0 | 3 |
| statistics | 6 | 9 | 20 | 35 |
| tonemap | 7 | 2 | 0 | 9 |
| led | 1 | 1 | 0 | 2 |
| info | 0 | 7 | 0 | 7 |
| blackLevel | 1 | 0 | 0 | 1 |
| sync | 0 | 1 | 1 | 2 |
| reprocess | 1 | 1 | 0 | 2 |
| depth | 0 | 15 | 0 | 15 |
| logicalMultiCamera | 0 | 2 | 2 | 4 |
| distortionCorrection | 1 | 1 | 0 | 2 |
| heic | 0 | 14 | 0 | 14 |
| automotive | 0 | 2 | 0 | 2 |
| extension | 1 | 0 | 2 | 3 |
| jpegr | 0 | 6 | 0 | 6 |
| sharedSession | 0 | 3 | 0 | 3 |
| desktopEffects | 4 | 2 | 0 | 6 |
| **合计** | **106** | **229** | **56** | **371** |

条目量最大的三个 section 是 `control`（67，3A 控制）、`sensor`（50）、`scaler`（37，流配置），符合"控制多、校准多、流配置多"的直觉。

**visibility 分布**（按 entry 元素统计）：public 189、ndk_public 72、java_public 29、system 29、hidden 18、fwk_only 7、fwk_java_public 3、fwk_public 1、fwk_system_public 1、未标注 22（生成代码按 system/hidden 处理）。据此粗估：

- Java SDK 可见（public + java_public + fwk_java_public）约 **221** 个（个别标记 synthetic/deprecated 的不会生成 Java Key）；
- NDK 可见（public + ndk_public）约 **261** 个；
- HAL 接口可见（非 fwk_* 系列）约 **359** 个；
- 与生成结果对照：`camera_metadata_tags.h` 枚举中有约 345 个具体 tag（另加 35 个 `*_END` 边界），对应 XML 中 371 − 25（synthetic="true"，不导出 C）个非合成条目。

**与 Java 框架层的映射**：应用层的 `CameraCharacteristics.Key` / `CaptureRequest.Key` / `CaptureResult.Key` 就是这些 tag 在框架层的投影。构建链路是：`metadata_definitions.xml` → `camera/docs` 下的 mako 模板（`CameraCharacteristicsKeys.mako`、`CaptureRequestKeys.mako`、`CaptureResultKeys.mako`、`CameraMetadataEnums.mako` 等）生成 Java 的 Key 常量与枚举注释，同时生成 `camera_metadata_tags.h/.c`；运行期 `CameraMetadataNative` 用 tag 的字符串名（如 `android.control.aeMode`）与 HAL 传来的二进制元数据互相查找，`Key<T>` 的泛型参数决定了值的 Java 类型（`Integer`↔TYPE_INT32、`Rect`↔int32[4]、`Range`/`Size`/`MeteringRectangle` 等按固定布局解包）。应用可参考 [CameraCharacteristics.Key 官方页](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCharacteristics.Key)：`Key` 由 `new Key<>(name, type)` 构造，设备实际支持的静态 key 用 `CameraCharacteristics.getKeys()` 枚举（请求/结果侧对应 `getAvailableCaptureRequestKeys()` / `getAvailableCaptureResultKeys()`）。

> 统计脚本（可复现，文件下载后直接运行）：
>
> ```python
> import xml.etree.ElementTree as ET
> NS = '{http://schemas.android.com/service/camera/metadata/}'
> root = ET.parse('metadata_definitions.xml').getroot()
> for ns in root.findall(NS+'namespace'):
>     for sec in ns.findall(NS+'section'):
>         n = len(sec.findall('.//'+NS+'entry'))
>         print(sec.get('name'), n)
> ```

---

## 5.5 厂商自定义元数据（vendor tag）

> 来源：[camera_vendor_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_vendor_tags.h)、[ICameraProvider.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProvider.aidl)、[CameraProviderManager.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/common/CameraProviderManager.cpp)

### 5.5.1 编号空间与命名规范

- 厂商 tag 的编号必须 ≥ `CAMERA_METADATA_VENDOR_TAG_BOUNDARY = 0x80000000`（对应 section 枚举 `VENDOR_SECTION = 0x8000`，tag 高 16 位为 0x8000 起的厂商自分区号）；
- vendor section 名必须以**厂商名的 Java 包风格前缀**开头，例如 CameraZoom Inc. 用 `com.camerazoom.`；
- 允许多个厂商 section 并存：手机厂商、芯片厂商、摄像头模组厂商可以各自维护 `com.<vendor>.` 前缀的 section。

### 5.5.2 注册与查询机制

**HAL 侧接口** `vendor_tag_ops_t`（`camera_vendor_tags.h`，共 5 个函数指针 + 8 个保留位）：

| 函数指针 | 作用 |
|---|---|
| `get_tag_count(v)` | 返回本平台支持的 vendor tag 数量（出错返回 -1） |
| `get_all_tags(v, tag_array)` | 填充所有 vendor tag 编号数组 |
| `get_section_name(v, tag)` | 返回 vendor section 名（如 `com.camerazoom.zoom`） |
| `get_tag_name(v, tag)` | 返回 tag 名 |
| `get_tag_type(v, tag)` | 返回类型（须是 `camera_metadata.h` 定义的 6 种之一）；越界返回 -1 |

**框架侧缓存接口** `vendor_tag_cache_ops`：与上面同构，但每个函数多带一个 `metadata_vendor_id_t id`（uint64_t，`CAMERA_METADATA_INVALID_VENDOR_ID = UINT64_MAX`），因为框架同时面对多个 provider，需要按 id 隔离各家的 vendor tag 表。

**打通链路**（以源码为准）：

1. HAL 在 provider 接口上声明 vendor tag：HIDL `android.hardware.camera.provider@2.4` 与 AIDL `ICameraProvider` 均提供 `getVendorTags()`，返回 `VendorTagSection[]`（每项含 tagId、tagName、tagType、tagSectionName）；HIDL 版签名 `getVendorTags() generates (Status status, vec<VendorTagSection> sections)`。
2. 框架 `CameraProviderManager` 在装载 provider 时用 `generateVendorTagId(providerName)`（对 provider 名做 `std::hash`）为它生成唯一的 `metadata_vendor_id_t`（成员 `mProviderTagid`），并把 `getVendorTags()` 的结果转换成 `VendorTagDescriptor` 存入 `ProviderInfo::mVendorTagDescriptor`；随后 `VendorTagDescriptorCache::setAsGlobalVendorTagCache()` 把"provider id → descriptor"设为全局缓存。
3. 之后框架在元数据与 HAL 之间传递时，用 `camera_metadata_t` 头部新增的 8 字节 `vendor_id` 字段标记归属（`Camera3Device` 用内部函数 `set_camera_metadata_vendor_id()` 打标），C 层的 `get_local_camera_metadata_*_vendor_id()` 系列即可按 id 解析 vendor tag 的名字/类型。
4. 一个细节澄清：很多资料提到的 `setVendorTagId` 并不是 HAL 接口里的现成 API（AIDL/HIDL 的 `ICameraDeviceSession` 上并无此方法）；源码里对应的是上述**框架侧概念**——`generateVendorTagId()` 生成的 `mProviderTagid` 与 `getProviderTagIdLocked()` 的按设备查询。HAL 侧声明的入口只有 `getVendorTags()`。

### 5.5.3 OEM 如何扩展、应用侧如何读取

- **OEM**：在 HAL 实现里定义自己的 tag 表、实现 `vendor_tag_ops_t`，通过 provider 的 `getVendorTags()` 上报；之后即可在 characteristics/request/result 元数据中自由使用这些 tag。
- **Java 应用**：vendor key **不会**出现在 `getKeys()` 之类的公开枚举里，也没有 SDK 常量；读取方式是用厂商文档中的名字自行构造 Key 再取值：
  ```java
  Key<Byte> zoomKey = new CameraCharacteristics.Key<>("com.camerazoom.zoomMode", Byte.class);
  Byte mode = characteristics.get(zoomKey);   // 失败会抛 IllegalArgumentException
  ```
  `Key.getName()` 的文档同样约定：非 `android.` 前缀、以 `com.` 开头的 name 属于设备/平台私有 key。
- **NDK**：API 24+ 提供 `ACameraManager_getVendorTags()` 枚举厂商 tag（`ACameraVendorTagsVendorTag` 结构含 id/name/sectionName/type），随后可用 `ACameraMetadata_getConstEntry()` 按 tag 读取。

---

## 5.6 查阅指南

> 来源：综合 [camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)、[metadata_definitions.xml](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml)、[developer.android.google.cn CameraCharacteristics.Key](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCharacteristics.Key)

遇到一个陌生的 `android.xxx.yyy`，按三条路径查（从快到全）：

1. **`camera_metadata_tags.h` 注释（最快）**：tag 枚举带 `// <类型> | <可见性> | <HAL版本>` 注释，同文件下半部分列出了所有枚举值（如 `ANDROID_CONTROL_AE_MODE_ON`）；配合 `get_camera_metadata_section_name/tag_name` 或 `dump_camera_metadata()` 的输出可直接定位。适合"只想知道类型、取值、可见性"。
2. **developer.android.google.cn 对应 Key 页（最适合应用开发者）**：把 snake_case 换成 camelCase（`android.scaler.cropRegion` → `SCALER_CROP_REGION`），到 `CameraCharacteristics` / `CaptureRequest` / `CaptureResult` 类页面搜索常量名；页面对每个 key 给出单位、取值范围、`CameraCharacteristics.Key`/`CaptureRequest.Key`/`CaptureResult.Key` 三种归属与 API level。
3. **`metadata_definitions.xml`（最权威、最完整）**：这是所有 `android.*` 条目的源定义，每个 `<entry>` 内含 description、units、range、details、`<enum>` 取值、hwlevel 要求（legacy/full 等）、hal_version；`@hide`/`system`/NDK-only 的条目只能在这里或生成文件里查到（官方网页不展示）。此外 `adb shell dumpsys media.camera` 的输出就是按此结构展开的静态特性。

学习建议：先把 5.1.2 的缓冲区结构和 5.2 的 find/update/clone 三件套用熟（这是 Framework 与 HAL 代码里出现频率最高的操作），再按 5.3 的 section 表建立"控制去 control、能力去 *_INFO、流配置去 scaler"的空间感，最后用 5.4 的统计脚本自己跑一遍 XML，对全体系条目规模留下量化印象。



