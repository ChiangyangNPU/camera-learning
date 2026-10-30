# 第 6 章 Kotlin 与协程

> 材料分层：A = Kotlin 官方文档（kotlinlang.org）与官方协程指南（developer.android.com/kotlin/coroutines），B = 通行工程知识。↔ 通用题：Kotlin 板块、协程板块。

## 6.1 语言要点速查

| 特性 | 一句话要点 | 高频坑 |
|---|---|---|
| 空安全 | `?.`/`?:`/`!!`；平台类型 `T!` 来自 Java | `!!` 滥用；Java 互操作判空 |
| lateinit vs by lazy | lateinit var（var、非空、运行期检查）；lazy（val、首次访问初始化、默认 SYNC 加锁） | lateinit 前访问抛 UninitializedPropertyAccessException |
| data class | 自动 equals/hashCode/copy/componentN | **不会**用 getter 重写（属性在构造器外则不参与） |
| sealed vs enum | sealed class 子类可带状态多实例；enum 固定单例 | when 穷尽性检查 |
| object/companion | 单例 / 静态等价物 | 静态上下文持 Activity 引用（泄漏） |
| inline | 函数体复制到调用点，lambda 可 return（非局部返回） | crossinline 禁止非局部返回；noinline 保留对象 |
| reified | 内联后保留泛型实参（`filterIsInstance<T>()`） | 只能用于 inline 函数 |
| 型变 | `out T` 生产者（协变）、`in T` 消费者（逆变）、`*` 星投影 | Java `? extends/super` 对应 |
| 委托 | `by` 接口委托、`Delegates.observable` | 属性委托对象生命周期 |

↔ 通用题 Kotlin 板块前 8 题。

## 6.2 协程本质：挂起 = 可恢复的回调

`suspend` 函数经编译器变换（CPS，续体传递风格）：每个挂起点之后的部分被封装成**状态机**，挂在 `Continuation` 上；挂起时**释放线程**（不阻塞），就绪后由调度器在任意线程恢复——所以一个线程可承载成千上万协程（A 层[官方协程指南](https://developer.android.com/kotlin/coroutines)）。

> 面试一句话：**"协程是用状态机把'回调地狱'折叠回顺序写法的轻量并发单元，挂起不等于暂停线程。"**

## 6.3 结构化并发

| 概念 | 规则 |
|---|---|
| Job | 协程句柄；生命周期 Active→Cancelling→Cancelled/Completed |
| 父子传播 | 父取消 → 所有子取消；**任一子异常（非 Cancellation）→ 取消父与兄弟** |
| SupervisorJob | 子互不连坐（UI 场景常用） |
| coroutineScope | 等全部子完成（出错全炸）；supervisorScope 子互不影响 |

作用域选择（A 层推荐）：

| 作用域 | 生命周期 | 用途 |
|---|---|---|
| viewModelScope | ViewModel 清除时取消 | UI 数据加载 |
| lifecycleScope + repeatOnLifecycle | 页面可见性 | Flow 采集 |
| 自建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | 手动管理 | 全局任务（明确持有者） |

标准 MVVM 片段（B 层模板）：

```kotlin
class MyViewModel(repo: Repo) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loading = true)
        runCatching { repo.fetch() }
            .onSuccess { _uiState.value = UiState(items = it) }
            .onFailure { if (it !is CancellationException) _uiState.value = UiState(error = it.message) }
    }
}
// View 侧：
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        vm.uiState.collect { render(it) }
    }
}
```

## 6.4 调度器

| 调度器 | 线程池 | 用途 |
|---|---|---|
| Dispatchers.Main / Main.immediate | 主线程（immediate 不再 post 一次） | UI 更新 |
| Dispatchers.IO | 弹性池（默认 ≤64） | 阻塞 IO |
| Dispatchers.Default | ≈CPU 核数 | CPU 密集 |
| Dispatchers.Unconfined | 不固定 | 少用（恢复点不可控） |

`withContext` 切换有复用优化（同池切换廉价）；**不要在 Main 里做磁盘 IO**（StrictMode 违规 + 卡顿）。

## 6.5 异常处理

- `CoroutineExceptionHandler` 只装在**根协程**才生效；子协程异常会先取消父再冒泡；
- **`CancellationException` 必须放行**：`runCatching { suspendFn() }` 会把取消当普通失败吞掉，导致"取消失效"——捕获后判断 `currentCoroutineContext().ensureActive()` 或 `if (e is CancellationException) throw e`（高频坑，B 层）；
- 取消是**协作式**：循环里 `ensureActive()`/`isActive`；`yield()` 给同池协程让路。

## 6.6 Flow

- 冷流：collect 才执行，每次采集独立跑一遍；
- 常用操作符：`map/filter`、`debounce`（搜索防抖）、`flatMapLatest`（取最新请求）、`combine`（多流合状态）、`conflate`（合并堆积值）、`buffer`（生产消费解耦）、`collectLatest`（新值到取消旧处理）；
- **flowOn 决定上游线程**（其上游切到指定调度器，下游不变）；
- Channel/callbackFlow 桥接回调 API（模板见 6.8）。

## 6.7 StateFlow vs SharedFlow（高频对比）

| | StateFlow | SharedFlow |
|---|---|---|
| 初始值 | 必须 | 无 |
| 新订阅者 | 立即收到当前值（粘性） | 按 replay 配置 |
| 去重 | 值相等不发射 | 原样发射 |
| 用途 | UI 状态 | 事件、广播 |

UI 状态用 StateFlow；Toast/导航类一次性事件用 replay=0 的 SharedFlow 或状态建模（事件带 id/id 去重）。

## 6.8 与回调互操作

```kotlin
fun cameraEvents(): Flow<CameraEvent> = callbackFlow {
    val listener = CameraListener { event -> trySend(event) }   // 非挂起侧用 trySend
    register(listener)
    awaitClose { unregister(listener) }                          // 取消时清理（必须）
}
```

一次性回调用 `suspendCancellableCoroutine { cont -> ...; cont.resume(v) }`（配 `invokeOnCancellation` 清理）。↔ 通用题协程板块全部。
