# LLM Benchmark

这个目录用于做“微信报单消息结构化识别”的离线基准测试，包含三类内容：

- 从本地 `wechat_msg` 数据库导出的真实样本与期望结果
- 独立的 Python 基准脚本 [`llm_benchmark.py`](./llm_benchmark.py)
- 已经跑过的模型报告、排行榜和人工可读汇总

## 目录内容

- `data/raw_message_sample_raw59.json`
  原始消息样本导出。
- `data/expected_result_set_raw59.json`
  对应的标准结果集导出。
- `data/benchmark_cases.json`
  全量基准 case 文件。
- `data/benchmark_cases_mixed20.json`
  当前主测集，20 条混合样本。
- `data/benchmark_cases_mixed20_summary.json`
  主测集摘要，便于快速查看样本构成。
- `reports/*.json`
  各模型的原始跑分报告和摘要。
- [`RESULTS_SUMMARY.md`](./RESULTS_SUMMARY.md)
  人工可读的当前结果汇总。
- [`reports/model_leaderboard.json`](./reports/model_leaderboard.json)
  机器可读排行榜，适合后续程序消费。

## 当前主测集

当前 `mixed20` 集合来自真实数据库导出，统计如下：

- case 数：20
- 期望结果总数：71
- `SUCCESS` 条目：61
- `SKIP` 条目：10
- 纯成功 case：11
- 纯跳过 case：8
- 混合 case：1

这 20 条里同时覆盖了：

- 单条直选、组三、组六、豹子、复式
- 多行合并报单、双彩种混合报单
- 停止指令、闲聊消息、定位/独胆等应跳过场景

## 评估口径

- `strict`
  完整比较标准化结果，`SKIP` 场景下也要求 `reason` 文案一致。
- `relaxed`
  仍要求 `index`、`valid`、`status` 一致，但对非 `SUCCESS` 条目忽略 `reason` 文案差异。

因此：

- `strict exact match ratio` = 20 条 case 中完全一致的比例
- `item level recall` = 71 条期望结果中，被模型正确命中的比例

## 脚本能力

[`llm_benchmark.py`](./llm_benchmark.py) 支持：

- OpenAI 兼容接口
- Anthropic `messages` 接口
- `--api-format openai|anthropic`
- `system` / `user` 两种 prompt 注入方式
- `thinking=on|off|auto`
- `--thinking-budget`
- `--reasoning-effort low|medium|high`
- `--retries`
- `--case-id` 过滤单条 case
- `--prompt-file` 替换提示词文件
- `--run-label` 写入报告标签
- `--report-file` 指定输出报告路径

默认 prompt 文件：

- [`src/main/resources/prompts/order-parse.prompt`](../src/main/resources/prompts/order-parse.prompt)

## 快速使用

下面命令默认从项目根目录执行。

### 1. 跑一个基础测试

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://your-provider.example/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model "YOUR_MODEL" `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --run-label "demo_run"
```

### 2. 对比 thinking 开关

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model qwen3.5-plus `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --thinking off `
  --run-label mixed20_thinking_off `
  --report-file benchmark/reports/mixed20_thinking_off.json
```

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model qwen3.5-plus `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --thinking on `
  --thinking-budget 1024 `
  --run-label mixed20_thinking_on `
  --report-file benchmark/reports/mixed20_thinking_on.json
```

### 3. 对比 GPT-5.2 Medium / High

这个 provider 路由下，`system` prompt 跟随度偏弱，建议把提示词内联到 `user` 消息里。

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://api-slb.packyapi.com/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model gpt-5.2-medium gpt-5.2-high `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement user `
  --run-label packy_gpt52_compare_user_prompt `
  --report-file benchmark/reports/packy_gpt52_compare_user_prompt.json
```

### 4. 跑 GPT-5.4

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://api-s.zwenooo.link/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model gpt-5.4 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --run-label zwenooo_gpt54_system `
  --report-file benchmark/reports/zwenooo_gpt54_system.json
```

### 4.1 对比 GPT-5.4 `reasoning.effort`

同一 provider 下可以只改变 `reasoning.effort`，直接横向比较 `low / medium / high`：

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://api-s.zwenooo.link/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model gpt-5.4 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --reasoning-effort low `
  --run-label zwenooo_gpt54_reasoning_low `
  --report-file benchmark/reports/zwenooo_gpt54_reasoning_low.json
```

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://api-s.zwenooo.link/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model gpt-5.4 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --reasoning-effort medium `
  --run-label zwenooo_gpt54_reasoning_medium `
  --report-file benchmark/reports/zwenooo_gpt54_reasoning_medium.json
```

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://api-s.zwenooo.link/v1/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model gpt-5.4 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --reasoning-effort high `
  --run-label zwenooo_gpt54_reasoning_high `
  --report-file benchmark/reports/zwenooo_gpt54_reasoning_high.json
```

### 5. 跑 Ark / GLM

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://ark.cn-beijing.volces.com/api/v3/chat/completions" `
  --api-key "YOUR_API_KEY" `
  --model glm-4-7-251222 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --run-label ark_glm4_7_251222_system `
  --report-file benchmark/reports/ark_glm4_7_251222_system.json
```

### 6. 对比 Claude Sonnet / Opus / Haiku

如果目标渠道使用的是 Anthropic `/v1/messages` 协议，需要显式指定 `--api-format anthropic`。

```powershell
python benchmark/llm_benchmark.py `
  --base-url "https://your-anthropic-proxy.example/api" `
  --api-key "YOUR_API_KEY" `
  --api-format anthropic `
  --model claude-sonnet-4-6 claude-opus-4-6 claude-haiku-4-5-20251001 `
  --cases-file benchmark/data/benchmark_cases_mixed20.json `
  --prompt-placement system `
  --retries 2 `
  --run-label anthropic_claude_compare_system `
  --report-file benchmark/reports/anthropic_claude_compare_system.json
```

## Provider 注意事项

- DashScope / `qwen3.5-plus`
  使用 `prompt-placement=system` 正常。
- Packy route / `gpt-5.2-medium`, `gpt-5.2-high`
  建议 `prompt-placement=user`，否则 system prompt 跟随度偏弱。
- Volcengine Ark / `glm-4-7-251222`
  使用 `prompt-placement=system` 正常，但延迟明显偏高。
- `api-s.zwenooo.link` / `gpt-5.4`
  使用 `prompt-placement=system` 正常，也支持传 `--reasoning-effort` 做 `low / medium / high` 对比。
- Anthropic `messages` 代理
  需要加 `--api-format anthropic`，认证头走 `x-api-key`。

## 当前结果文件

当前已经整理好的关键结果文件：

- [`RESULTS_SUMMARY.md`](./RESULTS_SUMMARY.md)
- [`reports/model_leaderboard.json`](./reports/model_leaderboard.json)
- [`reports/mixed20_thinking_compare_summary_recomputed.json`](./reports/mixed20_thinking_compare_summary_recomputed.json)
- [`reports/packy_gpt52_compare_user_prompt_summary.json`](./reports/packy_gpt52_compare_user_prompt_summary.json)
- [`reports/ark_glm4_7_251222_system_merged_summary.json`](./reports/ark_glm4_7_251222_system_merged_summary.json)
- [`reports/zwenooo_gpt54_system_summary.json`](./reports/zwenooo_gpt54_system_summary.json)

## 建议

如果你后面继续加模型，建议统一沿用：

- 同一份 `benchmark/data/benchmark_cases_mixed20.json`
- 同一份 prompt 文件
- 同样的 `strict` / `relaxed` 统计口径
- 报告文件名中保留 provider、model、prompt placement、thinking 配置

这样后续新增模型时，只需要把新报告再补进 [`reports/model_leaderboard.json`](./reports/model_leaderboard.json) 就能继续横向比较。
