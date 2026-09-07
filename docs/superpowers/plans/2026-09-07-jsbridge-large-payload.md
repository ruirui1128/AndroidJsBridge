# JSBridge 大数据量传输优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重写 JSBridge 的转义链（消除 8 次 replaceAll 与 `%` 污染 bug）并实现阈值路由的双向分块传输协议，使 34MB 级数据原生↔H5 传输内存峰值从数百 MB 降到 MB 级。

**Architecture:** 快速路径（≤1MB）走单次 `evaluateJavascript`，转义由"正则全文扫描"改为单遍流式字符转义 `EscapingAppendable`；分块路径（>1MB）流式序列化+转义+填块，逐块节流发出，JS 端按 tid 重组。H5→原生方向在注入 JS 与 `BridgeJavascritInterface` 两端对称实现分块。对外 API（`window.WebViewJavascriptBridge` 公开方法、库公开方法签名）完全不变。

**Tech Stack:** Java（jsbridge 库，minSdk 14，纯 JVM 单元测试用 JUnit4）、Kotlin（demo app）、Gson 2.8.6（`compileOnly`）、Gradle 8.13 / AGP 8.13。

**Spec:** `docs/superpowers/specs/2026-09-07-jsbridge-large-payload-design.md`

## Global Constraints

- 包名：所有新库代码放 `com.smallbuer.jsbridge.core`（与现有代码同包，可用包级可见类）
- jsbridge 模块 minSdk 14 / compileSdk 32，**新代码不得使用 API 14 以上才有的 Java/Android API**（纯 Java 部分用 Java 7 语法即可；`jsbridge/build.gradle` 无 sourceCompatibility 配置，默认 AGP 兼容级别）
- GSON 依赖是 `compileOnly`（`jsbridge/build.gradle:29`）——**单元测试需在 `testImplementation` 补 gson 才能跑**
- 对外 API 不变：`window.WebViewJavascriptBridge.{init,send,registerHandler,callHandler,callHandlerWithModule,_handleMessageFromNative}` 行为兼容；`BridgeWebView`/`BridgeTiny`/`BridgeHandler`/`CallBackFunction` 公开签名不变
- 注入 JS 是 Java 字符串常量（`BridgeUtil.WebviewJavascriptBridge`），新协议函数直接追加进该字符串；`WebviewJavascriptBridgeMin`（API<17 路径）**一律不动**
- 单元测试是纯 JVM（`src/test`），不能引用 `android.*` 类——被测类必须无 Android 依赖（`EscapingAppendable`、`ChunkAssembler` 满足；涉及 `Log`/`Handler` 的代码不进 JVM 测试）
- 测试命令（Windows Git Bash）：`./gradlew :jsbridge:testDebugUnitTest --tests "..."`（jsbridge 模块 testDebugUnitTest 可独立运行，不需要模拟器）
- 提交信息用中文，与仓库现有风格一致
- 每个 Task 结束必须全量跑一次 `./gradlew :jsbridge:testDebugUnitTest` 确认无回归

---

### Task 0: 基线探针（先于一切库改动）

**Files:**
- Create: `app/src/main/java/com/yqkj/Js/ProbeActivity.kt`
- Modify: `app/src/main/java/com/yqkj/Js/MainActivity.kt`（加一个跳转按钮）
- Modify: `app/src/main/res/layout/activity_main.xml`（加按钮）
- Modify: `app/src/main/AndroidManifest.xml`（注册 Activity）
- Create: `app/src/main/assets/jsbridge/probe.html`
- Create: `docs/superpowers/plans/2026-09-07-baseline-report.md`（探针产出，非代码）

**Interfaces:**
- Consumes: 现有 `BridgeWebView.callHandler(String, Object, OnBridgeCallback)`、assets 里已存在的 `test.txt`（34MB）
- Produces: 基线数据报告 `2026-09-07-baseline-report.md`，Task 4/6/7 的验收对照数据。探针代码本身是 demo 代码，标注 throwaway，不进库

- [ ] **Step 1: 创建 probe.html（JS 端接收侧 + 回报计时）**

写入 `app/src/main/assets/jsbridge/probe.html`：

```html
<html>
<head><meta content="text/html; charset=utf-8" http-equiv="content-type" /></head>
<body>
<p id="stat">waiting...</p>
<script>
    function connectWebViewJavascriptBridge(callback) {
        if (window.WebViewJavascriptBridge) { callback(WebViewJavascriptBridge); }
        else { document.addEventListener('WebViewJavascriptBridgeReady', function(){ callback(WebViewJavascriptBridge); }, false); }
    }
    connectWebViewJavascriptBridge(function(bridge) {
        bridge.registerHandler("probe", function(data, responseCallback) {
            var t0 = performance.now();
            // data 是 JSON 字符串，模拟业务 parse
            var obj = JSON.parse(data);
            var t1 = performance.now();
            document.getElementById("stat").innerHTML =
                'received len=' + data.length + ' parse=' + (t1 - t0).toFixed(1) + 'ms';
            if (responseCallback) { responseCallback('{"ok":true}'); }
        });
    });
</script>
</body>
</html>
```

- [ ] **Step 2: 创建 ProbeActivity.kt（原生发送侧，分段计时 + 内存采样）**

写入 `app/src/main/java/com/yqkj/Js/ProbeActivity.kt`。要点：从 assets 按 MB 梯度截取 test.txt 前缀；`Debug.getMemoryInfo` 采样前后的 `dalvikPss`；用 `SystemClock.elapsedRealtime()` 分段计时；发送前把 content 放到 **子线程读文件、主线程 callHandler**（callHandler 内部要求主线程）。完整代码：

```kotlin
package com.yqkj.Js

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.smallbuer.jsbridge.core.BridgeWebView
import kotlin.concurrent.thread

class ProbeActivity : AppCompatActivity() {
    private val TAG = "ProbeActivity"
    private lateinit var webView: BridgeWebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        val btn = Button(this)
        btn.text = "run probe (1/5/10/34 MB)"
        status = TextView(this)
        webView = BridgeWebView(this)
        layout.addView(btn)
        layout.addView(status)
        layout.addView(webView)
        setContentView(layout)
        webView.loadUrl("file:///android_asset/jsbridge/probe.html")
        btn.setOnClickListener { runProbe() }
    }

    private fun readAssetPrefix(bytes: Long): String? {
        return assets.open("test.txt").use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1 shl 20)
            var remaining = bytes
            while (remaining > 0) {
                val n = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (n < 0) break
                buf.write(chunk, 0, n)
                remaining -= n
            }
            buf.toString("UTF-8")
        }
    }

    private fun runProbe() {
        thread {
            for (mb in intArrayOf(1, 5, 10, 34)) {
                val t0 = SystemClock.elapsedRealtime()
                val content = readAssetPrefix(mb.toLong() * 1024L * 1024L) ?: continue
                val t1 = SystemClock.elapsedRealtime()
                val memBefore = Debug.MemoryInfo()
                Debug.getMemoryInfo(memBefore)
                runOnUiThread {
                    val t2 = SystemClock.elapsedRealtime()
                    webView.callHandler("probe", content) { resp ->
                        val t3 = SystemClock.elapsedRealtime()
                        val memAfter = Debug.MemoryInfo()
                        Debug.getMemoryInfo(memAfter)
                        val log = "MB=$mb read=${t1 - t0}ms roundtrip=${t3 - t2}ms " +
                                "pssBefore=${memBefore.dalvikPss}KB pssAfter=${memAfter.dalvikPss}KB resp=$resp"
                        Log.i(TAG, log)
                        runOnUiThread { status.append("\n$log") }
                    }
                }
                SystemClock.sleep(3000) // 等上一轮 GC 稳定
            }
        }
    }
}
```

- [ ] **Step 3: 注册 Activity + 加跳转入口**

`app/src/main/AndroidManifest.xml` 在 `<application>` 内、MainActivity 之后加：

```xml
<activity android:name=".ProbeActivity" android:exported="false" />
```

`app/src/main/res/layout/activity_main.xml` 根 LinearLayout 顶部（第一个 TextView 之前）加：

```xml
<Button
    android:id="@+id/btnProbe"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="性能探针" />
```

`MainActivity.kt` 的 `onCreate` 里 `initBridgeWebView()` 之前加：

```kotlin
findViewById<Button>(R.id.btnProbe).setOnClickListener {
    startActivity(android.content.Intent(this, ProbeActivity::class.java))
}
```

- [ ] **Step 4: 真机运行采集基线**

`./gradlew :app:installDebug`，真机打开 app → 性能探针 → run probe。等待 4 轮跑完，`adb logcat -s ProbeActivity` 抓取结果。每轮记录：roundtrip 耗时、dalvikPss 前后差、页面是否 ANR/OOM。

- [ ] **Step 5: 写基线报告**

写入 `docs/superpowers/plans/2026-09-07-baseline-report.md`：4 个梯度 × {roundtrip ms, ΔPss KB, 是否 OOM/ANR, JS parse ms（从 probe.html 页面上读）} 的表格 + 一句话结论（最大内存峰值出现在哪档）。此文件是 Task 4/6/7 验收的对照基线。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/yqkj/Js/ProbeActivity.kt app/src/main/java/com/yqkj/Js/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/AndroidManifest.xml app/src/main/assets/jsbridge/probe.html docs/superpowers/plans/2026-09-07-baseline-report.md
git commit -m "探针: 原生↔H5 大数据传输基线测量(1/5/10/34MB)"
```

---

### Task 1: EscapingAppendable（流式转义核心）

**Files:**
- Create: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/EscapingAppendable.java`
- Test: `jsbridge/src/test/java/com/smallbuer/jsbridge/core/EscapingAppendableTest.java`
- Modify: `jsbridge/build.gradle`（加 gson testImplementation）

**Interfaces:**
- Produces: `public class EscapingAppendable implements Appendable`，构造 `EscapingAppendable(Appendable out)`；`append(CharSequence)`/`append(CharSequence, int, int)`/`append(char)`；新增方法 `public String currentContent()`（返回已累积内容，供测试与快路径复用）；行为契约：`'\'`→`\\`，`'\''`→`\'`，U+2028→`\u2028`（字面反斜杠+u2028 六个字符），U+2029→`\u2029`，其余原样。纯 Java 无 Android 依赖。

- [ ] **Step 1: jsbridge/build.gradle 加 gson 测试依赖**

`dependencies` 块中 `testImplementation 'junit:junit:4.12'` 之后加一行：

```groovy
    testImplementation 'com.google.code.gson:gson:2.8.6'
```

- [ ] **Step 2: 写失败测试（转义表 + 性质测试 + 切割安全）**

写入 `jsbridge/src/test/java/com/smallbuer/jsbridge/core/EscapingAppendableTest.java`：

```java
package com.smallbuer.jsbridge.core;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EscapingAppendableTest {

    private String esc(String s) throws IOException {
        StringBuilder sb = new StringBuilder();
        EscapingAppendable ea = new EscapingAppendable(sb);
        ea.append(s);
        return sb.toString();
    }

    @Test
    public void backslashIsDoubled() throws IOException {
        assertEquals("\\\\", esc("\\"));
    }

    @Test
    public void singleQuoteIsEscaped() throws IOException {
        assertEquals("\\'", esc("'"));
    }

    @Test
    public void lineSeparator2028IsEscaped() throws IOException {
        assertEquals("\\u2028", esc("\u2028"));
    }

    @Test
    public void paragraphSeparator2029IsEscaped() throws IOException {
        assertEquals("\\u2029", esc("\u2029"));
    }

    @Test
    public void plainAsciiPassThrough() throws IOException {
        assertEquals("abc123", esc("abc123"));
    }

    @Test
    public void percentIsUntouched() throws IOException {
        // 旧实现 replaceAll("%","%25") 会污染含 % 数据，新实现必须原样
        assertEquals("100%纯净水", esc("100%纯净水"));
    }

    @Test
    public void cjkPassThrough() throws IOException {
        assertEquals("中文测试", esc("中文测试"));
    }

    @Test
    public void surrogatePairsPassThrough() throws IOException {
        assertEquals("😀", esc("\uD83D\uDE00"));
    }

    @Test
    public void appendRange() throws IOException {
        StringBuilder sb = new StringBuilder();
        EscapingAppendable ea = new EscapingAppendable(sb);
        ea.append("a'b\\c", 1, 3); // 'b
        assertEquals("\\'b", sb.toString());
    }

    @Test
    public void appendSingleChar() throws IOException {
        StringBuilder sb = new StringBuilder();
        EscapingAppendable ea = new EscapingAppendable(sb);
        ea.append('\'');
        assertEquals("\\'", sb.toString());
    }

    @Test
    public void propertyEqualsSplitComposition() throws IOException {
        // 流式切割等价性：esc(a+b) == esc(a)+esc(b)——分块路径正确性的根基
        String a = "abc\\def'ghi\u2028jkl\u2029mno😀pqr%";
        for (int cut = 0; cut <= a.length(); cut++) {
            String whole = esc(a);
            String split = esc(a.substring(0, cut)) + esc(a.substring(cut));
            assertTrue("cut=" + cut, whole.equals(split));
        }
    }

    @Test
    public void gsonOutputRoundTrip() throws IOException {
        // Gson 产物经转义后，包单引号字面量可被 JSON 端还原（人工协议模拟）
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson("{\"k\":\"a'b\\c\u2028%😀\"}");
        String escaped = esc(json);
        // 模拟 JS 单引号字符串字面量的求值：\' -> ' , \\ -> \ , \u2028 -> U+2028
        StringBuilder js = new StringBuilder();
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char n = escaped.charAt(++i);
                if (n == '\'') js.append('\'');
                else if (n == '\\') js.append('\\');
                else if (n == 'u') { js.append((char) Integer.parseInt(escaped.substring(i + 1, i + 5), 16)); i += 4; }
                else throw new AssertionError("unexpected escape \\" + n);
            } else js.append(c);
        }
        assertEquals(json, js.toString());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.EscapingAppendableTest"`
Expected: FAIL，`EscapingAppendable` 类不存在（编译错误即视为失败）

- [ ] **Step 4: 实现 EscapingAppendable**

写入 `jsbridge/src/main/java/com/smallbuer/jsbridge/core/EscapingAppendable.java`：

```java
package com.smallbuer.jsbridge.core;

import java.io.IOException;

/**
 * 单遍流式转义 Appendable：把任意文本安全嵌入 JS 单引号字符串字面量。
 * 转义局部性：每字符输出只依赖该字符本身，因此 esc(a+b) == esc(a)+esc(b)，
 * 分块流式切割与整串转义等价。
 * 无 Android 依赖，可 JVM 单测。
 */
public class EscapingAppendable implements Appendable {

    private final Appendable out;

    public EscapingAppendable(Appendable out) {
        this.out = out;
    }

    @Override
    public Appendable append(CharSequence csq) throws IOException {
        return append(csq, 0, csq.length());
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) throws IOException {
        for (int i = start; i < end; i++) {
            append(csq.charAt(i));
        }
        return this;
    }

    @Override
    public Appendable append(char c) throws IOException {
        switch (c) {
            case '\\':
                out.append('\\').append('\\');
                break;
            case '\'':
                out.append('\\').append('\'');
                break;
            case '\u2028':
                out.append('\\').append('u').append('2').append('0').append('2').append('8');
                break;
            case '\u2029':
                out.append('\\').append('u').append('2').append('0').append('2').append('9');
                break;
            default:
                out.append(c);
        }
        return this;
    }

    /** 已累积的转义内容（快路径物化用） */
    public String currentContent() throws IOException {
        return out.toString();
    }
}
```

注意：`currentContent()` 里 `out.toString()` 对 `StringBuilder` 有效；Task 3 的分块路径不调用此方法（分块直接读内部缓冲），不会踩 `Appendable` 无 `toString` 的坑。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.EscapingAppendableTest"`
Expected: PASS（12 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/EscapingAppendable.java jsbridge/src/test/java/com/smallbuer/jsbridge/core/EscapingAppendableTest.java jsbridge/build.gradle
git commit -m "一期: EscapingAppendable 流式转义核心(替代8次replaceAll, 修复%污染)"
```

---

### Task 2: dispatchMessage 重写（快速路径接线）

**Files:**
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java:90-121`（`dispatchMessage` 方法体）
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java`（类头部加 Gson 单例与阈值常量）

**Interfaces:**
- Consumes: Task 1 的 `EscapingAppendable(Appendable)` 
- Produces: `BridgeTiny` 新增 `public static final long CHUNK_THRESHOLD = 1024 * 1024;`（Task 3 分块路径以此路由）；`dispatchMessage` 行为：≤阈值时与旧版语义一致（`_handleMessageFromNative('<escaped json>')`），>阈值时**暂时仍走旧单包路径**（Task 3 接分块，本任务不引入行为变化）；删除 8 次 replaceAll 与 2 次全量 Log。

- [ ] **Step 1: 重写 dispatchMessage + 类头**

`BridgeTiny.java` 类头（字段声明区，`private Handler mMainHandler = ...` 之后）加：

```java
    private static final Gson GSON = new Gson();

    public static final long CHUNK_THRESHOLD = 1024 * 1024;
```

import 区加 `import com.smallbuer.jsbridge.core.EscapingAppendable;` 同包可省略 import，无需添加；Gson 的 import 已存在。

`dispatchMessage` 整个方法体（`BridgeTiny.java:90-121`，含被注释的旧代码块）替换为：

```java
    public void dispatchMessage(Object message) {

        StringBuilder sb = new StringBuilder(64);
        EscapingAppendable ea = new EscapingAppendable(sb);
        try {
            GSON.toJson(message, ea);
        } catch (IOException e) {
            // StringBuilder 不抛 IOException，防御性兜底
            throw new RuntimeException(e);
        }
        String messageJson = sb.toString();
        String javascriptCommand = String.format(
                BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
        BridgeLog.d(TAG, "dispatchMessage len=" + messageJson.length());
        mWebView.evaluateJavascript(javascriptCommand, null);
    }
```

文件顶部 import 区加：

```java
import java.io.IOException;
```

同时删除 `dispatchMessage` 下方整段被注释的旧实现（`//        try {` 到 `//        }` 约 `BridgeTiny.java:104-119`），以及不再使用的 import：`java.net.URLEncoder`、`org.json.JSONException`、`org.json.JSONObject` 中仅 dispatchMessage 用到的部分——注意 `onJsPrompt`（API<17 路径）仍在用 `JSONObject`/`JSONException`，**这两个 import 保留**；`URLEncoder` 确认无其他使用点后删除。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :jsbridge:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 全量单测回归**

Run: `./gradlew :jsbridge:testDebugUnitTest`
Expected: PASS（EscapingAppendableTest 12 个测试仍绿）

- [ ] **Step 4: 真机冒烟**

安装 demo，点"Native To JS"按钮（走 `functionInJs` 小数据路径），确认 H5 页面正常显示 `data from Java`；点 demo.html 的"调用Native方法(Toast)"，确认 toast 正常（验证 H5→原生→回包全环）。用含 `%` 的数据在 demo 里手动过一遍（如把 MainActivity 里 callHandler 的参数临时改为 `"100%测试'"`），确认显示无污染。

- [ ] **Step 5: Commit**

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java
git commit -m "一期: dispatchMessage 转义链重写, 拷贝份数 8~10 -> 3"
```

---

### Task 3: ChunkAssembler（H5→原生重组器）

**Files:**
- Create: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkAssembler.java`
- Test: `jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkAssemblerTest.java`

**Interfaces:**
- Produces: `public class ChunkAssembler`，无 Android 依赖、纯 JVM 可测：
  - `public void putChunk(String tid, int index, String chunk)` —— 存槽位，乱序/重复安全
  - `public String assemble(String tid, int total)` —— 全部到齐按 index 0..total-1 拼接返回；有缺块返回 `null`；组装成功即释放该 tid 的缓冲
  - `public boolean isComplete(String tid, int total)` —— 槽位数 ≥ total
  - `public void expireOlderThan(long deadlineMillis)` —— 清理最后访问早于 deadline 的 tid 缓冲（超时兜底用）
  - `public void clear()` —— 全清
- Consumes: 无（独立组件）

- [ ] **Step 1: 写失败测试**

写入 `jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkAssemblerTest.java`：

```java
package com.smallbuer.jsbridge.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChunkAssemblerTest {

    @Test
    public void assembleInOrder() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.putChunk("t1", 1, "bb");
        ca.putChunk("t1", 2, "cc");
        assertTrue(ca.isComplete("t1", 3));
        assertEquals("aabbcc", ca.assemble("t1", 3));
    }

    @Test
    public void assembleOutOfOrder() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 2, "cc");
        ca.putChunk("t1", 0, "aa");
        ca.putChunk("t1", 1, "bb");
        assertEquals("aabbcc", ca.assemble("t1", 3));
    }

    @Test
    public void duplicateChunkIsIdempotent() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.putChunk("t1", 0, "aa"); // 重复块覆盖无害
        ca.putChunk("t1", 1, "bb");
        assertEquals("aabb", ca.assemble("t1", 2));
    }

    @Test
    public void missingChunkReturnsNull() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.putChunk("t1", 2, "cc");
        assertFalse(ca.isComplete("t1", 3));
        assertNull(ca.assemble("t1", 3));
        // 缺块不销毁缓冲，补齐后可再次组装
        ca.putChunk("t1", 1, "bb");
        assertEquals("aabbcc", ca.assemble("t1", 3));
    }

    @Test
    public void tidIsolation() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.putChunk("t2", 0, "zz");
        assertEquals("aa", ca.assemble("t1", 1));
        assertEquals("zz", ca.assemble("t2", 1));
    }

    @Test
    public void assembleReleasesBuffer() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.assemble("t1", 1);
        // 组装后缓冲释放：再次组装返回 null
        assertNull(ca.assemble("t1", 1));
        assertFalse(ca.isComplete("t1", 1));
    }

    @Test
    public void expireRemovesStaleOnly() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("old", 0, "aa");
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        ca.putChunk("new", 0, "bb");
        ca.expireOlderThan(System.currentTimeMillis() - 10);
        assertNull(ca.assemble("old", 1));  // 过期被清
        assertEquals("bb", ca.assemble("new", 1)); // 新的仍在
    }

    @Test
    public void clearRemovesEverything() {
        ChunkAssembler ca = new ChunkAssembler();
        ca.putChunk("t1", 0, "aa");
        ca.clear();
        assertNull(ca.assemble("t1", 1));
    }

    @Test
    public void largeChunkCount() {
        ChunkAssembler ca = new ChunkAssembler();
        int n = 200;
        for (int i = 0; i < n; i++) {
            ca.putChunk("t1", i, "x" + i + ",");
        }
        String r = ca.assemble("t1", n);
        assertEquals(n * 1, r.split(",").length); // 每块以逗号结尾
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.ChunkAssemblerTest"`
Expected: FAIL（编译错误，类不存在）

- [ ] **Step 3: 实现 ChunkAssembler**

写入 `jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkAssembler.java`：

```java
package com.smallbuer.jsbridge.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 分块重组器：按 tid 隔离缓冲，按 index 存槽位，组装时按序拼接。
 * 纯 Java 无 Android 依赖，可 JVM 单测。
 * 线程模型：@JavascriptInterface 回调在 WebView 的 binder 线程，
 * 调用方（BridgeJavascritInterface）需自行保证串行或外部加锁；本类内部不加锁。
 */
public class ChunkAssembler {

    private static class Transfer {
        final HashMap<Integer, String> chunks = new HashMap<>();
        long lastAccess = System.currentTimeMillis();
    }

    private final HashMap<String, Transfer> transfers = new HashMap<>();

    public void putChunk(String tid, int index, String chunk) {
        Transfer t = transfers.get(tid);
        if (t == null) {
            t = new Transfer();
            transfers.put(tid, t);
        }
        t.lastAccess = System.currentTimeMillis();
        t.chunks.put(index, chunk);
    }

    public boolean isComplete(String tid, int total) {
        Transfer t = transfers.get(tid);
        return t != null && t.chunks.size() >= total;
    }

    /** 全部到齐则拼接返回并释放缓冲；有缺块返回 null 且保留缓冲 */
    public String assemble(String tid, int total) {
        Transfer t = transfers.get(tid);
        if (t == null || t.chunks.size() < total) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(t.chunks.get(i));
        }
        transfers.remove(tid);
        return sb.toString();
    }

    /** 清理最后访问早于 deadline 的传输缓冲（超时兜底） */
    public void expireOlderThan(long deadlineMillis) {
        Iterator<Map.Entry<String, Transfer>> it = transfers.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().lastAccess < deadlineMillis) {
                it.remove();
            }
        }
    }

    public void clear() {
        transfers.clear();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.ChunkAssemblerTest"`
Expected: PASS（9 个测试全绿）

- [ ] **Step 5: 全量回归 + Commit**

Run: `./gradlew :jsbridge:testDebugUnitTest`
Expected: PASS

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkAssembler.java jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkAssemblerTest.java
git commit -m "二期: ChunkAssembler 分块重组器(乱序/重复/超时/隔离)"
```

---

### Task 4: 原生→H5 分块发送（流式 ChunkedAppendable + 注入 JS 接收）

**Files:**
- Create: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkedAppendable.java`
- Test: `jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkedAppendableTest.java`
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java`（dispatchMessage 阈值路由 + 分块发送）
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeUtil.java`（注入 JS 加 `_receiveChunk`/`_commitChunked`）

**Interfaces:**
- Consumes: Task 1 `EscapingAppendable`；Task 2 `CHUNK_THRESHOLD`；Task 3 `ChunkAssembler`（本任务不直接用——原生→H5 方向的重组在 JS 端）
- Produces: 
  - `ChunkedAppendable(Appendable sink, int chunkChars, ChunkListener listener)`，接口 `public interface ChunkListener { void onChunk(String chunk); }`——每攒满 `chunkChars` 个**字符**（不拆 UTF-16 代理对）回调一次
  - `BridgeTiny.dispatchMessage`：> `CHUNK_THRESHOLD` 时走 `_receiveChunk/_commitChunked` 协议
  - 注入 JS 新增（对 H5 不可见）：`_receiveChunk(tid,index,chunk)`、`_commitChunked(tid,total)`
  - JS 全局临时缓冲 `window._chunkBuf`，`_commitChunked` 拼接后走既有 `_dispatchMessageFromNative`，随后自清理

- [ ] **Step 1: 写 ChunkedAppendable 失败测试**

写入 `jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkedAppendableTest.java`：

```java
package com.smallbuer.jsbridge.core;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChunkedAppendableTest {

    static class Collect implements ChunkedAppendable.ChunkListener {
        final List<String> chunks = new ArrayList<>();
        public void onChunk(String chunk) { chunks.add(chunk); }
    }

    @Test
    public void splitsAtBoundary() throws IOException {
        Collect c = new Collect();
        ChunkedAppendable ca = new ChunkedAppendable(c, 3);
        ca.append("abcdefg");
        ca.flush();
        assertEquals(3, c.chunks.size());
        assertEquals("abc", c.chunks.get(0));
        assertEquals("def", c.chunks.get(1));
        assertEquals("g", c.chunks.get(2));
    }

    @Test
    public void doesNotSplitSurrogatePair() throws IOException {
        Collect c = new Collect();
        ChunkedAppendable ca = new ChunkedAppendable(c, 2);
        // 😀 是代理对（2 char），第 2 块只剩 1 char 位时整体推迟到下块
        ca.append("ab😀cd");
        ca.flush();
        assertEquals("ab", c.chunks.get(0));
        assertEquals("😀c", c.chunks.get(1));
        assertEquals("d", c.chunks.get(2));
        for (String s : c.chunks) {
            // 每块不得以孤立高代理结尾
            char last = s.charAt(s.length() - 1);
            assertTrue(!Character.isHighSurrogate(last));
        }
    }

    @Test
    public void reassemblyEqualsOriginal() throws IOException {
        String data = "abc\\def'ghi\u2028jkl\u2029mno😀pqr%中文";
        for (int chunkSize : new int[]{1, 2, 3, 5, 100}) {
            Collect c = new Collect();
            ChunkedAppendable ca = new ChunkedAppendable(c, chunkSize);
            ca.append(data);
            ca.flush();
            StringBuilder sb = new StringBuilder();
            for (String s : c.chunks) sb.append(s);
            assertEquals(data, sb.toString());
        }
    }

    @Test
    public void multipleAppendsAccumulate() throws IOException {
        Collect c = new Collect();
        ChunkedAppendable ca = new ChunkedAppendable(c, 4);
        ca.append("ab");
        ca.append("cd");
        ca.append("efg");
        ca.flush();
        StringBuilder sb = new StringBuilder();
        for (String s : c.chunks) sb.append(s);
        assertEquals("abcdefg", sb.toString());
    }

    @Test
    public void emptyInputNoChunk() throws IOException {
        Collect c = new Collect();
        ChunkedAppendable ca = new ChunkedAppendable(c, 4);
        ca.flush();
        assertEquals(0, c.chunks.size());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.ChunkedAppendableTest"`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ChunkedAppendable**

写入 `jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkedAppendable.java`：

```java
package com.smallbuer.jsbridge.core;

import java.io.IOException;

/**
 * 分块输出流：内部缓冲攒满 chunkChars 个字符即回调一次 onChunk。
 * 切割点不拆 UTF-16 代理对（防御性：个别 WebView 经 UTF-8 转换时孤立代理会损坏）。
 * 转义由外层 EscapingAppendable 完成，本类只管切割，两者可串联：
 * ChunkedAppendable(EscapingAppendable(innerSink), ...) 或反向组合，
 * 本项目用法：Gson -> ChunkedAppendable(负责攒块) 的块文本再过 EscapingAppendable 转义后发送。
 */
public class ChunkedAppendable implements Appendable {

    public interface ChunkListener {
        void onChunk(String chunk);
    }

    private final ChunkListener listener;
    private final int chunkChars;
    private final StringBuilder buf = new StringBuilder();

    public ChunkedAppendable(ChunkListener listener, int chunkChars) {
        this.listener = listener;
        this.chunkChars = chunkChars;
    }

    @Override
    public Appendable append(CharSequence csq) throws IOException {
        return append(csq, 0, csq.length());
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) throws IOException {
        for (int i = start; i < end; i++) {
            append(csq.charAt(i));
        }
        return this;
    }

    @Override
    public Appendable append(char c) throws IOException {
        buf.append(c);
        if (buf.length() >= chunkChars && !endsWithHighSurrogate()) {
            emit();
        }
        return this;
    }

    private boolean endsWithHighSurrogate() {
        char last = buf.charAt(buf.length() - 1);
        return Character.isHighSurrogate(last);
    }

    private void emit() {
        listener.onChunk(buf.toString());
        buf.setLength(0);
    }

    /** 发送残留缓冲（流结束时调用；空缓冲不发） */
    public void flush() {
        if (buf.length() > 0) {
            emit();
        }
    }
}
```

注意组合顺序：`Gson.toJson(msg, chunked)`，`ChunkedAppendable` 的 `ChunkListener.onChunk(chunk)` 内部再 `escaping.append(chunk); send(chunk)`。转义局部性保证块间独立转义 == 整体转义（Task 1 性质测试已验证）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :jsbridge:testDebugUnitTest --tests "com.smallbuer.jsbridge.core.ChunkedAppendableTest"`
Expected: PASS（5 个测试全绿）

- [ ] **Step 5: 注入 JS 加接收协议（BridgeUtil.WebviewJavascriptBridge）**

`BridgeUtil.java` 的 `WebviewJavascriptBridge` 字符串中，在 `var WebViewJavascriptBridge=window.WebViewJavascriptBridge={init:init,...}` 的对象字面量里追加两个方法（插在 `_handleMessageFromNative:_handleMessageFromNative` 之后）：

```javascript
, _receiveChunk:function(tid,index,chunk){var b=window._chunkBuf;if(!b||b.tid!==tid){b=window._chunkBuf={tid:tid,parts:{}};}b.parts[index]=chunk;}
, _commitChunked:function(tid,total){var b=window._chunkBuf;if(!b||b.tid!==tid){return}var buf='';for(var i=0;i<total;i++){buf+=b.parts[i]}window._chunkBuf=null;_handleMessageFromNative(buf);}
```

（实际操作：在 Java 字符串常量里找到 `_handleMessageFromNative:_handleMessageFromNative}`，替换为 `_handleMessageFromNative:_handleMessageFromNative,_receiveChunk:function(tid,index,chunk){var b=window._chunkBuf;if(!b||b.tid!==tid){b=window._chunkBuf={tid:tid,parts:{}};}b.parts[index]=chunk;},_commitChunked:function(tid,total){var b=window._chunkBuf;if(!b||b.tid!==tid){return}var buf='';for(var i=0;i<total;i++){buf+=b.parts[i]}window._chunkBuf=null;_handleMessageFromNative(buf);}}`。注意这是压缩 JS，无换行；`_handleMessageFromNative(buf)` 接收的是字符串，与现有 `receiveMessageQueue`/`setTimeout` 调度兼容。）

`WebviewJavascriptBridgeMin` **不动**。

- [ ] **Step 6: BridgeTiny.dispatchMessage 接阈值路由**

`BridgeTiny.java` 加字段与常量（`CHUNK_THRESHOLD` 旁）：

```java
    private static final int CHUNK_SIZE_CHARS = 512 * 1024;

    private long mTidSeq = 0;
```

import 区加：

```java
import java.io.IOException;
```

`dispatchMessage`（Task 2 重写后的版本）整体替换为：

```java
    public void dispatchMessage(Object message) {

        StringBuilder sb = new StringBuilder(64);
        LengthAppendable counter = new LengthAppendable();
        GSON.toJson(message, counter);
        long len = counter.length();

        if (len <= CHUNK_THRESHOLD) {
            // 快速路径：单包
            EscapingAppendable ea = new EscapingAppendable(sb);
            try {
                GSON.toJson(message, ea);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            String messageJson = sb.toString();
            String javascriptCommand = String.format(
                    BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
            BridgeLog.d(TAG, "dispatchMessage fast len=" + messageJson.length());
            mWebView.evaluateJavascript(javascriptCommand, null);
        } else {
            // 分块路径：流式 序列化->攒块->转义->逐块节流发送
            final String tid = String.format("T_%d_%s", ++mTidSeq,
                    Long.toHexString(System.currentTimeMillis()));
            final List<String> pending = new ArrayList<>();
            ChunkedAppendable chunker = new ChunkedAppendable(
                    new ChunkedAppendable.ChunkListener() {
                        @Override
                        public void onChunk(String chunk) {
                            pending.add(chunk);
                        }
                    }, CHUNK_SIZE_CHARS);
            try {
                GSON.toJson(message, chunker);
                chunker.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            final int total = pending.size();
            BridgeLog.d(TAG, "dispatchMessage chunked len=" + len + " chunks=" + total);
            // 逐块节流：每 16ms 发一块（约一帧），避免连续大 evaluateJavascript 糊住渲染主线程
            for (int i = 0; i < total; i++) {
                final String chunk = pending.get(i);
                final int index = i;
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder esc = new StringBuilder(chunk.length() + 16);
                        try {
                            new EscapingAppendable(esc).append(chunk);
                        } catch (IOException ignored) {
                        }
                        String js = String.format(
                                "javascript:WebViewJavascriptBridge._receiveChunk('%s',%d,'%s');",
                                tid, index, esc.toString());
                        mWebView.evaluateJavascript(js, null);
                    }
                }, i * 16L);
            }
            // 提交消息（携带 total）在最后一块之后
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    String js = String.format(
                            "javascript:WebViewJavascriptBridge._commitChunked('%s',%d);",
                            tid, total);
                    mWebView.evaluateJavascript(js, null);
                }
            }, total * 16L);
        }
    }
```

同文件追加一个包级可见的小工具类（文件末尾、类外——或作为 `BridgeTiny` 的静态嵌套类，推荐后者）：

```java
    /** 只计长度的 Appendable：第一遍流式统计序列化长度，避免为路由而物化整串 */
    static class LengthAppendable implements Appendable {
        private long len = 0;
        public long length() { return len; }
        @Override
        public Appendable append(CharSequence csq) { len += csq.length(); return this; }
        @Override
        public Appendable append(CharSequence csq, int start, int end) { len += end - start; return this; }
        @Override
        public Appendable append(char c) { len += 1; return this; }
    }
```

import 区补：`java.util.ArrayList`、`java.util.List`（已有 HashMap/Map，注意核对）。

注意一个精确性问题：`LengthAppendable` 统计的是 Gson 输出字符数，而快速路径判断的其实是"转义后长度"。转义只可能变长（`\`,`'`,U+2028/9 各自膨胀），极端情况 len 恰在阈值下但转义后超阈值——影响仅是"本该分块却走了单包"的一档偏差，与旧版行为相同，可接受。**不做两遍转义修正。**

- [ ] **Step 7: 编译 + 全量单测**

Run: `./gradlew :jsbridge:assembleDebug :jsbridge:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 8: 真机验证分块路径**

MainActivity 临时代码（验证后删除）：`mBtnNativeToJsBridgeWebView` 点击处理里加一次性 34MB 发送：

```kotlin
thread {
    val big = FileHelper.readTextFromAssets(this@MainActivity, "test.txt")!!
    runOnUiThread {
        mBridgeWebView?.callHandler("functionInJs", big) { data ->
            Log.i(TAG, "chunked response: ${data.take(50)}")
        }
    }
}
```

运行后确认：demo.html 页面 `show` 段落显示完整的 `data from Java`（长度与 test.txt 字符数一致）；logcat 无 OOM；传输期间页面滚动不掉帧（肉眼）。

- [ ] **Step 9: 还原临时代码 + Commit**

删除 Step 8 的临时代码，恢复原按钮行为：

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeUtil.java jsbridge/src/main/java/com/smallbuer/jsbridge/core/ChunkedAppendable.java jsbridge/src/test/java/com/smallbuer/jsbridge/core/ChunkedAppendableTest.java
git commit -m "二期: 原生->H5 分块发送(流式+节流16ms/块), 注入JS接收重组"
```

---

### Task 5: H5→原生 分块接收（注入 JS 发送 + @JavascriptInterface）

**Files:**
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeUtil.java`（注入 JS `_doSend` 加分块）
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeJavascritInterface.java`（加 `receiveChunk`/`commitChunked` 方法）
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java`（持 assembling 引用贯穿给 interface）

**Interfaces:**
- Consumes: Task 3 `ChunkAssembler`；Task 4 注入 JS 的结构
- Produces:
  - 注入 JS：`_doSend` 中 `lastMessage.length() > 1048576`（1MB，与原生 `CHUNK_THRESHOLD` 数值一致，**写死在 JS 字符串里**）时改走 `window.jsbridge.receiveChunk(tid,i,chunk)` + `window.jsbridge.commitChunked(handlerName,callbackId,tid,total)`
  - Java：`@JavascriptInterface public void receiveChunk(String tid, int index, String chunk)`、`@JavascriptInterface public void commitChunked(String handlerName, String callbackId, String tid, int total)`
  - 分块重组完成后与现有 `handler()` 走完全相同的分发（local 优先、回调 CallBack）

- [ ] **Step 1: BridgeJavascritInterface 加两个接口方法**

`BridgeJavascritInterface.java` 中 `handler` 方法之后加：

```java
    private ChunkAssembler mChunkAssembler = new ChunkAssembler();

    /** 每块最长 512K 字符，与原生 CHUNK_SIZE_CHARS 一致，仅用于超时清理提示，不强制 */
    private static final long CHUNK_EXPIRE_MS = 60_000L;

    @JavascriptInterface
    public void receiveChunk(final String tid, final int index, final String chunk) {
        if (tid == null || chunk == null) return;
        synchronized (mChunkAssembler) {
            mChunkAssembler.putChunk(tid, index, chunk);
        }
    }

    @JavascriptInterface
    public void commitChunked(final String handlerName, final String callbackId,
                              final String tid, final int total) {
        if (tid == null) return;
        String assembled;
        // 顺手回收更早的残留缓冲（页面崩溃等极端情况留下的孤儿传输，60s 超时）
        synchronized (mChunkAssembler) {
            mChunkAssembler.expireOlderThan(System.currentTimeMillis() - CHUNK_EXPIRE_MS);
            assembled = mChunkAssembler.assemble(tid, total);
        }
        if (assembled == null) {
            Log.e(TAG, "commitChunked missing chunks tid=" + tid);
            return;
        }
        // 与 handler() 相同的分发路径
        dispatchToHandler(handlerName, callbackId, assembled);
    }

    /** handler 与 commitChunked 共用的分发逻辑（从 handler() 中抽出，行为不变） */
    private void dispatchToHandler(final String handlerName, final String callbackId, final String data) {
        if (TextUtils.isEmpty(handlerName)) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                IWebView webView = webViewRef.get();
                if (webView == null || mBridge == null) return;

                if (webView.getLocalMessageHandlers().containsKey(handlerName)) {
                    BridgeHandler bridgeHandler = webView.getLocalMessageHandlers().get(handlerName);
                    if (bridgeHandler == null) {
                        Log.e(TAG, "========bridgeHandler is null==========");
                        return;
                    }
                    if (webView.getHandlerLogNames().contains(handlerName)) {
                        bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, "", ""));
                    } else {
                        bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, handlerName, data));
                    }
                    return;
                }

                if (mBridge.getMessageHandlers().containsKey(handlerName)) {
                    BridgeHandler bridgeHandler = mBridge.getMessageHandlers().get(handlerName);
                    if (bridgeHandler == null) {
                        Log.e(TAG, "========bridgeHandler is null 2==========");
                        return;
                    }
                    if (webView.getHandlerLogNames().contains(handlerName)) {
                        bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, "", ""));
                    } else {
                        bridgeHandler.handler(webView.getContext(), data, new CallBack(mBridge, callbackId, handlerName, data));
                    }
                }
            }
        });
    }
```

然后把现有 `handler()` 方法体（`BridgeJavascritInterface.java:97-131` 的 `mMainHandler.post(...)` 整段）替换为对共用方法的调用：

```java
    @JavascriptInterface
    public void handler(final String handlerName, final String data, final String callbackId) {
        dispatchToHandler(handlerName, callbackId, data);
    }
```

（注意：原实现里 `if (TextUtils.isEmpty(handlerName)) return;` 在 post 之前，`dispatchToHandler` 已保留该判断。）

同时在 `release()` 中加缓冲清理：

```java
    @Override
    public void release() {
        super.release();
        if (webViewRef != null) {
            webViewRef.clear();
        }
        this.mBridge = null;
        synchronized (mChunkAssembler) {
            mChunkAssembler.clear();
        }
    }
```

- [ ] **Step 2: 注入 JS `_doSend` 加分块发送**

`BridgeUtil.java` 的 `WebviewJavascriptBridge` 字符串中，定位 `_doSend` 函数里：

```javascript
if(typeof message === 'string'){lastMessage = message;}else{lastMessage = JSON.stringify(message);}
```

将其后、`if(moduleName=='jsbridge'&&handlerName!='response')` 之前，插入分块分支（压缩形式）：

```javascript
if(lastMessage.length>1048576){var tid='J_'+(uniqueId++)+'_'+new Date().getTime();var total=Math.ceil(lastMessage.length/524288);for(var ci=0;ci<total;ci++){var part=lastMessage.substring(ci*524288,(ci+1)*524288);window.jsbridge.receiveChunk(tid,ci,part);}window.jsbridge.commitChunked(handlerName,callbackId,tid,total);return;}
```

即 Java 常量里替换为：

```java
"             if(typeof message === 'string'){\n" +
"                 lastMessage = message;\n" +
"             }else{\n" +
"                 lastMessage = JSON.stringify(message);\n" +
"             };if(lastMessage.length>1048576){var tid='J_'+(uniqueId++)+'_'+new Date().getTime();var total=Math.ceil(lastMessage.length/524288);for(var ci=0;ci<total;ci++){var part=lastMessage.substring(ci*524288,(ci+1)*524288);window.jsbridge.receiveChunk(tid,ci,part);}window.jsbridge.commitChunked(handlerName,callbackId,tid,total);return;}if(moduleName=='jsbridge'&&handlerName!='response')..."
```

（保持原字符串的引号/换行风格，只插入分块分支；`WebviewJavascriptBridgeMin` **不动**——API<17 无 addJavascriptInterface，分块不可行。）

注意：`callbackId` 可能是空串（无回调场景），照常传递，与 `handler` 现有行为一致。

- [ ] **Step 3: 编译 + 全量单测**

Run: `./gradlew :jsbridge:assembleDebug :jsbridge:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 4: 真机验证 H5→原生 大数据**

demo.html 临时加按钮（验证后还原）：

```html
<input type="button" id="enter4" value="大数据上传测试" onclick="testBigUpload();" />
```

script 里加：

```javascript
function testBigUpload() {
    var big = '';
    for (var i = 0; i < 40000; i++) { big += '这是第' + i + '行测试数据，包含中文和emoji😀与特殊字符%\'\\；\n'; }
    window.WebViewJavascriptBridge.callHandler('toast', big, function(resp) { alert('resp len=' + (resp ? resp.length : 'null')); });
}
```

运行 app，点"大数据上传测试"：toast 应弹出（内容截断显示），无 OOM/ANR。（~1.3MB 中文文本，超过 1MB 阈值走分块。）

- [ ] **Step 5: 还原 demo.html + Commit**

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeUtil.java jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeJavascritInterface.java
git commit -m "二期: H5->原生 分块接收(receiveChunk/commitChunked + 注入JS分块发送)"
```

---

### Task 6: 资源清理与生命周期接线

**Files:**
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java`（freeMemory 清理待发分块）
- Modify: `jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeWebView.java`（destroy 调用，已有，确认即可）

**Interfaces:**
- Consumes: Task 4 `mMainHandler.postDelayed` 的待发 Runnable、Task 5 `mChunkAssembler`
- Produces: `BridgeTiny.freeMemory()` 额外 `mMainHandler.removeCallbacksAndMessages(null)`；原生→H5 分块无重组缓冲（JS 端自清理），H5→原生缓冲在 `release()` 已清（Task 5）

- [ ] **Step 1: freeMemory 加主线程队列清理**

`BridgeTiny.freeMemory()`（`BridgeTiny.java:271-287`）末尾加：

```java
        // 清掉尚未发出的分块 Runnable（页面销毁时残留块发出无效，直接移除）
        mMainHandler.removeCallbacksAndMessages(null);
```

- [ ] **Step 2: 确认 BridgeWebView.destroy 链路**

`BridgeWebView.destroy()`（`BridgeWebView.java:76-79`）已调用 `bridgeTiny.freeMemory()`，`BridgeJavascritInterface.release()` 由 `freeMemory()` 里的 `mJavascriptInterface.release()` 触发——Task 5 已在 release 中清理 assembler。核对三处调用链存在即可，无代码改动；若无则补。

- [ ] **Step 3: 编译 + 全量单测**

Run: `./gradlew :jsbridge:assembleDebug :jsbridge:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 4: 真机冒烟（destroy 后重进页面不崩）**

demo 反复进出 ProbeActivity 与主页面各 5 次，确认无崩溃、无 leak 警告（`adb logcat` 看 ActivityThread/leakcanary 类输出）。

- [ ] **Step 5: Commit**

```bash
git add jsbridge/src/main/java/com/smallbuer/jsbridge/core/BridgeTiny.java
git commit -m "二期: freeMemory 清理待发分块与主线程队列"
```

---

### Task 7: 探针对比验收（收尾）

**Files:**
- Modify: `docs/superpowers/plans/2026-09-07-baseline-report.md`（追加优化后数据）

**Interfaces:**
- Consumes: Task 0 探针（原样复用）、Task 1-6 全部改动
- Produces: 优化前后对比表，作为 spec §9 验收标准的证据

- [ ] **Step 1: 用探针重跑 4 档梯度**

同 Task 0 Step 4 操作，记录优化后数据。

- [ ] **Step 2: 追加对比报告**

在 `2026-09-07-baseline-report.md` 追加"优化后"章节：4 档 × {roundtrip, ΔPss, JS parse} 的前后对比表 + 结论（对照 spec §9：34MB 全链路原生峰值是否 ≈ 2×块+payload 级；小 payload 是否无回退；`%`/emoji/U+2028 完整性——在 probe.html 的 probe handler 里对收到的字符串算长度对比发送长度，理想情况一致）。

- [ ] **Step 3: 完整性校验**

在 ProbeActivity 临时加 SHA-256 校验（验证后删除）：发送前对 content 算 hash，`probe` handler 回传接收长度（34MB 字符串在 JS 端算 SHA-256 太慢，用长度+首尾 100 字符比对足够），确认一致。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-09-07-baseline-report.md
git commit -m "验收: 优化前后探针对比报告"
```

---

## Self-Review 结论

- **Spec 覆盖**：§4 转义链重写 → Task 1/2；§5.1 原生→H5 分块 → Task 4；§5.2 H5→原生 → Task 3/5；§5.3 清理 → Task 5(release)/Task 6(freeMemory)；§6 边界（乱序/重复/超时/页面导航）→ Task 3 测试 + Task 6 清理；§7 探针 → Task 0/7；§8 测试策略 → 各 Task TDD 步骤；§9 验收 → Task 7。无缺口。
- **占位符扫描**：无 TBD/TODO；所有代码步骤给出完整代码；"从 handler() 抽出共用方法"处给出了完整的 dispatchToHandler 全文（非引用）。
- **类型一致性**：`ChunkListener.onChunk(String)` Task 4 定义/使用一致；`ChunkAssembler.{putChunk,isComplete,assemble,expireOlderThan,clear}` Task 3 定义与 Task 5 使用一致。spec §5.3 的 60s 超时回收实现为：Task 5 `commitChunked` 每次顺手 `expireOlderThan(now - 60s)`（回收孤儿传输）+ `release()`/`freeMemory()` 全清（页面销毁）。已知限制：长驻页面、H5 发起分块但永不 commit 的极端情况，缓冲要等 60s 后的下一次 commitChunked 或页面销毁才回收——可接受，记录于此。
