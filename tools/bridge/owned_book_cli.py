"""Unwired book CLI adapter; outbound policy defaults to deny.

This module supplies no HTTP route and does not change privateAi. A caller must
provide an already authorized transport and a policy appropriate to the data.
The real private-book policy remains disabled. Never log request/response bodies.
This low-level adapter does not reject source echoes; callers must run responses
through OwnedBookLearningProvider before treating them as learning material.
"""
import json
import time

import subscription_bridge as bridge


async def complete_book(request, policy=lambda _: False):
    # Do not even serialize a request for the external process until authorized.
    if not policy(request):
        raise PermissionError("private book outbound AI is not authorized")
    if not isinstance(request, dict) or set(request) not in (
        {"action", "excerpts"}, {"action", "question", "excerpts"}
    ):
        raise ValueError("invalid book request")
    action, excerpts = request["action"], request["excerpts"]
    if action not in ("explain", "ask") or not isinstance(excerpts, list) or not excerpts:
        raise ValueError("invalid book request")
    if action == "explain" and "question" in request:
        raise ValueError("explain cannot contain a question")
    if action == "ask" and (not isinstance(request.get("question"), str)
                            or not 1 <= len(request["question"].strip()) <= 500):
        raise ValueError("invalid question")
    for index, excerpt in enumerate(excerpts):
        if (not isinstance(excerpt, dict) or set(excerpt) != {"id", "text"}
                or type(excerpt["id"]) is not int or excerpt["id"] != index
                or not isinstance(excerpt["text"], str) or not excerpt["text"].strip()):
            raise ValueError("invalid excerpt")
    body = {"messages": [
        {"role": "system", "content": "Use only the supplied excerpts. Return only a JSON object with exactly two fields: answer (a useful explanation or answer, not a quotation) and citations (an array of excerpt IDs used). Do not use Markdown."},
        {"role": "user", "content": json.dumps(request, ensure_ascii=False)},
    ]}
    model = "gpt-6.1-sol"
    ctx = bridge.ReqCtx("owned-book-cli", model, bridge.ModelSpec(model, "codex", False), body,
                        bridge.build_spec(body), False, False, False, "low", time.monotonic(), 0)
    outcome = await bridge.call_codex(ctx, model, "low")
    if outcome.status != "complete":
        raise RuntimeError("book CLI did not complete")
    try:
        result = json.loads(outcome.text)
    except (TypeError, ValueError) as exc:
        raise ValueError("invalid structured answer") from exc
    if (not isinstance(result, dict) or set(result) != {"answer", "citations"}
            or not isinstance(result["answer"], str) or not result["answer"].strip()
            or not isinstance(result["citations"], list) or not result["citations"]
            or any(type(i) is not int or not 0 <= i < len(excerpts) for i in result["citations"])):
        raise ValueError("invalid structured answer")
    return result
