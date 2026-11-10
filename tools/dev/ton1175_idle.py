"""Platform-independent checks on inspector/kernel evidence, never caller counts."""
import datetime as dt


BRIDGE_PORT = 8791  # ChildServices rejects every other managed bridge port.
POLL_SECONDS = 10
JITTER_SECONDS = 1


class EvidenceError(ValueError):
    """Only fixed, safe-to-display rejection reasons."""


def need(condition, message):
    if not condition:
        raise EvidenceError(message)


def instant(value):
    stamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    need(stamp.tzinfo is not None, 'timestamp must have a timezone')
    return stamp.timestamp()


def process_identity(value):
    # The collector and verifier both run ps with LC_ALL=C, TZ=UTC.
    fields = value.decode('utf-8').split(maxsplit=5)
    need(len(fields) == 6, 'kernel process start missing')
    stamp = dt.datetime.strptime(' '.join(fields[:5]), '%a %b %d %H:%M:%S %Y')
    return stamp.replace(tzinfo=dt.timezone.utc).timestamp(), fields[5]


def lifetime(process, jvm_start):
    started, _ = process_identity(process)
    # ps truncates fork time to seconds; JFR reports VM initialization later.
    # Allow bounded startup delay, never a JVM predating the kernel process.
    # The full ps identity remains pinned before and after verification.
    need(0 <= instant(jvm_start) - started < 5,
         'recording belongs to a different JVM lifetime')


def bridge_identity(state, owner, bridge, guard, bridge_parent, guard_parent,
                    root, run_dir, window_start, now):
    need(state['healthy'] is True and state['ownerPid'] == owner
         and type(state['pid']) is int and type(state['guardPid']) is int
         and state['pid'] > 0 and state['guardPid'] > 0
         and len({owner, state['pid'], state['guardPid']}) == 3,
         'bridge owner or health missing')
    need(0 <= now - state['checkedAt'] / 1000 <= 15, 'bridge health is stale or future')
    need(bridge_parent == state['guardPid'] and guard_parent == owner,
         'bridge process ancestry changed')
    bridge_start, bridge_command = process_identity(bridge)
    guard_start, guard_command = process_identity(guard)
    need(guard_start <= bridge_start <= window_start, 'bridge lifetime does not cover window')
    need(bridge_command.endswith(' ' + root + '/tools/bridge/subscription_bridge.py')
         and guard_command.endswith(' ' + root + '/tools/dev/service-child.py '
                                    + run_dir + '/bridge-' + str(owner) + '.pid '
                                    + root + '/tools/bridge/run.sh'),
         'bridge is not the installed managed child')
    # Include PIDs as well as start/command: a same-command replacement must
    # never inherit a recording's whitelist merely by taking the same port.
    return b'\0'.join((str(state['pid']).encode(), str(state['guardPid']).encode(), bridge, guard))


def health_reads(measured, start, end):
    """Allow only complete, regular exchanges from the actual health call site.

    Every positive read is emitted by the trusted Java inspector. Its endpoint
    and stack decisions cannot be supplied by the idle JSON. TCP may split a
    response differently, so compare total bytes per bounded exchange rather
    than assuming two read events per poll.
    """
    rows = measured['reads']
    need(type(measured['socketReads']) is int and measured['socketReads'] == len(rows),
         'socket read coverage incomplete')
    if not rows:
        return 0  # Original strict idle: no positive read needs an exemption.
    exchanges = []
    for row in sorted(rows, key=lambda item: instant(item['start'])):
        at, until = instant(row['start']), instant(row['end'])
        need(row['bridgePeer'] is True and row['healthOrigin'] is True,
             'JFR contains non-health socket activity')
        need(type(row['bytes']) is int and row['bytes'] > 0
             and type(row['thread']) is int and row['thread'] > 0,
             'health read identity or size missing')
        need(start <= at <= until <= end and until - at <= JITTER_SECONDS,
             'health exchange crosses window or is too slow')
        if exchanges and at - exchanges[-1][0] <= JITTER_SECONDS:
            previous = exchanges[-1]
            need(row['thread'] == previous[3] and at >= previous[1]
                 and until - previous[0] <= JITTER_SECONDS,
                 'health exchange has concurrent or ambiguous reads')
            previous[1] = until
            previous[2] += row['bytes']
        else:
            exchanges.append([at, until, row['bytes'], row['thread']])
    need(exchanges[0][0] - start <= POLL_SECONDS + JITTER_SECONDS
         and end - exchanges[-1][1] <= POLL_SECONDS + JITTER_SECONDS,
         'health polling does not cover window')
    size = exchanges[0][2]
    for previous, current in zip(exchanges, exchanges[1:]):
        need(POLL_SECONDS - JITTER_SECONDS <= current[0] - previous[0]
             <= POLL_SECONDS + JITTER_SECONDS, 'health polling cadence changed')
        need(current[2] == size, 'health exchange size changed')
    return len(exchanges)
