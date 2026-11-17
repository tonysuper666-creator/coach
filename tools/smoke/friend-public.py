#!/usr/bin/env python3
"""Non-GUI friend endpoint checks. Secrets enter through environment only.

No book, explanation, credentials, URL or server response is written to output.
This is not native-client or real-material acceptance.
"""
import argparse
import json
import os
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

DENIED = (
    "/v1/ws", "/v1/hello", "/v1/commands", "/v1/devices",
    "/v1/pairing-code", "/v1/usage", "/v1/courses/x/snapshot",
    "/v1/courses/x/today", "/v1/courses/x/sources", "/v1/courses/x/pool",
    "/v1/courses/x/essays", "/v1/prewarm", "/v1/reader", "/v1/library",
    "/v1/health", "/health", "/v1/%64evices", "/v1/%70airing-code",
    "/v1/library/explain/", "/v1/public/status/../devices",
)
WRONG_METHODS = (("GET", "/v1/pair"), ("POST", "/v1/public/status"),
                 ("GET", "/v1/library/explain"), ("GET", "/v1/library/explain/cancel"))
MAX_BODY = 16384


class CheckFailed(Exception):
    pass


def check(condition, label):
    if not condition:
        raise CheckFailed(label)


def mask(value):
    # Workflow commands require escaping even for values from a server.
    if os.environ.get("GITHUB_ACTIONS") == "true":
        value = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::add-mask::" + value, flush=True)


def endpoint(value):
    parsed = urllib.parse.urlsplit(value)
    check(parsed.scheme == "https" and bool(parsed.hostname)
          and parsed.hostname.endswith(".ts.net") and parsed.port in (None, 443)
          and not parsed.username and not parsed.password
          and parsed.path in ("", "/") and not parsed.query and not parsed.fragment,
          "endpoint configuration")
    check(not any(ord(c) <= 32 for c in value), "endpoint configuration")
    mask(value)
    mask(parsed.hostname)
    return value.rstrip("/")


def credential(value):
    data = json.loads(value)
    check(isinstance(data, dict) and data.get("role") == "friend", "friend credential")
    for key in ("deviceId", "token"):
        check(isinstance(data.get(key), str) and bool(data[key])
              and all(32 < ord(c) < 127 for c in data[key]), "friend credential")
        mask(data[key])
    return {key: data[key] for key in ("deviceId", "token")}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, url):
        self.url = url
        # Never forward a bearer token or pairing code to a redirect or proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, method="GET", auth=None, body=None):
        request = urllib.request.Request(self.url + path, method=method,
            data=None if body is None else json.dumps(body).encode())
        if body is not None:
            request.add_header("Content-Type", "application/json")
        if auth:
            request.add_header("X-Coach-Device", auth["deviceId"])
            request.add_header("Authorization", "Bearer " + auth["token"])
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(MAX_BODY + 1)
            check(len(data) <= MAX_BODY, "response size")
            return response.code, data


def surface(client, code):
    check(re.fullmatch(r"[0-9]{6}", code) is not None, "pairing code configuration")
    mask(code)
    check(client.request("/v1/public/status")[0] == 401, "anonymous status")
    status, body = client.request("/v1/pair", "POST", body={"code": code, "name": "External protocol check"})
    check(status == 200, "friend pairing")
    auth = credential(body)
    check(client.request("/v1/pair", "POST", body={"code": code})[0] == 401, "single-use pairing")
    status, body = client.request("/v1/public/status", auth=auth)
    check(status == 200 and json.loads(body).get("online") is True, "friend status")
    for identity in (None, auth):
        for path in DENIED:
            check(client.request(path, auth=identity) == (404, b""), "private path boundary")
        for method, path in WRONG_METHODS:
            check(client.request(path, method, identity) == (404, b""), "method boundary")
    for path in ("/v1/library/explain", "/v1/library/explain/cancel"):
        check(client.request(path, "POST", body={})[0] == 401, "anonymous explanation")
    # Authenticated malformed requests, books and API traffic are deliberately absent.
    print("PASS pair=friend replay=401 status=200 denied_paths=20 identities=2 wrong_methods=4 anonymous_explain=401")


def revocation(client, revoked, active):
    check(revoked["deviceId"] != active["deviceId"] and revoked["token"] != active["token"], "distinct devices")
    check(client.request("/v1/public/status", auth=revoked)[0] == 401, "revoked device")
    status, body = client.request("/v1/public/status", auth=active)
    check(status == 200 and json.loads(body).get("online") is True, "other friend remains online")
    print("PASS revoked=401 other_friend=200")


def closed_ports(host):
    # A bounded check of relevant ports, not a claim of scanning every TCP port.
    for port in (80, 8443, 10000, 18791):
        try:
            connection = socket.create_connection((host, port), timeout=2)
        except (TimeoutError, ConnectionRefusedError):
            continue
        except OSError as error:
            # DNS failures and routing errors are inconclusive, not a pass.
            raise CheckFailed("alternate port reachability inconclusive") from error
        else:
            connection.close()
            raise CheckFailed("unexpected alternate listener")
    print("PASS alternate_ports=4 refused_or_timed_out")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("surface", "revocation"))
    args = parser.parse_args(argv)
    try:
        url = endpoint(os.environ["COACH_FRIEND_URL"])
        client = Client(url)
        if args.mode == "surface":
            surface(client, os.environ["COACH_FRIEND_CODE"])
        else:
            revocation(client, credential(os.environ["COACH_REVOKED_CREDENTIAL"]),
                       credential(os.environ["COACH_ACTIVE_CREDENTIAL"]))
        closed_ports(urllib.parse.urlsplit(url).hostname)
        print("SCOPE protocol-only; native GUI, real explanation, quota ledger and cleanup require separate verification")
        return 0
    except CheckFailed as error:
        # Labels are fixed program literals; never serialize responses/exceptions.
        print("FAIL " + str(error), file=sys.stderr)
    except Exception:
        print("FAIL configuration or transport; private diagnostic values suppressed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
