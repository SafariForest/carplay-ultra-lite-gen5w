#!/usr/bin/env python3
"""Prints the fingerprint CarApi.fingerprint computes on the car for an AIDL listener, from baksmali output
of the car's framework (automotive.odex), so cp-climate can check at runtime that a listener it registers
answers exactly like the one it was written against (CEEV USA 260507).

usage: listener_fingerprint.py <smali dir> <interface, e.g. com.hkmc.bcm.IBCMListener>

One line per call, sorted by code: "<code>:<name>(<java type names of the parameters, comma-separated>)";
the fingerprint is the SHA-256 of the lines joined by newlines."""
import hashlib, re, sys
from pathlib import Path

PRIM = {"I": "int", "Z": "boolean", "B": "byte", "J": "long", "F": "float", "D": "double", "S": "short", "C": "char"}


def java_names(desc):
    out, i = [], 0
    while i < len(desc):
        arr = 0
        while desc[i] == "[":
            arr, i = arr + 1, i + 1
        if desc[i] == "L":
            j = desc.index(";", i)
            name = desc[i + 1:j].replace("/", ".")
            i = j + 1
            out.append("[" * arr + "L" + name + ";" if arr else name)
        else:
            out.append("[" * arr + desc[i] if arr else PRIM[desc[i]])
            i += 1
    return out


def lines(smali_dir, iface):
    path = Path(smali_dir) / (iface.replace(".", "/") + ".smali")
    stub = Path(smali_dir) / (iface.replace(".", "/") + "$Stub.smali")
    methods = {}
    for name, params in re.findall(r"^\.method public abstract (\w+)\(([^)]*)\)", path.read_text(), re.M):
        methods.setdefault(name, []).append(params)
    codes = {n: int(v, 16) for n, v in re.findall(r"TRANSACTION_(\w+):I = (0x[0-9a-f]+)", stub.read_text())}
    out = []
    for name, code in sorted(codes.items(), key=lambda kv: kv[1]):
        sigs = methods.get(name, [])
        if len(sigs) != 1:
            raise SystemExit(f"{name}: {len(sigs)} declarations")
        out.append(f"{code}:{name}({','.join(java_names(sigs[0]))})")
    return out


if __name__ == "__main__":
    ls = lines(sys.argv[1], sys.argv[2])
    text = "\n".join(ls)
    print(len(ls), "calls")
    for l in ls:
        if "[" in l:
            print("  array:", l)
    print(hashlib.sha256(text.encode()).hexdigest())
