"""Model catalog, pricing table, UX latency lines, and 9-group evaluation configuration."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ModelSpec:
    name: str
    provider: str  # "anthropic" | "openai" | "codebuddy"
    api_model: str
    input_per_m: float
    output_per_m: float
    cached_per_m: float
    batch_discount: float = 0.5
    supports_vision: bool = False
    supports_stream: bool = True
    price_source: str = "confirmed"
    api_equiv_input_per_m: float | None = None
    api_equiv_output_per_m: float | None = None


MODEL_CATALOG: dict[str, ModelSpec] = {
    "claude-haiku-5-5": ModelSpec(
        name="claude-haiku-5-5",
        provider="anthropic",
        api_model="claude-haiku-5-5",
        input_per_m=0.10,
        output_per_m=0.50,
        cached_per_m=0.01,
        supports_vision=True,
        price_source="confirmed:model_routes.json",
    ),
    "claude-sonnet-5-5": ModelSpec(
        name="claude-sonnet-5-5",
        provider="anthropic",
        api_model="claude-sonnet-5-5",
        input_per_m=2.00,
        output_per_m=10.00,
        cached_per_m=0.20,
        supports_vision=True,
        price_source="confirmed:model_routes.json",
    ),
    "claude-opus-5-5": ModelSpec(
        name="claude-opus-5-5",
        provider="anthropic",
        api_model="claude-opus-5-5",
        input_per_m=10.00,
        output_per_m=50.00,
        cached_per_m=1.00,
        supports_vision=True,
        price_source="estimated",
    ),
    "gpt-6-luna": ModelSpec(
        name="gpt-6-luna",
        provider="openai",
        api_model="gpt-6-luna",
        input_per_m=0.15,
        output_per_m=0.60,
        cached_per_m=0.015,
        supports_vision=True,
        price_source="estimated",
    ),
    "gpt-6.1-terra": ModelSpec(
        name="gpt-6.1-terra",
        provider="openai",
        api_model="gpt-6.1-terra",
        input_per_m=1.00,
        output_per_m=5.00,
        cached_per_m=0.05,
        supports_vision=True,
        price_source="confirmed:model_routes.json",
    ),
    "gpt-6.2-sol": ModelSpec(
        name="gpt-6.2-sol",
        provider="openai",
        api_model="gpt-6.2-sol",
        input_per_m=2.00,
        output_per_m=10.00,
        cached_per_m=0.10,
        supports_vision=True,
        price_source="confirmed:model_routes.json",
    ),
    "gpt-6.2-astra": ModelSpec(
        name="gpt-6.2-astra",
        provider="openai",
        api_model="gpt-6.2-astra",
        input_per_m=3.00,
        output_per_m=12.00,
        cached_per_m=0.15,
        supports_vision=True,
        price_source="estimated",
    ),
    "deepseek-v4.1-flash": ModelSpec(
        name="deepseek-v4.1-flash",
        provider="codebuddy",
        api_model="deepseek-v4.1-flash",
        input_per_m=0.0,
        output_per_m=0.0,
        cached_per_m=0.0,
        batch_discount=1.0,
        supports_vision=False,
        supports_stream=True,
        price_source="confirmed:codebuddy_subscription_marginal_0;api_offpeak=0.15/0.60",
        api_equiv_input_per_m=0.15,
        api_equiv_output_per_m=0.60,
    ),
}


@dataclass(frozen=True)
class GroupSpec:
    group_id: str
    title: str
    frequency: str  # "per_interaction" | "once_per_book" | "once_per_course" | "once_per_page"
    streaming: bool
    candidates: tuple[str, ...]
    current_model: str
    daily_calls_per_active_user: float
    role_type: str = "one_time"  # "interactive" | "hybrid" | "one_time"
    ux_ttft_ref_ms: int | None = None
    ux_total_ref_ms: int | None = None


EVAL_GROUPS: dict[str, GroupSpec] = {
    "G1": GroupSpec(
        group_id="G1",
        title="讲解和选中就问",
        frequency="per_interaction",
        streaming=True,
        candidates=(
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gpt-6-luna",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "deepseek-v4.1-flash",
        ),
        current_model="claude-haiku-5-5",
        daily_calls_per_active_user=12.0,
        role_type="interactive",
        ux_ttft_ref_ms=2500,
        ux_total_ref_ms=8000,
    ),
    "G2": GroupSpec(
        group_id="G2",
        title="整门课和跨书问答",
        frequency="per_interaction",
        streaming=True,
        candidates=(
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gpt-6-luna",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
            "deepseek-v4.1-flash",
        ),
        current_model="claude-haiku-5-5",
        daily_calls_per_active_user=4.0,
        role_type="interactive",
        ux_ttft_ref_ms=3500,
        ux_total_ref_ms=12000,
    ),
    "G3": GroupSpec(
        group_id="G3",
        title="章节伴读和六种学法预生成",
        frequency="once_per_book",
        streaming=False,
        candidates=("deepseek-v4.1-flash", "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "gpt-6.1-terra", "gpt-6.2-astra"),
        current_model="deepseek-v4.1-flash",
        daily_calls_per_active_user=12.0,
        role_type="hybrid",
        ux_ttft_ref_ms=None,
        ux_total_ref_ms=10000,
    ),
    "G4": GroupSpec(
        group_id="G4",
        title="书的结构和目录识别",
        frequency="once_per_book",
        streaming=False,
        candidates=("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "gpt-6-luna", "gpt-6.1-terra", "gpt-6.2-astra"),
        current_model="claude-haiku-5-5",
        daily_calls_per_active_user=0.1,
        role_type="one_time",
    ),
    "G5": GroupSpec(
        group_id="G5",
        title="出题和答案键",
        frequency="once_per_book",
        streaming=False,
        candidates=(
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
            "deepseek-v4.1-flash",
        ),
        current_model="claude-haiku-5-5",
        daily_calls_per_active_user=3.0,
        role_type="one_time",
    ),
    "G6": GroupSpec(
        group_id="G6",
        title="逐点批改与诊断",
        frequency="per_interaction",
        streaming=False,
        candidates=(
            "deepseek-v4.1-flash",
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
        ),
        current_model="deepseek-v4.1-flash",
        daily_calls_per_active_user=3.0,
        role_type="interactive",
        ux_ttft_ref_ms=None,
        ux_total_ref_ms=10000,
    ),
    "G7": GroupSpec(
        group_id="G7",
        title="课程规划与模考规划",
        frequency="once_per_course",
        streaming=False,
        candidates=("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "gpt-6.1-terra", "gpt-6.2-sol", "gpt-6.2-astra"),
        current_model="claude-sonnet-5-5",
        daily_calls_per_active_user=0.15,
        role_type="one_time",
    ),
    "G8a": GroupSpec(
        group_id="G8a",
        title="课件对齐与书单主题图",
        frequency="once_per_course",
        streaming=False,
        candidates=(
            "deepseek-v4.1-flash",
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gpt-6.1-terra",
            "gpt-6.2-sol",
            "gpt-6.2-astra",
        ),
        current_model="deepseek-v4.1-flash",
        daily_calls_per_active_user=0.5,
        role_type="one_time",
    ),
    "G8b": GroupSpec(
        group_id="G8b",
        title="视觉看图与扫描件OCR",
        frequency="once_per_page",
        streaming=False,
        candidates=("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "gpt-6-luna", "gpt-6.1-terra", "gpt-6.2-sol", "gpt-6.2-astra"),
        current_model="claude-haiku-5-5",
        daily_calls_per_active_user=0.5,
        role_type="one_time",
    ),
    "G9": GroupSpec(
        group_id="G9",
        title="数学核验和修复",
        frequency="once_per_book",
        streaming=False,
        candidates=("deepseek-v4.1-flash", "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "gpt-6.1-terra", "gpt-6.2-astra"),
        current_model="deepseek-v4.1-flash",
        daily_calls_per_active_user=1.0,
        role_type="one_time",
    ),
}


def compute_call_cost_usd(
    model_name: str,
    input_tokens: int,
    output_tokens: int,
    cached_tokens: int = 0,
    *,
    batch: bool = False,
    use_api_equiv_for_codebuddy: bool = False,
) -> float:
    spec = MODEL_CATALOG[model_name]
    in_rate = spec.input_per_m
    out_rate = spec.output_per_m
    if use_api_equiv_for_codebuddy and spec.provider == "codebuddy" and spec.api_equiv_input_per_m is not None:
        in_rate = spec.api_equiv_input_per_m
        out_rate = spec.api_equiv_output_per_m or 0.0
    safe_in = max(0, input_tokens)
    safe_cached = max(0, min(cached_tokens, safe_in))
    billable_in = safe_in - safe_cached
    usd = (billable_in * in_rate + safe_cached * spec.cached_per_m + max(0, output_tokens) * out_rate) / 1_000_000.0
    if batch:
        usd *= spec.batch_discount
    return usd
