# 第 1 章 组件与生命周期

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。材料分层：A = 官方指南（developer.android.com/guide/components），B = 通行工程知识。↔ 通用题：组件板块。

## 1.1 四大组件总览

| 组件 | 角色 | 入口/宿主 | 生命周期 owner | 跨进程 |
|---|---|---|---|---|
| Activity | 用户交互界面 | Intent / Launcher | 自身 | 可被外部启动（exported） |
| Service | 后台长任务 | startService / bindService | 自身 | 支持（AIDL 绑定） |
| BroadcastReceiver | 系统级事件分发 | Intent 发送 | 收到广播期间 | 支持（全局/应用内） |
| ContentProvider | 跨进程数据共享 | ContentResolver 访问 | 无（按需创建） | 核心能力 |

共同约束（A 层）：组件都必须在 manifest 注册（动态广播除外）；均在主线程回调，回调内做耗时操作是 ANR 高发原因。

## 1.2 Activity 生命周期

七个回调（A 层，[官方指南](https://developer.android.com/guide/components/activities/activity-lifecycle)）：

| 回调 | 含义 | 之后通常做 |
|---|---|---|
| onCreate | 创建 | setContentView、findViewById/绑定、恢复 SavedState |
| onStart | 可见 | 注册接收器（onStop 成对） |
| onResume | 前台可交互 | 启动相机/传感器等独占资源 |
| onPause | 失去焦点 | 轻量收尾（必须快，影响下一个界面显示） |
| onStop | 不可见 | 释放资源、注销接收器 |
| onRestart | 重新可见前 | — |
| onDestroy | 销毁 | 最终清理 |

**状态保存**：`onSaveInstanceState(Bundle)` 在 Activity 进入后台（stop 前）被调用，进程被杀后重建时经 `onCreate(savedInstanceState)` / `onRestoreInstanceState` 恢复。坑位（B 层）：

- Bundle 走 Binder 事务共享缓冲（约 1MB 上限），塞大 Bitmap 会抛 `TransactionTooLargeException`——大数据应持久化，Bundle 只存轻量状态；Android 13+ 推荐 `SavedStateHandle`（ViewModel）承载；
- **横竖屏重建是"双份调用"**：旧实例 `onPause→onStop→onDestroy` 与新实例 `onCreate→onStart→onResume` 交错执行，旧实例的 onDestroy 晚于新实例的 onCreate——日志排查时不要误判顺序 bug。

**配置变更的三种策略**（B 层，按推荐排序）：

1. ViewModel + SavedStateHandle 承载数据 + UI 状态自动恢复（现代默认）；
2. `android:configChanges` 接管（仅限确有理由的场景，如相机预览不想重建 Surface——本仓库 Camera2Demo 即如此处理旋转）；
3. 放任重建 + 完整保存/恢复状态（官方默认行为，最可靠）。

**launchMode 与任务栈**：

| 模式 | 行为 | 典型场景 |
|---|---|---|
| standard | 每次新建实例 | 普通页面 |
| singleTop | 栈顶复用（走 onNewIntent） | 搜索页防连点 |
| singleTask | 栈内复用、清其上 Activity | 应用主页 |
| singleInstance | 独占任务栈 | 系统级入口（少见） |

常用 flags：`NEW_TASK`（service/广播中启动 Activity 必需）、`CLEAR_TOP`（清上方）、`SINGLE_TOP`。↔ 通用题"launchMode 区别"。

## 1.3 Fragment

- 生命周期比 Activity 多 `onAttach/onCreateView/onViewCreated/onDestroyView`；**View 销毁（onDestroyView）与 Fragment 实例销毁（onDestroy）分离**是泄漏高发点：View 绑定的协程/观察者必须在 onDestroyView 取消（B 层）；
- 管理：FragmentManager + BackStack；事务写法 `supportFragmentManager.commit { replace... addToBackStack... }`；
- 与 ViewPager2 搭配用 `FragmentStateAdapter`，构造传 `BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT`——可见性以 onResume 为准（老的 `setUserVisibleHint` 已废弃）；
- 结果通信用 **Fragment Result API**（`setFragmentResult`/`setFragmentResultListener`），替代被废弃的 `onActivityResult` 转发与接口回调（A 层）；
- `viewLifecycleOwner` vs `this`：观察 LiveData/Flow 时用前者，防止重复订阅与 View 泄漏。↔ 通用题"Fragment 生命周期"。

## 1.4 Service

两种形态（A 层，[Services 指南](https://developer.android.com/guide/components/services)）：

| 形态 | 启动 | 生命周期 | 典型 |
|---|---|---|---|
| Started | `startService()` | onCreate→onStartCommand→（stopSelf/stopService）→onDestroy | 音乐播放 |
| Bound | `bindService()` | onCreate→onBind→（onUnbind→onRebind…）→onDestroy | 与 UI 交互的长任务 |

前台服务（A 层）：

- `startForegroundService()` 启动后**必须在 5 秒内调用 `startForeground()`**，否则 ANR/崩溃；
- **Android 14 强制声明前台服务类型**：manifest 加 `android:foregroundServiceType` + 对应权限（如相机类型需 `FOREGROUND_SERVICE_CAMERA`）+ 运行时前提（相机权限已授予）——相机后台预览/录像即走此路径，↔ 本仓库性能文档第 5 章；
- Android 12 起后台启动 FGS 受严格限制（豁免清单外直接抛异常），替代方案 WorkManager expedited。

## 1.5 BroadcastReceiver

- 注册两式：manifest 静态注册（Android 8 起仅豁免清单内的隐式广播可用，绝大多数系统广播静态注册失效）；代码动态注册（必须配对注销）；
- **Android 13/14 强制指定导出性**：`ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`，静态注册用 `android:exported`——不给会直接崩；
- `goAsync()` 可把处理窗口延到约 10s（仍不应做重活）；有序广播 `sendOrderedBroadcast` 可截断；LocalBroadcastManager 已废弃，应用内事件用 SharedFlow/EventBus 替代（B 层）。

## 1.6 ContentProvider

- 唯一官方推荐的跨进程结构化数据访问入口；四方法（query/insert/update/delete）+ `getType`；URI 经 authority 定位，建议配套 Contract 类常量；
- `call()` 方法可自定义跨进程命令（MediaStore 等系统 API 即此用法）；
- Android 11 引入**包可见性（package visibility）**：跨应用解析 provider/包信息需在 manifest `<queries>` 声明，否则 getInstalledPackages/resolveActivity 等被过滤（↔ 第 13 章安全）；
- 共享单文件用 **FileProvider**（第 9.4 节模板）。

## 1.7 进程优先级与保活

进程优先级（oom_adj 粗粒度，A 层[Linux 中的进程](https://developer.android.com/guide/components/activities/process-lifecycle)）：

| 级别 | 状态 | 举例 |
|---|---|---|
| 前台进程 | 正在交互/FGS/onPause 前 | 当前 Activity |
| 可见进程 | 可见但非前台 | 覆盖透明 Activity |
| 服务进程 | 绑定到可见组件 | 音乐播放 |
| 缓存进程 | 无活动组件 | 退出后的应用 |

要点（B 层）：

- Android 11 起**缓存应用冻结（Cached Apps Freezer）**：缓存进程被 cgroup 冻结，不消费 CPU，但"保活心跳"也因此失效；
- **不要做进程保活**：Android 12+ 限制逐年收紧（显式广播限制、FGS 后台启动禁令、Phantom Process 杀戮），投入产出比为负；正确姿势是 WorkManager 延续任务 + 用户主动回到应用；
- 多进程写法 `android:process=":xxx"`（私有）或 `.xxx`（全局可共享）——注意每个进程都会独立初始化一次 Application（第 2.5 节陷阱）。

↔ 通用题：组件板块全部、性能板块"进程保活"。

