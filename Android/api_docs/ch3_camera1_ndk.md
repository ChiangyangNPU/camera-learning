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

<!-- ch3 done -->
