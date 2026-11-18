"""Linux snapshot reader. Requires the distribution's python3-seccomp package."""
import errno
import os
import stat
import sys

import seccomp


def restrict_io(descriptors):
    policy = seccomp.SyscallFilter(defaction=seccomp.ERRNO(errno.EPERM))
    for fd in descriptors:
        policy.add_rule(seccomp.ALLOW, "readv", seccomp.Arg(0, seccomp.EQ, fd))
    policy.add_rule(seccomp.ALLOW, "write", seccomp.Arg(0, seccomp.EQ, 1))
    policy.add_rule(seccomp.ALLOW, "exit_group")
    policy.load()


def main(args):
    count = int(args[0])
    # The parent creates these files inside its private temporary directory.
    # Open only that allowlist, before reading any of its contents.
    allowed = {}
    for path in args[1:1 + count]:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            return 1
        allowed[path] = fd
    targets = [allowed[path] for path in args[1 + count:]]
    buffer = memoryview(bytearray(16384))
    restrict_io(allowed.values())
    for fd in targets:
        while (size := os.readv(fd, [buffer])):
            chunk = buffer[:size]
            while chunk:
                chunk = chunk[os.write(1, chunk):]
    return 0


if __name__ == "__main__":
    try:
        result = main(sys.argv[1:])
    except Exception:
        result = 1
    os._exit(result)
