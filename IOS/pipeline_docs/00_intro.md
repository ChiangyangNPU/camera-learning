# Camera ISP 与图像管线学习文档（Apple 计算摄影 / 应用侧处理栈 / 3A 映射）

> 本文档是相机学习系列的第七份，与《Android_Camera_ISP_3A文档》同位对照：Android 版从 HAL 边界向下讲驱动/ISP/3A/平台实现；Apple 全栈闭源，本篇讲**可确认的 Apple 链路公开知识、系统级计算摄影体系、开发者自己的应用侧处理栈，以及 3A 控制面的两平台映射**。
>
> 官方文档入口：<https://developer.apple.com/documentation/avfoundation>、<https://developer.apple.com/documentation/coreimage>
> 整理日期：2026-09-19。全文按 A/B/C 三级标注材料可信度（A = Apple 官方公开资料；B = 公开通行知识；C = 社区研究须注出处）。

## 与 Android ISP/3A 文档的关系

- 算法原理共用：AE/AWB/AF 的算法原理与 libcamera 开源实现分析在《Android_Camera_ISP_3A文档》第 3 章，本篇不重复，只做**接口面与行为面映射**（第 4 章）；
- 材料面差异：Android 版能引用内核驱动与开源源码（A 层）；Apple 版 A 层只剩官方文档/WWDC 与标准格式（DNG），故各章先声明"能确认什么、不能确认什么"；
- 组织差异：Android 版按"传感器→ISP 管线→3A→平台 HAL"分层下潜；本篇按"链路边界→系统计算摄影→应用侧栈→3A 映射"收敛到开发者可作用的层面。

## 文档结构

| 章节 | 内容 | 材料层级 |
|---|---|---|
| 第 1 章 从 Sensor 到像素 | 传感器公开口径、ISP 闭源黑盒的可观测行为、DNG 通道 | A（规格/DNG）+ B/C |
| 第 2 章 Apple 计算摄影体系 | Smart HDR/深融合/Photonic Engine/夜间模式/语义渲染、ProRAW 与 Log 的色彩科学定位、可影响开关清单 | A（WWDC/官方）+ C（Halide 等实证） |
| 第 3 章 应用侧图像处理栈 | CVPixelBuffer/IOSurface 总线、Core Image、Metal、VideoToolbox、Vision、典型管线配方 | A 层为主 |
| 第 4 章 3A 接口映射与平台对比 | AE/AF/AWB 完整映射表、行为层差异、precapture 两种世界观、系列学习地图合并 | A + B/C |

## 建议阅读路径

- 先读《Android_Camera_ISP_3A文档》第 1~3 章建立管线与 3A 原理（本篇不重复原理）；
- 本篇顺序通读：第 1 章（认清 Apple 链路的可/不可观测边界）→ 第 2 章（计算摄影体系）→ 第 3 章（动手层）→ 第 4 章（两平台合流）；
- 配合《iOS_Camera_学习文档》第 3 章（3A API 机制）与《iOS_Camera_接口文档》第 5 章（元数据对照表）使用。

---

