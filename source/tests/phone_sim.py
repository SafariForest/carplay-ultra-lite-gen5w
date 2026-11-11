#!/usr/bin/env python3
"""Plays the iPhone on a vehicle state data stream against VsChannel (tests/VsChannelTest.java),
with an independent ChaCha20-Poly1305 (the cryptography package).

usage: phone_sim.py <dir for the key file> <port>
Writes cpc-vs-0.key as the native SETUP handler would, connects, sends packages in random
chunks (a sync with a nested OPACK message, an async, one larger than a 16 KB frame, then a
real registerRequest and writeRequest), and checks the empty reply the sync gets back and the
car's answers (M3: async "cmnd" packages; the updateNotify meant for the high lane comes on this
one, as it is the only lane open). Exit 0 if all is well."""
import os, random, socket, struct, sys, time
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

d, port = sys.argv[1], int(sys.argv[2])
seed = (-4170049560036521059) & 0xFFFFFFFFFFFFFFFF
car_read, car_write = os.urandom(32), os.urandom(32)        # the phone writes with car_read
open(os.path.join(d, "cpc-vs-0.key"), "wb").write(struct.pack("<Q", seed) + car_read + car_write)

def nonce(n):
    return b"\0" * 4 + struct.pack("<Q", n)

def seal(key, n, plain):
    out, i = b"", 0
    while True:
        chunk = plain[i:i + 0x4000]
        head = struct.pack("<H", len(chunk))
        out += head + ChaCha20Poly1305(key).encrypt(nonce(n), chunk, head)
        n += 1; i += 0x4000
        if i >= len(plain):
            return out, n

def pack(kind, mtype, token, body):
    return struct.pack(">I4s8s4sQ4s", 32 + len(body), kind, b"\0" * 8, mtype, token, b"\0" * 4) + body

def opack(v):
    if isinstance(v, bool): return b"\x01" if v else b"\x02"
    if isinstance(v, int):
        return bytes([0x08 + v]) if 0 <= v <= 39 else b"\x31" + struct.pack("<h", v) if -32768 <= v < 32768 else b"\x33" + struct.pack("<q", v)
    if isinstance(v, float): return b"\x36" + struct.pack("<d", v)
    if isinstance(v, str):
        b = v.encode(); return (bytes([0x40 + len(b)]) if len(b) <= 0x20 else b"\x61" + bytes([len(b)])) + b
    if isinstance(v, bytes):
        return (bytes([0x70 + len(v)]) if len(v) <= 0x20 else b"\x92" + struct.pack("<H", len(v))) + v
    if isinstance(v, list): return bytes([0xD0 + len(v)]) + b"".join(opack(x) for x in v)
    if isinstance(v, dict): return bytes([0xE0 + len(v)]) + b"".join(opack(k) + opack(x) for k, x in v.items())

inner = opack({"command": "follow", "iids": [520, 534, 525], "transaction": 7})
msg = opack({"pluginID": 0, "pluginData": inner})
packages = pack(b"sync", b"comm", 0x1122334455667788, msg) + pack(b"asyn", b"comm", 0, opack({"pluginID": 0, "pluginData": opack({"command": "read", "iids": [520]})})) \
    + pack(b"asyn", b"comm", 0, opack({"pluginID": 0, "pluginData": b"x" * 20000})) \
    + pack(b"asyn", b"comm", 0, opack({"pluginID": 0, "pluginData": opack(
        {"command": "registerRequest", "transactionID": -4276896051530212903, "values": [520, 534, 538]})})) \
    + pack(b"asyn", b"comm", 0, opack({"pluginID": 0, "pluginData": opack(
        {"command": "writeRequest", "transactionID": 7, "values": {520: 22.5}})}))
wire, _ = seal(car_read, 0, packages)

s = socket.create_connection(("127.0.0.1", port))
i = 0
while i < len(wire):                                         # the car must cope with any split
    n = random.randint(1, 3000)
    s.sendall(wire[i:i + n]); i += n; time.sleep(0.005)
s.settimeout(5)
got, frames, k = b"", [], 0
while len(frames) < 4:                                       # rply + three answers, a frame each
    got += s.recv(4096)
    while len(got) >= 2 and len(got) >= 2 + struct.unpack("<H", got[:2])[0] + 16:
        n, = struct.unpack("<H", got[:2])
        frames.append(ChaCha20Poly1305(car_write).decrypt(nonce(k), got[2:2 + n + 16], got[:2]))
        got, k = got[2 + n + 16:], k + 1
size, kind, _, mtype, token, _ = struct.unpack(">I4s8s4sQ4s", frames[0][:32])
ok = size == 32 and kind == b"rply" and token == 0x1122334455667788 and len(frames[0]) == 32
print("phone: reply", kind, hex(token), "OK" if ok else "WRONG")
want = [(b"registerResponse", b"\x33" + struct.pack("<q", -4276896051530212903)), (b"writeResponse", b"\x0f"),
        (b"updateNotify", b"\x36" + struct.pack("<d", 22.5))]
for f, (command, has) in zip(frames[1:], want):
    size, kind, _, mtype, token, _ = struct.unpack(">I4s8s4sQ4s", f[:32])
    good = size == len(f) and kind == b"asyn" and mtype == b"cmnd" and command in f and has in f
    print("phone: answer", kind, mtype, command.decode(), "OK" if good else "WRONG " + f[32:].hex())
    ok &= good
s.close()
sys.exit(0 if ok else 1)
