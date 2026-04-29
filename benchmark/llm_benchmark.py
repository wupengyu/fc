#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Any
from urllib import error, request


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CASES_PATH = SCRIPT_DIR / "data" / "benchmark_cases.json"
DEFAULT_PROMPT_PATH = SCRIPT_DIR.parent / "src" / "main" / "resources" / "prompts" / "order-parse.prompt"
DEFAULT_REPORT_DIR = SCRIPT_DIR / "reports"


def configure_stdout() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Benchmark different LLMs against exported lottery parsing cases."
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("OPENAI_BASE_URL") or os.getenv("LLM_BASE_URL"),
        help="Provider base URL or full endpoint URL.",
    )
    parser.add_argument(
        "--api-key",
        default=os.getenv("OPENAI_API_KEY") or os.getenv("LLM_API_KEY"),
        help="API key for the target provider.",
    )
    parser.add_argument(
        "--api-format",
        choices=["openai", "anthropic"],
        default="openai",
        help="Request/response format to use. Default: openai",
    )
    parser.add_argument(
        "--model",
        nargs="+",
        required=True,
        help="One or more model names to compare.",
    )
    parser.add_argument(
        "--cases-file",
        type=Path,
        default=DEFAULT_CASES_PATH,
        help=f"Benchmark case file. Default: {DEFAULT_CASES_PATH}",
    )
    parser.add_argument(
        "--prompt-file",
        type=Path,
        default=DEFAULT_PROMPT_PATH,
        help=f"System prompt file. Default: {DEFAULT_PROMPT_PATH}",
    )
    parser.add_argument(
        "--case-id",
        action="append",
        help="Only run the specified case_id. Can be passed multiple times.",
    )
    parser.add_argument(
        "--prompt-placement",
        choices=["system", "user"],
        default="system",
        help="Where to place the parsing prompt. Default: system",
    )
    parser.add_argument(
        "--repeats",
        type=int,
        default=1,
        help="How many times to run each case for each model. Default: 1",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=0.1,
        help="Sampling temperature. Default: 0.1",
    )
    parser.add_argument(
        "--max-tokens",
        type=int,
        default=16000,
        help="Max output tokens. Default: 16000",
    )
    parser.add_argument(
        "--thinking",
        choices=["auto", "on", "off"],
        default="auto",
        help="Whether to explicitly enable provider thinking mode. Default: auto",
    )
    parser.add_argument(
        "--thinking-budget",
        type=int,
        default=1024,
        help="Thinking budget to send when --thinking=on. Default: 1024",
    )
    parser.add_argument(
        "--reasoning-effort",
        choices=["low", "medium", "high"],
        help="Optional reasoning.effort value for providers that support GPT-5 reasoning controls.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=300,
        help="HTTP timeout in seconds. Default: 300",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=0,
        help="How many times to retry a failed API call. Default: 0",
    )
    parser.add_argument(
        "--run-label",
        help="Optional label written into the report, for example thinking_on or thinking_off.",
    )
    parser.add_argument(
        "--report-file",
        type=Path,
        help="Optional output report path. Default: benchmark/reports/benchmark_report_<timestamp>.json",
    )
    return parser.parse_args()


def resolve_chat_completions_url(base_url: str) -> str:
    trimmed = base_url.rstrip("/")
    if trimmed.endswith("/chat/completions"):
        return trimmed
    return f"{trimmed}/chat/completions"


def resolve_anthropic_messages_url(base_url: str) -> str:
    trimmed = base_url.rstrip("/")
    if trimmed.endswith("/v1/messages"):
        return trimmed
    return f"{trimmed}/v1/messages"


def load_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_cases(path: Path, selected_case_ids: list[str] | None) -> list[dict[str, Any]]:
    cases = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(cases, list):
        raise ValueError("cases file must be a JSON array")
    if selected_case_ids:
        allowed = set(selected_case_ids)
        cases = [case for case in cases if case.get("case_id") in allowed]
    if not cases:
        raise ValueError("no benchmark cases matched the current filter")
    return cases


def build_user_message(message: str) -> str:
    return f"请解析以下消息：\n[1] {message}"


def build_inlined_user_message(system_prompt: str, message: str) -> str:
    return (
        f"{system_prompt}\n\n"
        "现在只做结构化抽取任务，不要聊天，不要澄清，不要解释，不要建议。"
        "只返回 JSON 数组。\n"
        f"消息如下：\n[1] {message}"
    )


def extract_text_content(message_content: Any) -> str:
    if isinstance(message_content, str):
        return message_content
    if isinstance(message_content, list):
        parts: list[str] = []
        for item in message_content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text", "")))
        return "".join(parts)
    return str(message_content)


def extract_response_content(response_json: dict[str, Any]) -> str:
    error_obj = response_json.get("error")
    if isinstance(error_obj, dict):
        error_type = error_obj.get("type") or "error"
        error_message = error_obj.get("message") or json.dumps(error_obj, ensure_ascii=False)
        raise RuntimeError(f"{error_type}: {error_message}")

    choices = response_json.get("choices")
    if isinstance(choices, list) and choices:
        message = choices[0].get("message", {})
        return extract_text_content(message.get("content", ""))
    if "content" in response_json:
        return extract_text_content(response_json["content"])
    if "response" in response_json:
        return str(response_json["response"])
    raise ValueError("unsupported response format")


def normalize_usage(response_json: dict[str, Any]) -> dict[str, Any] | None:
    usage = response_json.get("usage")
    if not isinstance(usage, dict):
        return None

    prompt_tokens = usage.get("prompt_tokens")
    completion_tokens = usage.get("completion_tokens")

    if prompt_tokens is None and "input_tokens" in usage:
        prompt_tokens = usage.get("input_tokens")
    if completion_tokens is None and "output_tokens" in usage:
        completion_tokens = usage.get("output_tokens")

    normalized = dict(usage)
    normalized["prompt_tokens"] = int(prompt_tokens or 0)
    normalized["completion_tokens"] = int(completion_tokens or 0)
    normalized["total_tokens"] = normalized["prompt_tokens"] + normalized["completion_tokens"]
    return normalized


def should_retry_exception(exc: Exception) -> bool:
    message = str(exc)
    retry_markers = [
        "HTTP 429",
        "HTTP 500",
        "HTTP 502",
        "HTTP 503",
        "HTTP 504",
        "timed out",
        "timeout",
        "internal_error",
        "overloaded_error",
        "rate_limit",
        "temporarily unavailable",
    ]
    lowered = message.lower()
    return any(marker.lower() in lowered for marker in retry_markers)


def call_with_retry(
    func: Any,
    retries: int,
    retry_delay_seconds: float = 2.0,
) -> ApiCallResult:
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return func()
        except Exception as exc:
            last_error = exc
            if attempt >= retries or not should_retry_exception(exc):
                raise
            time.sleep(retry_delay_seconds * (attempt + 1))
    if last_error is not None:
        raise last_error
    raise RuntimeError("call_with_retry reached an unexpected state")


def extract_json_block(text: str) -> str:
    candidate = text.strip()
    if "</think>" in candidate:
        candidate = candidate.split("</think>", 1)[1].strip()
    if "```json" in candidate:
        start = candidate.index("```json") + len("```json")
        end = candidate.rfind("```")
        if end > start:
            return candidate[start:end].strip()
    if "```" in candidate:
        start = candidate.index("```") + len("```")
        end = candidate.rfind("```")
        if end > start:
            return candidate[start:end].strip()
    array_start = candidate.find("[")
    array_end = candidate.rfind("]")
    if array_start >= 0 and array_end > array_start:
        return candidate[array_start:array_end + 1].strip()
    return candidate


def decimal_string(value: Any) -> str:
    try:
        decimal_value = Decimal(str(value)).quantize(Decimal("0.00"), rounding=ROUND_HALF_UP)
    except (InvalidOperation, ValueError):
        decimal_value = Decimal("0.00")
    return format(decimal_value, "f")


def normalize_success_data(data: dict[str, Any]) -> dict[str, Any]:
    numbers = data.get("numbers") or []
    if not isinstance(numbers, list):
        numbers = [numbers]
    return {
        "category": str(data.get("category") or ""),
        "game": str(data.get("game") or ""),
        "play": str(data.get("play") or ""),
        "zone": str(data.get("zone") or "MAIN"),
        "numbers": [str(number) for number in numbers],
        "bet": int(data.get("bet") or 0),
        "multiple": int(data.get("multiple") or 1),
        "amount": decimal_string(data.get("amount", 0)),
    }


def normalize_result(item: dict[str, Any]) -> dict[str, Any]:
    normalized = {
        "index": int(item.get("index") or 1),
        "valid": bool(item.get("valid")),
        "status": str(item.get("status") or "").upper(),
    }
    data = item.get("data")
    if normalized["status"] == "SUCCESS" and isinstance(data, dict):
        normalized["data"] = normalize_success_data(data)
    else:
        normalized["reason"] = str(item.get("reason") or "").strip()
        if isinstance(data, dict):
            normalized["data"] = normalize_success_data(data)
    return normalized


def normalize_results(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [normalize_result(item) for item in items]


def result_signature(item: dict[str, Any]) -> str:
    payload = {
        "index": item["index"],
        "valid": item["valid"],
        "status": item["status"],
    }
    if item.get("data") is not None:
        payload["data"] = item["data"]
    if item.get("reason"):
        payload["reason"] = item["reason"]
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)


def compare_results(expected: list[dict[str, Any]], actual: list[dict[str, Any]]) -> dict[str, Any]:
    expected_counter = Counter(result_signature(item) for item in expected)
    actual_counter = Counter(result_signature(item) for item in actual)
    matched_counter = expected_counter & actual_counter
    missing_counter = expected_counter - actual_counter
    extra_counter = actual_counter - expected_counter

    expected_count = sum(expected_counter.values())
    matched_count = sum(matched_counter.values())
    extra_count = sum(extra_counter.values())
    missing_count = sum(missing_counter.values())

    return {
        "expected_count": expected_count,
        "actual_count": sum(actual_counter.values()),
        "matched_count": matched_count,
        "missing_count": missing_count,
        "extra_count": extra_count,
        "accuracy_ratio": round((matched_count / expected_count) if expected_count else 1.0, 4),
        "exact_match": expected_counter == actual_counter,
        "missing_items": list(missing_counter.elements()),
        "extra_items": list(extra_counter.elements()),
    }


@dataclass
class ApiCallResult:
    latency_ms: int
    response_json: dict[str, Any]
    content: str


def call_chat_completions(
    url: str,
    api_key: str,
    model: str,
    system_prompt: str,
    message: str,
    prompt_placement: str,
    temperature: float,
    max_tokens: int,
    thinking_mode: str,
    thinking_budget: int,
    reasoning_effort: str | None,
    timeout_seconds: int,
) -> ApiCallResult:
    if prompt_placement == "user":
        messages = [
            {"role": "user", "content": build_inlined_user_message(system_prompt, message)},
        ]
    else:
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": build_user_message(message)},
        ]

    payload = {
        "model": model,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "messages": messages,
    }
    if thinking_mode != "auto":
        payload["enable_thinking"] = thinking_mode == "on"
        if thinking_mode == "on":
            payload["thinking_budget"] = thinking_budget
    if reasoning_effort:
        payload["reasoning"] = {"effort": reasoning_effort}
    request_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    http_request = request.Request(
        url=url,
        data=request_body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "x-dashscope-session-cache": "enable",
        },
        method="POST",
    )

    started_at = time.perf_counter()
    try:
        with request.urlopen(http_request, timeout=timeout_seconds) as response:
            response_text = response.read().decode("utf-8")
    except error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    except error.URLError as exc:
        raise RuntimeError(f"request failed: {exc}") from exc

    latency_ms = round((time.perf_counter() - started_at) * 1000)
    response_json = json.loads(response_text)
    content = extract_response_content(response_json)
    return ApiCallResult(latency_ms=latency_ms, response_json=response_json, content=content)


def call_anthropic_messages(
    url: str,
    api_key: str,
    model: str,
    system_prompt: str,
    message: str,
    prompt_placement: str,
    temperature: float,
    max_tokens: int,
    timeout_seconds: int,
) -> ApiCallResult:
    if prompt_placement == "user":
        system_value = None
        user_content = build_inlined_user_message(system_prompt, message)
    else:
        system_value = system_prompt
        user_content = build_user_message(message)

    payload: dict[str, Any] = {
        "model": model,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "messages": [
            {
                "role": "user",
                "content": user_content,
            }
        ],
    }
    if system_value:
        payload["system"] = system_value

    request_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    http_request = request.Request(
        url=url,
        data=request_body,
        headers={
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    started_at = time.perf_counter()
    try:
        with request.urlopen(http_request, timeout=timeout_seconds) as response:
            response_text = response.read().decode("utf-8")
    except error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    except error.URLError as exc:
        raise RuntimeError(f"request failed: {exc}") from exc

    latency_ms = round((time.perf_counter() - started_at) * 1000)
    response_json = json.loads(response_text)
    content = extract_response_content(response_json)
    return ApiCallResult(latency_ms=latency_ms, response_json=response_json, content=content)


def parse_model_output(content: str) -> list[dict[str, Any]]:
    json_block = extract_json_block(content)
    parsed = json.loads(json_block)
    if not isinstance(parsed, list):
        raise ValueError("model output must be a JSON array")
    normalized_items: list[dict[str, Any]] = []
    for item in parsed:
        if not isinstance(item, dict):
            raise ValueError("every item in model output must be a JSON object")
        normalized_items.append(item)
    return normalized_items


def now_timestamp() -> str:
    return time.strftime("%Y%m%d_%H%M%S")


def summarize_model_runs(model_runs: list[dict[str, Any]]) -> dict[str, Any]:
    successful_runs = [run for run in model_runs if run["status"] == "ok"]
    latencies = [run["latency_ms"] for run in successful_runs]
    exact_matches = sum(1 for run in successful_runs if run["comparison"]["exact_match"])
    return {
        "run_count": len(model_runs),
        "successful_run_count": len(successful_runs),
        "failed_run_count": len(model_runs) - len(successful_runs),
        "exact_match_count": exact_matches,
        "avg_latency_ms": round(sum(latencies) / len(latencies), 2) if latencies else None,
        "min_latency_ms": min(latencies) if latencies else None,
        "max_latency_ms": max(latencies) if latencies else None,
    }


def ensure_parent_dir(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def main() -> int:
    configure_stdout()
    args = parse_args()

    if not args.base_url:
        raise SystemExit("missing --base-url or OPENAI_BASE_URL / LLM_BASE_URL")
    if not args.api_key:
        raise SystemExit("missing --api-key or OPENAI_API_KEY / LLM_API_KEY")
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")

    if args.api_format == "anthropic":
        url = resolve_anthropic_messages_url(args.base_url)
    else:
        url = resolve_chat_completions_url(args.base_url)
    system_prompt = load_text(args.prompt_file)
    cases = load_cases(args.cases_file, args.case_id)

    report_file = args.report_file
    if report_file is None:
        report_file = DEFAULT_REPORT_DIR / f"benchmark_report_{now_timestamp()}.json"
    ensure_parent_dir(report_file)

    all_runs: list[dict[str, Any]] = []
    summary_by_model: dict[str, Any] = {}

    for model in args.model:
        model_runs: list[dict[str, Any]] = []
        print(
            f"\n=== model: {model}, api_format: {args.api_format}, thinking: {args.thinking}, "
            f"reasoning_effort: {args.reasoning_effort or 'default'} ==="
        )
        for case in cases:
            case_id = str(case.get("case_id"))
            message = str(case["input"]["message"])
            expected = normalize_results(case.get("expected_results", []))

            for repeat_index in range(1, args.repeats + 1):
                run_record: dict[str, Any] = {
                    "model": model,
                    "case_id": case_id,
                    "repeat_index": repeat_index,
                    "api_format": args.api_format,
                    "thinking": args.thinking,
                    "reasoning_effort": args.reasoning_effort,
                    "retries": args.retries,
                    "run_label": args.run_label,
                }
                try:
                    if args.api_format == "anthropic":
                        api_result = call_with_retry(
                            lambda: call_anthropic_messages(
                                url=url,
                                api_key=args.api_key,
                                model=model,
                                system_prompt=system_prompt,
                                message=message,
                                prompt_placement=args.prompt_placement,
                                temperature=args.temperature,
                                max_tokens=args.max_tokens,
                                timeout_seconds=args.timeout,
                            ),
                            retries=args.retries,
                        )
                    else:
                        api_result = call_with_retry(
                            lambda: call_chat_completions(
                                url=url,
                                api_key=args.api_key,
                                model=model,
                                system_prompt=system_prompt,
                                message=message,
                                prompt_placement=args.prompt_placement,
                                temperature=args.temperature,
                                max_tokens=args.max_tokens,
                                thinking_mode=args.thinking,
                                thinking_budget=args.thinking_budget,
                                reasoning_effort=args.reasoning_effort,
                                timeout_seconds=args.timeout,
                            ),
                            retries=args.retries,
                        )
                    parsed_output = parse_model_output(api_result.content)
                    actual = normalize_results(parsed_output)
                    comparison = compare_results(expected, actual)

                    run_record.update(
                        {
                            "status": "ok",
                            "latency_ms": api_result.latency_ms,
                            "comparison": comparison,
                            "usage": normalize_usage(api_result.response_json),
                            "raw_response_text": api_result.content,
                            "raw_response_json": api_result.response_json,
                            "normalized_output": actual,
                        }
                    )
                    print(
                        f"{case_id} #{repeat_index}: latency={api_result.latency_ms}ms, "
                        f"exact_match={comparison['exact_match']}, "
                        f"matched={comparison['matched_count']}/{comparison['expected_count']}"
                    )
                except Exception as exc:
                    run_record.update(
                        {
                            "status": "error",
                            "error": str(exc),
                        }
                    )
                    print(f"{case_id} #{repeat_index}: error={exc}")

                model_runs.append(run_record)
                all_runs.append(run_record)

        summary_by_model[model] = summarize_model_runs(model_runs)

    report = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "base_url": url,
        "api_format": args.api_format,
        "cases_file": str(args.cases_file),
        "prompt_file": str(args.prompt_file),
        "models": args.model,
        "run_label": args.run_label,
        "repeats": args.repeats,
        "prompt_placement": args.prompt_placement,
        "thinking": args.thinking,
        "thinking_budget": args.thinking_budget if args.thinking == "on" else None,
        "reasoning_effort": args.reasoning_effort,
        "retries": args.retries,
        "summary_by_model": summary_by_model,
        "runs": all_runs,
    }

    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nreport written to: {report_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
