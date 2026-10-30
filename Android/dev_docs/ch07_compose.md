# 第 7 章 Compose

> 材料分层：A = 官方 Compose 文档（developer.android.com/jetpack/compose），B = 通行工程知识。↔ 通用题：Compose 板块。

## 7.1 声明式模型与重组

- 核心：**UI = f(state)**；状态变化触发**重组**（重新执行受影响的可组合函数）；
- 智能重组：只重执行**输入变化**的作用域（最近的可重启函数）；参数全部 **stable 且 equals 相等** → 跳过（skippable）；
- 稳定性规则（B 层）：基本类型/String/data class（全 val）默认稳定；`List/Map`（接口类型）**不稳定** → 用 `kotlinx.collections.immutable` 的 ImmutableList 或 `@Immutable` 注解声明；不稳定参数每次都重组，是性能杀手第一名。

## 7.2 状态管理

| API | 用途 | 关键差异 |
|---|---|---|
| `remember { mutableStateOf(x) }` | 组合内记忆 | 重建（旋转）丢失 |
| `rememberSaveable` | 存入 SavedState | 可跨重建；自定义类型配 `Saver` |
| 状态提升（hoisting） | `XxxStateless(value, onValueChange)` | 调用者持有状态，可复用可测试 |

状态提升模板（A 层推荐模式）：

```kotlin
@Composable
fun CounterScreen() {
    var count by rememberSaveable { mutableStateOf(0) }
    CounterDisplay(count = count, onInc = { count++ })   // 无状态子组件
}
```

单一数据源：同一状态只有一个所有者（通常 ViewModel 的 StateFlow → `collectAsStateWithLifecycle`）。

## 7.3 副作用 API 家族（面试高频表）

| API | 语义 | 典型用途 |
|---|---|---|
| `LaunchedEffect(key)` | 进入组合/换 key 时启动协程，离开取消 | 采集 Flow、一次性拉取 |
| `DisposableEffect(key)` | 需要 onDispose 清理 | 注册广播/监听器 |
| `SideEffect` | **每次成功重组后**同步执行 | 把状态回写给非 Compose 对象 |
| `rememberUpdatedState` | 长生命周期效果里拿到最新值 | LaunchedEffect 中防闭包旧值 |
| `derivedStateOf` | 只在派生结果变化时重组 | 高频滚动里判断"是否显示回顶部" |
| `snapshotFlow` | 状态 → Flow | 组合内状态转冷流 |
| `rememberCoroutineScope` | 拿组合内作用域（onOk 回调里启动） | 触发导航/滚动等命令 |

坑：key 不稳定 → 副作用反复重启；LaunchedEffect 里读到的 lambda 捕获旧值 → 配 rememberUpdatedState。

## 7.4 渲染三阶段与性能

三阶段（A 层）：**Composition**（决定显示什么）→ **Layout**（测量摆放，1~多次）→ **Drawing**。优化手法：

1. **延迟读取**：读状态的代码放进 lambda（如 `Modifier.offset { x }`、`graphicsLayer { alpha = s }`），滚动/动画时只走 Layout/Draw 不触发重组；
2. 稳定参数 + key（`LazyColumn(items = ..., key = { it.id })`）；
3. 避免在重组路径做重计算（`derivedStateOf`/提前到 ViewModel）；
4. 工具：Recompose Highlighter、Composition Tracing（Perfetto）、Layout Inspector 重组计数；
5. Baseline Profile 对首屏收益同样适用（↔ 性能文档 ch3）。

## 7.5 Modifier 链的顺序语义

Modifier 按链序从外到内生效：`Modifier.padding(8.dp).background(...).clickable()` 与调换顺序的命中区域/绘制范围不同——"先 size 后 padding"和"先 padding 后 size"是两个布局（高频坑，B 层）。底层 Modifier.Node 引擎（1.7+）优化了链式查找与更新。

## 7.6 与 View 互操作

| 方向 | API | 相机场景 |
|---|---|---|
| Compose 用 View | `AndroidView(factory = { PreviewView(it) }, update = {...})` | Compose 界面嵌 PreviewView（↔ CameraX） |
| XML 用 Compose | `ComposeView` + `setViewCompositionStrategy(DISPOSE_ON_DETACHED_FROM_WINDOW)` | 老页面渐进迁移 |

## 7.7 页面与测试

- 导航：Navigation Compose（类型安全路由 ↔ 第 5.8 节）；脚手架 Scaffold + SnackbarHostState；
- 测试：`createComposeRule()` + `onNodeWithText(...).performClick().assertIsDisplayed()`；语义树（Semantics）是 Compose 测试与无障碍的共同基础（A 层）；
- 生态：Compose 已是官方默认 UI 方案，随 BOM 发布（版本以 [BOM 页](https://developer.android.com/develop/ui/compose/bom/bom-mapping) 为准）；Material 3 为默认主题。

↔ 通用题：Compose 板块全部。
