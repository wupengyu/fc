# AI解析优化技术方案 v2

## 一、现状分析

### 当前架构
```
微信群消息
    ↓
RecvMsgGroupEvent
    ↓
OrderBufferService (缓冲)
    ↓
OrderDetector (内部过滤)  ← 问题：硬编码关键词/正则
    ↓
AiParseService (AI解析)   ← 问题：提示词硬编码
    ↓
NormalizePersistService
```

### 核心问题
1. `OrderDetector` 用关键词+正则做预过滤，维护成本高
2. 提示词硬编码在 `buildBatchPrompt()` 方法中
3. 修改规则需要改代码、重新部署

---

## 二、优化目标

| 目标 | 说明 |
|-----|------|
| 去内部解析 | 移除 OrderDetector，不在项目内做任何报单判断 |
| 提示词外部化 | 提示词存配置文件，支持热加载 |
| AI全权负责 | 判断是否报单 + 解析报单内容 全部由AI完成 |
| 易于迭代 | 后续优化只改提示词文件即可 |

---

## 三、新架构设计

### 3.1 简化后的流程

```
微信群消息
    ↓
RecvMsgGroupEvent
    ↓
MessageBufferService (缓冲所有消息)
    ↓
AiParseService
    ├─ 加载外部提示词
    ├─ 发送原始消息给AI
    ├─ AI返回: {是否报单, 解析结果}
    └─ 返回结构化结果
    ↓
NormalizePersistService (只存有效报单)
```

### 3.2 AI响应格式设计

```json
[
  {
    "index": 1,
    "valid": true,
    "status": "SUCCESS",
    "data": {
      "category": "FC",
      "game": "3D",
      "play": "直选",
      "zone": "MAIN",
      "numbers": ["512"],
      "bet": 4,
      "multiple": 1,
      "amount": 10.00
    }
  },
  {
    "index": 2,
    "valid": false,
    "status": "SKIP",
    "reason": "闲聊消息"
  }
]
```

**字段说明**：
- `valid`: AI判断是否为有效报单
- `status`: SUCCESS(解析成功) / FAIL(是报单但解析失败) / SKIP(非报单跳过)
- `data`: 解析出的结构化数据（仅valid=true时有）
- `reason`: 跳过或失败的原因

---

## 四、详细改动

### 4.1 新增文件

#### (1) 提示词模板文件

**路径**: `src/main/resources/prompts/order-parse.prompt`

```text
# 角色
你是彩票报单解析专家。

# 任务
分析消息列表，判断每条是否为有效报单，如果是则解析出结构化数据。

# 报单识别标准
有效报单必须包含：
1. 彩种信息（明确或可推断）
2. 投注号码
3. 金额或注数

非报单示例：闲聊、表情、图片描述、广告、红包消息等

# 彩种映射
| 关键词 | category | game |
|--------|----------|------|
| 3D/3d/福彩3D | FC | 3D |
| 双色球/ssq/SSQ | FC | SSQ |
| 七乐彩 | FC | QLQ |
| 排列三/排三/P3/p3 | TC | P3 |
| 排列五/排五/P5/p5 | TC | P5 |
| 大乐透/DLT/dlt | TC | DLT |

# 玩法识别
| 关键词 | play |
|--------|------|
| 直选/直 | 直选 |
| 组三/组3 | 组三 |
| 组六/组6 | 组六 |
| 复式 | 复式 |
| 无明确说明 | 直选 |

# 金额规则
- "米"="元", "块"="元"
- "单"="注", 每注2元
- 无金额时按注数×2计算

# 消息列表
${MESSAGES}

# 输出要求
返回JSON数组，每条消息对应一个结果：
- valid=true: 是报单，需包含data对象
- valid=false: 非报单，需包含reason

```json
[
  {"index":1,"valid":true,"status":"SUCCESS","data":{"category":"FC","game":"3D","play":"直选","zone":"MAIN","numbers":["512"],"bet":4,"multiple":1,"amount":10.00}},
  {"index":2,"valid":false,"status":"SKIP","reason":"表情消息"}
]
```

只输出JSON，不要其他文字。
```

#### (2) 提示词服务

**路径**: `src/main/java/cn/daenx/myadmin/modules/service/PromptService.java`

```java
@Service
@Slf4j
public class PromptService {

    @Value("${ai.prompt.path:classpath:prompts/order-parse.prompt}")
    private String promptPath;

    @Value("${ai.prompt.hot-reload:false}")
    private boolean hotReload;

    private volatile String cachedTemplate;
    private volatile long lastLoadTime;

    /**
     * 获取提示词模板
     */
    public String getTemplate() {
        if (cachedTemplate == null || (hotReload && shouldReload())) {
            cachedTemplate = loadTemplate();
            lastLoadTime = System.currentTimeMillis();
        }
        return cachedTemplate;
    }

    /**
     * 渲染提示词（替换变量）
     */
    public String render(Map<String, String> variables) {
        String template = getTemplate();
        for (Map.Entry<String, String> e : variables.entrySet()) {
            template = template.replace("${" + e.getKey() + "}", e.getValue());
        }
        return template;
    }

    /**
     * 手动刷新缓存
     */
    public void refresh() {
        cachedTemplate = null;
    }

    private boolean shouldReload() {
        // 每60秒检查一次
        return System.currentTimeMillis() - lastLoadTime > 60_000;
    }

    private String loadTemplate() {
        try {
            if (promptPath.startsWith("classpath:")) {
                // 从classpath加载
                String path = promptPath.substring("classpath:".length());
                return new String(getClass().getClassLoader()
                    .getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
            } else {
                // 从文件系统加载
                return Files.readString(Path.of(promptPath), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("加载提示词失败: {}", promptPath, e);
            throw new RuntimeException("提示词加载失败", e);
        }
    }
}
```

### 4.2 修改文件

#### (1) AiParseResult.java - 调整数据结构

```java
@Data
public class AiParseResult {
    private int index;
    private boolean valid;      // AI判断是否为有效报单
    private String status;      // SUCCESS / FAIL / SKIP
    private String reason;      // 失败或跳过的原因
    private ParsedData data;    // 解析数据（valid=true时有值）

    @Data
    public static class ParsedData {
        private String category;     // FC / TC
        private String game;         // 3D / SSQ / P3 / P5 / DLT / QLQ
        private String play;         // 直选 / 组三 / 组六
        private String zone;         // MAIN / RED / BLUE
        private List<String> numbers;
        private int bet;             // 注数
        private int multiple;        // 倍数
        private BigDecimal amount;   // 金额
    }

    // 便捷方法
    public boolean isSuccess() {
        return valid && "SUCCESS".equals(status);
    }

    public boolean isSkip() {
        return !valid && "SKIP".equals(status);
    }
}
```

#### (2) AiParseService.java - 核心改造

```java
@Service
@Slf4j
public class AiParseService {

    @Autowired
    private PromptService promptService;

    @Value("${ai.batch-size:20}")
    private int batchSize;

    /**
     * 批量解析（入口方法）
     */
    public List<AiParseResult> batchParse(List<OrderRaw> rawList) {
        if (rawList.isEmpty()) {
            return Collections.emptyList();
        }

        // 分批处理（避免单次请求过大）
        List<AiParseResult> allResults = new ArrayList<>();
        List<List<OrderRaw>> batches = partition(rawList, batchSize);

        for (List<OrderRaw> batch : batches) {
            List<AiParseResult> results = parseBatch(batch);
            allResults.addAll(results);
        }

        return allResults;
    }

    /**
     * 解析单批
     */
    private List<AiParseResult> parseBatch(List<OrderRaw> batch) {
        // 1. 构建消息文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
              .append(batch.get(i).getRawText())
              .append("\n");
        }

        // 2. 渲染提示词
        String prompt = promptService.render(Map.of("MESSAGES", sb.toString()));

        // 3. 调用AI
        String response = callAiApi(prompt);

        // 4. 解析响应
        return parseResponse(response, batch.size());
    }

    /**
     * 解析AI响应
     */
    private List<AiParseResult> parseResponse(String response, int expectedCount) {
        try {
            // 提取JSON（兼容markdown包裹）
            String json = extractJson(response);
            List<AiParseResult> results = JSON.parseArray(json, AiParseResult.class);

            // 校验数量
            if (results.size() != expectedCount) {
                log.warn("AI返回数量不匹配: expected={}, actual={}", expectedCount, results.size());
            }

            return results;
        } catch (Exception e) {
            log.error("解析AI响应失败: {}", response, e);
            // 全部标记为失败
            return createFailedResults(expectedCount, "响应解析失败: " + e.getMessage());
        }
    }

    private String extractJson(String text) {
        // 处理 ```json ... ``` 包裹
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.lastIndexOf("```");
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        // 处理 ``` ... ``` 包裹
        if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.lastIndexOf("```");
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        return text.trim();
    }

    // ... callAiApi 方法保持不变 ...
}
```

#### (3) OrderFlushTask.java - 简化流程

```java
@Component
@Slf4j
public class OrderFlushTask {

    @Autowired
    private OrderBufferService orderBufferService;

    // 移除: private OrderDetector orderDetector;

    @Autowired
    private OrderIngressService orderIngressService;

    @Autowired
    private AiParseService aiParseService;

    @Autowired
    private NormalizePersistService normalizePersistService;

    @Scheduled(fixedDelayString = "${order.flush-interval:15000}")
    public void flush() {
        // 1. 取出缓冲消息
        List<OrderMessage> messages = orderBufferService.swapOut();
        if (messages.isEmpty()) return;

        log.info("处理消息批次: count={}", messages.size());

        // 2. 【移除过滤】直接入库
        // 原代码: List<OrderMessage> orders = orderDetector.filter(messages);
        List<OrderRaw> rawList = orderIngressService.batchIngest(messages);
        if (rawList.isEmpty()) return;

        // 3. AI全权解析
        List<AiParseResult> results = aiParseService.batchParse(rawList);

        // 4. 持久化（内部根据valid判断）
        normalizePersistService.batchPersistAndStat(rawList, results);
    }
}
```

#### (4) NormalizePersistService.java - 增加valid判断

```java
@Transactional
public void batchPersistAndStat(List<OrderRaw> rawList, List<AiParseResult> results) {
    for (int i = 0; i < rawList.size(); i++) {
        OrderRaw raw = rawList.get(i);
        AiParseResult result = (i < results.size()) ? results.get(i) : null;

        // 结果缺失
        if (result == null) {
            saveParseBatch(raw, PARSE_STATUS_FAILED, "AI未返回结果");
            continue;
        }

        // 非报单 - 跳过
        if (result.isSkip()) {
            saveParseBatch(raw, PARSE_STATUS_SKIPPED, result.getReason());
            continue;
        }

        // 是报单但解析失败
        if (!result.isSuccess()) {
            saveParseBatch(raw, PARSE_STATUS_FAILED, result.getReason());
            continue;
        }

        // 解析成功 - 持久化
        persistValidOrder(raw, result);
    }
}

private void persistValidOrder(OrderRaw raw, AiParseResult result) {
    // 保存批次记录
    OrderParseBatch batch = saveParseBatch(raw, PARSE_STATUS_SUCCESS, null);

    // 保存订单项
    AiParseResult.ParsedData data = result.getData();
    OrderItem item = new OrderItem();
    item.setRawId(raw.getId());
    item.setBatchId(batch.getId());
    item.setLotteryCategory(data.getCategory());
    item.setGameType(data.getGame());
    item.setPlayType(data.getPlay());
    item.setBetCount(data.getBet());
    item.setMultiple(data.getMultiple());
    item.setTotalAmount(data.getAmount());
    // ... 保存 ...

    // 保存号码 & 更新统计
    // ...
}
```

#### (5) OrderConstant.java - 新增状态

```java
public class OrderConstant {
    public static final int PARSE_STATUS_SUCCESS = 1;  // 解析成功
    public static final int PARSE_STATUS_PARTIAL = 2;  // 部分成功
    public static final int PARSE_STATUS_FAILED = 3;   // 解析失败
    public static final int PARSE_STATUS_SKIPPED = 4;  // 非报单跳过（新增）
}
```

#### (6) application.yml - 配置项

```yaml
ai:
  base-url: "https://www.cpass.cc/v1/chat/completions"
  api-key: "sk-xxx"
  model: "gpt-5.2"
  timeout: 60000
  temperature: 0.1
  max-tokens: 4000

  # 提示词配置
  prompt:
    path: "classpath:prompts/order-parse.prompt"  # 或绝对路径
    hot-reload: false  # 生产环境建议false

  # 批处理配置
  batch-size: 20  # 每批最大消息数
```

---

## 五、文件改动汇总

| 文件 | 操作 | 说明 |
|-----|------|------|
| `prompts/order-parse.prompt` | **新增** | 提示词模板 |
| `PromptService.java` | **新增** | 提示词加载服务 |
| `AiParseResult.java` | 修改 | 调整字段结构 |
| `AiParseService.java` | 修改 | 使用外部提示词 |
| `OrderFlushTask.java` | 修改 | 移除OrderDetector |
| `NormalizePersistService.java` | 修改 | 增加valid判断 |
| `OrderConstant.java` | 修改 | 新增SKIPPED状态 |
| `application.yml` | 修改 | 新增prompt配置 |
| `OrderDetector.java` | 废弃 | 不再使用 |

---

## 六、提示词迭代示例

优化后，修改解析规则只需编辑 `order-parse.prompt` 文件：

**示例1: 新增彩种**
```diff
# 彩种映射
| 关键词 | category | game |
|--------|----------|------|
| 3D/3d/福彩3D | FC | 3D |
+ | 快三/快3/K3 | FC | K3 |
```

**示例2: 增加容错规则**
```diff
# 金额规则
- "米"="元", "块"="元"
+ "米"="元", "块"="元", "刀"="元"
+ 金额前有"共"字时取后面的数字
```

**示例3: 优化识别准确率**
```diff
# 报单识别标准
有效报单必须包含：
1. 彩种信息（明确或可推断）
2. 投注号码
3. 金额或注数

+ # 特殊情况处理
+ - 纯3位数字如"512"默认为3D直选
+ - "红球xx蓝球xx"格式默认为双色球
```

---

## 七、实施步骤

```
1. 新增 prompts/order-parse.prompt
2. 新增 PromptService.java
3. 修改 AiParseResult.java
4. 修改 AiParseService.java
5. 修改 OrderFlushTask.java
6. 修改 NormalizePersistService.java
7. 修改 OrderConstant.java
8. 修改 application.yml
9. 测试验证
```

---

## 八、验收标准

1. 所有微信消息都发送给AI，不经过内部过滤
2. 提示词从配置文件加载，非硬编码
3. AI返回的valid=false消息不入库到订单表
4. 修改提示词文件后，重启生效（或热加载生效）
5. 现有功能（报单解析、统计）正常工作
