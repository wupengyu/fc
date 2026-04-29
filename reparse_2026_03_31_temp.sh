#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   bash reparse_2026_03_31_temp.sh
#   DATE=2026-03-31 BASE_URL=http://127.0.0.1:8989/wechat bash reparse_2026_03_31_temp.sh
#   API_KEY=xxxx bash reparse_2026_03_31_temp.sh

DATE="${DATE:-2026-03-31}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8989/wechat}"
API_KEY="${API_KEY:-${API_AUTH_KEY:-}}"

if [[ ! "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "[ERROR] DATE 格式必须是 YYYY-MM-DD，当前: $DATE" >&2
  exit 1
fi

echo "[INFO] 目标日期: $DATE"
echo "[INFO] 服务地址: $BASE_URL"
echo "[INFO] 操作: 清理该日期旧AI解析结果并使用当前(新)API配置强制重跑"

auth_args=()
if [[ -n "$API_KEY" ]]; then
  auth_args=(-H "X-API-Key: $API_KEY")
  echo "[INFO] 已携带 X-API-Key"
else
  echo "[INFO] 未设置 API_KEY/API_AUTH_KEY，若服务开启鉴权会返回 401"
fi

echo "[STEP] 调用 /api/force-parse ..."
response=$(curl -sS -X POST "${BASE_URL}/api/force-parse?date=${DATE}" "${auth_args[@]}")

echo "[DONE] 接口返回:"
echo "$response"

echo "[TIP] 如需确认结果，可再查: ${BASE_URL}/api/runtime-status?date=${DATE}"
