"""Read-only: print the calling process's active TSF keyboard profile as one JSON line.

Run as a separate process so it reports what a fresh process (the user's current input setup) sees, independent of any
process-level ActivateProfile done inside the Coach probe JVM. Never activates or changes anything.
"""
import ctypes
import json
import os
import uuid

ole = ctypes.OleDLL("ole32")


def guid(text):
    return (ctypes.c_byte * 16).from_buffer_copy(uuid.UUID(text).bytes_le)


ole.CoInitializeEx(None, 2)
out = ctypes.c_void_p()
ole.CoCreateInstance(ctypes.byref(guid("{33C53A50-F456-4884-B049-85FD643ECFED}")), None, 1,
                     ctypes.byref(guid("{71C6E74C-0F28-11D8-A82A-00065B84435C}")), ctypes.byref(out))
vtbl = ctypes.cast(out, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p)))[0]
proto = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
get_active = proto(vtbl[10])
buf = (ctypes.c_ubyte * 128)()
hr = get_active(out, ctypes.byref(guid("{34745C63-B2F0-4784-8B67-5E12C8701A31}")), ctypes.byref(buf))
raw = bytes(buf)
result = dict(
    pid=os.getpid(), hr=hex(hr & 0xFFFFFFFF),
    profileType=int.from_bytes(raw[0:4], "little"),
    langid=hex(int.from_bytes(raw[4:6], "little")),
    clsid=str(uuid.UUID(bytes_le=raw[8:24])).upper(),
    profile=str(uuid.UUID(bytes_le=raw[24:40])).upper(),
    hkl=hex(int.from_bytes(raw[72:80], "little") & 0xFFFFFFFF),
)
print(json.dumps(result))
