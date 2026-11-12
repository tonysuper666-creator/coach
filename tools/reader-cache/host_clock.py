"""Bracket fixture-host epoch reads; never assume another process's clock source."""
import time


def sample():
    before=time.monotonic_ns()
    epoch=time.time_ns()
    after=time.monotonic_ns()
    return dict(hostMonotonicBeforeNs=before,hostEpochNs=epoch,hostMonotonicAfterNs=after)


def offset_bounds(witness):
    before,epoch,after=(witness[key] for key in ('hostMonotonicBeforeNs','hostEpochNs','hostMonotonicAfterNs'))
    if any(type(value) is not int for value in (before,epoch,after)) or before>after:
        raise ValueError('Ordered integer host-clock samples required')
    # This is arithmetic for the recorded read. Clock resolution, drift and
    # timestamp-source uncertainty are not included. It does not align clocks.
    return dict(minimumNs=epoch-after,maximumNs=epoch-before,widthNs=after-before)
