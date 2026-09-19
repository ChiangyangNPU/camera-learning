# 第 3 章 设备控制：3A 的 Apple 接口面

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 本章整理自 Apple 官方文档 `AVCaptureDevice` 及其扩展（逐节附链接），讲"Android 3A 元数据在 iOS 上的对应物"。3A 算法本体在系统内闭源（见《iOS_Camera_ISP_图像管线文档》第 2 章），本章只讲开发者可见的控制面。

## 3.1 控制模型总览：模式 + 锁定，而非逐帧元数据

> 来源（A 层）：[AVCaptureDevice 文档](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、[AVCam 示例](https://developer.apple.com/documentation/avfoundation/capture_setup/avcam_building_a_camera_app)。

Android 的 3A 控制是**逐帧键值**：每条 `CaptureRequest` 携带 `CONTROL_AF_MODE/AE_MODE/AWB_MODE` 等键，结果帧带回 3A 状态。iOS 的 3A 控制是**设备属性**：在 `AVCaptureDevice` 上设置模式与参数，一旦生效即持续作用，直到再次修改。两个关键机制：

1. **写锁**：所有配置修改必须包在 `lockForConfiguration()` / `unlockForConfiguration()` 之间（或 Swift 的 `device.lockForConfiguration { ... }`）；不锁直接写会抛异常。对应 Android 没有这个概念——Android 的"锁"隐含在"请求是原子提交"里。
2. **观察者**：3A 状态变化通过 KVO 与 NotificationCenter 观察（如 `adjustingFocus`、`subjectAreaDidChangeNotification`），对应 Android 的 `CaptureResult.CONTROL_AF_STATE` 轮询。

三大 3A 模式与 Android 元数据的对照（详细方法表见《接口文档》第 1 章）：

| 能力 | iOS 模式枚举 | Android 元数据对应 |
|---|---|---|
| 对焦 | `focusMode`: locked / autoFocus / continuousAutoFocus | `CONTROL_AF_MODE`: OFF / AUTO / CONTINUOUS_PICTURE（语义对齐） |
| 曝光 | `exposureMode`: locked / autoExpose / continuousAutoExposure / **custom** | `CONTROL_AE_MODE`: OFF / ON / ON_AUTO_FLASH…（custom ≈ AE OFF + 手动曝光） |
| 白平衡 | `whiteBalanceMode`: locked / continuousAutoWhiteBalance | `CONTROL_AWBC_MODE` / `COLOR_CORRECTION_MODE` |

iOS 没有独立的"3A 触发"接口——Android 的 `CONTROL_AF_TRIGGER_START/CANCEL` 与 precapture 序列（`AE_PRECAPTURE_TRIGGER`）在 iOS API 面上不存在；Apple 把"何时收敛"完全交给系统（拍照时照片管线内部自动处理收敛，开发者只选 flashMode 与 photoQualityPrioritization）。

## 3.2 对焦控制

> 来源（A 层）：[对焦相关 API](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、[AVCaptureDevice.focusPointOfInterest](https://developer.apple.com/documentation/avfoundation/avcapturedevice/1624621-focuspointofinterest)。

| 接口 | 类型 | 说明 |
|---|---|---|
| `focusMode` | 读写 | 三态模式；`continuousAutoFocus` 是预览默认 |
| `focusPointOfInterest` | 读写 | 归一化坐标 (0~1)，**原点在左上**；仅 `isFocusPointOfInterestSupported` 时有效 |
| `adjustingFocus` | 只读 KVO | 当前是否正在收敛（对应 AF_STATE SCANNING） |
| `isLockingFocusWithCustomLensPositionSupported` / `setFocusModeLocked(lensPosition:)` | 接口 | 手动镜头位置；**Apple 未公开 lens position 的绝对标定**（Android 有 `LENS_FOCUS_DISTANCE` 屈光度值） |
| `lensPosition` | 只读 | 当前镜头位置 (0.0~1.0) |
| `minimumFocusDistance` | 只读（iOS 15+） | 最近对焦距离（毫米），微距判定依据 |

与 Android 的实质差异：

- **触发语义缺失**：Android 单次对焦是 AF_TRIGGER + 等 AF_STATE_FOCUSED_LOCKED；iOS 惯用做法是"切到 autoFocus 模式触发一次收敛，收完（KVO 观察 `adjustingFocus` 变 false）再切回 continuousAutoFocus"——这是社区通行模式，Apple 示例 AVCam 亦然；
- **点击对焦的坐标换算**：`focusPointOfInterest` 用**设备传感器坐标系**（landscape 左上原点），与 UI 坐标（portrait）之间要做旋转换算；`videoRotationAngle`/previewLayer 的 `metadataOutputRectConverted` 系列可辅助换算。Android 的 `METERING_REGIONS` 用活动阵列 (0~1000) 且需自行乘方向。
- **手动对焦**：iOS 17 起部分机型支持锁定镜头位置（`setFocusModeLocked(lensPosition:)`），但无屈光度单位；要"焦点呼吸补偿/跟焦"这类电影级控制，能力面仍窄于 Android 手动路线。

## 3.3 曝光控制

> 来源（A 层）：[AVCaptureDevice 曝光 API](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、`setExposureModeCustom` 文档页。

曝光是 iOS 控制面最完整的一块，`ExposureModeCustom` 提供了接近 Android 手动 AE 的能力：

| 接口 | 说明 | Android 对应 |
|---|---|---|
| `exposureMode = .custom` + `setExposureModeCustom(duration:iso:)` | 手动锁定曝光时间与增益（参数传 `AVCaptureExposureDurationCurrent`/`AVCaptureCurrentISO` 表示"只锁一项"） | `CONTROL_AE_MODE=OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` |
| `exposureDuration` / `ISO`（min/max） | 只读边界查询 | `SENSOR_INFO_EXPOSURE_TIME_RANGE` 等 |
| `exposureTargetBias` / `minExposureTargetBias` / `maxExposureTargetBias` | 曝光补偿（EV，档位连续） | `CONTROL_AE_EXPOSURE_COMPENSATION` |
| `exposurePointOfInterest` + `exposurePointOfInterestSupported` | 点测光位置 | `CONTROL_AE_REGIONS` |
| `adjustingExposure`（KVO） | 收敛中标志 | AE_STATE |
| `exposureTargetOffset`（只读） | 当前实测与目标的偏差 | AE_STATE 里的目标差 |

要点：

- 手动曝光参数在**会话运行中**即时生效且持续到更改（Android 手动曝光通常配 `OFF` 模式 + 每帧请求；iOS 是"设置一次，锁定直到解锁"）；
- 与 Android 相同的坑：锁死曝光后场景变化会过曝/欠曝，滑动条 UI 应在用户松手后提供"回自动"入口；
- `exposureTargetBias` 是连续值（浮点 EV），Android 是步进（step）；换算 UI 时注意取整策略。

## 3.4 白平衡控制

> 来源（A 层）：[AVCaptureDevice.WhiteBalanceGains](https://developer.apple.com/documentation/avfoundation/avcapturedevice/whitebalancegains)、`deviceWhiteBalanceGains` 文档。

| 接口 | 说明 |
|---|---|
| `whiteBalanceMode` | locked / continuousAutoWhiteBalance 两态 |
| `deviceWhiteBalanceGains` | 手动 RGB 增益三元组（1.0 ~ `maxWhiteBalanceGain`），仅 locked 模式下可写 |
| `captureDeviceWhiteBalanceGains(for:)` | 把**色温+色调**（`AVCaptureDevice.WhiteBalanceTemperatureAndTintValues`）换算为 RGB 增益 |
| `chromaticityValues(for:)` / `temperatureAndTintValues(for:)` | 色度坐标 (x,y) 与温/色调、RGB 增益互转 |

对照 Android：`COLOR_CORRECTION_GAINS`（R/G_B even四通道）与 `COLORCORRECTION_TRANSFORM`（3×3 CCM）两个层级在 iOS 合并为一个"设备 RGB 增益"概念，CCM 不可第三方控制；"色温滑条"类 UI 直接用 `captureDeviceWhiteBalanceGains(for: temperatureAndTint)` 换算即可，Apple 已把_sensor 响应下的温色→增益标定_做进系统（Android 上这需要 OEM 标定或自算）。

## 3.5 闪光灯、手电筒与变焦

> 来源（A 层）：[torch/flash API](https://developer.apple.com/documentation/avfoundation/avcapturedevice)、[变焦与虚拟设备](https://developer.apple.com/documentation/avfoundation/avcapturedevice/virtual_device_support)（Virtual Device Support 文档组）。

**闪光灯/手电筒**（对照 Android `FLASH_MODE`/`TORCH_MODE`）：

- 手电筒：`torchMode`（off/on/auto）+ `torchLevel`（0~1 亮度，iOS 部分机型支持多级）；是**设备属性**，与会话无关（不搭会话也能开灯）；
- 拍照闪光：`AVCapturePhotoSettings.flashMode`（off/on/auto），由照片管线决策（auto 的判定算法不可见，对应 Android `AE_MODE_ON_AUTO_FLASH` 交给 HAL）；
- 外部闪光同步：`AVCapturePhotoOutput.externalFlash?`——iOS 无 Android 的 `STROBE` 热靴协议，仅有"检测外部闪光可用"级别的支持。

**变焦**（对照 Android `CONTROL_ZOOM_RATIO`，iOS 17 也加入了 `zoomFactor` 每帧语义统一，此处讲设备属性路线）：

| 接口 | 说明 |
|---|---|
| `videoZoomFactor` | 当前变焦倍率，1.0 = 该设备的全视场（广角基准）；`ramp(toVideoZoomFactor:withRate:)` 平滑变焦 |
| `activeFormat.videoMaxZoomFactor` | 数字变焦上限（混合变焦上限机型上另由虚拟设备行为决定） |
| `displayVideoZoomUpFactor` | 预览显示口径的倍率（UI 显示用） |
| `virtualDeviceSwitchOverVideoZoomFactors` | 虚拟设备镜头切换的倍率锚点（对应 Android `SCALER_AVAILABLE_ZOOM_RATIO` 中跨镜头的跳变点） |
| `centerStageControlMode` / Center Stage 相关（iOS 16+） | 中心舞台（人像居中裁切跟随）开关 |

虚拟设备的变焦行为是 Apple 特色：`builtInDualWideCamera` 等虚拟设备上改 `videoZoomFactor`，系统在镜头切换锚点处自动完成**跨镜头无缝切换**（含曝光/色彩匹配），App 无感——Android 的 `CONTROL_ZOOM_RATIO` 由 OEM 实现类似行为但无系统保证。写拍照 App 时应**默认使用虚拟设备**而不是手动拼接多路镜头。

## 3.6 系统压力与散热（iOS 特色控制面）

> 来源（A 层）：[AVCaptureDevice.SystemPressureState](https://developer.apple.com/documentation/avfoundation/avcapturedevice/systempressurestate)。

iOS 提供系统压力状态查询（`systemPressureState`，KVO 可观察），`level` 从 nominal 到 critical、`factors` 含 systemTemperature 等——相机长时间 4K 录制降帧/中断前，系统会先给出压力信号。Android 无统一对应 API（热管理在各自 HAL 内部）。做长录/直播类 App 应把压力状态接进码率/分辨率降级策略。
