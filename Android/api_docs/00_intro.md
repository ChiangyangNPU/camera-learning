# Android Camera 接口速查学习文档（应用层 + HAL 层）

> 本文档是《Android Camera 框架学习文档》的姊妹篇：前一份按 AOSP 官方文档讲**框架与机制**（[Android_Camera_学习文档.md](../Android_Camera_学习文档.md)），本份按**接口层**整理——从应用层 API（Camera2 / CameraX / Camera1 / NDK）到 HAL 层接口（camera3.h / AIDL HAL / 元数据），每节均附官方来源链接。
>
> 整理日期：2026-09-05。接口说明以官方参考文档与 AOSP 源码（main 分支）为准，方法做了精选摘要而非全量罗列。

## 文档结构

| 章节 | 内容 | 接口层次 |
|---|---|---|
| 第 1 章 Camera2 API | 包架构、CameraManager/CameraDevice/CameraCaptureSession 等核心类方法表、三类元数据 Key、典型调用流程 | 应用层（Java/Kotlin） |
| 第 2 章 CameraX | Use Case 模型、Preview/ImageCapture/ImageAnalysis/VideoCapture、ProcessCameraProvider、典型流程 | 应用层（Jetpack 封装） |
| 第 3 章 Camera1 与 NDK | 旧版 Camera 状态机与方法表、Parameters、回调体系、NDK libcamera2 函数分组、三代 API 对比 | 应用层（旧版 + NDK） |
| 第 4 章 HAL 层接口 | camera_common.h 模块接口、camera3.h 全部 ops 与结构体、AIDL HAL（Provider/Device/Session/Callback）、C↔AIDL 对照表 | HAL 层（C/HIDL/AIDL） |
| 第 5 章 元数据体系 | camera_metadata C API、tag 命名空间全表、metadata_properties 统计、vendor tag 机制、查阅指南 | 框架↔HAL 数据协议 |

## 建议使用方式

- **写应用**：查第 1、2 章的方法表与流程；需要更细参数时循着每节"来源"链接进官方参考页。
- **调 HAL / 看框架源码**：查第 4 章接口表与第 5 章元数据 API；第 4 章的 C↔AIDL 对照表适合从旧资料迁移时查阅。
- **跨层理解**：第 1 章的 Key（应用层）与第 5 章的 tag（HAL 层）是同一套元数据体系的两侧映射，对照阅读最有收获。

---
