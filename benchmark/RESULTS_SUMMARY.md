# Mixed20 Benchmark Results

## 数据集说明

- 样本文件：[`data/benchmark_cases_mixed20.json`](./data/benchmark_cases_mixed20.json)
- case 数：20
- 期望结果总数：71
- `SUCCESS` 条目：61
- `SKIP` 条目：10
- 口径：
  `strict` 要求 `SKIP.reason` 也一致；
  `relaxed` 忽略非 `SUCCESS` 条目的 `reason` 文案差异。

## 当前排行榜

按 `strict item recall` 排序：

| 排名 | 模型配置 | Provider | Prompt | Thinking | 成功 case | Strict exact | Strict recall | Relaxed exact | Relaxed recall | 平均延迟(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `gpt-5.4` | `zwenooo` | `system` | `auto` | `20/20` | `0.70` | `0.8451` | `0.85` | `0.8873` | `12896.55` |
| 2 | `glm-4-7-251222` | `ark` | `system` | `auto` | `19/20` | `0.70` | `0.7606` | `0.80` | `0.8028` | `67924.89` |
| 3 | `gpt-5.2-medium` | `packy` | `user` | `auto` | `20/20` | `0.50` | `0.6479` | `0.65` | `0.6901` | `14762.80` |
| 4 | `gpt-5.2-high` | `packy` | `user` | `auto` | `20/20` | `0.45` | `0.6338` | `0.60` | `0.6761` | `18669.40` |
| 5 | `qwen3.5-plus` | `dashscope` | `system` | `on` | `20/20` | `0.75` | `0.5352` | `0.75` | `0.5493` | `25932.95` |
| 6 | `qwen3.5-plus` | `dashscope` | `system` | `off` | `20/20` | `0.40` | `0.2535` | `0.50` | `0.2817` | `5794.50` |

## 结论

- 当前这组 `mixed20` 样本里，综合准确率最好的配置是 `gpt-5.4`。
  它的 `strict recall=0.8451`，`relaxed recall=0.8873`，并且 `20/20` 全部成功返回。
- `glm-4-7-251222` 准确率也很强，但平均延迟接近 `68s`，而且有 `1` 条 timeout。
- `gpt-5.2-medium` 在这组样本上比 `gpt-5.2-high` 更稳，速度也更快。
- `qwen3.5-plus` 开启 thinking 后，准确率提升非常明显，但平均延迟从 `5.8s` 提升到 `25.9s`。

## GPT-5.4 这轮的主要误差

来源文件：

- [`reports/zwenooo_gpt54_system.json`](./reports/zwenooo_gpt54_system.json)
- [`reports/zwenooo_gpt54_system_summary.json`](./reports/zwenooo_gpt54_system_summary.json)

主要偏差点：

- `raw_142_tc_multi_bundle`
  期望每个号码都是 `3单`，模型把单注翻倍成了 `6单`。
- `raw_18_mixed_success_and_skip`
  6 个成功项识别正确，但漏掉了 2 个应输出的 `SKIP` 项。
- `raw_153_skip_chat_result`
  语义上识别成了闲聊，但 `reason` 文案和标准集不一致。
- `raw_149_skip_stop_command`
  正确判断为非报单，不过 `reason` 文案更细化，导致 strict 不命中。
- `raw_146_skip_group_mix`
  这类“混合组玩法 + 复式”的消息仍容易被过度解析。
- `raw_122_skip_dudan`
  也是 reason 文案差异，放到 relaxed 口径下可视为命中。

## Prompt Placement 经验

- `dashscope / qwen3.5-plus`
  `system` 正常。
- `packy / gpt-5.2-*`
  `user` 更稳，system 跟随度偏弱。
- `ark / glm-4-7-251222`
  `system` 正常，但延迟高。
- `zwenooo / gpt-5.4`
  `system` 正常。

## 机器可读结果

完整机器可读榜单见：

- [`reports/model_leaderboard.json`](./reports/model_leaderboard.json)
