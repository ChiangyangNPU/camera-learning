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

<!-- ch1 done -->
