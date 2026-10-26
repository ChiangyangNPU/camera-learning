# Android Camera Trace 分析文档（Perfetto / Systrace 基础 + 相机性能实战）

> 本文档是相机学习系列的补充学习文档：前四份主文档讲"机制/接口/源码/ISP"，面试题集讲"考察点"，本篇讲**性能问题怎么被定位出来**——以 Perfetto 为代表的 trace 工具基础，以及 Camera 启动、拍照延迟的 trace 分析方法与实战模式。
>
> 工具入口：<https://ui.perfetto.dev>　官方文档：<https://perfetto.dev/docs/>
> 整理日期：2026-10-18。材料按 A/B/C 分层标注（A = Perfetto/AOSP 官方文档；B = 通行工程知识；C = 社区经验），相机 HAL 内部行为以厂商打点为准。

## 文档结构

| 章节 | 内容 | 适合 |
|---|---|---|
| 第 1 章 Trace 基础 | 数据模型（track/slice/counter/flow）、线程状态、渲染与帧模型、FrameTimeline、相机链路的 trace 语义、消费端地图（SurfaceView/SurfaceTexture/ImageReader/编码 Surface） | 首次接触 trace |
| 第 2 章 抓取与工具链 | Perfetto config、命令行、category 选择、App 自定义打点、legacy atrace、抓取坑位 | 动手抓数据 |
| 第 3 章 Camera 启动分析 | 首帧定义、启动阶段地图、逐段判读、示例拆解、异常模式 | 优化首帧/冷启动 |
| 第 4 章 拍照延迟分析 | 延迟定义、precapture 收敛、pipeline depth、编码落盘、ImageReader 背压、问题速查 | 优化快门/出图 |
| 第 5 章 分析方法论 | 五步法、阻塞链追踪、归因分类、工具组合、误判清单、验收预算 | 形成分析习惯 |
| 第 6 章 Perfetto SQL | schema 速查与常用查询（配流/线程状态/打点/binder/jank/频率） | 批量统计与复核 |

## 与其它文档的关系

- 启动/拍照的机制与优化清单见《Android_Camera_APP_面试题》Q65（首帧启动）与 Q36/Q57（性能与拍照流程）；
- 配套题集：《Android_Camera_Trace面试题》（26 题，覆盖 trace 基础、抓取、启动/拍照分析、方法论与实战），用于自测与面试准备；
- 请求/结果的框架内部链路见《Android_Camera_源码链路文档》第 3~4 章（RequestThread、in-flight、结果回程）；queueBuffer 之后到屏幕的显示链路（BufferQueue/BLAST/SF/HWC）见其第 5 章；
- HAL/3A 的元数据语义见《Android_Camera_ISP_3A文档》第 2~3 章；
- 预览消费端的**选型对比**（SurfaceView/TextureView/GL 中转/PreviewView 模式）见《Android_Camera_性能分析文档》4.4；ImageReader 接口语义见《Android_Camera_接口文档》1.8；GL 中转的完整实现见《Android_Camera_算法集成文档》第 2 章（本篇只讲 trace 判读视角）；
- trace 里的"谁等谁"可与《Android_通用面试题》Handler/Binder/渲染相关题目互相印证。

## 建议阅读路径

1. 先读第 1 章建立"trace 语言"（slice/状态/帧模型）；
2. 按第 2 章配置抓一段自己 App 的启动 trace，对照第 3 章逐段看；
3. 拍照延迟问题按第 4 章的链路拆解；
4. 分析结论用第 5 章的五步法与归因表收口；
5. 需要批量统计时用第 6 章 SQL。

---

