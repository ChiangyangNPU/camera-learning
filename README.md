# camera-study 📷

个人相机开发学习仓库，目前以 **Android Camera 框架与接口**为主，后续计划补充 iOS 相机相关内容。

## 目录结构

```
camera-study/
├── Android/                        # Android 相机学习资料
│   ├── Android_Camera_学习文档.md   # ★ 主文档：AOSP Camera 框架与机制（7 章）
│   ├── Android_Camera_接口文档.md   # ★ 主文档：应用层 + HAL 层接口速查（5 章）
│   ├── aosp_camera_docs/           # 学习文档的分章源文件（chapter01~07）
│   ├── api_docs/                   # 接口文档的分章源文件（ch1~ch5）
│   └── images/                     # 文档配图（44 张，已本地化，可离线阅读）
└── IOS/                            # iOS 相机学习（规划中）
```

## 两份主文档

| 文档 | 内容 | 适用场景 |
|---|---|---|
| [Android_Camera_学习文档.md](Android/Android_Camera_学习文档.md) | 依据 AOSP 官方文档"摄像头"板块全部 31 个页面整理：概览与架构、核心概念（3A/元数据/流配置）、性能优化、版本控制、18 个相机功能专题 | 系统学习 Camera 框架机制 |
| [Android_Camera_接口文档.md](Android/Android_Camera_接口文档.md) | Camera2 / CameraX / Camera1 / NDK 应用层接口速查 + HAL 层（camera3.h、AIDL HAL、camera_metadata）接口与元数据体系 | 写代码时查方法、查接口 |

建议阅读顺序：先读学习文档第 1 章建立整体图景 → 第 2 章核心机制 → 接口文档第 1/4 章对照应用层与 HAL 层的方法表 → 其余章节按需查阅。

两份文档均特性：

- 每节附**官方来源链接**，可溯源到 AOSP 文档 / API 参考 / AOSP 源码；
- 配图已下载到本地（`images/`），**整仓克隆后可完全离线阅读**；
- 关键表格（3A 状态机、版本对照、HAL ops、元数据统计）以 Markdown 表格保留。

## 资料来源与致谢

- [AOSP 官方文档 - 摄像头](https://source.android.google.cn/docs/core/camera?hl=zh-cn)（CC BY 4.0）
- [Android API 参考](https://developer.android.google.cn/reference)（Camera2 / CameraX / Camera1 / NDK）
- AOSP 源码：[hardware/interfaces/camera](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/)、[camera3.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h)、[system/media/camera](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/)

本文档为个人学习笔记，内容为对官方资料的整理与转述，如有侵权或谬误请联系更正。

## 更新日志

- 2026-09-05：首次整理，完成 Android 相机学习文档与接口文档，配图本地化。
