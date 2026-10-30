# 第 9 章 数据存储

> 材料分层：A = 官方存储文档（DataStore/Scoped Storage），B = 通行工程知识。↔ 通用题：组件/性能板块的存储相关题。

## 9.1 选型决策

| 需求 | 首选 | 次选 |
|---|---|---|
| 键值配置（小） | Preferences DataStore | SharedPreferences（遗留） |
| 结构化配置/模型 | Proto DataStore | JSON 文件 |
| 关系数据/列表 | Room | — |
| 媒体文件 | MediaStore | App-specific 目录 |
| 用户挑选的文档 | SAF（DocumentsContract） | — |
| HTTP 缓存 | OkHttp Cache | 自建 LRU |

## 9.2 SharedPreferences：原理与缺陷（面试高频）

缺陷根源（B 层源码结论）：

1. **首次 getSharedPreferences 全量读 XML**，主线程等待载入完成（大文件直接卡）；
2. **apply() 不是即时落盘**：先写内存，事务进 `QueuedWork` 异步落盘——Activity onPause/Service 停止时 **QueuedWork 会同步等待全部待写任务完成**（wait），SP 多且大时这里就是 ANR 元凶（↔ 第 3.8 节 ANR 元凶清单）；
3. 全量重写 XML（每次 setXxx 都是整文件序列化）；
4. **官方明确不支持多进程**。

迁移：`preferencesDataStore(name = "settings") { it: SharedPreferences → 迁移逻辑 }`，DataStore 启动时一次性搬运后删除旧文件。

## 9.3 Room 要点补充（配合第 5.5 节）

- 索引：`@Entity(indices = [Index("timestamp")])`——查询慢先看 EXPLAIN QUERY PLAN（是否全表扫）；
- 大字段拆表或存文件路径（BLOB 慎用）；
- FTS（全文搜索）、`@Transaction` 保证多表一致性；
- 加密：SQLCipher 支持库（Room 直接换 factory）；
- 测试：`Room.inMemoryDatabaseBuilder` + runTest。

## 9.4 Scoped Storage 与 FileProvider

分区存储模型（Android 10 引入、11 强制，A 层）：

- **App-specific 目录**（getExternalFilesDir）：随意读写，卸载即清，免权限；
- **公共媒体**（图片/音视频）：经 MediaStore 插入，别的应用写入的媒体需申请读权限或用户授权；
- **用户主动挑选**：SAF（ACTION_OPEN_DOCUMENT）或 Photo Picker（13+，免权限拿用户选中的图片——推荐，↔ 第 13.3 节）。

FileProvider 分享模板（把私有文件给相机/微信等）：

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <external-files-path name="pics" path="Pictures/" />
</paths>
```

```kotlin
val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
startActivity(Intent(ACTION_VIEW).setDataAndType(uri, "image/jpeg")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
```

（拍照落盘用 `MediaStore.ACTION_IMAGE_CAPTURE` + EXTRA_OUTPUT 的 uri——↔ Camera2Demo 的 MediaStore 两段式保存。）

## 9.5 缓存策略

- HTTP 层：OkHttp `Cache(dir, 50MB)` 自动按 Cache-Control 协商；服务端给 `max-age`/`stale-while-revalidate`；
- 磁盘 LRU：OkHttp Cache 内部即 DiskLruCache 实现；自建缓存注意**总配额 + 清理时机**（启动/低电/后台时）；
- 图片缓存两级：内存 LruCache + 磁盘；Coil/Glide 内置（可调 percent）。

## 9.6 多进程一致性

DataStore/Room 均单进程设计；多进程共享配置：ContentProvider 封装（自带跨进程串行）或 MMKV（C 层：微信开源，mmap + 进程间文件锁）。禁止"两个进程各自读写同一 SP/XML"。

↔ 通用题：存储/性能相关题；官方 Storage 指南见附录 A。
