#!/usr/bin/env python3
"""Writes Java stand-ins for the car's AIDL interfaces from baksmali output of its framework, for
tests/CarApiTest.java: each interface with its methods (same names and parameter/return types) and a nested
Stub holding the same TRANSACTION_ numbers, plus empty classes for the com.hkmc types they use. CarApi then
resolves and fingerprints them on the host exactly as it does the real ones on the car.

usage: gen_car_framework.py <smali dir> <out dir> <interface>...
With --alter, the last interface gets one more call (as a different firmware might), to see the check fail."""
import re, sys
from pathlib import Path

PRIM = {"I": "int", "Z": "boolean", "B": "byte", "J": "long", "F": "float", "D": "double", "S": "short", "C": "char", "V": "void"}


def types(desc):
    out, i = [], 0
    while i < len(desc):
        arr = 0
        while desc[i] == "[":
            arr, i = arr + 1, i + 1
        if desc[i] == "L":
            j = desc.index(";", i)
            t = desc[i + 1:j].replace("/", ".").replace("$", ".")
            i = j + 1
        else:
            t = PRIM[desc[i]]
            i += 1
        out.append(t + "[]" * arr)
    return out


def main():
    args = [a for a in sys.argv[1:] if a != "--alter"]
    alter = "--alter" in sys.argv
    smali, out = Path(args[0]), Path(args[1])
    ifaces = args[2:]
    used = set()
    for n, iface in enumerate(ifaces):
        pkg, name = iface.rsplit(".", 1)
        src = (smali / (iface.replace(".", "/") + ".smali")).read_text()
        stub = (smali / (iface.replace(".", "/") + "$Stub.smali")).read_text()
        methods = re.findall(r"^\.method public abstract (\w+)\(([^)]*)\)(\S+)", src, re.M)
        codes = re.findall(r"TRANSACTION_(\w+):I = (0x[0-9a-f]+)", stub)
        if alter and n == len(ifaces) - 1:
            methods.append(("onSomethingNew", "I", "V"))
            codes.append(("onSomethingNew", hex(0x1000)))
        body = []
        for m, params, ret in methods:
            ps = types(params)
            for t in ps + types(ret):
                if t.startswith("com.hkmc."):
                    used.add(t.rstrip("[]"))
            body.append(f"    {types(ret)[0]} {m}({', '.join(f'{t} p{k}' for k, t in enumerate(ps))});")
        consts = "\n".join(f"        static final int TRANSACTION_{c} = {v};" for c, v in codes)
        java = f"package {pkg};\n\npublic interface {name} {{\n" + "\n".join(body) + \
               f"\n\n    public static class Stub {{\n{consts}\n    }}\n}}\n"
        p = out / (iface.replace(".", "/") + ".java")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(java)
    for t in sorted(used - set(ifaces)):
        pkg, name = t.rsplit(".", 1)
        p = out / (t.replace(".", "/") + ".java")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(f"package {pkg};\n\npublic class {name} {{\n}}\n")
    print(len(ifaces), "interfaces,", len(used - set(ifaces)), "other classes")


main()
