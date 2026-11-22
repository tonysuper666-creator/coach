#!/usr/bin/env python3
"""Safe symbolic & exact-rational verifier for LearningCoach 2.0 companion math checks.

Reads one JSON payload from stdin:
  {"checks": [ ... ]}
Writes one JSON response to stdout:
  {"engine": "sympy", "sympyVersion": "...", "results": [ ... ]}

Security: never uses eval/exec on arbitrary Python; parses expressions strictly through
SymPy's parse_expr with an explicit allowlist of math symbols and functions.
"""
from __future__ import annotations

import json
import re
import sys
import unicodedata
from typing import Any

import sympy as sp
from sympy.parsing.sympy_parser import (
    convert_xor,
    implicit_multiplication_application,
    parse_expr,
    standard_transformations,
)

TRANSFORMATIONS = standard_transformations + (
    implicit_multiplication_application,
    convert_xor,
)

MAX_EXPR_LEN = 400
FORBIDDEN_RE = re.compile(
    r"__|import|lambda|open|exec|eval|compile|getattr|setattr|delattr|globals|locals|builtins|"
    r"[;\"'`\\\[\]{}]"
)
IDENT_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9_]*\b")
PERCENT_NUM_RE = re.compile(r"(?<![A-Za-z0-9_])(\d+(?:\.\d+)?)\s*%")
DEC_NUM_RE = re.compile(r"(?<![A-Za-z0-9_.])(\d+\.\d+)(?![A-Za-z0-9_.])")

SAFE_FUNCS: dict[str, Any] = {
    "sqrt": sp.sqrt,
    "abs": sp.Abs,
    "Abs": sp.Abs,
    "log": sp.log,
    "ln": sp.log,
    "exp": sp.exp,
    "min": sp.Min,
    "Min": sp.Min,
    "max": sp.Max,
    "Max": sp.Max,
    "Rational": sp.Rational,
    "pi": sp.pi,
    "E": sp.E,
}


def normalize_math_text(raw: str) -> str:
    s = unicodedata.normalize("NFKC", str(raw or "")).strip()
    replacements = [
        ("−", "-"),
        ("–", "-"),
        ("—", "-"),
        ("×", "*"),
        ("·", "*"),
        ("⋅", "*"),
        ("÷", "/"),
        ("≤", "<="),
        ("≥", ">="),
        ("≠", "!="),
        ("（", "("),
        ("）", ")"),
        ("$", ""),
        ("£", ""),
        ("€", ""),
        ("¥", ""),
        ("Δ", "Delta_"),
        ("π", "pi_var"),
        ("μ", "mu"),
        ("σ", "sigma"),
        ("θ", "theta"),
        ("α", "alpha"),
        ("β", "beta"),
        ("γ", "gamma"),
        ("λ", "lambda_var"),
        ("ε", "epsilon"),
        ("ρ", "rho"),
        ("η", "eta"),
    ]
    for a, b in replacements:
        s = s.replace(a, b)
    # Remove thousands separators like 15,000 -> 15000
    s = re.sub(r"(?<=\d),(?=\d{3}(?!\d))", "", s)
    # Replace E[X] / E(X) style expected value notation with symbol E_X
    s = re.sub(r"\bE\s*\[\s*([A-Za-z][A-Za-z0-9_]*)\s*\]", r"E_\1", s)
    # Convert 40% -> (40/100) and (100 - q)% -> (100 - q)/100
    s = PERCENT_NUM_RE.sub(r"(\1/100)", s)
    s = re.sub(r"\)\s*%", ")/100", s)
    return s.strip()


def convert_decimals_to_rationals(expr_str: str) -> str:
    def _to_rat(m: re.Match[str]) -> str:
        tok = m.group(1)
        int_p, frac_p = tok.split(".")
        num = int(int_p + frac_p)
        den = 10 ** len(frac_p)
        return f"Rational({num},{den})"

    return DEC_NUM_RE.sub(_to_rat, expr_str)


def safe_parse(raw: str, real_symbols: bool = True) -> sp.Expr:
    norm = normalize_math_text(raw)
    if not norm or len(norm) > MAX_EXPR_LEN:
        raise ValueError("empty_or_too_long_expression")
    if FORBIDDEN_RE.search(norm):
        raise ValueError("forbidden_token_in_expression")
    # Reject attribute access like x.y (decimals are already converted to Rational)
    rat_expr = convert_decimals_to_rationals(norm)
    if "." in rat_expr:
        raise ValueError("unexpected_dot_in_expression")

    local_dict: dict[str, Any] = dict(SAFE_FUNCS)
    for ident in set(IDENT_RE.findall(rat_expr)):
        if ident not in local_dict:
            local_dict[ident] = sp.Symbol(ident, real=real_symbols)

    safe_globals: dict[str, Any] = {
        "__builtins__": {},
        "Integer": sp.Integer,
        "Float": sp.Float,
        "Rational": sp.Rational,
        "Symbol": sp.Symbol,
        "Function": sp.Function,
    }
    parsed = parse_expr(
        rat_expr,
        local_dict=local_dict,
        global_dict=safe_globals,
        transformations=TRANSFORMATIONS,
        evaluate=True,
    )
    if not isinstance(parsed, sp.Basic):
        raise ValueError("parsed_result_not_sympy_expr")
    return parsed


def format_exact(val: sp.Expr) -> str:
    simp = sp.simplify(val)
    return str(simp)


def format_decimal(val: sp.Expr) -> str:
    try:
        num = sp.N(val, 10)
        if num.is_real:
            return f"{float(num):.6f}".rstrip("0").rstrip(".")
    except Exception:
        pass
    return str(val)


def expressions_equal(a: sp.Expr, b: sp.Expr, tolerance: float = 0.015) -> tuple[bool, str]:
    diff = sp.simplify(a - b)
    if diff == 0:
        return True, "exact_symbolic_zero"
    expanded = sp.expand(a - b)
    if expanded == 0:
        return True, "exact_expanded_zero"
    together = sp.simplify(sp.together(a - b))
    if together == 0:
        return True, "exact_together_zero"
    # Numeric tolerance check for rounded decimal answers (e.g. 33.33 vs 100/3)
    if not diff.free_symbols:
        try:
            dval = abs(complex(sp.N(diff, 15)))
            if dval <= tolerance:
                return True, f"within_tolerance({dval:.6g})"
            return False, f"numeric_mismatch(diff={format_decimal(diff)})"
        except Exception as exc:
            return False, f"numeric_eval_error({exc})"
    return False, f"symbolic_mismatch(diff={diff})"


def check_identity(spec: dict[str, Any]) -> dict[str, Any]:
    lhs_raw = spec.get("lhs", "")
    rhs_raw = spec.get("rhs", "")
    tol = float(spec.get("tolerance", 0.015))
    lhs = safe_parse(lhs_raw)
    rhs = safe_parse(rhs_raw)
    ok, reason = expressions_equal(lhs, rhs, tol)
    return {
        "passed": ok,
        "exactValue": format_exact(sp.simplify(lhs - rhs)),
        "decimalValue": format_decimal(lhs) if not lhs.free_symbols else format_exact(lhs),
        "reason": reason,
    }


def check_eval(spec: dict[str, Any]) -> dict[str, Any]:
    expr_raw = spec.get("expression", "")
    expected_raw = spec.get("expected", "")
    variables = spec.get("variables") or {}
    tol = float(spec.get("tolerance", 0.015))

    expr = safe_parse(expr_raw)
    subs_map: dict[sp.Symbol, sp.Expr] = {}
    for k, v in variables.items():
        sym_name = normalize_math_text(str(k))
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", sym_name):
            raise ValueError(f"invalid_variable_name:{k}")
        val_expr = safe_parse(str(v))
        for sym in expr.free_symbols:
            if sym.name == sym_name:
                subs_map[sym] = val_expr
    evaluated = sp.simplify(expr.subs(subs_map))
    expected = safe_parse(expected_raw)
    ok, reason = expressions_equal(evaluated, expected, tol)
    return {
        "passed": ok,
        "exactValue": format_exact(evaluated),
        "decimalValue": format_decimal(evaluated),
        "expectedExact": format_exact(expected),
        "reason": reason,
    }


def check_ratio(spec: dict[str, Any]) -> dict[str, Any]:
    claimed_raw = spec.get("claimed", spec.get("lhs", ""))
    expected_raw = spec.get("expected", spec.get("rhs", ""))
    tol = float(spec.get("tolerance", 1e-6))
    claimed = safe_parse(claimed_raw)
    expected = safe_parse(expected_raw)
    ok, reason = expressions_equal(claimed, expected, tol)
    return {
        "passed": ok,
        "exactValue": format_exact(claimed),
        "expectedExact": format_exact(expected),
        "reason": reason,
    }


def substitute_known_variables(expr: sp.Expr, variables: dict[str, Any], exclude_var: str = "") -> sp.Expr:
    if not variables:
        return expr
    subs_map: dict[sp.Symbol, sp.Expr] = {}
    for k, v in variables.items():
        sym_name = normalize_math_text(str(k))
        if exclude_var and sym_name == exclude_var:
            continue
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", sym_name):
            continue
        val_expr = safe_parse(str(v))
        for sym in expr.free_symbols:
            if sym.name == sym_name:
                subs_map[sym] = val_expr
    return sp.simplify(expr.subs(subs_map)) if subs_map else expr


def check_solve(spec: dict[str, Any]) -> dict[str, Any]:
    var_name = normalize_math_text(spec.get("variable", "x"))
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", var_name):
        raise ValueError(f"invalid_variable:{var_name}")
    lhs = safe_parse(spec.get("lhs", spec.get("expression", "")))
    rhs_raw = spec.get("rhs", "0")
    rhs = safe_parse(rhs_raw) if str(rhs_raw).strip() else sp.Integer(0)
    variables = spec.get("variables") or {}
    diff = substitute_known_variables(lhs - rhs, variables, exclude_var=var_name)
    expected = safe_parse(spec.get("expected", spec.get("expectedThreshold", "")))
    tol = float(spec.get("tolerance", 0.015))
    var = sp.Symbol(var_name, real=True)
    # Match symbol instance
    for s in diff.free_symbols:
        if s.name == var_name:
            var = s
            break
    sols = sp.solve(sp.Eq(diff, 0), var)
    for sol in sols:
        ok, _ = expressions_equal(sol, expected, tol)
        if ok:
            return {
                "passed": True,
                "exactValue": format_exact(sol),
                "decimalValue": format_decimal(sol),
                "reason": "root_matched",
            }
    return {
        "passed": False,
        "exactValue": ", ".join(format_exact(s) for s in sols),
        "expectedExact": format_exact(expected),
        "reason": f"root_mismatch(solutions={[format_exact(s) for s in sols]})",
    }


def check_inequality(spec: dict[str, Any]) -> dict[str, Any]:
    ineq_raw = normalize_math_text(spec.get("inequality", ""))
    var_name = normalize_math_text(spec.get("variable", ""))
    expected_rel = normalize_math_text(spec.get("expectedRelation", ""))
    expected_thr_raw = spec.get("expectedThreshold", "")
    variables = spec.get("variables") or {}
    tol = float(spec.get("tolerance", 0.015))

    op_match = re.search(r"(<=|>=|<|>)", ineq_raw)
    if not op_match:
        raise ValueError("missing_inequality_operator")
    op = op_match.group(1)
    lhs_str = ineq_raw[: op_match.start()].strip()
    rhs_str = ineq_raw[op_match.end() :].strip()
    lhs = safe_parse(lhs_str)
    rhs = safe_parse(rhs_str)
    diff = substitute_known_variables(sp.simplify(lhs - rhs), variables, exclude_var=var_name)

    # Pure numeric inequality (e.g. "0.6**2 + 0.4**2 > 0.25" or "(3/2)*20 - 40 < 0")
    if not diff.free_symbols:
        val = float(sp.N(diff, 15))
        holds = (
            (val < 0 if op == "<" else val <= 1e-12 if op == "<=" else val > 0 if op == ">" else val >= -1e-12)
        )
        return {
            "passed": holds,
            "exactValue": format_exact(diff),
            "decimalValue": format_decimal(diff),
            "reason": "numeric_inequality_holds" if holds else f"numeric_inequality_false(diff={format_decimal(diff)},op={op})",
        }

    var = None
    for s in diff.free_symbols:
        if s.name == var_name:
            var = s
            break
    if var is None:
        if len(diff.free_symbols) == 1:
            var = next(iter(diff.free_symbols))
        else:
            raise ValueError(f"variable_not_found_in_inequality:{var_name}")

    # Solve boundary diff == 0
    roots = sp.solve(sp.Eq(diff, 0), var)
    if not roots:
        # Constant sign inequality (e.g. P/4 > 0 for P > 0)
        return {
            "passed": False,
            "reason": f"no_boundary_root_found(diff={diff})",
        }

    expected_thr = safe_parse(expected_thr_raw)
    matched_root = None
    for r in roots:
        ok, _ = expressions_equal(r, expected_thr, tol)
        if ok:
            matched_root = r
            break
    if matched_root is None:
        return {
            "passed": False,
            "exactValue": ", ".join(format_exact(r) for r in roots),
            "expectedExact": format_exact(expected_thr),
            "reason": f"threshold_mismatch(actual={[format_exact(r) for r in roots]},expected={format_exact(expected_thr)})",
        }

    # Check direction using derivative / probe on both sides of boundary if numeric
    if expected_rel in ("<=", "<", ">=", ">") and not matched_root.free_symbols and len(diff.free_symbols) == 1:
        r_val = float(sp.N(matched_root, 12))
        eps = max(1e-3, abs(r_val) * 1e-3)
        above = float(sp.N(diff.subs(var, matched_root + sp.Rational(1, 1000) * max(1, int(abs(r_val) + 1)))))
        below = float(sp.N(diff.subs(var, matched_root - sp.Rational(1, 1000) * max(1, int(abs(r_val) + 1)))))
        holds_above = (above > 0) if op in (">", ">=") else (above < 0)
        holds_below = (below > 0) if op in (">", ">=") else (below < 0)
        expected_above = expected_rel in (">", ">=")
        expected_below = expected_rel in ("<", "<=")
        strict_match = (op in ("<", ">")) == (expected_rel in ("<", ">"))
        if expected_above and not holds_above:
            return {
                "passed": False,
                "exactValue": format_exact(matched_root),
                "reason": f"direction_mismatch(expected={var.name}{expected_rel}{format_exact(expected_thr)},holds_above={holds_above})",
            }
        if expected_below and not holds_below:
            return {
                "passed": False,
                "exactValue": format_exact(matched_root),
                "reason": f"direction_mismatch(expected={var.name}{expected_rel}{format_exact(expected_thr)},holds_below={holds_below})",
            }
        if not strict_match:
            # Allow <= vs < softness only if caller explicitly doesn't require strictness; otherwise flag
            return {
                "passed": False,
                "exactValue": format_exact(matched_root),
                "reason": f"strictness_mismatch(ineq_op={op},expected_rel={expected_rel})",
            }

    return {
        "passed": True,
        "exactValue": format_exact(matched_root),
        "decimalValue": format_decimal(matched_root),
        "reason": "inequality_threshold_and_direction_matched",
    }


def run_check(spec: dict[str, Any]) -> dict[str, Any]:
    cid = str(spec.get("id", ""))
    kind = str(spec.get("kind", "eval")).strip().lower()
    try:
        if kind == "identity":
            res = check_identity(spec)
        elif kind == "eval":
            res = check_eval(spec)
        elif kind == "ratio":
            res = check_ratio(spec)
        elif kind == "solve":
            res = check_solve(spec)
        elif kind == "inequality":
            res = check_inequality(spec)
        else:
            res = {"passed": False, "reason": f"unsupported_check_kind:{kind}"}
        return {"id": cid, "kind": kind, **res}
    except Exception as exc:
        return {
            "id": cid,
            "kind": kind,
            "passed": False,
            "reason": f"parse_or_eval_error:{type(exc).__name__}:{exc}",
        }


def main() -> int:
    raw = sys.stdin.read()
    payload = json.loads(raw)
    checks = payload.get("checks") or []
    results = [run_check(c) for c in checks]
    out = {
        "engine": "sympy",
        "sympyVersion": getattr(sp, "__version__", "unknown"),
        "results": results,
    }
    sys.stdout.write(json.dumps(out, ensure_ascii=False))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
