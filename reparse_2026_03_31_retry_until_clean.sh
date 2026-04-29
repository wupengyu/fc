#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   bash reparse_2026_03_31_retry_until_clean.sh
#   MAX_RETRY=5 SLEEP_SECONDS=8 bash reparse_2026_03_31_retry_until_clean.sh
#   API_KEY=xxxx bash reparse_2026_03_31_retry_until_clean.sh

DATE="${DATE:-2026-03-31}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8989/wechat}"
MAX_RETRY="${MAX_RETRY:-5}"
SLEEP_SECONDS="${SLEEP_SECONDS:-8}"
API_KEY="${API_KEY:-${API_AUTH_KEY:-}}"

if [[ ! "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "[ERROR] DATE 格式必须是 YYYY-MM-DD，当前: $DATE" >&2
  exit 1
fi
if [[ ! "$MAX_RETRY" =~ ^[0-9]+$ ]] || [[ "$MAX_RETRY" -lt 1 ]]; then
  echo "[ERROR] MAX_RETRY 必须是正整数，当前: $MAX_RETRY" >&2
  exit 1
fi
if [[ ! "$SLEEP_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "[ERROR] SLEEP_SECONDS 必须是非负整数，当前: $SLEEP_SECONDS" >&2
  exit 1
fi

auth_args=()
if [[ -n "$API_KEY" ]]; then
  auth_args=(-H "X-API-Key: $API_KEY")
fi

echo "[INFO] date=$DATE, baseUrl=$BASE_URL, maxRetry=$MAX_RETRY, sleepSeconds=$SLEEP_SECONDS"

attempt=1
while [[ "$attempt" -le "$MAX_RETRY" ]]; do
  echo "[STEP] 第 ${attempt}/${MAX_RETRY} 次强制重跑..."
  response=$(curl -sS -X POST "${BASE_URL}/api/force-parse?date=${DATE}" "${auth_args[@]}")
  echo "[RESP] $response"

  data_field=$(printf '%s' "$response" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')
  fail_count=$(printf '%s' "$data_field" | sed -n 's/.*失败=\([0-9][0-9]*\).*/\1/p')

  if [[ -z "$fail_count" ]]; then
    echo "[WARN] 未能从返回中解析失败数，停止自动重试。"
    exit 2
  fi

  if [[ "$fail_count" -eq 0 ]]; then
    echo "[DONE] 已无失败记录，结束。"
    exit 0
  fi

  echo "[INFO] 当前失败数=$fail_count"
  if [[ "$attempt" -lt "$MAX_RETRY" ]] && [[ "$SLEEP_SECONDS" -gt 0 ]]; then
    echo "[INFO] 等待 ${SLEEP_SECONDS}s 后继续重试..."
    sleep "$SLEEP_SECONDS"
  fi

  attempt=$((attempt + 1))
done

echo "[END] 已达到最大重试次数，仍有失败记录。"
exit 3
