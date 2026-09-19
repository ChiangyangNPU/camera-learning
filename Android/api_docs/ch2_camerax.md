# 第 2 章 CameraX（androidx.camera）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

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

<!-- ch2 done -->
