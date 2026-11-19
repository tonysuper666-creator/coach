#!/usr/bin/env python3
"""Pre-pair B on the Mac, then sign A's one-use Windows code.

Use dedicated A/B identities after any previous temporary session is cleaned up.
This helper prepares credentials only.
Secrets enter via env; the new 0600 output is private, never an artifact.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

spec = importlib.util.spec_from_file_location("friend_public", Path(__file__).with_name("friend-public.py"))
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)
A = "External acceptance A"
B = "External acceptance B"


def require(ok):
    if not ok:
        raise ValueError("preparation rejected")


def local_endpoint(value):
    p = urlsplit(value)
    require(p.scheme == "http" and p.hostname == "127.0.0.1" and p.port is not None
            and 1024 <= p.port <= 65535 and p.port not in {18791, 8790, 8791, 3790, 19540}
            and not p.username and not p.password and p.path == "" and not p.query and not p.fragment)
    return value


def code(client, owner, name):
    status, body = client.request("/v1/pairing-code", "POST", owner, {"friend": name})
    require(status == 200)
    value = json.loads(body)["code"]
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{6}", value))
    return value


def prepare(private, public, owner):
    require(owner.get("deviceId") == "desktop" and isinstance(owner.get("token"), str) and owner["token"])
    # Do not sign A until B has been redeemed and independently authorized.
    b_code = code(private, owner, B)
    status, body = public.request("/v1/pair", "POST", body={"code": b_code, "name": "Mac pre-paired usage control"})
    require(status == 200)
    b = json.loads(body)
    require(isinstance(b, dict) and b.get("role") == "friend"
            and isinstance(b.get("deviceId"), str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", b["deviceId"])
            and isinstance(b.get("token"), str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", b["token"]))
    b = {key: b[key] for key in ("deviceId", "token", "role")}
    require(public.request("/v1/public/status", auth=b)[0] == 200)
    a_code = code(private, owner, A)
    # Issuing A must leave B authorized. No B code survives this sequence.
    require(public.request("/v1/public/status", auth=b)[0] == 200)
    return {"codeA": a_code, "credentialB": b}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    fd = None
    output = Path(args.output)
    created = False
    private = owner = None
    try:
        # No overwrite or symlink output; containing directory must be private.
        st = output.parent.stat()
        require(not output.parent.is_symlink() and st.st_uid == os.getuid() and st.st_mode & 0o777 == 0o700)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        created = True
        private_url = local_endpoint(os.environ["COACH_PREPARE_OWNER_URL"])
        public_url = local_endpoint(os.environ["COACH_PREPARE_PUBLIC_URL"])
        require(private_url != public_url)
        private, public = protocol.Client(private_url), protocol.Client(public_url)
        owner = json.loads(os.environ["COACH_PREPARE_OWNER"])
        result = prepare(private, public, owner)
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            fd = None
            json.dump(result, out)
            out.flush()
            os.fsync(out.fileno())
        print("FRIEND_PREPARE PASS bPrepairedOnMac=1 aCodeReady=1 modelRequests=0")
        return 0
    except Exception:
        # Only the fixed temporary fixture names, never owner devices. Failure
        # remains failure even if cleanup succeeds. Operator must verify cleanup.
        if private is not None and owner is not None:
            for name in (A, B):
                try:
                    private.request("/v1/friends/revoke", "POST", owner, {"friend": name})
                except Exception:
                    pass
        if created:
            output.unlink(missing_ok=True)
        print("FRIEND_PREPARE FAIL cleanupVerificationRequired=1", file=sys.stderr)
        return 1
    finally:
        if fd is not None:
            os.close(fd)


if __name__ == "__main__":
    sys.exit(main())
