# 第 6 章 Perfetto SQL 常用查询

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> Perfetto UI 的 **Query (SQL)** 页可直接对 trace 跑 SQL；命令行用 `trace_processor_shell trace.pftrace` 进入交互后粘贴。表结构以 Perfetto 官方 schema 为准（部分表名随版本演进，报错时以 UI 自动补全为准）。

## 6.1 基础表速查

| 表 | 关键列 | 用途 |
|---|---|---|
| `slice` | `id, ts, dur, track_id, name, depth, arg_set_id` | 所有切片（含 async） |
| `track` | `id, name, type, parent_id` | 轨道 |
| `thread_track` | `id, utid` | 线程轨道（id 即 track.id） |
| `thread` | `utid, tid, name, upid, is_main_thread` | 线程 |
| `process` | `upid, pid, name` | 进程 |
| `thread_state` | `utid, ts, dur, state, cpu` | 线程调度状态 |
| `counter` / `counter_track` | `ts, value, track_id` / `id, name` | 计数器（频率/内存等） |
| `actual_frame_timeline_slice` / `expected_frame_timeline_slice` | `ts, dur, jank_type, jank_tag, process_name` | 系统帧时间线（jank 判定） |

## 6.2 查询示例

**1）找最长的 `configureStreams`（配流定位）**

```sql
SELECT
  p.name  AS process_name,
  t.name  AS thread_name,
  s.name  AS slice_name,
  s.dur / 1e6 AS dur_ms,
  s.ts
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t        ON tt.utid = t.utid
JOIN process p       ON t.upid = p.upid
WHERE s.name GLOB '*configureStreams*'
ORDER BY s.dur DESC
LIMIT 20;
```

**2）某进程各线程的状态占比（判"在算还是在等"）**

```sql
SELECT t.name AS thread_name, ts.state, SUM(ts.dur) / 1e6 AS total_ms
FROM thread_state ts
JOIN thread t USING (utid)
WHERE t.upid = (SELECT upid FROM process WHERE name = 'com.example.camera')
GROUP BY t.name, ts.state
ORDER BY total_ms DESC;
```

**3）App 自定义打点的阶段耗时（`Camera#` 前缀）**

```sql
SELECT s.name, s.ts, s.dur / 1e6 AS dur_ms
FROM slice s
WHERE s.name GLOB 'Camera#*'
ORDER BY s.ts;
```

**4）binder 事务耗时 Top N（找跨进程慢调用）**

```sql
SELECT t.name AS client_thread, s.dur / 1e6 AS dur_ms, s.ts
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t        ON tt.utid = t.utid
WHERE s.name = 'binder transaction'
ORDER BY s.dur DESC
LIMIT 20;
```

**5）FrameTimeline 的 jank 分布（帧问题归属）**

```sql
SELECT jank_type, COUNT(*) AS frames
FROM actual_frame_timeline_slice
GROUP BY jank_type
ORDER BY frames DESC;
```

**6）首帧相关：某时间窗口内的相机关键切片**

```sql
SELECT s.name, s.ts, s.dur / 1e6 AS dur_ms
FROM slice s
WHERE s.name GLOB '*CaptureStarted*'
   OR s.name GLOB '*submitRequestList*'
ORDER BY s.ts;
```

**7）CPU 频率采样（判断降频/大核爬升）**

```sql
SELECT ct.name, c.ts, c.value
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name GLOB '*cpufreq*'
ORDER BY c.ts
LIMIT 2000;
```

**8）统计某切片出现次数（如 precapture 帧数、配流重试）**

```sql
SELECT s.name, COUNT(*) AS cnt, SUM(s.dur) / 1e6 AS total_ms
FROM slice s
WHERE s.name GLOB '*submitRequestList*'
GROUP BY s.name;
```

## 6.3 使用建议

- **先窄后宽**：先用切片名/进程名过滤到目标窗口，再看全量；直接全表扫描大 trace 会很慢；
- 时间单位：`ts`/`dur` 为纳秒，换算 ms 记得 `/1e6`；
- 切片名大小写与版本相关（AOSP 函数名会变）：用 `GLOB '*关键词*'` 模糊匹配，先 `SELECT DISTINCT name FROM slice WHERE name GLOB '*amera*'` 探明命名；
- 结论落文档时附上 SQL + trace 文件名 + 时间窗口，保证可复核。
