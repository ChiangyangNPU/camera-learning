# Android Camera 性能分析文档（指标 / 度量 / 分域优化 / 体系）

> 本文档是相机学习系列的性能总纲：**定义指标 → 建立度量 → 分域优化（启动/预览/拍照录像/内存/功耗热）→ 稳定性与线上体系**。内容不限于本仓库既有文档：以 Android 官方 developer docs（Vitals、JankStats/FrameMetrics、Thermal API、Baseline Profiles、Macrobenchmark、CameraX、前台服务等）、androidx 库、Perfetto 官方文档与公开工程实践为 A/B 层来源，结合本仓库的 Trace 分析、算法集成与源码链路文档组织成体系。
>
> 整理日期：2026-10-25。材料分层沿用仓库惯例：**A = 官方文档可验证；B = 公开通行实践；C = 行业经验（口径不一，仅供参考）**。各章末尾附来源链接。

## 与其它文档的关系

- 《Android_Camera_Trace分析文档》：本篇第 2 章的**工具细节**（Perfetto 配置/判读/SQL）都在那边，本篇只引用不重复；
- 《Android_Camera_算法集成文档》：GL 处理链/多帧融合的**实现方案**在那边（本篇第 4/5 章只讲预算与降级）；
- 《Android_Camera_性能分析面试题》（46 题）：本篇的速查版，问答形式，含板块九公开资料扩展专题；
- 《Android_Camera_源码链路文档》/《ISP_3A文档》：启动链路与 3A 行为的底层依据。

## 文档结构

| 章节 | 内容 | 主要来源层 |
|---|---|---|
| 第 1 章 指标体系与性能预算 | 体验/资源/稳定三层指标、每个指标的起止点定义、冷/温/热启动、行业 KPI 术语（TTFF/Shot-to-Shot）、预算制定方法 | B + A（Vitals） |
| 第 2 章 度量方法学 | 实验室/线上/程序化三环境分工、埋点设计、FrameMetrics 与 JankStats、统计口径与公平性、端到端延迟测量 | A + B |
| 第 3 章 启动性能 | 首帧成本分解、App 层九条优化清单、provider 冷启动对策、Baseline Profile、两段式会话、CameraDeviceSetup 预检 | A + B |
| 第 4 章 预览流畅性与延迟 | 帧预算模型（30/60/120fps）、掉帧四分类归因、SurfaceView 硬件叠层、GL 链预算与降级、高帧率约束、capture-to-display | A（CameraX/Preview）+ B |
| 第 5 章 拍照与录像性能 | 快门/出图延迟五段拆分、precapture 取舍、ZSL、连拍、录像专项（起录/音画/码控/前台服务与 wake lock） | A + B |
| 第 6 章 内存与带宽 | 像素缓冲估算模型、maxImages 策略、Image close 铁律、相机特有泄漏检测、多路流带宽评估、onTrimMemory/低内存降档 | A + B |
| 第 7 章 功耗与热 | 热源构成、Thermal API（状态监听 + headroom 预测式降级）、降级矩阵与滞回、功耗工具链（Battery Historian/power rails/Monsoon）、热稳态基线 | A + B |
| 第 8 章 稳定性与线上体系 | 相机稳定性场景、Vitals 外部红线、线上监控设计、CI 门禁与基线管理、事故复盘模板、成熟度自检 | A + B |

## 建议阅读路径

1. 先读第 1 章建立指标语言（全团队对齐口径的起点）；
2. 按问题域跳读第 3~7 章（每章独立成立，含优化清单与案例）；
3. 搭体系时读第 2 章与第 8 章（度量环境分工 + 线上/CI/复盘闭环）；
4. 需要动手采集时转《Trace分析文档》，需要实现算法链路时转《算法集成文档》。

---

