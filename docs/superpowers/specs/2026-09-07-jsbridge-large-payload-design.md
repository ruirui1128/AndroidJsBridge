# JSBridge 大数据量传输优化设计

日期：2026-09-07
状态：已评审通过
范围：仅 `jsbridge` 库内部（对外 API 不变，`window.WebViewJavascriptBridge` 公开方法不变）

## 1. 背景与问题

业务需要在原生与 H5 之间传输大数据量文本（KB 级到数十 MB，`test.txt` 实测 34MB，量级跨度大、不固定）。当前两条链路均存在瓶颈：

### 原生 → H5（`BridgeTiny.dispatchMessage`）

34MB 数据走一遍的代价链：

1. `new Gson().toJson()` —— 第 1 份全量拷贝（且每次调用新建 Gson 实例）
2. 连续 4 次 `replaceAll` 正则全文扫描（`BridgeTiny.java:94-96`）—— 每次产生一个等量新字符串
3. 4 次 `%` 相关 `replaceAll`（`:97-100`）—— 其中 `replaceAll("%", URLEncoder.encode("%"))` 把数据中所有 `%` 替换后**没有任何地方解码回来**，含 `%` 的数据被永久污染
4. `String.format` —— 再拷贝 1 次
5. 两次 `Log.d` 打印完整 messageJson + javascriptCommand —— 34MB × 2 的额外拷贝
6. `evaluateJavascript` —— 整串作为 JS 源码跨进程传给渲染进程，V8 解析 34MB 字符串字面量
7. JS 端 `JSON.parse` 34MB —— 渲染进程主线程卡顿

根因：JSON 被塞进单引号字符串字面量 `'%s'`，双层引号互相污染，只能靠正则打补丁。粗算 34MB 数据瞬时内存峰值可达数百 MB（8~10 份拷贝叠加）。

### H5 → 原生（`BridgeJavascritInterface.handler`）

- `addJavascriptInterface` 直通本身已是系统最快路径，大字符串 1~2 次 IPC 拷贝无法绕开
- 收到后 `mMainHandler.post` 到**主线程**分发给 BridgeHandler，大 payload 处理会卡 UI
- 回包路径（`sendResponse` → `dispatchMessage`）复用上述转义链，同样受损
- 超大单包字符串过 IPC 有内存/崩溃风险

## 2. 目标与优先级

1. **内存安全**（最高）：全量级不 OOM、瞬时内存峰值可控
2. **传输耗时**：端到端时间最短
3. **UI 流畅度**：收发大数据时主线程不卡顿

约束：

- 只改 `jsbridge` 库内部；`window.WebViewJavascriptBridge.callHandler` 等公开 API 对 H5 不变；`BridgeWebView`、`BridgeTiny` 等公开方法签名不变
- 注入的桥 JS 属于库内部，可以改（对 H5 透明）
- API<17 的 Min 桥（prompt 路径）不在本次范围，只保证小 payload 可用

## 3. 总体架构：阈值路由的双通道

```
dispatchMessage(message)
   ├─ 序列化后 ≤ CHUNK_THRESHOLD (默认 1MB，可配)
   │     → 快速路径：单次 evaluateJavascript，行为同现状但转义重写（见 §4）
   └─ > 阈值
         → 分块路径：流式序列化+转义+填块缓冲，逐块节流发出（见 §5）
```

关键决策：**分块路径不"先物化完整转义串再切"，而是边序列化边转义边发块**（Gson `toJson(obj, Appendable)` 流式输出 → 自定义 `EscapingAppendable` 逐字符转义 → 填满一块即发一块）。100MB 数据的原生侧峰值内存从数百 MB 压到 MB 级。

## 4. 一期：转义链重写（快速路径）

### 4.1 根因与修复方式

JSON 直接作为 JS 表达式传入不再包字符串字面量本可行，但为保留 JS 端 `JSON.parse` 的性能优势（比对象字面量求值快 2~5 倍），维持"字符串 → JSON.parse"协议，将转义从"正则全文扫描"改为"单遍流式字符转义"：

新增 `EscapingAppendable`（实现 `java.lang.Appendable`，约 40 行纯 Java，无 Android 依赖）：

| 输入字符 | 输出 |
|---|---|
| `\` (U+005C) | `\\` |
| `'` (U+0027) | `\'`（防御性；默认 Gson 配置下会输出 `'`） |
| U+2028 | ` `（旧 JS 引擎字符串字面量中的换行符陷阱） |
| U+2029 | ` ` |
| 其他 | 原样透传 |

### 4.2 具体改动（`BridgeTiny.dispatchMessage`）

- 删除 8 次 `replaceAll`
- 删除 2 次全量 `Log.d`（改为只打长度）
- `new Gson()` → 静态单例
- 序列化：`gson.toJson(message, escapingAppendable)`，单遍完成"序列化+转义"，中间不再产生完整中间字符串（快速路径仍需物化最终串用于 evaluateJavascript，这是必要的）
- 只走 `evaluateJavascript`（API≥19；API<19 现状本来就是断的，见 §7）
- 模板改为 `_handleMessageFromNative('...' )` 内嵌转义后 JSON（转义保证 `'`、`\`、U+2028/2029 均安全，字符串字面量闭合无歧义）

### 4.3 效果与不变量

- 内存拷贝 8~10 份 → ≤3 份（Gson 输出缓冲、最终串、IPC 传输）
- `%` 污染 bug 顺带修复
- H5 收到的数据与现在语义完全一致（`_handleMessageFromNative` 收到的仍是 JSON 字符串）
- JS 端零改动

## 5. 二期：分块协议（双向）

### 5.1 原生 → H5（流式发送方）

- 超过阈值时：`gson.toJson(message, chunkingAppendable)` 流式输出，`EscapingAppendable` 填满 512KB（`CHUNK_SIZE`，可配）即产生一个块
- 每块经 `mMainHandler` **逐块节流发送**（每帧最多一块，post 队列自然节流，不糊主线程）
- 每块调用注入 JS：`WebViewJavascriptBridge._receiveChunk(tid, index, '块文本')`；块文本用 §4 同一套转义
- 协议容忍乱序：JS 端按 `index` 缓冲，收到提交消息 `_commitChunked(tid, total)` 后按序拼接、`JSON.parse` 一次（流式发送时 `total` 在流结束后才可知，故由提交消息携带，而非每块携带），走现有 `_dispatchMessageFromNative` 分发 —— H5 业务层无感
- 无需 ACK：`evaluateJavascript` 在同一渲染上下文按调用序执行，天然有序
- 转义是逐字符局部的，流式切割点天然安全（转义序列 `\\u2028` 等不会在块边界被切断——发送侧以"完整字符"为切分单位）；切割点不拆开 UTF-16 代理对（防御性：个别 WebView 实现经 UTF-8 转换时孤立代理会被替换损坏）（JS 字符串按码元拼接自动复原）
- 可选进度钩子 `onChunkProgress(tid, sent, total)`：本次只留接口位，默认不实现 UI

### 5.2 H5 → 原生（注入 JS + Java 接收方）

注入 JS（库内部字符串，对 H5 透明）：

- `_doSend` 检测 payload `length > 阈值`（与原生侧一致的常量，两端各存一份）
- 分块调用 `window.jsbridge.receiveChunk(tid, index, chunk)`（新增 `@JavascriptInterface` 方法，增量添加，不影响既有 `send`/`handler`）
- 最后附一次 `window.jsbridge.commitChunked(handlerName, callbackId, tid, total)` 声明重组意图

Java 端：

- 新增 `ChunkAssembler`：按 `tid` 重组；`total` 由提交消息携带，块按 `index` 存槽位，组装时按序拼接
- 重组完成后走现有主线程分发路径（与 `handler` 一致）
- 大 payload 的 BridgeHandler 分发仍在主线程（保持现有行为）；业务侧若处理耗时可在自己的 Handler 里自行切线程，库不强制（不做线程模型改造，控制范围）

### 5.3 资源清理

- 两端重组缓冲：60s 超时回收（`Handler.postDelayed`）
- `tid` 冲突：`SystemClock` + 自增序号生成，进程内唯一
- `BridgeTiny.freeMemory()` / `BridgeWebView.destroy()`：清空全部重组缓冲与待发队列

## 6. 错误处理与边界

- 页面中途导航：JS 上下文重置，残留块发送静默无效；原生侧超时回收兜底（不引入 ACK/重传，避免复杂化——丢包场景在 WebView 同进程 IPC 中不存在，只有页面销毁一种，超时即可覆盖）
- 重复块/乱序块：按 `index` 幂等写入，重复块覆盖无害
- 并发传输：`tid` 隔离，各传输独立缓冲

### 已知既有问题（本次不修，记录在案）

- API<19 的原生→H5 路径实际不可用（`evaluateJavascript` 需 API 19，`loadUrl` 回退被注释）——本次仅保证不在其上雪上加霜
- Min 桥（API<17，prompt 路径）不接分块，仅适合小 payload
- H5 不回包导致 `mCallbacks` 泄漏——既有行为，不在本次范围

## 7. 探针（Phase 0，先于一切代码改动）

Demo app 内加探针页（不动库代码）：

- 数据集：test.txt 按 1 / 5 / 10 / 34MB 梯度截取
- 采样项：Gson 序列化耗时、转义耗时、dispatch 总耗时、JS 端 `JSON.parse` 耗时、原生侧内存峰值（`Debug.getMemoryInfo` 采样）、渲染主线程丢帧统计
- 产出：基线报告（写入 `docs/superpowers/specs/baseline-report.md`），作为一期/二期验收的对照数据

## 8. 测试策略

### JVM 单元测试（jsbridge 模块，可常规运行）

- `EscapingAppendable`：全 ASCII 转义表逐一验证；中文/emoji/代理对往返正确性；U+2028/2029；性质测试 `esc(a+b) == esc(a)+esc(b)`（保证流式切割等价性）；连续反斜杠边界（`\\\\` 结尾切割）
- `ChunkAssembler`：乱序到达、重复块、超时回收、tid 隔离、首块预分配
- 阈值路由：边界值（=阈值、=阈值+1）
- 恶意输入：数据含 `%`、`'`、`\`、U+2028、emoji（4 字节 UTF-16 代理对）混合

### 集成验证

- 探针即集成测试：完整性校验（发收两端 SHA-256 比对）+ 性能对照表
- 注入 JS 无 JVM 测试手段，靠探针 + 手动真机验证覆盖

## 9. 分期与验收标准

| 期 | 内容 | 验收标准 |
|---|---|---|
| 0 探针 | Demo 内探针页 | 基线报告产出：各阶段耗时+内存数据 |
| 1 转义重写 | `EscapingAppendable` + `dispatchMessage` 重写 | 内存拷贝 8~10 份→≤3 份；34MB 不 OOM；`%`/emoji/U+2028 数据完整性通过；单元测试全绿；探针对比数据 |
| 2 分块协议 | 双向分块 + ChunkAssembler + 注入 JS 升级 | 34MB 全链路原生峰值 ≈ 2×块大小+payload 级；主线程单帧无 >16ms 长卡（丢帧统计<基线）；小 payload 快速路径性能无回退；双向大数据往返 SHA-256 校验通过 |

每期独立提交，独立验收。
