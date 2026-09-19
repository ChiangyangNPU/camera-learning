# iOS Camera 接口速查文档

> 本文档是相机学习系列的第六份，与《Android_Camera_接口文档》同构：**写代码时查方法、查字段、查对应关系**。所有签名以 Swift 5 / iOS 17+ SDK 为准，逐章附 Apple 官方文档链接。
>
> 官方文档入口：<https://developer.apple.com/documentation/avfoundation>
> 整理日期：2026-09-19。iOS 无公开 HAL，故不设 Android 版第 4 章（HAL）的对应章，改为"元数据与色彩管理对照"（第 5 章）。

## 文档结构

| 章节 | 内容 | Android 版对应 |
|---|---|---|
| 第 1 章 核心捕获类接口 | Session/Device/Input/Connection/Format 方法与属性表 | 第 1 章 Camera2 核心类 |
| 第 2 章 照片捕获接口 | AVCapturePhotoOutput/PhotoSettings/Photo 全字段表、48MP 路径 | 第 1、5 章（拍照与元数据） |
| 第 3 章 视频捕获接口 | 文件输出与逐帧输出双路线、AVAssetWriter、ProRes/Log 参数 | 第 1、2 章（录制部分） |
| 第 4 章 高级能力接口 | MultiCam、深度、MetadataOutput、事件 API、Cinematic、外接 | 第 2 章（能力部分） |
| 第 5 章 元数据与色彩管理对照 | 3A 控制语义总表、输出流对照、颜色空间、迁移检查清单 | 第 5 章 camera_metadata |

## 使用建议

- 从 Android 迁移：先读第 5 章总表建立映射，再按需查第 1~4 章的签名；
- 新学 iOS：先通读《iOS_Camera_学习文档》第 2~4 章（机制），再回本册查表；
- 表中"Android 对应"列是**语义对照**（近似），行为差异以《学习文档》各章说明为准。

---

