# Camera ISP 与 3A 算法学习文档（sensor / ISP 管线 / 平台 HAL）

> 本文档是相机学习系列的第四份，也是越过 framework/HAService 边界向下的一层：**传感器成像原理、ISP 处理管线、3A 算法，以及高通/联发科平台 HAL 的架构**。
>
> 前三份文档（机制 / 接口 / 源码链路）都能以 AOSP 官方材料逐行核对；本篇所处领域**大量内容是厂商闭源的**，因此采用材料可信度分层体系，全文严格标注：
>
> - **A 层**：可抓取验证的官方资料——Linux 内核文档与 mainline 驱动源码、libcamera 开源算法实现、AOSP 元数据定义、Qualcomm 官方文档站（docs.qualcomm.com 的 RB5 相机文档组）、CodeLinaro 内核仓库；
> - **B 层**：公开通行知识/官方公开材料——行业通用的管线 stage 与算法原理、厂商公开博客与产品页；
> - **C 层**：社区研究——必须给出可访问来源且多源相互印证才采用。
>
> 阅读时请留意每节的层级标注：A 层内容可放心引用，B/C 层内容用于建立心智模型，具体实现细节以你手上的平台代码为准。

## 文档结构

| 章节 | 内容 | 材料层级 |
|---|---|---|
| 第 1 章 Sensor 成像基础 | CMOS 原理、Bayer/CFA、曝光模型与快门、PDAF 像素与数据通路、V4L2 subdev/ media-controller、QCOM CAMSS 驱动、ANDROID_SENSOR_* 元数据 | 以 A 层为主 |
| 第 2 章 ISP 管线与图像处理算法 | ISP 全景管线（BLS→LSC→去马赛克→降噪→AWB/CCM→Gamma→锐化→CSC）、统计模块与 ANDROID_STATISTICS_*、rkisp1 实例、libcamera IPA 框架、tuning 概念 | A 层骨架 + B 层原理 |
| 第 3 章 3A 算法深入 | AE/AWB/AF 的算法原理与 libcamera 开源实现逐函数分析（agc 两段式收敛、AWB 统计域还原、ipu3 AF 爬山）、Android 3A 控制接口回顾、厂商 3A 与开源差距 | A 层源码 + B 层原理 |
| 第 4 章 平台 HAL：高通与 MTK | CAMX/Chi-CDK 架构（CHI 层/Node 图/Topology XML/Spectra 硬件块/Chromatix）、MTK mtkcam 与 P1、两平台对比、平台 HAL 学习方法 | 4.2 以 Qualcomm 官方文档为 A 层，4.3 多为 C 层多源印证 |

## 与前三份文档的关系

- 前三份讲"**框架内**"：HAL 接口以上、CameraService 上下——本篇从 HAL 边界**向下**：`processCaptureRequest` 进到 vendor 之后，算法和硬件发生了什么。
- 衔接点：`ANDROID_SENSOR_*`/`ANDROID_STATISTICS_*`/`ANDROID_CONTROL_*` 元数据（接口文档第 5 章）在本篇各章反复出现，它们正是框架与 3A/ISP 之间的"协议"。
- 建议阅读顺序：第 1 章（成像基础）→ 第 2 章（管线全景）→ 第 3 章（算法）→ 第 4 章（平台）；有高通/MTK 设备调试经验的读者可直接跳第 4 章再回补前三章。

---
