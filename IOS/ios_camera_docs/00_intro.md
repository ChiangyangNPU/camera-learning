# iOS Camera 框架学习文档

> 本文档是相机学习系列的第五份（Android 系列四份之后），面向已熟悉 Android 相机框架、希望系统掌握 **iOS 相机体系**的工程师。内容依据 Apple 官方文档（developer.apple.com）、WWDC 视频与 Apple 官方样例整理，逐节附来源链接。
>
> 官方文档入口：<https://developer.apple.com/documentation/avfoundation>
> 整理日期：2026-09-19（Apple 文档持续更新，API 可用版本以官方页面为准）

## 与 Android 系列的根本差异（先读这一段）

iOS 全栈闭源：没有 AOSP 源码可逐行核对、没有 HAL 边界可考察、没有 CTS 可查。因此本系列 iOS 文档采用与《Android_Camera_ISP_3A文档》相同的**材料可信度分层**（A 层 = Apple 官方资料；B 层 = 公开通行知识；C 层 = 社区研究须注出处），且不设《源码链路》对应文档。Android 文档讲"HAL 协议长什么样"，iOS 文档讲"Apple 把哪些能力 API 化了、行为由谁保证"。

## 文档结构

| 章节 | 内容 | 对应 Android 系列 |
|---|---|---|
| 第 1 章 架构概览与版本演进 | iOS 相机软件栈分层（与 Android 三进程模型对照）、版本时间线（iOS 8→26）、API 体系总览、高层封装生态（无 CameraX 等价物）与对照阅读地图 | 学习文档第 1、4 章 |
| 第 2 章 AVFoundation 捕获核心 | 会话—输入—输出模型、配置原子性、设备发现、中断恢复、权限隐私 | 学习文档第 1~2 章 |
| 第 3 章 设备控制：3A 接口面 | 对焦/曝光/白平衡的模式与锁定、闪光灯、变焦、系统压力（对照 Android CONTROL_* 元数据） | 学习文档第 2 章 + ISP/3A 文档第 3 章接口部分 |
| 第 4 章 拍照与录像管线 | Photo settings 对象模型、格式体系（HEIC/DNG/ProRAW）、高分辨率与延迟处理、视频双路线、ProRes/Log/Cinematic | 学习文档第 2、5~7 章 |
| 第 5 章 多摄、深度与硬件特性 | 虚拟设备、MultiCamSession、AVDepthData 三源、微距/Center Stage/Capture Controls、外接摄像头与 Continuity Camera | 学习文档第 5~7 章 |
| 第 6 章 生态、扩展与合规 | 为什么没有第三方相机 HAL、ScreenCaptureKit、系统相机边界、测试与合规 | 学习文档第 1 章 + 版本控制章 |
| 第 7 章 照片库与 HDR 交付闭环 | PhotoKit 对象模型、保存/读取、addOnly/limited 授权、PHPicker、Adaptive HDR / Gain Map（对照 Android Ultra HDR） | 学习文档第 5 章（Ultra HDR）+ MediaStore 体系 |
| 第 8 章 音频会话与录制闭环 | AVAudioSession 类别/模式/路由/中断，与相机会话协作检查清单 | AudioFocus / AudioRecord 体系对照 |
| 第 9 章 性能、导出与新形态交付 | sample buffer 生命周期、热降级矩阵、后台边界、ExportSession/Composition/缩略图、空间视频与 Immersive Media | 学习文档第 3 章（性能）+ 编辑生态 |

## 建议学习路径

1. **建立差异认知**：第 1 章，理解"没有 HAL、没有逐帧元数据、计算摄影默认在场"三个结构差异；
2. **掌握会话模型**：第 2 章的对象图是全部 iOS 相机代码的骨架，对照 Android 的 session/stream 记忆成本最低；
3. **3A 对照着学**：第 3 章每节都有 Android 元数据对照表，用已知的 CONTROL_* 体系映射；
4. **管线按需深入**：第 4 章拍照/录像两条路线，写代码前精读；第 5~6 章按需选读；
5. **补齐闭环**：第 7 章照片库与 HDR 交付、第 8 章音频会话是拍照/录像 App 落地的必经路径；第 9 章性能与导出按需；
6. **横向配合**：《iOS_Camera_接口文档》速查方法签名，《iOS_Camera_ISP_图像管线文档》解释行为背后的图像处理。

---

