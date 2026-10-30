# 第 13 章 安全与权限

> 材料分层：A = 官方权限/安全文档，B = 通行工程知识。↔ 通用题：安全板块。

## 13.1 权限模型

四级（A 层）：

| 级别 | 授予方式 | 例子 |
|---|---|---|
| normal | 安装即授予 | 网络、振动 |
| **dangerous** | 运行时请求 | 相机、位置、麦克风、存储 |
| signature | 同签名才授予 | 系统级协作 |
| 特殊（special） | 设置页手动开 | 悬浮窗、精确闹钟、所有文件 |

运行时请求流程（现代写法）：

```kotlin
val launcher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) openCamera() else showWhyWeNeedIt()
}
launcher.launch(Manifest.permission.CAMERA)
```

**shouldShowRequestPermissionRationale 三态**（高频）：

| 返回 | 含义 | 处理 |
|---|---|---|
| true | 拒过一次（未永久拒） | 解释价值后再请求 |
| false | 首次请求，或已永久拒绝 | 首次直接请求；永久拒后引导设置页 |
| （区分） | 首次 vs 永久拒需自记标记 | 用 SharedPreferences 记"请求过" |

## 13.2 特殊权限

- `SYSTEM_ALERT_WINDOW`（悬浮窗）/ `WRITE_SETTINGS`：intent 跳设置页授权；
- 精确闹钟：Android 12 `SCHEDULE_EXACT_ALARM`，14 起默认拒绝需用户在设置授（`USE_EXACT_ALARM` 限闹钟类应用）；
- `MANAGE_EXTERNAL_STORAGE`（所有文件）：Play 白名单审查，能不用就不用；
- 后台位置（Android 10+）：**必须分步**——先请求使用中，再引导"始终允许"（Play 政策要求渐进披露）。

## 13.3 隐私行为演进速览（Android 10 → 17）

| 版本 | 关键隐私/行为变化 |
|---|---|
| 10 | Scoped Storage 起步、后台位置独立权限 |
| 11 | 包可见性（<queries>）、权限自动重置（闲置应用）、Scoped Storage 强制 |
| 12 | 蓝牙拆分权限（SCAN/CONNECT）、PendingIntent 必须显式可变性、麦克风/相机使用指示器与总开关 |
| 13 | **通知运行时权限**、Photo Picker、附近设备权限、按应用语言 |
| 14 | **前台服务类型强制**、部分照片访问（READ_MEDIA_VISUAL_USER_SELECTED） |
| 15 | 私密空间、16KB 页对齐（native so）、屏幕录制检测 |
| 16 | 预测性返回完善、照片访问进一步收紧（以官方 behavior changes 页为准） |
| 17 | 大屏适配限制 opt-out 移除（targetSdk 37 强制自适应）、后台音频行为变更、MessageQueue 无锁实现（↔ 第 14 章） |

## 13.4 数据存储安全

- **Android Keystore**：密钥不出安全硬件（TEE/StrongBox），用于对称（AES-GCM）/非对称加解密——本地敏感数据加密的基石（A 层）；
- 数据库加密：SQLCipher（Room 可接入）；注意 **EncryptedSharedPreferences / EncryptedFile（Jetpack Security-Crypto）已废弃**，官方建议直接用 Tink 或 Keystore 自管（B 层，2026 校准）；
- 日志红线：release 关闭 verbose、禁止落盘用户敏感明文。

## 13.5 传输安全

TLS 默认 + 明文禁用（networkSecurityConfig，↔ 8.6）；证书固定权衡（↔ 8.5）；禁止信任用户证书进 release（debug-overrides 仅调试）。

## 13.6 组件与 WebView 安全清单

- **exported 显式声明**（Android 12 起含 intent-filter 的组件必须写）；对外组件做调用方校验（↔ 2.3）；
- Intent 重定向漏洞：把外部 intent 直接 startActivity 前要校验目标（防权限绕过）；
- PendingIntent：始终显式包名/组件 + FLAG_IMMUTABLE（默认推荐）；
- WebView：`allowFileAccess=false`、JS 桥白名单方法 + 输入校验、`setSafeBrowsingEnabled`、postMessage 通道替代 addJavascriptInterface 传对象（B 层）。

## 13.7 完整性与反作弊

- **Play Integrity API**（A 层）：设备/应用/账号三重完整性判定，替代 SafetyNet；
- root/模拟器检测属对抗领域（C 层）：分层降级策略（可观察但不动手 → 功能限制 → 拒绝服务），误杀率优先评估。

## 13.8 安全编码 Checklist

权限最小化 ✓ exported 最小化 ✓ 输入校验（含 intent extra）✓ 敏感数据不进日志/不进明文存储 ✓ 网络全 TLS ✓ WebView 收紧 ✓ 三方 SDK 版本跟进（CVE）✓ 上架前 lint 安全项清零。

↔ 通用题：安全板块全部。
