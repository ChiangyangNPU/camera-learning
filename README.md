# camera-learning 📷

个人相机开发学习仓库，目前以 **Android Camera 框架与接口**为主，后续计划补充 iOS 相机相关内容。

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
└── IOS/                              # iOS 相机学习（规划中）
```

## 四份主文档

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

## 资料来源与致谢

- [AOSP 官方文档 - 摄像头](https://source.android.google.cn/docs/core/camera?hl=zh-cn)（CC BY 4.0）
- [Android API 参考](https://developer.android.google.cn/reference)（Camera2 / CameraX / Camera1 / NDK）
- AOSP 源码（main 分支）：[frameworks/av/services/camera/libcameraservice](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/)、[hardware/interfaces/camera](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/)、[camera3.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h)、[system/media/camera](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/)

本文档为个人学习笔记，内容为对官方资料与开源代码的整理与转述，如有侵权或谬误请联系更正。

## 更新日志

- 2026-09-05：首次整理，完成 Android 相机学习文档与接口文档，配图本地化。
- 2026-09-06：新增《Android_Camera_源码链路文档》（framework/CameraService 源码链路 4 章，21 张 Mermaid 图，全部结论出自 AOSP main 分支源码并附行号）。
- 2026-09-06：新增《Android_Camera_ISP_3A文档》（sensor/ISP 管线、3A 算法与平台 HAL 4 章，24 张 Mermaid 图；含 libcamera 开源算法分析、Qualcomm 官方文档引用，内容按 A/B/C 可信度分层标注）。
