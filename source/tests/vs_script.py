#!/usr/bin/env python3
"""Writes a phone's side of a vehicle state session for tests/VehicleStateTest.java: records of
lane (0 normal, 1 high), u32 LE length, message, the format of vsdump --replay (data/vsdump-src).
The same file goes to LIVI's own VehicleState and to the Java port; their answers must match.

usage: vs_script.py <out file>
The registerRequests are the ones the iPhone (iOS 27.0) sent in m2-run040; the rest tries every
command and each of LIVI's climate rules, plus messages that must be dropped."""
import struct, sys


def opack(v):
    if v is None: return b"\x04"
    if v is True: return b"\x01"
    if v is False: return b"\x02"
    if isinstance(v, int):
        if 0 <= v <= 39: return bytes([0x08 + v])
        for tag, fmt in ((0x30, "<b"), (0x31, "<h"), (0x32, "<i"), (0x33, "<q")):
            try: return bytes([tag]) + struct.pack(fmt, v)
            except struct.error: pass
    if isinstance(v, float): return b"\x36" + struct.pack("<d", v)
    if isinstance(v, str): v = v.encode(); return length(0x40, len(v)) + v
    if isinstance(v, bytes): return length(0x70, len(v)) + v
    if isinstance(v, list):
        return (bytes([0xD0 + len(v)]) if len(v) <= 14 else b"\xDF") + b"".join(map(opack, v)) + (b"" if len(v) <= 14 else b"\x03")
    if isinstance(v, dict):
        items = b"".join(opack(k) + opack(x) for k, x in v.items())
        return (bytes([0xE0 + len(v)]) if len(v) <= 14 else b"\xEF") + items + (b"" if len(v) <= 14 else b"\x03")
    raise TypeError(v)


def length(base, n):
    if n <= 0x20: return bytes([base + n])
    w = 1 if n <= 0xFF else 2 if n <= 0xFFFF else 4
    return bytes([base + 0x20 + w]) + n.to_bytes(w, "little")


T = [0]


def msg(command, values=None, plugin=0, tid=True):
    T[0] += 1
    inner = {"command": command}
    if tid: inner["transactionID"] = -8710252789170012419 + 977 * T[0]
    if values is not None: inner["values"] = values
    return opack({"pluginID": plugin, "pluginData": opack(inner)})


HIGH = 1
script = [
    (HIGH, msg("registerRequest", [2050, 533, 2058, 530, 519, 549, 538, 2055, 2063, 2052, 524, 535, 2060, 554, 521, 2068,
                                   2057, 559, 2065, 2062, 534, 520, 2067, 528, 2053])),
    (HIGH, msg("configRequest")),
    (HIGH, msg("registerRequest", [1545, 1546, 1798, 1799, 1794, 1795, 1796, 2321, 2317, 2311, 2309, 2306, 2314, 4115, 4116,
                                   4117, 4100, 4101, 4102])),
    (HIGH, msg("registerRequest", [4103, 4104])),
    (HIGH, msg("registerRequest", [4118])),
    (HIGH, msg("registerRequest", [273, 268, 269, 270, 271, 266, 267, 258, 259, 260, 261, 262, 264, 555, 560, 515, 516, 517, 514,
                                   541, 545, 525, 526, 550, 1030, 1031, 1026, 1027, 1538, 1542, 1543, 1541, 4354, 4355, 4356,
                                   4357, 4359])),
    (HIGH, msg("registerRequest", [4358])),
    (HIGH, msg("registerRequest", list(range(3585, 3665)) + [1286, 3086, 3087])),
    (0, msg("readRequest", [520, 521, 534, 525, 99999])),
    # synced zones: the driver's temperature carries the passenger's along
    (HIGH, msg("writeRequest", {538: True})),
    (HIGH, msg("writeRequest", {520: 23.5})),
    (HIGH, msg("writeRequest", {534: 19.0})),                 # unsyncs
    # fan level and its on switch, and the hand overriding auto
    (HIGH, msg("writeRequest", {525: 0})),
    (HIGH, msg("writeRequest", {524: True})),
    (HIGH, msg("writeRequest", {525: 5})),
    (HIGH, msg("writeRequest", {524: False})),
    (HIGH, msg("writeRequest", {524: True})),
    (HIGH, msg("writeRequest", {530: True})),
    (HIGH, msg("writeRequest", {554: False})),
    (HIGH, msg("writeRequest", {530: True})),
    # seat heating levels
    (HIGH, msg("writeRequest", {1026: 2, 1030: 0})),
    (HIGH, msg("writeRequest", {1031: True, 1027: False})),
    # max A/C on and off, and on then broken by a change
    (HIGH, msg("writeRequest", {516: True})),
    (HIGH, msg("writeRequest", {516: True})),
    (HIGH, msg("writeRequest", {516: False})),
    (HIGH, msg("writeRequest", {516: True})),
    (HIGH, msg("writeRequest", {525: 3})),
    (HIGH, msg("writeRequest", {516: False})),
    # volume and mute
    (HIGH, msg("writeRequest", {2309: True})),
    (HIGH, msg("writeRequest", {2309: False})),
    (HIGH, msg("writeRequest", {2306: 0})),
    (HIGH, msg("writeRequest", {2309: False})),
    (HIGH, msg("writeRequest", {2306: 30})),
    # values the phone may not write, phone-only values, a history entry hidden, a value already held
    (HIGH, msg("writeRequest", {521: 30.0, 99999: 1, 258: "Main St", 3592: True})),
    (HIGH, msg("writeRequest", {514: True, 541: 1, 545: 0})),
    (0, msg("writeRequest", {528: True, 515: False})),
    (0, msg("readRequest", [528, 515, 516, 525, 524, 526, 530, 550, 555, 560, 2306, 2309, 1026, 1027])),
    # controls
    (HIGH, msg("controlRequest", {4360: {}, 4364: {"identifier": "x", "sourceIdentifier": "fm"}, 4999: {}})),
    (HIGH, msg("controlRequest", {})),
    # dropped or ignored
    (HIGH, b"\xff\xfe"),
    (HIGH, opack({"pluginID": 1, "pluginData": opack({"command": "configRequest"})})),
    (HIGH, opack({"pluginID": 0, "pluginData": b"\xff"})),
    (HIGH, msg("frobnicate", [1])),
    (HIGH, msg("readRequest", [520], tid=False)),
    (HIGH, msg("registerRequest", "not a list")),
    (HIGH, msg("writeRequest", [1, 2])),
]

with open(sys.argv[1], "wb") as f:
    for lane, m in script:
        f.write(bytes([lane]) + struct.pack("<I", len(m)) + m)
print(len(script), "messages")
