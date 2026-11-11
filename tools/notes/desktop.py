"""Desktop/CLI JSON adapter. No database, network or file access; stdout is JSON."""
import json
import sys

from .exporter import build_notes, build_point_note, render_markdown


def export_request(request):
    snapshot = request['snapshot']
    if not snapshot.get('events') or not snapshot.get('course'):
        raise ValueError('课程尚无账本，请先同步')
    if snapshot.get('mistake_policy') != 'pending' or 'selected_attempt_ids' not in snapshot:
        raise ValueError('缺少应用待练错题快照')
    if any(e['type'] == 'legacy_source_revised' for e in snapshot['events']):
        raise ValueError('历史修订尚不支持导出，请先由维护者核对')
    selection = request['selection']
    if selection.get('point_id'):
        note = build_point_note(snapshot, selection['point_id'])
    else:
        week = selection.get('week')
        if not isinstance(week, int) or isinstance(week, bool) or not 1 <= week <= 52:
            raise ValueError('周次须在 1–52 内')
        note = build_notes(snapshot, weeks=week)[-1]
    ids = [r['attempt_id'] for r in note['mistakes']]
    if set(ids) != set(snapshot['selected_attempt_ids']):
        raise ValueError('账本提取结果与应用错题快照不一致，导出已停止')
    return {'markdown': render_markdown(note), 'attempt_ids': ids, 'last_seq': note['last_seq']}


def main():
    # Also protect direct CLI launches; isolated Python ignores global encoding variables.
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='strict')
    try:
        result = export_request(json.load(sys.stdin))
        json.dump(result, sys.stdout, ensure_ascii=False)
    except (ValueError, KeyError, TypeError) as error:
        # No private payloads or Python tracebacks in the UI/logs.
        json.dump({'error': str(error) if isinstance(error, ValueError) else '导出快照格式不完整'}, sys.stdout, ensure_ascii=False)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
