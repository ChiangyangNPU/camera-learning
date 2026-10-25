# Android Camera APP 面试题与答案

> 依据本仓库四份主文档整理：《Android\_Camera\_学习文档》《Android\_Camera\_接口文档》《Android\_Camera\_源码链路文档》《Android\_Camera\_ISP\_3A文档》。
> 难度标注：★ 基础（APP 岗必会）/ ★★ 中级（熟练掌握）/ ★★★ 高级（framework/HAL/ISP 方向加分项）。

## 目录

* [一、架构与基础概念](#一架构与基础概念)
* [二、Camera2 API 核心类](#二camera2-api-核心类)
* [三、CameraX](#三camerax)
* [四、3A 与元数据](#四3a-与元数据)
* [五、数据流配置与缓冲区](#五数据流配置与缓冲区)
* [六、性能优化](#六性能优化)
* [七、Framework 源码链路（进阶）](#七framework-源码链路进阶)
* [八、HAL 层（进阶）](#八hal-层进阶)
* [九、ISP 与 3A 算法（进阶）](#九isp-与-3a-算法进阶)
* [十、实战与开放题](#十实战与开放题)

***

## 一、架构与基础概念

### Q1. Android 相机系统整体架构分几层？各层职责是什么？ ★

自下而上分为：

1. **内核驱动层**：sensor/ISP 驱动（V4L2 框架）；
2. **HAL 层（相机硬件抽象层）**：实现 `ICameraProvider` / `ICameraDevice` / `ICameraDeviceSession` 接口，把 camera2 框架 API 映射到具体硬件，运行在独立的 provider 进程；
3. **CameraService（cameraserver 进程）**：原生框架，承载相机服务，管理 client 生命周期、优先级、权限；
4. **应用框架层**：`android.hardware.camera2` / CameraX（Java/Kotlin API）；
5. **应用进程**：相机 APP。

数据通路：APP → Binder → CameraService → HIDL/AIDL → HAL → 内核驱动 → sensor。

### Q2. 说说 Android 相机的三进程模型。 ★★

* **APP 进程**：调用 camera2 API，通过 Binder 接口 `ICameraService` / `ICameraDeviceUser` 与系统交互；
* **cameraserver 进程**：`CameraService::instantiate()` 注册为 `media.camera` 服务，负责权限校验、client 优先级管理与驱逐、会话与请求转发、结果回程分发；
* **HAL provider 进程**：厂商实现，管理真实的相机硬件，通过 `ICameraProvider` 注册到框架。

两个关键进程边界：APP ↔ CameraService 之间应用参数被视为**不可信**；CameraService ↔ HAL 是**安全边界**，HAL 必须自行验证参数（缓冲区长度、排错），防止权限提升。

### Q3. HAL3 的管道模型与 HAL1 的黑盒模型有什么区别？ ★★

* **HAL1（黑盒模型）**：只有预览、录像、静态拍照三种固定模式，模式间功能重叠，无法扩展连拍等新功能；参数通过 `set_parameters()` 字符串键值对下发。
* **HAL3（管道模型）**：相机子系统建模为一条管道，每个捕获请求 1:1 转化为一帧输出；输出 Surface 组**预先配置**（stream configuration），请求携带全部分辨率/3A/后处理控制；统一了各种运行模式，支持连拍和逐帧细粒度控制。

HAL1 已弃用；Android 8.0（Treble）起 HAL 必须 HIDL 化，Android 13 起改用 AIDL，此后新功能只在 AIDL 接口上提供。

### Q4. Camera2 的 request/result 模型有哪些核心规则？ ★★

* 一个请求 = 一次捕获的全部配置（分辨率、像素格式、3A、后处理控件等），对应一组结果；
* 请求**提交不阻塞**，HAL 必须\*\*按接收顺序（FIFO）\*\*处理；
* 同一请求的所有输出使用**完全相同的时间戳**，便于框架匹配结果与请求；
* "设置随请求走，状态随结果回"——除 3A 例程外，所有配置与状态都封装在请求/结果中；
* 在途请求（in-flight）数量由**管道深度**决定，结果因流水线延迟滞后返回。

### Q5. Camera1 和 Camera2 的设计差异？为什么 Camera2 更好？ ★

| 维度   | Camera1                  | Camera2                    |
| ---- | ------------------------ | -------------------------- |
| 模型   | 命令式、状态机                  | 会话 + 请求管道                  |
| 控制粒度 | `Camera.Parameters` 整包设置 | 逐帧 `CaptureRequest` 控制     |
| 输出   | 单一预览回调                   | 多 Surface（预览/拍照/分析并行）      |
| 手动控制 | 基本没有                     | 手动曝光/对焦/ISO（FULL 级设备）      |
| 同步   | 回调松散                     | 逐帧 `CaptureResult` + 精确时间戳 |

Camera1 已 deprecated；旧链路 `setPreviewDisplay + startPreview` 对应 Camera2 的 `createCaptureSession + setRepeatingRequest`。

### Q6. 什么是 Treble？对相机有什么影响？ ★★

Android 8.0 引入的 Project Treble 把框架（system 分区）与 vendor 实现（vendor 分区）通过稳定的 HIDL 接口解耦，两者可独立升级。相机 HAL 因此从旧的 `camera.h` 共享库（`camera.<device_name>`）迁移到 binderized HIDL HAL（provider 进程）；Android 13 起进一步迁移到 AIDL。验证要求是 HAL 必须通过全部 CTS 和 VTS 测试。

***

## 二、Camera2 API 核心类

### Q7. 描述 Camera2 的"三层句柄"模型与一次完整使用流程。 ★

三层句柄：**CameraManager → CameraDevice → CameraCaptureSession**，配单向请求流。

固定主线五步：

1. **枚举**：`CameraManager.getCameraIdList()`，用 `getCameraCharacteristics()` 查 `LENS_FACING` 选相机（ID 无顺序语义，"0" 不一定是后置）；
2. **打开**：`openCamera(cameraId, StateCallback, handler)`，异步，需 CAMERA 权限；
3. **建会话**：`device.createCaptureSession(SessionConfiguration)`，登记所有输出 Surface（预览/拍照/分析）；
4. **下发请求**：`setRepeatingRequest()`（预览）/ `capture()`（拍照）；
5. **收结果**：`CaptureCallback` 回调元数据（CaptureResult），图像数据直接流入登记的 Surface（如 ImageReader）。

核心思想：**控制流与数据流分离**；openCamera/createCaptureSession 都是异步回调，同步异常只表示"调用本身不合法"。

### Q8. CameraDevice.StateCallback 有哪些回调？onDisconnected 和 onError 怎么处理？ ★

* `onOpened`：打开成功，此后才能建会话；
* `onDisconnected`：被更高优先级客户端抢占，此后调用抛 `IllegalStateException`，应尽快 `close()`；
* `onError(error)`：致命错误，错误码 1\~5：`ERROR_CAMERA_IN_USE`(1)、`ERROR_MAX_CAMERAS_IN_USE`(2)、`ERROR_CAMERA_DISABLED`(3)、`ERROR_CAMERA_DEVICE`(4，需重新 open)、`ERROR_CAMERA_SERVICE`(5，通常需重启设备)；
* `onClosed`：close 完成的最终确认。

处理原则：onDisconnected/onError 后设备不可再用，应 close 并视情况重新 open；所有相机资源释放在 onClosed 之后才算彻底。

### Q9. createCaptureRequest 的 TEMPLATE 有哪些？拍照用哪个？ ★

| Template                    | 用途                       |
| --------------------------- | ------------------------ |
| `TEMPLATE_PREVIEW`          | 预览（低延迟、连续 3A）            |
| `TEMPLATE_RECORD`           | 录像（稳定帧率）                 |
| `TEMPLATE_STILL_CAPTURE`    | **拍照**（倾向高质量）            |
| `TEMPLATE_VIDEO_SNAPSHOT`   | 录像中拍静帧                   |
| `TEMPLATE_ZERO_SHUTTER_LAG` | ZSL 拍照，配合 reprocess      |
| `TEMPLATE_MANUAL`           | 全手动（需 MANUAL\_SENSOR 能力） |

模板只是预置了一组合理默认值，可在 Builder 上继续覆盖。

### Q10. setRepeatingRequest 和 capture 的区别？预览为什么要用 repeating？ ★

* `setRepeatingRequest()`：请求无限重复进入 HAL，用于预览/录像这类连续流；
* `capture()`：单次请求，**优先级高于重复请求**，用于拍照。

预览用 repeating 是因为每帧参数基本相同，让 HAL 自己循环填充请求效率最高；拍照用单次 capture 以便下发完全不同的参数（如高质量模板、precapture 序列）。

API 33 起新增了 Executor 版本的新方法：`captureSingleRequest`、`setSingleRepeatingRequest`（旧 `Handler` 版方法被标记 deprecated，语义一致）。

### Q11. CameraCaptureSession.CaptureCallback 的回调顺序是怎样的？ ★★

单帧生命周期：`onCaptureStarted`（曝光开始，含精确时间戳）→ `onCaptureProgressed`（partial result，可能多次）→ `onCaptureCompleted`（TotalCaptureResult）/ `onCaptureFailed`；一批请求结束后回调 `onCaptureSequenceCompleted`；`onCaptureBufferLost` 表示某输出缓冲区丢失。

注意：partial result 的回调时机不保证，需要完整元数据必须用 `TotalCaptureResult`（如做 reprocess 或读最终 3A 状态）。

### Q12. CameraCharacteristics / CaptureRequest / CaptureResult 三类元数据什么关系？ ★★

三者都继承 `CameraMetadata`，共用 Key 体系，分别对应"能力 → 意图 → 实效"：

* **CameraCharacteristics（静态）**：设备能力，`getCameraCharacteristics()` 获得，不可变；常用 Key：`LENS_FACING`、`SENSOR_ORIENTATION`、`SCALER_STREAM_CONFIGURATION_MAP`、`INFO_SUPPORTED_HARDWARE_LEVEL`、`REQUEST_AVAILABLE_CAPABILITIES`；
* **CaptureRequest（动态控制）**：每帧想怎么拍，Builder 设置；
* **CaptureResult（动态状态）**：每帧实际怎么拍的（最终曝光、3A 状态）。

设备实际支持哪些 Key 要查 `getAvailableCaptureRequestKeys()` / `getAvailableCaptureResultKeys()`，避免下发无效控制。

### Q13. 硬件支持级别 INFO\_SUPPORTED\_HARDWARE\_LEVEL 有哪几级？ ★★

* `LEGACY`：兼容模式，只具备旧 Camera API 能力；
* `LIMITED`：≈ 旧 API+，部分 Camera2 能力；
* `FULL`：手动控制、高速连拍、逐帧控制等全量能力；
* `LEVEL_3`：FULL 之上再加 RAW/YUV reprocess 等；
* 另有 `EXTERNAL`（USB 外置相机，能力在 LIMITED 之下）。

能力标签看 `REQUEST_AVAILABLE_CAPABILITIES`：`RAW`、`MANUAL_SENSOR`、`MANUAL_POST_PROCESSING`、`BURST_CAPTURE`、`LOGICAL_MULTI_CAMERA`、`CONSTRAINED_HIGH_SPEED_VIDEO` 等。

### Q14. ImageReader 怎么用？acquireLatestImage 和 acquireNextImage 区别？ ★

`ImageReader(format, width, height, maxImages)` 创建，其 `getSurface()` 注册进会话；`setOnImageAvailableListener` 收帧。

* `acquireNextImage()`：按 FIFO 取下一张，配合 `maxImages` 做背压；
* `acquireLatestImage()`：丢弃队列中旧帧、只留最新一张（适合实时分析，防止积压延迟）。

**关键坑**：取到的 `Image` 用完必须 `close()`，否则缓冲区耗尽后预览卡死；YUV\_420\_888 要按 plane + rowStride/pixelStride 读取，不能假设内存连续。

### Q15. 为什么 getCameraIdList 返回的 "0" 不一定是后置相机？ ★

相机 ID 只是字符串标识，无顺序语义。判断前后置必须用 `CameraCharacteristics.LENS_FACING`（`BACK`/`FRONT`/`EXTERNAL`）。

### Q16. 打开相机需要哪些权限和前置条件？ ★

* Manifest 声明 `android.permission.CAMERA` 并运行时申请；
* 可选声明 `<uses-feature android:name="android.hardware.camera.any">`；
* 无 CAMERA 权限时 `getCameraCharacteristics()` 部分 Key 会被过滤（API 32+ 可查 `getKeysNeedingPermission()`）；
* `openCamera` 可能抛 `CameraAccessException`（`CAMERA_IN_USE`、`MAX_CAMERAS_IN_USE`、`CAMERA_DISABLED` 等原因码）或 `SecurityException`。

***

## 三、CameraX

### Q17. CameraX 是什么？和 Camera2 是什么关系？ ★

CameraX 是 Jetpack 相机库，**不是新的底层框架**，而是对 Camera2 的封装（默认实现 `camera-camera2`，入口 `Camera2Config`），最终仍走 `android.hardware.camera2` 到 HAL。核心价值：

* **生命周期感知**：与 `LifecycleOwner` 绑定，自动 open/start/stop/close；
* **Use Case 抽象**：预览/拍照/分析/录像四个用例，分辨率协商自动完成；
* **设备兼容性**：内置大量 OEM workaround，官方设备实验室回归；
* 最低支持 API 21。

### Q18. CameraX 的四大 UseCase 是什么？绑定规则？ ★

* `Preview`：预览流；
* `ImageCapture`：拍照（JPEG/RAW）；
* `ImageAnalysis`：帧分析（YUV 回调，接 MLKit 等）；
* `VideoCapture`：录像（`Recorder`/`Recording` API）。

通过 `ProcessCameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCases...)` 一次性绑定；同一相机最多同时绑定 3 个 UseCase；分辨率协商优先级 **ImageCapture > Preview > ImageAnalysis**。

### Q19. 什么场景选 CameraX，什么场景选 Camera2？ ★

* 选 **CameraX**：常规预览/拍照/录像/扫码，追求开发效率、兼容性与生命周期安全；
* 选 **Camera2**：需要逐帧手动控制（手动曝光/对焦曲线）、多 Surface 精细编排、reprocess/ZSL、高速连拍、并发多摄、自定义 pipeline、极致性能调优；
* 混合方案：CameraX 为主，通过 `Camera2Interop.Extender`（Builder 期）和 `Camera2CameraControl.setCaptureRequestOptions()`（运行时）下发 Camera2 独有参数，`Camera2CameraInfo` 读底层 Characteristics。

### Q20. CameraX 预览 Surface 是怎么提供的？ ★★

`Preview.setSurfaceProvider { surfaceRequest -> ... }`：CameraX 协商出分辨率后回调 `SurfaceRequest`，应用在 `provideSurface()` 里给出目标 Surface（典型用 `camera-view` 的 `PreviewView.surfaceProvider` 一行搞定）。注意：不打算提供 Surface 也要调 `request.close()`，否则相机会一直等待；回调有阻塞版（主线程，须立即返回）与非阻塞版（指定 Executor，推荐）两种。

### Q21. CameraX 如何切换前后摄？如何限制可用相机？ ★

用 `CameraSelector`：`DEFAULT_BACK_CAMERA` / `DEFAULT_FRONT_CAMERA`，或 `Builder().requireLensFacing(LENS_FACING_BACK).build()`，重新 `bindToLifecycle` 即可切换。全局筛选可用 `CameraXConfig.Builder.setAvailableCamerasLimiter(...)`（如只允许后置）。

***

## 四、3A 与元数据

### Q22. 3A 指什么？各自解决什么问题？ ★

* **AE（自动曝光）**：控制曝光时间与增益，使画面亮度收敛到目标值；
* **AF（自动对焦）**：驱动镜头马达使目标区域清晰；
* **AWB（自动白平衡）**：估计环境色温，调整 R/B 增益使白色还原为白色。

底层是"ISP 统计 → 3A 算法 → 下发参数 → sensor"的闭环反馈回路。

### Q23. ANDROID\_CONTROL\_MODE 有哪些取值？ ★★

* `OFF`：全手动，3A 全部停用；
* `AUTO`：默认，3A 各自按模式自动运行；
* `USE_SCENE_MODE`：按场景模式（人像/夜景/运动等）整体配置 3A 与后处理；
* `USE_EXTENDED_SCENE_MODE`：厂商扩展场景（如 BOKEH）。

### Q24. AF 有哪几种模式？预览和拍照分别用什么模式？ ★★

`CONTROL_AF_MODE`：`OFF`（手动）、`AUTO`（单次对焦，配合 trigger）、`MACRO`（微距）、`CONTINUOUS_VIDEO`、`CONTINUOUS_PICTURE`、`EDOF`（泛焦）。

* 预览/录像：`CONTINUOUS_VIDEO` 或 `CONTINUOUS_PICTURE`（连续对焦，无需触发）；
* 拍照：`AUTO` 模式 + `CONTROL_AF_TRIGGER_START` 触发一次对焦扫描，锁定后再 capture。

### Q25. AF 状态机有哪些状态？一次点按对焦的状态流转是怎样的？ ★★

状态：`INACTIVE`、`PASSIVE_SCAN`、`PASSIVE_FOCUSED`、`PASSIVE_UNFOCUSED`、`ACTIVE_SCAN`、`FOCUSED_LOCKED`、`NOT_FOCUSED_LOCKED`。

点按对焦（AUTO 模式）流转：收到 `AF_TRIGGER_START` → `ACTIVE_SCAN`（主动扫描）→ 锁定为 `FOCUSED_LOCKED`（合焦）或 `NOT_FOCUSED_LOCKED`（失败）；`AF_TRIGGER_CANCEL` 或新 trigger 可解锁回 `INACTIVE`。连续对焦模式下走 `PASSIVE_SCAN` ↔ `PASSIVE_FOCUSED`/`PASSIVE_UNFOCUSED` 的被动回路。面试常考点：**要等 `CONTROL_AF_STATE` 变为 FOCUSED\_LOCKED 再拍照**。

### Q26. AE 的模式与状态？拍照前的 precapture 流程是什么？ ★★★

AE 模式：`OFF`、`ON`、`ON_AUTO_FLASH`、`ON_ALWAYS_FLASH`、`ON_AUTO_FLASH_REDEYE`、`ON_EXTERNAL_FLASH`。
AE 状态：`INACTIVE`、`SEARCHING`、`CONVERGED`、`LOCKED`、`FLASH_REQUIRED`、`PRECAPTURE`。

标准拍照流程（高品质静帧）：

1. 发 `CONTROL_AE_PRECAPTURE_TRIGGER_START`，AE 状态进入 `PRECAPTURE`；
2. AE 做预闪测光（需要闪光时触发 pre-flash），重新收敛；
3. 轮询 CaptureResult，等 `CONTROL_AE_STATE` 变为 `CONVERGED`（或 `FLASH_REQUIRED`/`LOCKED`）；
4. 发 `TEMPLATE_STILL_CAPTURE` 请求正式拍照（需要时主闪光同步点亮）；
5. 拍完发 `PRECAPTURE_TRIGGER_CANCEL` 恢复预览。

### Q27. AWB 有哪些模式？AWB 状态机比 AE 简单在哪里？ ★★

`CONTROL_AWB_MODE`：`OFF`（手动，配 `COLOR_CORRECTION_GAINS`）、`AUTO`、`INCANDESCENT`、`FLUORESCENT`、`WARM_FLUORESCENT`、`DAYLIGHT`、`CLOUDY_DAYLIGHT`、`TWILIGHT`、`SHADE`（后七个是预设色温）。

AWB 状态只有 `INACTIVE`、`SEARCHING`、`CONVERGED`、`LOCKED` 四个——没有触发器（无 precapture 等价物），因为白平衡不需要像闪光/对焦那样的"准备动作"，锁定靠 `CONTROL_AWB_LOCK`。

### Q28. AE/AF/AWB 的 LOCK 有什么作用？和 trigger 什么关系？ ★★

三个锁（`CONTROL_AE_LOCK`、`CONTROL_AF_LOCK`、`CONTROL_AWB_LOCK`）把对应算法冻结在当前状态，不再随场景变化更新。典型场景：连拍时锁 3A 保证整组照片曝光/白平衡一致；录像变焦时锁 AE 防亮度跳变。AF 无独立 LOCK 控件，锁定体现在 `FOCUSED_LOCKED` 状态；trigger 是"一次性动作"（START/CANCEL），lock 是"持续状态"。

### Q29. 如何做手动曝光（M 档）？需要哪些能力和 Key？ ★★

前置：设备 `INFO_SUPPORTED_HARDWARE_LEVEL` ≥ FULL 且 `REQUEST_AVAILABLE_CAPABILITIES` 含 `MANUAL_SENSOR`。

步骤：`CONTROL_MODE = OFF`（或保持 AUTO 但 `CONTROL_AE_MODE = OFF`），然后逐帧设置 `SENSOR_EXPOSURE_TIME`（曝光时间，ns）、`SENSOR_SENSITIVITY`（ISO）、`LENS_FOCUS_DISTANCE`（手动对焦，单位屈光度 diopter）、`SENSOR_FRAME_DURATION`（帧时长）。范围从 Characteristics 的 `SENSOR_INFO_EXPOSURE_TIME_RANGE`、`SENSOR_INFO_SENSITIVITY_RANGE` 等查询。结果里的实际值以 CaptureResult 为准。

***

## 五、数据流配置与缓冲区

### Q30. StreamConfigurationMap 是什么？选输出尺寸的注意事项？ ★★

`SCALER_STREAM_CONFIGURATION_MAP` 提供各输出格式（`getOutputSizes(format/class)`）支持的尺寸与最小帧时长。注意：

* 不同格式可选尺寸集合不同（JPEG 通常全量，YUV 可能较少）；
* 预览 Surface 尺寸受显示控件限制（TextureView 一般用 `getOutputSizes(SurfaceTexture.class)`）；
* 盲目选大尺寸会带来带宽/耗电代价，官方另提供**推荐数据流配置**（`RecommendedStreamConfigurationMap`，按 PREVIEW/RECORD/SNAPSHOT 等用例给出子集）；
* 配流合法性可用 `isStreamCombinationSupported`（API 29+；Android 15 起还有 `CameraDeviceSetup` 组合查询 API）验证。

### Q31. 预览 + 拍照 + 分析三路同时输出怎么实现？有什么限制？ ★★

建会话时把三个 Surface 一起登记：预览（SurfaceView/TextureView）、拍照（ImageReader JPEG）、分析（ImageReader YUV\_420\_888）。请求里按需要 `addTarget()`：预览 repeating 请求带预览 + 分析 Surface，拍照 capture 请求带 JPEG Surface。

限制：HAL 一次只能配置少量输出 Surface（约 3 个）；尺寸组合必须落在保证支持的 stream combination 内；JPEG 属于 stalling stream，预览流要求 non-stalling。

### Q32. 数字变焦怎么做？SCALER\_CROP\_REGION 和 ZOOM\_RATIO 什么关系？ ★★

两种方式：

1. `SCALER_CROP_REGION`：在传感器有效阵列（`SENSOR_INFO_ACTIVE_ARRAY_SIZE`）内裁剪一个小区域，HAL 放大到输出尺寸；最大变焦倍数查 `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`；
2. `CONTROL_ZOOM_RATIO`（Android 11+）：直接设目标倍率，框架换算成 crop region（多摄逻辑设备下还可能触发镜头切换）。

裁剪坐标以 active array 为基准，多输出流共享同一 crop region，但各流可有不同分辨率。

### Q33. 什么是 reprocess？ZSL 是怎么实现的？ ★★★

**Reprocess**：把之前捕获的 YUV（或 RAW）数据作为输入流重新送进 HAL 做高质量再处理。需 `createReprocessableCaptureSession`（InputConfiguration）+ `createReprocessCaptureRequest(TotalCaptureResult)`。

**ZSL（零快门延迟）**：维护一个循环缓冲区持续接收预览帧；按下快门时取最近一帧的 `TotalCaptureResult` 构造 reprocess 请求，对该帧做高质量重处理出图。因为帧在按快门前已曝光完成，所以感知上零延迟。模板 `TEMPLATE_ZERO_SHUTTER_LAG` 配合，要求设备支持 `YUV_REPROCESSING`/`PRIVATE_REPROCESSING` 能力。

### Q34. YUV\_420\_888 是什么格式？为什么实时分析常用它？ ★

Android 通用的 YUV 4:2:0 半平面格式：三个 plane（Y、U、V），色度分辨率是亮度的 1/4。它是预览/分析的默认格式，因为 ISP 原生输出 YUV、可直接送编码器或算法；读的时候必须用 `plane.rowStride`/`pixelStride` 逐 plane 访问（不同厂商内存布局不同）。转 NV21 或 Bitmap 需要手动拼 plane。

### Q35. 一帧图像从 HAL 到屏幕经过哪些缓冲区环节？ ★★★

生命周期：消费者（如 SurfaceFlinger/ImageReader）从 BufferQueue **出队**一块空缓冲 → 经 CameraService 交给 HAL **填充** → HAL 归还 → **queue** 回消费者 → 消费者取用/渲染 → 释放回 BufferQueue 循环。缓冲区在配流阶段按 `maxImages`/usage 预分配，归属在 producer 与 consumer 间流转；APP 侧 `Image.close()` 才是真正归还缓冲区的动作，不归还会导致出队失败、预览卡死。

***

## 六、性能优化

### Q36. 预览帧率上不去 / 卡顿，从哪些方向排查？ ★★

1. **尺寸与格式**：输出尺寸过大、JPEG stalling 流混入预览请求；
2. **帧时长**：`SENSOR_FRAME_DURATION`/AE 曝光时间超过帧间隔（暗光下曝光 50ms 则最高 20fps）；查 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 设置合理 FPS 区间；
3. **缓冲区**：ImageReader 的 Image 没有及时 close，缓冲耗尽；
4. **线程**：回调 Handler 线程被阻塞（建议专用 HandlerThread）；
5. **请求模板**：预览误用 STILL\_CAPTURE 模板（后处理 FAST→HIGH\_QUALITY 降帧率）；
6. 用 `adb shell dumpsys media.camera` 看 in-flight 请求与流状态。

### Q37. 什么是会话参数（session parameters）？解决了什么问题？ ★★

建会话时通过 `SessionConfiguration.setSessionParameters()` 一次性下发的参数（API 28+）。传统做法是每个请求重复携带某些参数（如厂商扩展开关），会话参数让 HAL 在配流阶段就知道这些设置，避免逐请求开销、且让 HAL 有机会据此选择更优的 pipeline 配置。代价：改动会话参数必须重建会话；设备支持哪些会话 Key 查 `getAvailableSessionKeys()`；API 29+ 有 `isReconfigurationSupported` 可查询不重配流的参数变更。

### Q38. HAL3 缓冲区管理 API 是什么？ ★★★

Android 10 引入的优化（需 HAL 支持）：把缓冲管理职责部分交给 HAL，三个核心方法——`requestStreamBuffers`（HAL 主动向框架要缓冲）、`returnStreamBuffers`（归还）、`signalStreamFlush`（流刷新）。解决传统模式下框架为每个流预分配最大缓冲数、内存占用高的问题；启用后 HAL 按需申请，降低峰值内存。注意 `signalStreamFlush` 与整会话 `flush()` 语义不同，只刷新指定流。

### Q39. "单一生产方，多个使用方"（surface sharing）是什么场景？ ★★★

指一个图像流（如预览）同时被多个消费者使用（如屏幕显示 + 录屏/分享）。实现机制是让多个消费者共享同一 Surface/BufferQueue，HAL 作为唯一生产方填充一次、多方读取，避免多开一路流带来的带宽与内存翻倍。相关 API：`OutputConfiguration.enableSurfaceSharing()`、`setPhysicalCameraId()`（物理多摄场景）。验证要求：共享期间缓冲区时序与同步（fence）必须正确。

### Q40. 高帧率录像（120/240fps 慢动作）怎么实现？ ★★

设备需 `CONSTRAINED_HIGH_SPEED_VIDEO` 能力；用 `CameraConstrainedHighSpeedCaptureSession`（或 `SessionConfiguration.SESSION_HIGH_SPEED`）。约束：尺寸/帧率组合必须来自 `StreamConfigurationMap.getHighSpeedVideoSizes()`，且高速会话只支持预览 + 录像两路；`CONTROL_AE_TARGET_FPS_RANGE` 设为高速区间；高速模式通常锁 3A 行为，画质调节空间小。

***

## 七、Framework 源码链路（进阶）

### Q41. 描述 openCamera 从 APP 到 HAL 的完整调用链。 ★★★

1. **Java 层**：`CameraManager.openCamera` → `openCameraDeviceUserAsync` → `CameraManagerGlobal` 拿到 `ICameraService` Binder 代理；
2. **跨进程**：调用 `ICameraService.connectDevice`（携带 cameraId、回调 `ICameraDeviceCallbacks`）；
3. **cameraserver 侧**：权限校验（`checkCameraAccess`）→ 优先级判断与驱逐（`handleEvictionsLocked`）→ 创建 `CameraDeviceClient`（内含 `Camera2ClientBase` / `Camera3Device` / `FrameProcessor`）→ 调用 HAL `ICameraDevice::open` → 注册死亡监听 → 把 `ICameraDeviceUser` 句柄返回 APP；
4. **APP 侧**：`StateCallback.onOpened` 收到 `CameraDevice`。

### Q42. 相机被"抢占"是怎么回事？框架的优先级驱逐机制？ ★★★

CameraService 维护 client 优先级体系：每个 client 有 `ClientPriority`（score + state），score 来源是 UidPolicy（监听 proc 状态）与 ProcessInfoService 的 oom score 实时刷新——前台 APP 分数高。新 client open 时若资源冲突，`ClientManager` 会驱逐低优先级 client：旧 client 收到 `onDisconnected`（对应 ICameraDeviceCallbacks 的断开回调），必须释放。这就是"微信视频时打开自家相机 APP 被拒/被踢"的机制。

### Q43. createCaptureSession 在框架侧做了什么？ ★★★

Java 层收集 Surface 列表 → cameraserver 侧 `CameraDeviceClient` 把 Surface 转成 `camera3_stream_t`（尺寸/格式/usage）→ `Camera3Device::configureStreams` → HAL `ICameraDeviceSession::configureStreams` 完成流协商（HAL 可修改 gralloc usage 等）→ 成功后会话就绪。之后 `capture()`/`setRepeatingRequest()` 的请求经 `submitRequestList` 进入 **RequestThread**——一个单线程请求泵，负责把请求按序喂给 HAL、管理 repeating 队列保活、处理会话参数热更新。

### Q44. 结果是怎么从 HAL 回到 APP 的？partial result 是什么？ ★★★

双通道回程：

1. **notify 通道**：HAL 调 `ICameraDeviceCallback.notify` 发 SHUTTER（曝光开始，含帧号与时间戳）和 ERROR 消息；
2. **processCaptureResult 通道**：HAL 调 `processCaptureResult` 返回元数据与输出缓冲。

cameraserver 用 in-flight map 按 `frameNumber` 匹配请求与结果。**partial result**：HAL 允许把一帧的元数据分多次返回（如 3A 状态先到、统计信息后至），框架累积拼接成 `TotalCaptureResult` 后再回调 `onCaptureCompleted`；`onCaptureProgressed` 拿到的就是中间 partial 结果。

### Q45. 相机框架层面有哪些错误处理与自愈机制？ ★★★

* **错误分类**：设备级错误（disconnect，需重开）、单帧错误（dropped buffer/result，可跳过）、缓冲错误（重新 queue）；HAL 错误经映射转成 APP 的 `onCaptureFailed`/`onError`；
* **flush()**：丢弃全部待处理请求，用于急停、切会话；
* **provider 死亡**：死亡监听触发，链路逐级断开通知 APP；
* **CameraServiceWatchdog**：定期探测 HAL 是否卡死，卡死则杀掉 provider 进程让其重启自愈；
* 请求/结果元数据 IPC 用 **FMQ（快速消息队列）** 降低 Binder 开销。

***

## 八、HAL 层（进阶）

### Q46. AIDL 相机 HAL 有哪些核心接口？各自职责？ ★★★

* `ICameraProvider`：枚举相机设备（`getCameraIdList`）、返回 `ICameraDevice`、状态回调（`ICameraProviderCallback` 热插拔通知）；
* `ICameraDevice`：打开会话（`open` → `ICameraDeviceSession`）、查静态元数据；
* `ICameraDeviceSession`：配流（`configureStreams`）、构造默认请求、提交请求（`processCaptureRequest`）、`flush`、缓冲区管理；
* `ICameraDeviceCallback`：HAL → 框架的回调（`notify`/`processCaptureResult`）；
* 另有 `ICameraOfflineSession`（离线处理会话：`ICameraDeviceSession.switchToOffline` 切入，继续交付切换前的 in-flight 请求；App 层对应 `CameraCaptureSession.switchToOffline()`，API 30。详见《Android_Camera_OfflineSession文档》）。

AIDL 与 HIDL 主要差异：统一可版本化接口、Android 13 起新功能只在 AIDL 提供。

### Q47. camera3.h 里 camera3\_device\_ops\_t 有哪些关键 op？ ★★★

方向均为 框架→HAL：`initialize`（注册 callback）、`configure_streams`（配流）、`construct_default_request_settings`（默认请求模板）、`process_capture_request`（提交请求）、`flush`、`dump` 等；HAL→框架回调在 `camera3_callback_ops_t`：`process_capture_result`、`notify`。关键结构：`camera3_stream_t`（尺寸/格式/usage/max\_buffers）、`camera3_capture_request_t`（frame\_number + settings + 输入/输出缓冲列表）、`camera3_stream_buffer_t`（buffer + acquire/release fence）。

### Q48. 元数据（camera\_metadata）体系怎么组织？厂商如何扩展？ ★★★

`camera_metadata_t` 是紧凑的二进制结构（header + entry 表 + data 区），按 tag 存取。tag 编号 = section（高 16 位）+ index；section 有 `CONTROL`、`SENSOR`、`LENS`、`FLASH`、`STATISTICS`、`SCALER`、`JPEG` 等。可见性分 public / system / vendor。**vendor tag**：厂商在 vendor section（section ID `0x8000`，即完整 tag 值从 `0x80000000` 起）注册私有 tag（provider 启动时把 tag 描述符上报给 CameraService，框架经 `ICameraService` 拿到），APP 侧按名字构造 Key 后像普通 Key 一样读写，是 OEM 开放私有能力（如超分、人像引擎开关）的标准通道。

***

## 九、ISP 与 3A 算法（进阶）

### Q49. Bayer 阵列是什么？为什么 sensor 输出要叫 RAW？ ★

CMOS 每个像素只能感知一种颜色，表面覆盖 Bayer 滤色阵列（CFA，RGGB 排列，G 通道两倍采样因为人眼对绿敏感）。sensor 输出的就是这种每个像素单通道的原始马赛克数据（raw domain），不含任何去马赛克/色彩处理，需要 ISP 的 demosaic 才能还原全彩图像。Android 元数据里 `SENSOR_INFO_WHITE_LEVEL`、`SENSOR_BLACK_LEVEL_PATTERN`、`SENSOR_NOISE_PROFILE` 等描述 raw 的特性。

### Q50. 曝光的数学模型？模拟增益和数字增益的区别？ ★★

曝光量 = **曝光时间 × 增益**（光圈固定时）。`SENSOR_EXPOSURE_TIME`（ns）控制电荷积分时间，`SENSOR_SENSITIVITY`（ISO）控制增益。

* **模拟增益**：在电荷/模拟域放大信号，信噪比损失小，优先使用；
* **数字增益**：ADC 之后数字域乘系数，同时放大噪声，画质差，作为模拟增益不够时的补充。

AE 的曝光表（exposure table）就是"时长/增益拆分策略"：优先拉长曝光时间，到帧时长上限后加模拟增益，最后才用数字增益。

### Q51. 卷帘快门和全局快门的区别？果冻效应怎么来的？ ★★

* **卷帘快门（rolling shutter）**：逐行曝光读出，各行曝光时刻不同 → 拍摄高速运动物体/快速移动手机时产生倾斜变形（果冻效应）；Android 有 `SENSOR_ROLLING_SHUTTER_SKEW` 元数据描述行间时差；
* **全局快门（global shutter）**：所有像素同时曝光，无畸变，成本高，手机 CMOS 基本不用。

### Q52. PDAF 原理？和反差对焦的区别？ ★★

* **反差对焦（CDAF）**：用 ISP 的 AF 统计（锐度/对比度曲线），镜头爬山搜索锐度峰值；准但慢、有拉风箱现象；
* **PDAF（相位差对焦）**：sensor 上埋专用 PD 像素（遮蔽一半的二合一像素），比较左右像的相位差直接算出离焦方向与距离，一步把镜头推到位；快但依赖标定、暗光/低反差失效。

实际手机多为**混合对焦**：PDAF 粗调 + 反差微调。PD 数据经 CSI-2 嵌入行送给 ISP 的 PDAF 统计块再到 AF 算法；Android 标准元数据中没有标准 PD tag（厂商私有）。

### Q53. 通用 ISP 管线有哪些 stage？ ★★

通行排布（硬件实际顺序可能不同）：BPC 坏点校正 → LSC 镜头阴影校正 → 黑电平校正 → demosaic 去马赛克 → NR 降噪 → 锐化 → 色彩校正（CCM）→ gamma/色调映射 → YUV 转换与缩放。另有三条统计支路：AE 直方图/分区测光、AWB 白块统计、AF 锐度/PD 统计，以及 LSC map。处理块模式：`OFF` / `FAST`（不降帧率，预览录像用）/ `HIGH_QUALITY`（允许降帧率，拍照用）。

### Q54. AE 算法的收敛过程是怎样的？什么是 antibanding？ ★★★

流程：ISP 给出分区测光均值/直方图 → 与目标亮度比较 → 迭代求初始增益 → 约束钳位（曝光时间 ≤ 帧时长、增益 ≤ 上限）→ 指数滤波平滑输出，避免画面亮度跳变。

**Antibanding（防工频闪烁）**：交流电灯光以 50/60Hz 频闪，卷帘快门各行曝光时刻不同会出现明暗条纹；对策是把曝光时间设为 1/100s（50Hz）或 1/120s（60Hz）的整数倍。`CONTROL_AE_ANTIBANDING_MODE`（AUTO/50HZ/60HZ/OFF）即对应此机制。

### Q55. AWB 的灰世界算法与色温估计？ ★★★

**灰世界假设**：自然场景平均色彩趋近灰色，ISP 白块统计筛选出"接近白色"的块，计算其 R/G/B 均值，反推 R、B 通道增益使三者相等。进阶做法是**色温估计**：把统计点映射到色温轴上与黑体轨迹/标定曲线比对估计色温（还有 Bayes 方法在色温轴上做概率估计），再插值出增益，并对帧间结果做平滑防抖。预设 AWB 模式（日光/阴天/白炽灯…）本质就是查预设色温对应的增益表。

### Q56. 高通 CAMX 和 MTK mtkcam 是什么定位？tuning 是什么？ ★★★

* **高通 CAMX/Chi-CDK**：高通平台的相机 HAL 框架，CAMX 是核心（pipeline/node 抽象，用 topology XML 描述数据流图，驱动 Spectra ISP），Chi-CDK 是 OEM 定制扩展层（覆盖 node 实现、插入自研算法）；
* **MTK mtkcam**：联发科平台相机 HAL，自有 pipeline 管理（P1/P2 驱动节点对应 sensor/ISP 通路）。

**Tuning**：针对具体 sensor/镜头模组标定 ISP 各模块参数（黑电平、LSC 表、CCM、NR/锐化强度、3A 目标与约束等）的过程，产出如高通 Chromatix 参数文件；是把同一硬件调出不同成像风格的关键，一般由 tuning 团队与算法/HAL 团队协作完成。

***

## 十、实战与开放题

### Q57. 实现一个完整拍照流程（含对焦与预闪），写出关键代码骨架。 ★

```kotlin
// 1. 打开相机
cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
    override fun onOpened(device: CameraDevice) {
        // 2. 建会话：预览 Surface + ImageReader(JPEG)
        device.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(previewSurface), OutputConfiguration(imageReader.surface)),
            executor, sessionStateCallback))
    }
    // ... onError/onDisconnected 省略
}, handler)

// 3. 会话就绪后：预览 repeating
val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    .apply { addTarget(previewSurface) }
session.setRepeatingRequest(previewReq.build(), captureCallback, handler)

// 4. 点按拍照：先发 precapture 触发
val precapture = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
}
session.capture(precapture.build(), captureCallback, handler)

// 5. 在 CaptureCallback 里等 CONTROL_AE_STATE == CONVERGED/FLASH_REQUIRED 后：
val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
    addTarget(imageReader.surface)
    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation) // 由 SENSOR_ORIENTATION + 设备旋转角算出
}
session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
        // 6. 恢复预览 repeating，等 ImageReader.onImageAvailable 保存 JPEG
    }
}, handler)
```

评分点：异步回调链、precapture 等待、JPEG\_ORIENTATION 计算、Image close、恢复预览。

### Q58. 预览画面旋转/镜像怎么处理？ ★★

* **旋转**：`SENSOR_ORIENTATION`（传感器安装角，常见后置 90、前置 270）结合 `display.rotation` 计算补偿角，对 TextureView 设 `setTransform(matrix)` 或 SurfaceView 选竖屏尺寸；公式：`(sensorOrientation - deviceRotation * (facing == FRONT ? -1 : 1) + 360) % 360`；
* **前置镜像**：前置默认输出已按"照镜子"习惯处理，拍照存 JPEG 时通过 `JPEG_ORIENTATION` 让系统写入正确 EXIF 方向即可，不要自己再翻转像素。

### Q59. 拍照出来是绿色/花屏/方向错，可能的原因？ ★★

* **花屏/绿图**：YUV\_420\_888 读取时忽略 rowStride/pixelStride；分辨率/stride 对齐问题；把 YUV 当 RGB 解；
* **方向错**：`JPEG_ORIENTATION` 没算对（sensor 安装角 + 设备旋转角 + 前后置）；
* **颜色异常**：AWB 锁定在错误色温；手动模式 `COLOR_CORRECTION_TRANSFORM` 配置错；
* **全黑**：AE 锁定在极低曝光、或 ImageReader 尺寸与请求 target 不匹配。

### Q60. 如何实现录像中拍照（video snapshot）？ ★★

录像会话里同时登记 MediaCodec/MediaRecorder 的 Surface 和 ImageReader(JPEG) Surface；录像走 `TEMPLATE_RECORD` repeating；拍静帧时发 `TEMPLATE_VIDEO_SNAPSHOT` 的单次 capture（画质偏录像模式、不打断视频流）。尺寸要求：VIDEO\_SNAPSHOT 至少与最大 RECORD 分辨率一样大，且不应引起预览故障、保持 30fps。

### Q61. 双摄像头同时打开（如前后同开）怎么做？ ★★★

1. `CameraManager.getConcurrentCameraIds()`（API 30+）查询可保证并发打开的相机组合；
2. 用 `isConcurrentSessionConfigurationSupported()` 验证具体会话配置；
3. 分别 openCamera 并各自建会话；注意并发属于**强制并发流组合**（有规定的低分辨率组合），ISP 资源有限，超出会被拒。

逻辑多摄（`LOGICAL_MULTI_CAMERA`）是另一回事：一个逻辑相机下挂多个物理相机，框架自动做镜头切换，APP 可用 `setPhysicalCameraId` 拿物理子相机的流。

### Q62. 如何用厂商扩展（夜景/人像虚化）？ ★★

两条路：

1. **Camera Extensions**（API 31+，Android 12 引入）：`CameraManager.getCameraExtensionCharacteristics()` 查支持的扩展（`EXTENSION_NIGHT`、`EXTENSION_BOKEH` 等），用 `createExtensionSession()` 建扩展会话；CameraX 侧用 `camera-extensions` 库 `ExtensionsManager`；
2. **Vendor tag**：OEM 私有元数据，`CameraCharacteristics.getAvailableVendorTags()`（或名字查询）拿到后像普通 Key 一样 set 到 CaptureRequest。

### Q63. 线上相机问题（黑屏/打不开/卡死）怎么排查？ ★★

1. `adb shell dumpsys media.camera`：看当前打开的 client、会话、in-flight 请求、错误计数；
2. `adb shell cmd media.camera watch start -m android.control,android.sensor`（Android 13+）：实时监听指定元数据变化，观察 3A 是否收敛；
3. 检查 APP 侧：权限、回调线程阻塞、Image 未 close、onError 处理；
4. 区分错误层级：APP 逻辑错 / CameraService（看 logcat `CameraService` tag）/ HAL（vendor log）/ 驱动（kernel log）；
5. 复现优先级问题：是否有其他 APP 抢占（onDisconnected）。

### Q64. 你做过最有挑战的相机需求是什么？（开放题答题框架） ★

建议按 STAR 结构答，并落到本仓库的技术点上，例如：

* **场景**：扫码识别率低 / 夜景拍照慢 / 多摄切换卡顿；
* **分析**：用元数据/日志定位（3A 不收敛？帧率掉？缓冲区耗尽？）；
* **方案**：手动 3A 控制、调整 stream 组合、ImageReader 缓冲策略、会话参数预热、reprocess/ZSL、CameraX 迁移等；
* **结果**：量化收益（首帧时间、拍照出图延迟、识别率）。

### Q65. Camera APP 首帧启动速度怎么优化？ ★★★

先明确优化对象：用户感知的"首帧"是**预览画面出现在屏幕上**，而启动链路是 `Activity 创建 → openCamera → onOpened → createCaptureSession → onConfigured → setRepeatingRequest → 首帧 onCaptureStarted → 上屏`。每一段都有独立的压缩手段，下面按链路逐点展开。

| 阶段                                  | 主要成本                               | 对应优化         |
| ----------------------------------- | ---------------------------------- | ------------ |
| 进相机页之前                              | 设备枚举/能力查询、权限弹窗（不可控）                | 提前枚举、权限预申请   |
| openCamera → onOpened               | sensor 上电、provider 绑定、优先级仲裁        | 异步并行，把等待窗口填满 |
| createCaptureSession → onConfigured | **configureStreams（HAL 配流，常为最大头）** | 精简首会话、小流先出帧  |
| 首次请求 → 首帧上屏                         | 请求构建、参数下发、3A 冷启动                   | 会话参数预热、请求预构建 |

**1）提前枚举与缓存设备能力**

* **原理**：`getCameraIdList()` / `getCameraCharacteristics()` 是 Binder 调用，首次访问还要在 `CameraManagerGlobal` 建立 listener 快照，冷启动时可能几十 ms；如果放在 `onCreate` 的同步路径上，直接推迟 openCamera 的发起时刻。
* **做法**：在启动页/首页/Application 的后台线程预热 `CameraManager`，把目标相机的 ID 列表与 Characteristics 缓存（内存单例/LruCache）；相机页直接读缓存选相机，把 Binder 查询移出关键路径。SDK 层可以把"预热"做成显式 API 供业务在进入相机前调用。
* **坑**：
  * 预热**不要在主线程**——虽然是轻量 Binder，但冷启动首次访问可能触发 provider 枚举；
  * 提前**必须已授予 CAMERA 权限**才有意义：无权限时 Characteristics 的部分 Key 会被过滤（API 32+ 可用 `getKeysNeedingPermission()` 感知），权限授予后要作废缓存重新获取；
  * 多摄/热插拔会改变设备列表，监听 availability 回调或在使用前做一次廉价校验，不要无限期信任缓存。

**2）权限预申请，且不要让权限路径阻塞启动**

* **原理**：运行时权限弹窗是**用户交互**，耗时完全不可控（用户思考时间）；如果放在"点开相机"之后，首帧被弹窗整体阻塞——这不是技术问题，是流程设计问题。
* **做法**：在入口页（首页/引导页/拍照入口按钮处）就申请 CAMERA 权限（`ActivityResultContracts.RequestPermission`）；进入相机页时只做**同步的权限状态检查**：已授权 → 直接 openCamera；未授权 → 展示引导 UI，而不是把弹窗挂在启动链路上。
* **细节**：
  * "拒绝且不再询问"时引导到系统设置页，避免反复弹窗被系统静默拒绝；
  * 权限可能随时被撤销（设置页/多任务），相机页 `onResume` 要复查；`openCamera` 抛 `SecurityException` 要有兜底；
  * 录音/存储等附属权限同理不要串行进首帧路径，可在预览起来后再申请。

**3）异步并行：openCamera 的等待窗口要填满**

* **原理**：`openCamera` 是异步的（结果在 `onOpened` 回调），期间主线程如果只是"空等"，就把 UI 构建、资源加载的时间白白浪费；反过来，如果回调 Handler 用主线程，回调会排在主线程已有任务之后，进一步推迟 onOpened 的处理。
* **做法**：
  * 权限就绪后**第一时间发起 openCamera**（onCreate 里越早越好），紧接着并行做与相机无关的初始化：预览控件创建、UI 骨架、滤镜/编码器/模型加载、线程池准备；
  * 用**专用 HandlerThread** 承载 `openCamera` 回调与 `CaptureCallback`，回调线程不阻塞主线程的构建任务，主线程也不占回调队列；
  * 建立一个"启动栅栏"聚合两个异步源：**Surface 就绪（surfaceCreated）+ onOpened**，二者齐了才 `createCaptureSession`——这是并行的正确边界，会话必须等 Surface。
* **坑**：回调线程里不要做同步重活（会推迟后续回调）；几何/资源初始化失败要能降级（不能因为滤镜没加载完就不出预览）。

**4）精简首会话：小流先出帧，大流后补**

* **原理**：`createCaptureSession` 内部走 `configureStreams`，HAL 配流耗时随**流数量与尺寸**增长（预览 + JPEG + YUV + 高分辨率组合可达数百 ms），是启动链路第二大头。首帧只需要预览一路流，其余流对"看到画面"没有贡献。
* **方案 A：两段式会话**（最常用）——首会话只配预览流（PRIV/SurfaceView 直通、1080p 甚至 720p），`onConfigured` 后立即 `setRepeatingRequest` 出首帧；随后重建会话补上 JPEG ImageReader / YUV 分析流。重建期间预览会短暂闪断，产品可接受的话这是收益最大的做法（把大流成本挪到首帧之后）。
* **方案 B：懒配置 + 预检**——拍照 ImageReader 用最小可用尺寸、`maxImages=2`，或者干脆在第一次按快门前才创建（配合"首帧后预热"在空闲时预建）；会话组合先用 `isSessionConfigurationSupported`（API 28+）/ `isStreamCombinationSupported`（API 29+）预检，Android 15 起还有 `CameraDeviceSetup` 的离线组合查询，避免配流失败后的重试往返。
* **别忽略的选择**：预览控件尺寸与预览流尺寸匹配（避免 SurfaceView 拉伸缩放的大图）；预览请求用 `TEMPLATE_PREVIEW` 而不是拍照/录像模板（后处理档位影响帧率）。

**5）会话参数预热与请求预构建**

* **原理**：传统写法每个请求都要重复携带一批不变的设置（FPS 区间、模板参数、厂商扩展开关），首帧前多一轮解析；把这些设置放到**会话参数**里，HAL 在 `configureStreams` 阶段就能看到它们，还能据此选择更优的 pipeline 配置。
* **做法**：API 28+ 用 `SessionConfiguration.setSessionParameters(request)` 在建会话时一次性下发常用键（可用键查 `CameraCharacteristics.getAvailableSessionKeys()`）；`onConfigured` 后立刻用预构建的 `CaptureRequest` 发 repeating（模板请求在 `onOpened` 后就创建好 Builder，避免回调里再拼装）；`AE_TARGET_FPS_RANGE` 等稳定参数在首帧前就固定，减少首帧参数抖动。
* **代价**：会话参数变更通常要重建会话——只放**稳定**设置；不稳定的逐请求控制仍走 `CaptureRequest`。

**6）监控与验收：按阶段埋点，不要只看 onOpened**

* 打点：`t0` 页创建 → `t1` openCamera 发起 → `t2` onOpened → `t3` createCaptureSession → `t4` onConfigured → `t5` setRepeatingRequest → `t6` 首帧 `onCaptureStarted` → `t7` 首帧上屏（Surface 回调 + 帧回调近似）。
* 工具：Perfetto/Systrace 的 camera 轨道看 `configureStreams`/HAL 耗时；`dumpsys media.camera` 看会话与流状态；logcat 核对 onOpened 前后耗时。
* 验收口径：冷启动（进程被杀）与热启动（后台返回）分开统计——热启动时**不要重复 openCamera/重建会话**，保留 device/session 直接 `setRepeatingRequest` 恢复；配置变更（旋转）时用 ViewModel/retained 机制复用已打开设备，避免整链重走。
* 常见反模式：首帧前等 AE 收敛（拍照才需要，预览不需要）；把所有初始化塞进 `onOpened` 回调；预览等 JPEG ImageReader 一起建会话；在主线程做设备枚举。

> trace 视角的逐段拆解（Perfetto 抓取配置、启动阶段地图、判读与 SQL）见《Android\_Camera\_Trace分析文档》第 2\~3 章。

***

> 深入学习建议：Q1-Q6 对应《学习文档》第 1 章；Q22-Q29 对应第 2 章与《ISP\_3A文档》第 3 章；Q30-Q40 对应《学习文档》第 2/3 章；Q41-Q48 对应《源码链路文档》与《接口文档》第 4/5 章；Q49-Q56 对应《ISP\_3A文档》第 1-4 章；Q57-Q65 对应《源码链路文档》第 3 章（会话创建与请求提交）与《学习文档》第 3 章（性能）。
