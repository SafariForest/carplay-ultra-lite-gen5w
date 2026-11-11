"""Lists methods and fields a dex uses on java.*/javax.*/android.*/org.json/org.w3c/org.xml/dalvik classes that
an Android API's android.jar doesn't have (inherited members count).  usage: api19.py classes.dex android.jar"""
import struct, sys, zipfile

def dex_refs(path):
    b = open(path, 'rb').read()
    def uleb(o):
        r = s = 0
        while True:
            c = b[o]; o += 1; r |= (c & 0x7f) << s; s += 7
            if c < 0x80: return r, o
    sid_n, sid_o = struct.unpack_from('<II', b, 0x38)
    tid_n, tid_o = struct.unpack_from('<II', b, 0x40)
    pid_n, pid_o = struct.unpack_from('<II', b, 0x48)
    fid_n, fid_o = struct.unpack_from('<II', b, 0x50)
    mid_n, mid_o = struct.unpack_from('<II', b, 0x58)
    cd_n, cd_o = struct.unpack_from('<II', b, 0x60)
    def string(i):
        o = struct.unpack_from('<I', b, sid_o + 4 * i)[0]
        _, o = uleb(o)
        e = b.index(b'\0', o); return b[o:e].decode('utf-8', 'replace')
    def typ(i): return string(struct.unpack_from('<I', b, tid_o + 4 * i)[0])
    def proto(i):
        shorty, ret, params = struct.unpack_from('<III', b, pid_o + 12 * i)
        ps = []
        if params:
            n = struct.unpack_from('<I', b, params)[0]
            ps = [typ(struct.unpack_from('<H', b, params + 4 + 2 * k)[0]) for k in range(n)]
        return '(' + ''.join(ps) + ')' + typ(ret)
    defined = set(typ(struct.unpack_from('<I', b, cd_o + 32 * k)[0]) for k in range(cd_n))
    meths = set()
    for k in range(mid_n):
        c, p, n = struct.unpack_from('<HHI', b, mid_o + 8 * k)
        meths.add((typ(c), string(n), proto(p)))
    fields = set()
    for k in range(fid_n):
        c, t, n = struct.unpack_from('<HHI', b, fid_o + 8 * k)
        fields.add((typ(c), string(n), typ(t)))
    return defined, meths, fields

def jar_index(path):
    z = zipfile.ZipFile(path); idx = {}
    for name in z.namelist():
        if not name.endswith('.class'): continue
        d = z.read(name); o = 8
        n = struct.unpack_from('>H', d, o)[0]; o += 2; cp = [None] * n; i = 1
        while i < n:
            t = d[o]
            if t == 1:
                l = struct.unpack_from('>H', d, o + 1)[0]; cp[i] = d[o + 3:o + 3 + l].decode('utf-8', 'replace'); o += 3 + l
            elif t in (7, 8, 16, 19, 20): cp[i] = ('ref', struct.unpack_from('>H', d, o + 1)[0]); o += 3
            elif t in (3, 4): o += 5
            elif t in (5, 6): o += 9; i += 1
            elif t in (9, 10, 11, 12, 17, 18): o += 5
            elif t == 15: o += 4
            else: raise ValueError(t)
            i += 1
        this = cp[cp[struct.unpack_from('>H', d, o + 2)[0]][1]]
        sup_i = struct.unpack_from('>H', d, o + 4)[0]
        sup = cp[cp[sup_i][1]] if sup_i else None
        ni = struct.unpack_from('>H', d, o + 6)[0]; o += 8
        ifs = [cp[cp[struct.unpack_from('>H', d, o + 2 * k)[0]][1]] for k in range(ni)]; o += 2 * ni
        members = set()
        for kind in ('f', 'm'):
            cnt = struct.unpack_from('>H', d, o)[0]; o += 2
            for _ in range(cnt):
                nm = cp[struct.unpack_from('>H', d, o + 2)[0]]; ds = cp[struct.unpack_from('>H', d, o + 4)[0]]
                ac = struct.unpack_from('>H', d, o + 6)[0]; o += 8
                for _ in range(ac):
                    l = struct.unpack_from('>I', d, o + 2)[0]; o += 6 + l
                members.add((kind, nm, ds))
        idx['L' + this + ';'] = (('L' + sup + ';') if sup else None, ['L' + x + ';' for x in ifs], members)
    return idx

def has(idx, cls, kind, name, desc, seen=None):
    seen = seen or set()
    if cls in seen or cls not in idx: return False
    seen.add(cls)
    sup, ifs, mem = idx[cls]
    if (kind, name, desc) in mem: return True
    for p in ([sup] if sup else []) + ifs:
        if has(idx, p, kind, name, desc, seen): return True
    return False

LIB = ('Ljava/', 'Ljavax/', 'Landroid/', 'Lorg/json/', 'Lorg/w3c/', 'Lorg/xml/', 'Ldalvik/', 'Lorg/apache/http/')
defined, meths, fields = dex_refs(sys.argv[1])
idx = jar_index(sys.argv[2])
missing = []
for c, n, p in sorted(meths):
    if c in defined or not c.startswith(LIB) or c.startswith('['): continue
    if c not in idx: missing.append(('class', c, '', '')); continue
    if not has(idx, c, 'm', n, p): missing.append(('method', c, n, p))
for c, n, t in sorted(fields):
    if c in defined or not c.startswith(LIB): continue
    if c not in idx: missing.append(('class', c, '', '')); continue
    if not has(idx, c, 'f', n, t): missing.append(('field', c, n, t))
seen = set()
for m in missing:
    if m in seen: continue
    seen.add(m); print(*m)
print(len(seen), 'missing', file=sys.stderr)
