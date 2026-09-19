# camera-learning 📷

个人相机开发学习仓库，覆盖 **Android 与 iOS 两大平台的相机框架**：Android 部分以 AOSP 官方文档与源码为基（可逐行溯源），iOS 部分以 Apple 官方文档/WWDC 为基（全栈闭源，采用可信度分层标注）。

## 目录结构

```
camera-learning/
├── Android/                          # Android 相机学习资料
│   ├── Android_Camera_学习文档.md     # ★ 主文档：AOSP Camera 框架与机制（7 章）
│   ├── Android_Camera_接口文档.md     # ★ 主文档：应用层 + HAL 层接口速查（5 章）
│   ├── Android_Camera_源码链路文档.md # ★ 主文档：framework/CameraService 源码实现（4 章）
│   ├── Android_Camera_ISP_3A文档.md  # ★ 主文档：sensor/ISP 管线 + 3A 算法 + 高通/MTK 平台 HAL（4 章）
│   ├── aosp_camera_docs/             # 学习文档的分章源文件（chapter01~07）
│   ├── api_docs/                     # 接口文档的分章源文件（ch1~ch5）
│   ├── src_docs/                     # 源码链路文档的分章源文件（ch1~ch4）
│   ├── isp_docs/                     # ISP/3A 文档的分章源文件（ch1~ch4）
│   └── images/                       # 文档配图（44 张，已本地化，可离线阅读）
└── IOS/                              # iOS 相机学习资料
    ├── iOS_Camera_学习文档.md         # ★ 主文档：AVFoundation 架构与机制（9 章）
    ├── iOS_Camera_接口文档.md         # ★ 主文档：AVFoundation 接口速查 + PhotoKit/导出 + 两平台对照（6 章）
    ├── iOS_Camera_ISP_图像管线文档.md # ★ 主文档：Apple 计算摄影 + 应用侧处理栈 + 3A 映射（4 章）
    ├── ios_camera_docs/              # 学习文档的分章源文件（chapter01~09）
    ├── api_docs/                     # 接口文档的分章源文件（ch1~ch6）
    └── pipeline_docs/                # 图像管线文档的分章源文件（ch1~ch4）
```

## Android 四份主文档

| 文档 | 内容 | 适用场景 |
|---|---|---|
| [Android_Camera_学习文档.md](Android/Android_Camera_学习文档.md) | 依据 AOSP 官方文档"摄像头"板块全部 31 个页面整理：概览与架构、核心概念（3A/元数据/流配置）、性能优化、版本控制、18 个相机功能专题 | 系统学习 Camera 框架机制 |
| [Android_Camera_接口文档.md](Android/Android_Camera_接口文档.md) | Camera2 / CameraX / Camera1 / NDK 应用层接口速查 + HAL 层（camera3.h、AIDL HAL、camera_metadata）接口与元数据体系 | 写代码时查方法、查接口 |
| [Android_Camera_源码链路文档.md](Android/Android_Camera_源码链路文档.md) | framework/CameraService 源码实现：三进程模型、openCamera 全链、会话与请求下发、结果回程与错误恢复（AOSP main 分支源码逐段核对，Mermaid 流程图/时序图） | 框架开发、面试准备、问题排查 |
| [Android_Camera_ISP_3A文档.md](Android/Android_Camera_ISP_3A文档.md) | Sensor 成像原理、ISP 处理管线与统计模块、3A 算法（含 libcamera 开源实现逐函数分析）、高通 CAMX/Chi-CDK 与 MTK mtkcam 平台 HAL 架构（材料按 A/B/C 可信度分层标注） | ISP/3A 入门、平台 HAL 调研、tuning 团队衔接 |

建议阅读顺序：先读学习文档第 1 章建立整体图景 → 第 2 章核心机制 → 接口文档第 1/4 章对照应用层与 HAL 层的方法表 → 源码链路文档打通三层调用链 → ISP/3A 文档下沉到硬件与算法 → 其余章节按需查阅。

三份机制/源码文档共同特性：

- 每节附**官方来源链接**，可溯源到 AOSP 文档 / API 参考 / AOSP 源码（源码链路文档精确到文件与行号）；
- 配图已下载到本地（`images/`），**整仓克隆后可完全离线阅读**；源码链路文档与 ISP/3A 文档的图全部为 Mermaid 语法，可在 GitHub/Gitee/Typora 直接渲染；
- 关键表格（3A 状态机、版本对照、HAL ops、元数据统计、AIDL 接口方法表）以 Markdown 表格保留。

ISP/3A 文档额外特性：厂商闭源内容（CAMX/mtkcam 内部）按 **A（可验证官方资料）/ B（公开通行知识）/ C（社区多源印证）** 三级可信度逐条标注，不可印证的细节一律不写；开源参考实现（libcamera 的 AE/AWB/AF、内核 rkisp1/CAMSS 驱动）均给出可复查的源码来源。

## iOS 三份主文档

| 文档 | 内容 | 适用场景 |
|---|---|---|
| [iOS_Camera_学习文档.md](IOS/iOS_Camera_学习文档.md) | iOS 相机软件栈分层（与 Android 三进程模型对照）、AVFoundation 会话—输入—输出模型、3A 接口面、拍照/录像管线、多摄与深度、版本演进（iOS 8→26），照片库与 HDR 交付（PhotoKit/Adaptive HDR）、音频会话、性能与导出，全程与 Android 系列对照 | 有 Android 背景系统学习 iOS 相机 |
| [iOS_Camera_接口文档.md](IOS/iOS_Camera_接口文档.md) | Session/Device/Photo/Video/高级能力接口字段与方法速查，PhotoKit/音频会话/导出编辑接口，第 5 章为两平台 3A 元数据与流语义总对照表 + 迁移检查清单 | 写代码时查方法、跨平台迁移 |
| [iOS_Camera_ISP_图像管线文档.md](IOS/iOS_Camera_ISP_图像管线文档.md) | Apple 传感器/ISP 公开口径与可观测边界、计算摄影体系（Smart HDR/Photonic Engine/ProRAW/Log）、应用侧处理栈（Core Image/Metal/VideoToolbox）、3A 控制面两平台完整映射 | 理解 Apple 成像行为、跨平台图像架构、3A 对齐 |

建议阅读顺序：学习文档第 1 章（三个结构差异：没有 HAL、没有逐帧元数据、计算摄影默认在场）→ 第 2 章会话模型 → 第 3 章 3A 对照 → 第 4 章拍照/录像管线 → 第 7/8 章照片库与音频闭环 → 接口文档第 5 章总对照表 → 图像管线文档按需深入。

iOS 系列文档特性：

- iOS 全栈闭源，**不设源码链路文档**；以 Apple 官方文档 / WWDC / AVCam 官方示例为 A 层来源，逐节附链接，延续 A/B/C 可信度分层；
- 章节组织与 Android 系列同位对照（学习/接口/ISP 三份 ↔ Android 四份），每个 3A 与能力主题均给出 Android 元数据/接口的映射表；
- 全部流程图/时序图为 Mermaid 语法；iOS 26（2025）与 WWDC26 的新 API（Cinematic 捕获、Capture Controls、ProRes RAW/Genlock、高分辨率拍照）已纳入版本演进与能力章节。

## 资料来源与致谢

- [AOSP 官方文档 - 摄像头](https://source.android.google.cn/docs/core/camera?hl=zh-cn)（CC BY 4.0）
- [Android API 参考](https://developer.android.google.cn/reference)（Camera2 / CameraX / Camera1 / NDK）
- [Apple 开发者文档](https://developer.apple.com/documentation/avfoundation)（AVFoundation / CoreImage / VideoToolbox / DriverKit）、[WWDC 视频](https://developer.apple.com/videos/)、[AVCam 官方示例](https://developer.apple.com/documentation/avfoundation/capture_setup/avcam_building_a_camera_app)
- AOSP 源码（main 分支）：[frameworks/av/services/camera/libcameraservice](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/)、[hardware/interfaces/camera](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/)、[camera3.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h)、[system/media/camera](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/)

本文档为个人学习笔记，内容为对官方资料与开源代码的整理与转述，如有侵权或谬误请联系更正。

## 更新日志

- 2026-09-05：首次整理，完成 Android 相机学习文档与接口文档，配图本地化。
- 2026-09-06：新增《Android_Camera_源码链路文档》（framework/CameraService 源码链路 4 章，21 张 Mermaid 图，全部结论出自 AOSP main 分支源码并附行号）。
- 2026-09-06：新增《Android_Camera_ISP_3A文档》（sensor/ISP 管线、3A 算法与平台 HAL 4 章，24 张 Mermaid 图；含 libcamera 开源算法分析、Qualcomm 官方文档引用，内容按 A/B/C 可信度分层标注）。
- 2026-09-19：ISP/3A 文档第 3 章扩充：新增闪光灯 3A 与 precapture 时序（3.2.7）、AWB Bayes 色温估计算法逐函数分析（3.3.5）、多摄 3A 同步元数据与变焦切换（3.5.5）；全部分章源文件加入与主文档的同步声明。
- 2026-09-19：新增 iOS 系列三份主文档（《iOS_Camera_学习文档》6 章、《iOS_Camera_接口文档》5 章、《iOS_Camera_ISP_图像管线文档》4 章）及对应分章源文件：以 Apple 官方文档/WWDC 为 A 层来源，延续 A/B/C 可信度分层，全程与 Android 系列对照（3A/元数据/能力映射表），不设源码链路文档（iOS 闭源）。
- 2026-09-19：iOS 系列补全闭环：学习文档扩至 9 章（新增第 7 章照片库与 HDR 交付/PhotoKit/Adaptive HDR Gain Map、第 8 章音频会话 AVAudioSession、第 9 章性能/导出/空间视频；第 1 章增补"无 CameraX 等价物"定位、第 5 章扩充 Continuity Camera）；接口文档扩至 6 章（新增第 6 章 PhotoKit/音频/导出接口速查）。
