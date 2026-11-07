#!/usr/bin/env python3
"""Deterministic isolated-rehearsal CLI. Never reads stdin, files or network."""
import json
import sys

MODEL = 'ton1193-synthetic-claude'
TEXT = '这是合成讲课内容：机会成本是作出选择时放弃的最佳替代方案的价值。'


def main(args):
    if args == ['auth', 'status']:
        print(json.dumps({'loggedIn': True, 'authMethod': 'SYNTHETIC'}))
        return 0
    if '-p' not in args or '--output-format' not in args or 'stream-json' not in args:
        return 2
    events = [
        {'type': 'system', 'subtype': 'init', 'model': MODEL},
        {'type': 'stream_event', 'event': {'type': 'content_block_delta',
         'delta': {'type': 'text_delta', 'text': TEXT}}},
        {'type': 'result', 'subtype': 'success', 'is_error': False, 'result': TEXT,
         'stop_reason': 'end_turn', 'modelUsage': {MODEL: {'inputTokens': 0, 'outputTokens': 1}}},
    ]
    for event in events:
        print(json.dumps(event, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
