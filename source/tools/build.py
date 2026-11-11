#!/usr/bin/env python3
"""cp-climate M1: build the code segment and turn it into an HMG-API memory recipe.

usage: build.py <stock libCarPlayCore.so> <HMG-API source> <out dir> [full|a|b|c]

1. compiles native/seg.S + native/hooks.c for the head unit (Thumb-2, no libc, no relocations)
   and links them as one block at SEG_VA;
2. fills cpc_table: each entry = (target in libCarPlayCore) - (address of cpc_table);
3. makes a patched copy of the stock library: the block appended as a PT_LOAD at
   SEG_VA (file offset = vaddr), and a b.w into it at each hook site;
4. runs HMG-API's tools/mkmemrecipe.py on (stock, patched).
Needs clang, ld.lld and pyelftools."""
import hashlib, os, struct, subprocess, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile

R = Path(__file__).resolve().parents[1]
STOCK_MD5 = "d3a47296437df56fb1ffc7ac3dfca4cb"
SEG_VA = 0x30000

# hook sites: (vaddr, stock bytes, stub symbol)
SITES = [
    (0x9c20, "fff7ecfa", "cpc_stub_info"),     # bl AirPlayCopyServerInfo
    (0x12430, "dff8f007", "cpc_stub_setup"),   # ldr.w r0, [pc, #0x7f0]
    (0x11718, "fb487844", "cpc_stub_stream"),  # ldr r0, [pc, #0x3ec]; add r0, pc (unsupported stream type)
    (0xaf10, "81460028", "cpc_stub_command"),  # mov r9, r0; cmp r0, #0 (POST /command, after its "type")
]
# the setup log (cp-setup-log 0.1.x): the phone's SETUP request printed at Notice with its plist.
# Part of this recipe because HMG-API applies one recipe per library and mod (the first that fits).
SETUP_LOG = [(0x10ef4, "1e2b", "322b"), (0x1254a, "1e21", "3221"), (0x12564, "0021", "0121"), (0x12580, "1e22", "3222")]
# variant f: DiPO's own version, as the phone hears it (/info sourceVersion constant, Bonjour srcvers
# and the "Server: AirTunes/%s" header, the timing User-Agent), 320.17.6 -> LIVI's 950.7.1, in place
VERSION = [(0x19850, "3332302e31372e36", "3935302e372e3100"),
           (0x1a238, "3332302e31372e36", "3935302e372e3100"),
           (0x1d420, "3332302e31372e36", "3935302e372e3100")]
# table targets (cpc.h order): library vaddrs (Thumb code with bit 0 set), GOT slots, PLT names
TABLE = [0x91fd, 0x9c25, 0x12437, 0x200c8, 0x1fb20, 0x1fb1c, 0x1fb24,
         "CFDictionaryGetValue", "CFDictionarySetValue", "CFRelease", "CFBinaryPlistV0CreateWithData",
         "CFArrayGetCount", "CFArrayGetValueAtIndex", "CFEqual", "CFArrayCreateMutable",
         "CFArrayAppendValue", "CFDictionaryCreateMutable", "CFDictionarySetInt64",
         "CFStringGetCStringPtr", "CFStringCreateWithCString", "LogPrintF", "CFGetTypeID",
         "CFArrayGetTypeID", "CFStringGetTypeID", "CFDictionaryGetTypeID",
         "CFBinaryPlistV0Create", "free", "CFDictionaryApplyFunction",
         0x1171d, 0x11725, 0xf015, 0xd59d, "CFDictionaryGetInt64",
         0xaf19, 0xb6d1, 0xaf6d,
         "dispatch_get_global_queue", "dispatch_async_f", "dispatch_sync_f", "HTTPMessageCreate",
         "HTTPHeader_InitRequest", "HTTPHeader_AddFieldF", "HTTPMessageSetBodyPtr", "HTTPClientSendMessage",
         "CFRetain", "malloc", "memcpy"]
# test variants (optional 4th argument): which of M1's additions are compiled in
VARIANTS = {
    "full": [],                                                        # 0.1.0: everything
    "a": ["-DCPC_FEATURES=0"],                                         # /info only
    "b": ["-DCPC_INFO=0"],                                             # SETUP features only
    "c": ["-DCPC_CLUSTER=0"],                                          # both, no instrument panel
    "e": ["-DCPC_FEATURES=0", "-DCPC_PANELS=0"],                        # /info: vehicleStateProtocolInfo only
    "f": [],                                                           # everything, reported as 950.7.1
    "d": ["-DCPC_DIAG=1", "-DCPC_FEATURES=0"],                                       # /info stock; save what would be sent
}
ARM = ["--target=armv7a-linux-androideabi", "-mthumb", "-march=armv7-a", "-mfloat-abi=soft", "-Os",
       "-fPIC", "-ffreestanding", "-fno-builtin", "-fno-stack-protector", "-fno-unwind-tables",
       "-fno-asynchronous-unwind-tables", "-fno-jump-tables", "-fvisibility=hidden", "-fno-common", "-Wall", "-Wextra", "-Werror"]


def run(*a):
    subprocess.run([str(x) for x in a], check=True)


def plt_targets(elf_bytes):
    """name -> PLT entry vaddr, from .rel.plt order"""
    import io
    e = ELFFile(io.BytesIO(elf_bytes))
    plt = e.get_section_by_name(".plt"); rel = e.get_section_by_name(".rel.plt")
    sym = e.get_section_by_name(".dynsym")
    out = {}
    for i, r in enumerate(rel.iter_relocations()):
        out[sym.get_symbol(r["r_info_sym"]).name] = plt["sh_addr"] + 20 + 12 * i
    return out


def bw(at, to):
    """Thumb-2 B.W (T4) at vaddr at to vaddr to"""
    off = (to & ~1) - (at + 4)
    assert -(1 << 24) <= off < (1 << 24) and off % 2 == 0
    s = 1 if off < 0 else 0
    imm = off & ((1 << 25) - 1)
    i1 = (imm >> 23) & 1; i2 = (imm >> 22) & 1
    j1 = (1 - i1) ^ s; j2 = (1 - i2) ^ s
    hw1 = 0xf000 | (s << 10) | ((imm >> 12) & 0x3ff)
    hw2 = 0x9000 | (j1 << 13) | (j2 << 11) | ((imm >> 1) & 0x7ff)
    return struct.pack("<HH", hw1, hw2)


def main():
    stock_path, hmgapi, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    variant = sys.argv[4] if len(sys.argv) > 4 else "full"
    flags = VARIANTS[variant]
    out.mkdir(parents=True, exist_ok=True)
    stock = stock_path.read_bytes()
    if hashlib.md5(stock).hexdigest() != STOCK_MD5:
        sys.exit("not the CEEV.USA 260507 libCarPlayCore.so")
    clang = os.environ.get("CLANG", "clang"); lld = os.environ.get("LLD", "ld.lld")
    nat = R / "native"
    run(clang, *ARM, "-I", nat, f'-DVSPI_FILE="{R / "data/vspi.bplist"}"', "-c", nat / "seg.S", "-o", out / "seg.o")
    run(clang, *ARM, "-I", nat, *flags, "-c", nat / "hooks.c", "-o", out / "hooks.o")
    (out / "seg.ld").write_text(f"""ENTRY(cpc_stub_info)
SECTIONS {{
  . = {SEG_VA:#x};
  .seg : {{ *(.text.entry) *(.text .text.*) *(.rodata .rodata.*) }}
  /DISCARD/ : {{ *(.ARM.exidx*) *(.ARM.attributes) *(.comment) *(.note*) }}
}}
""")
    run(lld, "-static", "--no-dynamic-linker", "-T", out / "seg.ld", "-o", out / "seg.elf", out / "seg.o", out / "hooks.o")
    with open(out / "seg.elf", "rb") as f:
        e = ELFFile(f)
        names = [s.name for s in e.iter_sections() if s["sh_flags"] & 2]   # SHF_ALLOC
        if names != [".seg"]:
            sys.exit(f"segment must be one allocated section, got {names}")
        if any(s.name.startswith(".rel") for s in e.iter_sections()):
            sys.exit("segment has relocations")
        seg = e.get_section_by_name(".seg")
        code = bytearray(seg.data())
        assert seg["sh_addr"] == SEG_VA
        syms = {s.name: s["st_value"] for s in e.get_section_by_name(".symtab").iter_symbols() if s.name}
        undef = [s.name for s in e.get_section_by_name(".symtab").iter_symbols() if s.name and s["st_shndx"] == "SHN_UNDEF"]
        if undef:
            sys.exit(f"undefined symbols: {undef}")
    plt = plt_targets(stock)
    table = syms["cpc_table"]
    for k, t in enumerate(TABLE):
        target = plt[t] if isinstance(t, str) else t
        struct.pack_into("<i", code, table - SEG_VA + 4 * k, target - table)
    # patched library: the block as a PT_LOAD at SEG_VA, b.w at each site
    p = bytearray(stock)
    for at, before, stub in SITES:
        if p[at:at + 4].hex() != before:
            sys.exit(f"site {at:#x} isn't the stock instruction")
        p[at:at + 4] = bw(at, syms[stub])
    for at, before, after in SETUP_LOG:
        if p[at:at + 2].hex() != before:
            sys.exit(f"setup log site {at:#x} isn't stock")
        p[at:at + 2] = bytes.fromhex(after)
    version = VERSION if variant == "f" else []
    for at, before, after in version:
        if p[at:at + 8].hex() != before:
            sys.exit(f"version string at {at:#x} isn't stock")
        p[at:at + 8] = bytes.fromhex(after)
    if len(p) > SEG_VA:
        sys.exit("library longer than SEG_VA")
    p += b"\0" * (SEG_VA - len(p)) + code
    phoff, = struct.unpack_from("<I", p, 0x1c); phnum, = struct.unpack_from("<H", p, 0x2c)
    for k in range(phnum):
        if struct.unpack_from("<I", p, phoff + 32 * k)[0] == 0x6474e551:   # PT_GNU_STACK: file-only stand-in
            struct.pack_into("<8I", p, phoff + 32 * k, 1, SEG_VA, SEG_VA, SEG_VA, len(code), len(code), 5, 0x1000)
            break
    else:
        sys.exit("no PT_GNU_STACK to stand in for the segment's PT_LOAD")
    (out / "libCarPlayCore.patched.so").write_bytes(p)
    (out / "segment.bin").write_bytes(code)
    recipe = subprocess.run([sys.executable, "-I", hmgapi / "tools/mkmemrecipe.py", "carplay-climate-m1",
                             "/system/lib/libCarPlayCore.so", stock_path, out / "libCarPlayCore.patched.so",
                             "--code", hex(SEG_VA), "--table", f"{table:#x}:{len(TABLE)}", "--context", "8",
                             "--desc", "libCarPlayCore: vehicleStateProtocolInfo + displayPanels in /info, "
                                       "features [vehicleStateProtocol] in the SETUP reply (cp-climate M1)"],
                            check=True, capture_output=True, text=True).stdout
    # the stand-in PT_LOAD is a file-only change: keep its program header out of the recipe
    ph_lo, ph_hi = phoff, phoff + 32 * phnum
    kept = [l for l in recipe.splitlines()
            if not (l.split()[:1] in (["verify"], ["patch"]) and ph_lo <= int(l.split()[1], 0) < ph_hi)]
    changes = [l.split()[0] + " " + l.split()[1] for l in kept if l.split()[:1] in (["patch"], ["branch"])]
    want = [f"branch {at:#x}" for at, _, _ in SITES] + [f"patch {at:#x}" for at, _, _ in SETUP_LOG] \
        + [f"patch {at:#x}" for at, _, _ in version]
    if sorted(changes) != sorted(want):
        sys.exit(f"recipe changes {changes}, expected only {want}")
    recipe = "\n".join(kept) + "\n"
    (out / "carplay-climate-m1.recipe").write_text(recipe)
    print(f"segment {len(code)} bytes at {SEG_VA:#x}; table {table:#x} x{len(TABLE)}; "
          + ", ".join(f"{s}={syms[s]:#x}" for _, _, s in SITES))
    print(out / "carplay-climate-m1.recipe")


main()
