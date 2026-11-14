"""Synthetic CLI process: no model, network, credentials or repository inputs."""
import json
import os
from pathlib import Path
import sys

config = Path(__file__).with_suffix(".json")
settings = json.loads(config.read_text(encoding="utf-8"))
case = settings["case"]
args = sys.argv[1:]
home = Path(os.environ["CODEX_HOME"])
cwd = Path.cwd()
with Path(settings["log"]).open("a", encoding="utf-8") as log:
    log.write(json.dumps({"args": args, "cwd": str(cwd), "home": str(home),
                          "env_keys": sorted(os.environ),
                          "auth_file_present": (home / "auth.json").exists()}) + "\n")


def done(value, rc=0):
    print(value)
    sys.exit(rc)


if args == ["--version"]:
    if case == "timeout":
        import time
        time.sleep(2)
    done("codex-cli " + ("1.2.30-synthetic" if case == "version_drift" else "1.2.3-synthetic"))
if args == ["exec", "--help"]:
    flags = "--ephemeral --ignore-user-config --strict-config --skip-git-repo-check --sandbox"
    if case == "missing_strict":
        flags = flags.replace("--strict-config", "")
    done("Usage: codex exec " + flags)
if args == ["debug", "prompt-input", "--help"]:
    done("Usage: codex debug prompt-input" if case != "missing_renderer" else "unknown command",
         0 if case != "missing_renderer" else 2)

overrides = {}
for i, arg in enumerate(args):
    if arg == "-c":
        key, value = args[i + 1].split("=", 1)
        overrides[key] = json.loads(value)

if args[0] == "exec":
    # A safety tripwire in the fake: a normal model invocation is always an error.
    assert overrides.get("model_provider") == "__precheck_nonexistent_provider__"
    assert "--strict-config" in args
    assert "--ephemeral" in args
    assert "resume" not in args and "fork" not in args
    cfg = home / "config.toml"
    keys = list(overrides)
    if cfg.exists() and "--ignore-user-config" not in args:
        keys.append(cfg.read_text(encoding="utf-8").split("=", 1)[0])
    bad = next((k for k in keys if k.startswith("precheck_unknown_")), None)
    if case == "ignored_unknown":
        done("synthetic accepted", 0)
    if case == "auth_error":
        done("authentication required SYNTHETIC-SECRET-MUST-NOT-APPEAR", 1)
    if case == "provider_error":
        done("missing provider", 1)
    if case == "ignored_file" and cfg.exists():
        done("missing provider", 1)
    assert bad
    done("unknown configuration field `" + bad + "`", 1)

assert args[:2] == ["debug", "prompt-input"]
if case == "malformed":
    done("not-json")
if case == "empty_evidence":
    done("[]")
if case == "wrong_shape":
    done('{"messages": []}')
if case == "no_developer":
    done('[{"type": "message", "role": "user", "content": [{"text": "synthetic"}]}]')


def message(role, text):
    return {"type": "message", "role": role, "content": [{"type": "input_text", "text": text}]}


baseline = "tiny base"  # Deliberately no prompt-length threshold.
base = baseline
if "model_instructions_file" in overrides:
    path = Path(overrides["model_instructions_file"])
    if not path.exists():
        if case == "wrong_missing_error":
            done("authentication required", 1)
        done("failed to read model instructions file " + path.name, 1)
    if case != "ignored_replacement":
        base = path.read_text(encoding="utf-8")
residual = "SYNTHETIC-SECRET-MUST-NOT-APPEAR " + str(config.parent)
items = [message("developer", base), message("developer", residual)]
if case == "append_instead_of_replace" and base != baseline:
    items = [message("developer", baseline), message("developer", base), message("developer", residual)]
if case == "lost_residual" and base != baseline:
    items = items[:1]
if "developer_instructions" in overrides and case != "ignored_append":
    items.append(message("developer", overrides["developer_instructions"]))
if "developer_instructions" in overrides and case == "append_replaces_base":
    items[0] = message("developer", overrides["developer_instructions"])
project_file = cwd / "AGENTS.md"
state = config.with_suffix(".state")
if project_file.exists():
    project_marker = project_file.read_text(encoding="utf-8")
    state.write_text(project_marker, encoding="utf-8")
    if case != "ignored_agents":
        items.append(message("developer", project_marker))
elif case == "cwd_leak" and state.exists():
    items.append(message("developer", state.read_text(encoding="utf-8")))
tools = [{"name": "functions", "tools": [{"name": "exec", "description": residual}]}]
if case == "no_tools":
    tools = []
if case == "malformed_tools":
    tools = [{"name": "functions", "tools": None}]
items.append({"type": "additional_tools", "tools": tools})
items.append(message("user", "SYNTHETIC-PRECHECK"))
if case == "jsonl":
    done("\n".join(json.dumps(i) for i in items))
done(json.dumps(items))
