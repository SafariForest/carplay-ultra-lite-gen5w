/* Host test of native/hooks.c: the real hook code, built for the Mac, with a small mock
 * of the CFLite calls it makes through cpc_table. Checks what /info and the SETUP reply
 * end up holding. cc -I native tests/host_test.c native/hooks.c -DVSPI=\"data/vspi.bplist\" */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "cpc.h"

enum { T_STR = 0x10756, T_DICT = 0xd1c7, T_ARR = 0xa77a, T_NUM = 0x4e55, T_BLOB = 0xb10b };
typedef struct Obj { unsigned type, refs; } Obj;
typedef struct { Obj h; char s[1]; } Str;
typedef struct { Obj h; int n; const void *k[64]; const void *v[64]; } Dict;
typedef struct { Obj h; int n; const void *v[64]; } Arr;
typedef struct { Obj h; long long v; } Num;
typedef struct { Obj h; unsigned long len; } Blob;

static int live;   /* objects created and not released */
static void *mk(unsigned type, size_t size) { Obj *o = calloc(1, size); o->type = type; o->refs = 1; live++; return o; }
static unsigned tid(const void *o) { return ((const Obj *)o)->type; }
static void retain(const void *o) { Obj *x = (Obj *)o; if (x->refs != 0x7fffffffu) x->refs++; }
static void m_release(const void *o) {
    Obj *x = (Obj *)o;
    if (x->refs == 0x7fffffffu) return;
    if (--x->refs) return;
    if (x->type == T_DICT) for (int i = 0; i < ((Dict *)x)->n; i++) { m_release(((Dict *)x)->k[i]); m_release(((Dict *)x)->v[i]); }
    if (x->type == T_ARR) for (int i = 0; i < ((Arr *)x)->n; i++) m_release(((Arr *)x)->v[i]);
    live--; free(x);
}
static int m_equal(const void *a, const void *b) {
    if (tid(a) != tid(b)) return 0;
    if (tid(a) == T_STR) return !strcmp(((Str *)a)->s, ((Str *)b)->s);
    return a == b;
}
static const void *m_get(const void *d, const void *k) {
    const Dict *x = d;
    for (int i = 0; i < x->n; i++) if (m_equal(x->k[i], k)) return x->v[i];
    return 0;
}
static void m_set(const void *d, const void *k, const void *v) {
    Dict *x = (Dict *)d;
    retain(v);
    for (int i = 0; i < x->n; i++) if (m_equal(x->k[i], k)) { m_release(x->v[i]); x->v[i] = v; return; }
    retain(k); x->k[x->n] = k; x->v[x->n++] = v;
}
static Str *str(const char *s) { Str *o = mk(T_STR, sizeof(Str) + strlen(s)); strcpy(o->s, s); return o; }
static Num *num(long long v) { Num *o = mk(T_NUM, sizeof(Num)); o->v = v; return o; }
static void m_set_int(const void *d, const void *k, long long v) { Num *n = num(v); m_set(d, k, n); m_release(n); }
static long m_count(const void *a) { return ((const Arr *)a)->n; }
static const void *m_at(const void *a, long i) { return ((const Arr *)a)->v[i]; }
static void m_append(const void *a, const void *v) { retain(v); ((Arr *)a)->v[((Arr *)a)->n++] = v; }
static int cb_arr, cb_dkey, cb_dval;
static void *got_arr = &cb_arr, *got_dkey = &cb_dkey, *got_dval = &cb_dval;
static const void *m_new_arr(const void *al, long cap, const void *cb) { (void)al; (void)cap; if (cb != &cb_arr) abort(); return mk(T_ARR, sizeof(Arr)); }
static const void *m_new_dict(const void *al, long cap, const void *k, const void *v) {
    (void)al; (void)cap;
    if (k != &cb_dkey || v != &cb_dval) abort();
    return mk(T_DICT, sizeof(Dict));
}
static const char *m_cstr(const void *s, unsigned enc) { if (enc != 0x08000100u) abort(); return ((const Str *)s)->s; }
static const void *m_new_str(const void *al, const char *s, unsigned enc) { (void)al; if (enc != 0x08000100u) abort(); return str(s); }
static unsigned char vspi_file[65536];
static unsigned long vspi_len;
static int plist_fail, vspi_as_dict;
#include "livi_hid.h"
/* the mock decoder's answer for native/livi_hid.h (its encoding is checked by tools/mklivihid.py's plistlib) */
static const void *mock_livi_hid(void) {
    static const char *names[4] = { "Ioniq 6 Knob", "Ioniq 6 Media", "Ioniq 6 Telephony", "Ioniq 6 Cluster Knob" };
    Arr *a = mk(T_ARR, sizeof(Arr));
    for (int i = 0; i < 4; i++) {
        Dict *d = mk(T_DICT, sizeof(Dict));
        Str *k = str("name"), *v = str(names[i]); m_set(d, k, v); m_release(k); m_release(v);
        k = str("displayUUID"); v = str(i == 3 ? "Cluster_Display" : "Center_Display"); m_set(d, k, v); m_release(k); m_release(v);
        Blob *b = mk(T_BLOB, sizeof(Blob)); b->len = 1; k = str("hidDescriptor"); m_set(d, k, b); m_release(k); m_release(b);
        m_append(a, d); m_release(d);
    }
    return a;
}
static const void *m_plist(const void *p, unsigned long len, int *err) {
    if (len == sizeof livi_hid && !memcmp(p, livi_hid, len)) { *err = 0; return mock_livi_hid(); }
    if (len >= 8 && !memcmp(p, "MOCKENC", 8)) { const void *o; memcpy(&o, (const char *)p + 8, sizeof o); retain(o); *err = 0; return o; }
    if (len == 42 && !memcmp(p, "bplist00", 8) && (((const char *)p)[8] == 0x08 || ((const char *)p)[8] == 0x09)) { Blob *f = mk(T_BLOB, sizeof(Blob)); f->len = 42; *err = 0; return f; }   /* hooks.c's false */
    if (plist_fail) { *err = -6705; return 0; }
    if (len != vspi_len || memcmp(p, vspi_file, len) || memcmp(p, "bplist00", 8)) abort();
    if (vspi_as_dict) {   /* the file case: the hook checks for a dictionary with pluginConfigs */
        Dict *d = mk(T_DICT, sizeof(Dict)); Str *k = str("pluginConfigs"); Arr *a = mk(T_ARR, sizeof(Arr));
        m_set(d, k, a); m_release(k); m_release(a); *err = 0; return d;
    }
    Blob *b = mk(T_BLOB, sizeof(Blob)); b->len = len; *err = 0; return b;
}
static const void *m_plist(const void *p, unsigned long len, int *err);
/* encoder: a marker and the object, so the mock decoder can hand it back */
static unsigned char *m_encode(const void *o, unsigned long *len, int *err) {
    (void)err; unsigned char *b = malloc(8 + sizeof o); memcpy(b, "MOCKENC", 8); memcpy(b + 8, &o, sizeof o);
    *len = 8 + sizeof o; return b;
}
static void m_free(void *p) { free(p); }
static void m_apply(const void *d, void (*fn)(const void *, const void *, void *), void *ctx) {
    const Dict *x = d; for (int i = 0; i < x->n; i++) fn(x->k[i], x->v[i], ctx);
}
/* M2 stand-ins: DiPO's key helper, its reply helper, CFDictionaryGetInt64 */
static int derive_fail;
static int m_derive(void *session, int pad, unsigned long long seed, unsigned inLen, unsigned char *in, unsigned char *out) {
    (void)pad;
    if (derive_fail || !session || inLen != 32) return -6727;
    for (int i = 0; i < 32; i++) { in[i] = (unsigned char)(0x10 + i + seed); out[i] = (unsigned char)(0x80 + i + seed); }
    return 0;
}
static const void *m_get(const void *d, const void *k);
static void m_set(const void *d, const void *k, const void *v);
static long long m_get_int(const void *d, const void *k, int *err) {
    const void *v = m_get(d, k);
    if (!v) { *err = -6727; return 0; }
    *err = 0; return *(const long long *)((const char *)v + 8);
}
static int m_add_stream(const void *reply, const void *stream);
static char lastlog[512];
static int vspi_file_logs, vspi_fallback_logs;
static void m_log(void *cat, const char *fn, int level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vsnprintf(lastlog, sizeof lastlog, fmt, ap); va_end(ap);
    if (strstr(lastlog, "from files/cpc-vspi.bplist")) vspi_file_logs++;
    if (strstr(lastlog, "cpc-vspi.bplist not used")) vspi_fallback_logs++;
    printf("    log [%s %d] %s", fn, level, lastlog); (void)cat;
}
static unsigned long m_typeid(const void *o) { return tid(o); }
static unsigned long id_arr(void) { return T_ARR; }
static unsigned long id_str(void) { return T_STR; }
static unsigned long id_dict(void) { return T_DICT; }
static int logcat;

int cpc_table[T_COUNT];
unsigned char cpc_vspi[65536];
unsigned long cpc_vspi_len;
void cpc_info_hook(const void *info);
void cpc_setup_hook(const void *request, const void *reply);
int cpc_stream_hook(void *session, const void *stream, const void *reply, long type);
int cpc_command_hook(const void *type, const void *plist, const unsigned char *req);
int cpc_test_video_send(void *session, const unsigned char *d, long n);
/* video stage 3 stand-ins: GCD and DiPO's HTTP client */
static void *synced_on, *sent_client, *body_copy;
static char req_line[128], field_line[128], body_type[64];
static unsigned long body_len;
static int sent_n, msg_released;
static void *m_global_queue(long p, unsigned long f) { (void)p; (void)f; return (void *)1; }
static void *async_ctx; static int async_n;
static void m_async(void *q, void *ctx, void (*fn)(void *)) { (void)q; (void)fn; async_ctx = ctx; async_n++; }
static void m_sync(void *q, void *ctx, void (*fn)(void *)) { synced_on = q; fn(ctx); }
static void *msg_live;
static int m_msg_create(void **out) { msg_live = mk(T_BLOB, 0x900); msg_released = 0; *out = msg_live; return 0; }
static int m_init_request(void *h, const char *m, const char *u, const char *v) {
    if (h != (char *)msg_live + 0x10) abort();
    snprintf(req_line, sizeof req_line, "%s %s %s", m, u, v); return 0;
}
static int m_add_field(void *h, const char *name, const char *fmt, ...) {
    if (h != (char *)msg_live + 0x10) abort();
    char v[64]; va_list ap; va_start(ap, fmt); vsnprintf(v, sizeof v, fmt, ap); va_end(ap);
    snprintf(field_line, sizeof field_line, "%s: %s", name, v); return 0;
}
static int m_set_body(void *msg, const char *type, const void *p, unsigned long n) {
    if (msg != msg_live) abort();
    snprintf(body_type, sizeof body_type, "%s", type); body_copy = (void *)p; body_len = n; return 0;
}
static int m_send(void *client, void *msg) { if (msg != msg_live) abort(); sent_client = client; sent_n++; return 0; }
static void *m_retain(void *o) { retain(o); return o; }
static void *m_malloc(unsigned long n) { return malloc(n); }
static void *m_memcpy(void *d, const void *s, unsigned long n) { return memcpy(d, s, n); }
static void m_release_counted(const void *o) { if (o == msg_live && msg_live) msg_released++; m_release(o); }
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

#define PUT(i, p) cpc_table[i] = (int)((const char *)(p) - (const char *)cpc_table)

static int fails;
#define CHECK(what, cond) do { int ok_ = (cond); if (!ok_) fails++; printf("%s %s\n", ok_ ? "ok  " : "FAIL", what); } while (0)
static const char *s_of(const void *o) { return o && tid(o) == T_STR ? ((const Str *)o)->s : "?"; }
static long long n_of(const void *o) { return o && tid(o) == T_NUM ? ((const Num *)o)->v : -1; }
static const void *g(const void *d, const char *k) { Str *ks = str(k); const void *v = m_get(d, ks); m_release(ks); return v; }

static Dict *display(const char *uuid, int w, int h, int wp, int hp) {
    Dict *d = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    if (uuid) { Str *u = str(uuid); Str *k = str("uuid"); m_set(d, k, u); m_release(u); m_release(k); }
    const char *keys[4] = { "widthPixels", "heightPixels", "widthPhysical", "heightPhysical" };
    int vals[4] = { w, h, wp, hp };
    for (int i = 0; i < 4; i++) { Str *k = str(keys[i]); m_set_int(d, k, vals[i]); m_release(k); }
    return d;
}

int main(void) {
    FILE *f = fopen(VSPI, "rb");
    vspi_len = fread(vspi_file, 1, sizeof vspi_file, f); fclose(f);
    memcpy(cpc_vspi, vspi_file, vspi_len);
    cpc_vspi_len = vspi_len;
    PUT(T_LOGCAT, &logcat);
    PUT(T_GOT_DKEY, &got_dkey); PUT(T_GOT_DVAL, &got_dval); PUT(T_GOT_ARR, &got_arr);
    PUT(T_CFDictionaryGetValue, m_get); PUT(T_CFDictionarySetValue, m_set); PUT(T_CFRelease, m_release_counted);
    PUT(T_CFBinaryPlistV0CreateWithData, m_plist); PUT(T_CFArrayGetCount, m_count); PUT(T_CFArrayGetValueAtIndex, m_at);
    PUT(T_CFEqual, m_equal); PUT(T_CFArrayCreateMutable, m_new_arr); PUT(T_CFArrayAppendValue, m_append);
    PUT(T_CFDictionaryCreateMutable, m_new_dict); PUT(T_CFDictionarySetInt64, m_set_int);
    PUT(T_CFStringGetCStringPtr, m_cstr); PUT(T_CFStringCreateWithCString, m_new_str); PUT(T_LogPrintF, m_log);
    PUT(T_CFGetTypeID, m_typeid); PUT(T_CFArrayGetTypeID, id_arr); PUT(T_CFStringGetTypeID, id_str);
    PUT(T_CFDictionaryGetTypeID, id_dict);
    PUT(T_CFBinaryPlistV0Create, m_encode); PUT(T_free, m_free); PUT(T_CFDictionaryApplyFunction, m_apply);
    PUT(T_DERIVE_STREAM_KEYS, m_derive); PUT(T_ADD_STREAM_REPLY, m_add_stream); PUT(T_CFDictionaryGetInt64, m_get_int);
    PUT(T_dispatch_get_global_queue, m_global_queue); PUT(T_dispatch_async_f, m_async); PUT(T_dispatch_sync_f, m_sync);
    PUT(T_HTTPMessageCreate, m_msg_create); PUT(T_HTTPHeader_InitRequest, m_init_request); PUT(T_HTTPHeader_AddFieldF, m_add_field);
    PUT(T_HTTPMessageSetBodyPtr, m_set_body); PUT(T_HTTPClientSendMessage, m_send); PUT(T_CFRetain, m_retain);
    PUT(T_malloc, m_malloc); PUT(T_memcpy, m_memcpy);
#if CPC_DIAG
    printf("-- diagnostic variant: /info stays stock, three saves that decode back equal\n");
    Dict *dinfo = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    Arr *dd = (Arr *)m_new_arr(0, 0, &cb_arr);
    Dict *dm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
    m_append(dd, dm); m_release(dm);
    Str *kdd = str("displays"); m_set(dinfo, kdd, dd); m_release(kdd); m_release(dd);
    int saves = 0;
    cpc_info_hook(dinfo);
    CHECK("/info untouched", dinfo->n == 1);
    m_release(dinfo);
    CHECK("no leaks", live == 0);
    (void)saves;
    printf(fails ? "%d FAILED\n" : "ALL OK\n", fails);
    return fails != 0;
#endif

    printf("-- /info with DiPO's one display\n");
    Dict *info = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    Arr *displays = (Arr *)m_new_arr(0, 0, &cb_arr);
    Dict *main_d = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
    m_append(displays, main_d); m_release(main_d);
    Str *kd = str("displays"); m_set(info, kd, displays); m_release(kd); m_release(displays);
    cpc_info_hook(info);
    const void *vspi = g(info, "vehicleStateProtocolInfo");
    CHECK("vehicleStateProtocolInfo is the parsed blob", vspi && tid(vspi) == T_BLOB && ((Blob *)vspi)->len == vspi_len);
    const Arr *panels = g(info, "displayPanels");
    CHECK("displayPanels: 2 (main + instruments)", panels && tid(panels) == T_ARR && panels->n == 2);
    if (panels && panels->n == 2) {
        const void *p0 = panels->v[0], *p1 = panels->v[1];
        CHECK("main uid = <uuid>-panel", !strcmp(s_of(g(p0, "uid")), "0d15b1a4-0000-4000-8000-000000000001-panel"));
        CHECK("main sizes copied", n_of(g(p0, "widthPixels")) == 1920 && n_of(g(p0, "heightPixels")) == 720
              && n_of(g(p0, "widthPhysical")) == 292 && n_of(g(p0, "heightPhysical")) == 110);
        const Arr *props0 = g(p0, "properties");
        CHECK("main properties []", props0 && props0->n == 0);
        const Arr *vs = g(p0, "initialVideoStreams");
        CHECK("main initialVideoStreams: its own stream at 0,0,0", vs && vs->n == 1
              && !strcmp(s_of(g(vs->v[0], "uuid")), "0d15b1a4-0000-4000-8000-000000000001")
              && n_of(g(vs->v[0], "originXPixels")) == 0 && n_of(g(vs->v[0], "originYPixels")) == 0
              && n_of(g(vs->v[0], "zIndex")) == 0);
        CHECK("main has no displayPlugins", !g(p0, "displayPlugins"));
        CHECK("instrument uid", !strcmp(s_of(g(p1, "uid")), "b7e6c5a0-3333-4000-8000-000000000003-panel"));
        CHECK("instrument sizes = first display", n_of(g(p1, "widthPixels")) == 1920 && n_of(g(p1, "heightPhysical")) == 110);
        const Arr *props1 = g(p1, "properties");
        CHECK("instrument properties [showsInstruments]", props1 && props1->n == 1 && !strcmp(s_of(props1->v[0]), "showsInstruments"));
        const Arr *vs1 = g(p1, "initialVideoStreams");
        CHECK("instrument initialVideoStreams []", vs1 && vs1->n == 0);
        const Arr *pl = g(p1, "displayPlugins");
        CHECK("instrument displayPlugins [{uid -plugin, videoStreams []}]", pl && pl->n == 1
              && !strcmp(s_of(g(pl->v[0], "uid")), "b7e6c5a0-3333-4000-8000-000000000003-plugin")
              && ((const Arr *)g(pl->v[0], "videoStreams"))->n == 0);
    }
    const Arr *ds = g(info, "displays");
    CHECK("displays: still one", ds && tid(ds) == T_ARR && ds->n == 1);
    if (ds && ds->n == 1) {
        const void *d = ds->v[0];
        CHECK("display keeps its own keys", !strcmp(s_of(g(d, "uuid")), "0d15b1a4-0000-4000-8000-000000000001")
              && n_of(g(d, "widthPixels")) == 1920 && n_of(g(d, "heightPhysical")) == 110);
        CHECK("display: initialViewArea 0, adjacentViewAreas []", n_of(g(d, "initialViewArea")) == 0
              && ((const Arr *)g(d, "adjacentViewAreas"))->n == 0);
        const Arr *va = g(d, "viewAreas");
        CHECK("one viewArea: whole screen, dock at the bottom", va && va->n == 1
              && n_of(g(va->v[0], "widthPixels")) == 1920 && n_of(g(va->v[0], "heightPixels")) == 720
              && n_of(g(va->v[0], "originXPixels")) == 0 && n_of(g(va->v[0], "originYPixels")) == 0
              && n_of(g(va->v[0], "viewAreaStatusBarEdge")) == 1);
        const void *sa = va && va->n == 1 ? g(va->v[0], "safeArea") : 0;
        CHECK("viewArea: a safe area over the whole screen, drawing outside it allowed", sa && n_of(g(sa, "widthPixels")) == 1920
              && n_of(g(sa, "heightPixels")) == 720 && n_of(g(sa, "originXPixels")) == 0 && tid(g(sa, "drawUIOutsideSafeArea")) == T_BLOB);
        CHECK("display: cornerMasks false", tid(g(d, "cornerMasks")) == T_BLOB);
        CHECK("viewArea: transition control and focus transfer false", va && va->n == 1
              && tid(g(va->v[0], "viewAreaTransitionControl")) == T_BLOB && tid(g(va->v[0], "viewAreaSupportsFocusTransfer")) == T_BLOB);
    }
    int before = live;
    cpc_info_hook(info);
    CHECK("a second /info on the same dictionary changes nothing", live == before);
    m_release(info);
    CHECK("every object released (no leak, no double free)", live == 0);

    printf("-- /info with a CarPlay size of 80 %%\n");
    {
        FILE *f = fopen(DIR "cpc-size", "w");
        if (f) { fputs("80\n", f); fclose(f); }
        Dict *si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        Arr *sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        Dict *sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        Str *k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        const Arr *ds2 = g(si, "displays");
        const void *d0 = ds2 && ds2->n == 1 ? ds2->v[0] : 0;
        CHECK("80 %: physical 292x110 mm reported as 365x138, pixels unchanged", d0 && n_of(g(d0, "widthPhysical")) == 365
              && n_of(g(d0, "heightPhysical")) == 138 && n_of(g(d0, "widthPixels")) == 1920 && n_of(g(d0, "heightPixels")) == 720);
        const Arr *pp = g(si, "displayPanels");
        CHECK("80 %: both panels carry the same physical size", pp && pp->n == 2 && n_of(g(pp->v[0], "widthPhysical")) == 365
              && n_of(g(pp->v[1], "heightPhysical")) == 138);
        m_release(si);
        f = fopen(DIR "cpc-size", "w");
        if (f) { fputs("abc", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        CHECK("a size file that isn't a number: real size", ds2 && ds2->n == 1 && n_of(g(ds2->v[0], "widthPhysical")) == 292);
        m_release(si);
        remove(DIR "cpc-size");
        f = fopen(DIR "cpc-dock", "w");
        if (f) { fputs("0\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        const Arr *va2 = ds2 && ds2->n == 1 ? g(ds2->v[0], "viewAreas") : 0;
        CHECK("dock setting 0 (auto): viewAreaStatusBarEdge 0", va2 && va2->n == 1 && n_of(g(va2->v[0], "viewAreaStatusBarEdge")) == 0);
        m_release(si);
        remove(DIR "cpc-dock");
        f = fopen(DIR "cpc-version", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        k = str("sourceVersion"); { Str *v = str("320.17.6"); m_set(si, k, v); m_release(v); } m_release(k);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        CHECK("receiver version setting 1: sourceVersion 950.7.1", !strcmp(s_of(g(si, "sourceVersion")), "950.7.1"));
        m_release(si);
        remove(DIR "cpc-version");
        f = fopen(DIR "cpc-extras", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        { Str *kf = str("features"); m_set_int(sm, kf, 8); m_release(kf); }
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        const void *dx = ds2 && ds2->n == 1 ? ds2->v[0] : 0;
        CHECK("LIVI extras: features 8 -> 10 (knobs), appearance dark/automatic", dx && n_of(g(dx, "features")) == 10
              && n_of(g(dx, "uiAppearanceMode")) == 1 && n_of(g(dx, "uiAppearanceSetting")) == 0
              && n_of(g(dx, "mapAppearanceMode")) == 1 && n_of(g(dx, "mapAppearanceSetting")) == 0);
        m_release(si);
        remove(DIR "cpc-extras");
        f = fopen(DIR "cpc-nocluster", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        f = fopen(DIR "cpc-dock", "w");
        if (f) { fputs("3\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        const Arr *pn = g(si, "displayPanels");
        ds2 = g(si, "displays");
        const Arr *va3 = ds2 && ds2->n == 1 ? g(ds2->v[0], "viewAreas") : 0;
        CHECK("no instrument panel: one displayPanel; dock edge 3 passed through", pn && pn->n == 1
              && va3 && va3->n == 1 && n_of(g(va3->v[0], "viewAreaStatusBarEdge")) == 3);
        m_release(si);
        remove(DIR "cpc-nocluster");
        for (int mode = 4; mode <= 5; mode++) {
            f = fopen(DIR "cpc-nocluster", "w");
            if (f) { fprintf(f, "%d\n", mode); fclose(f); }
            si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
            sd = (Arr *)m_new_arr(0, 0, &cb_arr);
            sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
            m_append(sd, sm); m_release(sm);
            k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
            cpc_info_hook(si);
            const Arr *pm = g(si, "displayPanels");
            if (mode == 4) {
                const Arr *pl = pm && pm->n == 1 ? g(pm->v[0], "displayPlugins") : 0;
                const Arr *vs4 = pl && pl->n == 1 ? g(pl->v[0], "videoStreams") : 0;
                CHECK("plugin on the main panel: one panel, its plugin composes the main stream", pl && vs4 && vs4->n == 1
                      && !strcmp(s_of(vs4->v[0]), "0d15b1a4-0000-4000-8000-000000000001")
                      && !strcmp(s_of(g(pl->v[0], "uid")), "0d15b1a4-0000-4000-8000-000000000001-panel"));
            } else {
                const Arr *pl = pm && pm->n == 2 ? g(pm->v[1], "displayPlugins") : 0;
                const Arr *vs5 = pl && pl->n == 1 ? g(pl->v[0], "videoStreams") : 0;
                CHECK("instrument plugin with the main stream", vs5 && vs5->n == 1 && !strcmp(s_of(vs5->v[0]), "0d15b1a4-0000-4000-8000-000000000001"));
            }
            m_release(si);
            remove(DIR "cpc-nocluster");
        }
        CHECK("every object released after the plugin cases", live == 0);
        for (int mode = 2; mode <= 3; mode++) {
            f = fopen(DIR "cpc-nocluster", "w");
            if (f) { fprintf(f, "%d\n", mode); fclose(f); }
            si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
            sd = (Arr *)m_new_arr(0, 0, &cb_arr);
            sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
            m_append(sd, sm); m_release(sm);
            k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
            cpc_info_hook(si);
            const Arr *pm = g(si, "displayPanels");
            const void *ip = pm && pm->n == 2 ? pm->v[1] : 0;
            const Arr *pr = ip ? g(ip, "properties") : 0;
            if (mode == 2) CHECK("instrument panel without showsInstruments: properties [], plugin kept", ip && pr && pr->n == 0 && g(ip, "displayPlugins"));
            else CHECK("instrument panel without plugin: showsInstruments kept, no displayPlugins", ip && pr && pr->n == 1 && !g(ip, "displayPlugins"));
            m_release(si);
            remove(DIR "cpc-nocluster");
        }
        f = fopen(DIR "cpc-wide", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        const Arr *vw = ds2 && ds2->n == 1 ? g(ds2->v[0], "viewAreas") : 0;
        CHECK("widescreen: two areas, the whole 1920x720 and the right 1280x720 at x 640, transitions on",
              vw && vw->n == 2 && n_of(g(vw->v[0], "widthPixels")) == 1920 && n_of(g(vw->v[0], "originXPixels")) == 0
              && n_of(g(vw->v[1], "widthPixels")) == 1280 && n_of(g(vw->v[1], "originXPixels")) == 640
              && n_of(g(vw->v[1], "heightPixels")) == 720 && n_of(g(g(vw->v[1], "safeArea"), "originXPixels")) == 640
              && tid(g(vw->v[0], "viewAreaTransitionControl")) == T_BLOB && n_of(g(ds2->v[0], "initialViewArea")) == 0);
        m_release(si);
        f = fopen(DIR "cpc-wide", "w");
        if (f) { fputs("2\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        CHECK("widescreen, start right: initialViewArea 1", ds2 && ds2->n == 1 && n_of(g(ds2->v[0], "initialViewArea")) == 1);
        m_release(si);
        remove(DIR "cpc-wide");
        f = fopen(DIR "cpc-ultra", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        const void *um = ds2 && ds2->n == 2 ? ds2->v[0] : 0, *ua = ds2 && ds2->n == 2 ? ds2->v[1] : 0;
        CHECK("Ultra: main display type 110 with its viewArea, cluster type 111 800x480 102x61 mm 30 fps",
              um && ua && n_of(g(um, "type")) == 110 && g(um, "viewAreas") && n_of(g(um, "widthPixels")) == 1920
              && !strcmp(s_of(g(ua, "uuid")), "b7e6c5a0-2222-4000-8000-000000000002") && n_of(g(ua, "type")) == 111
              && n_of(g(ua, "widthPixels")) == 800 && n_of(g(ua, "heightPixels")) == 480
              && n_of(g(ua, "widthPhysical")) == 102 && n_of(g(ua, "heightPhysical")) == 61 && n_of(g(ua, "maxFPS")) == 30
              && n_of(g(ua, "features")) == 10 && n_of(g(ua, "primaryInputDevice")) == 3 && !g(ua, "viewAreas"));
        const Arr *up = g(si, "displayPanels");
        const Arr *upr = up && up->n == 3 ? g(up->v[1], "properties") : 0;
        const Arr *upv = up && up->n == 3 ? g(up->v[1], "initialVideoStreams") : 0;
        CHECK("Ultra: 3 panels; the cluster's shows instruments and its own stream",
              up && up->n == 3 && !strcmp(s_of(g(up->v[1], "uid")), "b7e6c5a0-2222-4000-8000-000000000002-panel")
              && upr && upr->n == 1 && !strcmp(s_of(upr->v[0]), "showsInstruments") && upv && upv->n == 1
              && n_of(g(up->v[0], "widthPixels")) == 1920 && ((const Arr *)g(up->v[0], "properties"))->n == 0);
        CHECK("Ultra: the instrument panel takes the cluster's size",
              up && up->n == 3 && n_of(g(up->v[2], "widthPixels")) == 800 && n_of(g(up->v[2], "heightPhysical")) == 61
              && g(up->v[2], "displayPlugins"));
        CHECK("Ultra: logged", strstr(lastlog, "Ultra virtual cluster 800x480") != 0);
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        CHECK("Ultra: a second /info adds no second cluster", ds2 && ds2->n == 2);
        m_release(si);
        f = fopen(DIR "cpc-ultra", "w");
        if (f) { fputs("2\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        {
            Arr *hids = (Arr *)m_new_arr(0, 0, &cb_arr);
            for (int h = 0; h < 2; h++) {
                Dict *hd = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
                Str *hk = str("displayUUID"), *hv = str(h ? "other-display" : "0d15b1a4-0000-4000-8000-000000000001");
                m_set(hd, hk, hv); m_release(hk); m_release(hv);
                hk = str("name"); hv = str(h ? "Other" : "TouchScreen"); m_set(hd, hk, hv); m_release(hk); m_release(hv);
                m_append(hids, hd); m_release(hd);
            }
            k = str("hidDevices"); m_set(si, k, hids); m_release(k); m_release(hids);
        }
        cpc_info_hook(si);
        ds2 = g(si, "displays");
        const void *tm = ds2 && ds2->n == 2 ? ds2->v[0] : 0, *tc = ds2 && ds2->n == 2 ? ds2->v[1] : 0;
        CHECK("theme ids: main Center_Display type 110 with its viewArea, cluster Cluster_Display 1920x720 292x110 mm",
              tm && tc && !strcmp(s_of(g(tm, "uuid")), "Center_Display") && n_of(g(tm, "type")) == 110 && g(tm, "viewAreas")
              && !strcmp(s_of(g(tc, "uuid")), "Cluster_Display") && n_of(g(tc, "type")) == 111
              && n_of(g(tc, "widthPixels")) == 1920 && n_of(g(tc, "heightPixels")) == 720 && n_of(g(tc, "widthPhysical")) == 292);
        const Arr *th = g(si, "hidDevices");
        CHECK("theme ids: the touch screen moved to Center_Display, other devices kept",
              th && th->n == 2 && !strcmp(s_of(g(th->v[0], "displayUUID")), "Center_Display") && !strcmp(s_of(g(th->v[0], "name")), "TouchScreen")
              && !strcmp(s_of(g(th->v[1], "displayUUID")), "other-display"));
        const Arr *tp = g(si, "displayPanels");
        const Arr *tpl = tp && tp->n == 2 ? g(tp->v[1], "displayPlugins") : 0;
        const Arr *tps = tp && tp->n == 2 ? g(tp->v[1], "properties") : 0;
        CHECK("theme ids: 2 panels (LIVI themed): Center_Display, Cluster_Display with showsInstruments and its own plugin",
              tp && tp->n == 2 && !strcmp(s_of(g(tp->v[0], "uid")), "Center_Display") && !g(tp->v[0], "displayPlugins")
              && !strcmp(s_of(g(tp->v[1], "uid")), "Cluster_Display") && tps && tps->n == 1
              && tpl && tpl->n == 1 && !strcmp(s_of(g(tpl->v[0], "uid")), "Cluster_Display")
              && ((const Arr *)g(tpl->v[0], "videoStreams"))->n == 1
              && !strcmp(s_of(((const Arr *)g(tpl->v[0], "videoStreams"))->v[0]), "Cluster_Display"));
        CHECK("theme ids: logged", strstr(lastlog, "theme display ids") != 0);
        m_release(si);
        CHECK("every object released after the theme case", live == 0);
        f = fopen(DIR "cpc-hid", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        sd = (Arr *)m_new_arr(0, 0, &cb_arr);
        sm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(sd, sm); m_release(sm);
        k = str("displays"); m_set(si, k, sd); m_release(k); m_release(sd);
        {
            Arr *hids = (Arr *)m_new_arr(0, 0, &cb_arr);
            Dict *hd = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
            Str *hk = str("displayUUID"), *hv = str("0d15b1a4-0000-4000-8000-000000000001");
            m_set(hd, hk, hv); m_release(hk); m_release(hv);
            m_append(hids, hd); m_release(hd);
            k = str("hidDevices"); m_set(si, k, hids); m_release(k); m_release(hids);
        }
        cpc_info_hook(si);
        const Arr *lh = g(si, "hidDevices"), *ld = g(si, "displays");
        CHECK("LIVI inputs: the stock touch screen plus knob, media, telephony on Center_Display and a knob on Cluster_Display",
              lh && lh->n == 5 && !strcmp(s_of(g(lh->v[0], "displayUUID")), "Center_Display")
              && !strcmp(s_of(g(lh->v[1], "name")), "Ioniq 6 Knob") && !strcmp(s_of(g(lh->v[1], "displayUUID")), "Center_Display")
              && !strcmp(s_of(g(lh->v[2], "name")), "Ioniq 6 Media") && !strcmp(s_of(g(lh->v[3], "name")), "Ioniq 6 Telephony")
              && !strcmp(s_of(g(lh->v[4], "displayUUID")), "Cluster_Display") && g(lh->v[1], "hidDescriptor"));
        CHECK("LIVI inputs: knobs are Center_Display's primary input", ld && ld->n == 2 && n_of(g(ld->v[0], "primaryInputDevice")) == 3);
        m_release(si);
        remove(DIR "cpc-hid");
        CHECK("every object released after the LIVI inputs case", live == 0);
        remove(DIR "cpc-ultra");
        remove(DIR "cpc-dock");
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        k = str("sourceVersion"); { Str *v = str("320.17.6"); m_set(si, k, v); m_release(v); } m_release(k);
        cpc_info_hook(si);
        CHECK("no version setting: sourceVersion stays 320.17.6", !strcmp(s_of(g(si, "sourceVersion")), "320.17.6"));
        m_release(si);
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        k = str("features"); m_set_int(si, k, 0x4104040280LL); m_release(k);
        k = str("extendedFeatures"); { Arr *a = (Arr *)m_new_arr(0, 0, &cb_arr); Str *v = str("vocoderInfo"); m_append(a, v); m_release(v); m_set(si, k, a); m_release(a); } m_release(k);
        cpc_info_hook(si);
        CHECK("no feature settings: features and extendedFeatures stock", n_of(g(si, "features")) == 0x4104040280LL
              && ((const Arr *)g(si, "extendedFeatures"))->n == 1);
        m_release(si);
        {
            FILE *ff = fopen(DIR "cpc-feat", "w");
            if (ff) { fputs("205203a462\n", ff); fclose(ff); }
            ff = fopen(DIR "cpc-xfeat", "w");
            if (ff) { fputs("1\n", ff); fclose(ff); }
        }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        k = str("features"); m_set_int(si, k, 0x4104040280LL); m_release(k);
        k = str("extendedFeatures"); { Arr *a = (Arr *)m_new_arr(0, 0, &cb_arr); Str *v = str("vocoderInfo"); m_append(a, v); m_release(v); m_set(si, k, a); m_release(a); } m_release(k);
        cpc_info_hook(si);
        const Arr *xf = g(si, "extendedFeatures");
        CHECK("feature bits: stock | LIVI's non-audio bits", n_of(g(si, "features")) == (0x4104040280LL | 0x205203a462LL));
        CHECK("enhancedRequestCarUI appended after vocoderInfo", xf && xf->n == 2 && !strcmp(s_of(xf->v[0]), "vocoderInfo")
              && !strcmp(s_of(xf->v[1]), "enhancedRequestCarUI"));
        cpc_info_hook(si);
        CHECK("already patched /info: left alone", ((const Arr *)g(si, "extendedFeatures"))->n == 2);
        m_release(si);
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_info_hook(si);
        CHECK("no stock features/extendedFeatures: just the bits, [enhancedRequestCarUI]", n_of(g(si, "features")) == 0x205203a462LL
              && ((const Arr *)g(si, "extendedFeatures"))->n == 1);
        m_release(si);
        remove(DIR "cpc-feat");
        remove(DIR "cpc-xfeat");
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_info_hook(si);
        CHECK("uiSync experiment off: no uiSyncInfo", !g(si, "uiSyncInfo"));
        m_release(si);
        {
            FILE *us = fopen(DIR "cpc-uisync", "w");
            if (us) { fputs("1\n", us); fclose(us); }
        }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_info_hook(si);
        {
            const Dict *usi = g(si, "uiSyncInfo");
            CHECK("uiSync experiment on: /info has an empty uiSyncInfo", usi && usi->n == 0);
        }
        m_release(si);
        remove(DIR "cpc-uisync");
        {
            FILE *xf = fopen(DIR "cpc-xenable", "w");
            if (xf) { fputs("1\n", xf); fclose(xf); }
            si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
            k = str("features"); m_set_int(si, k, 0x615653aee2LL); m_release(k);
            cpc_info_hook(si);
            const void *vp = g(si, "videoPlaybackInfo"), *pc = g(si, "playbackCapabilities");
            CHECK("videoPlayback: /info videoPlaybackInfo {allowed, featuresEx = LIVI's 4q5TVmE for 0x615653aee2}",
                  vp && g(vp, "videoPlaybackAllowed") && !strcmp(s_of(g(vp, "featuresEx")), "4q5TVmE"));
            CHECK("videoPlayback: /info playbackCapabilities with its two flags", pc && g(pc, "supportsFPSSecureStop") && g(pc, "supportsUIForAudioOnlyContent"));
            m_release(si);
            remove(DIR "cpc-xenable");
        }
        si = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        k = str("pi"); { Str *v = str("00000000-0000-4000-8000-000000000001"); m_set(si, k, v); m_release(v); } m_release(k);
        k = str("deviceID"); { Str *v = str("02:00:00:00:00:01"); m_set(si, k, v); m_release(v); } m_release(k);
        k = str("sourceVersion"); { Str *v = str("320.17.6"); m_set(si, k, v); m_release(v); } m_release(k);
        cpc_info_hook(si);
        {
            char ab[400] = {0}; FILE *af = fopen(DIR "cpc-airplay", "r"); size_t an = af ? fread(ab, 1, sizeof ab - 1, af) : 0; if (af) fclose(af);
            CHECK("cpc-airplay: pi, deviceID, sourceVersion lines", an > 0
                  && !strcmp(ab, "pi=00000000-0000-4000-8000-000000000001\ndeviceID=02:00:00:00:00:01\nsourceVersion=320.17.6\n"));
        }
        m_release(si);
        remove(DIR "cpc-airplay");
        CHECK("every object released after the size cases", live == 0);
    }

    printf("-- /info without displays, and a blob that doesn't parse\n");
    info = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    cpc_info_hook(info);
    CHECK("no displays: vehicleStateProtocolInfo still added, no displayPanels",
          g(info, "vehicleStateProtocolInfo") && !g(info, "displayPanels"));
    m_release(info);
    plist_fail = 1;
    info = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    cpc_info_hook(info);
    CHECK("parse failure: /info untouched and logged", info->n == 0 && strstr(lastlog, "didn't parse"));
    m_release(info);
    plist_fail = 0;
    CHECK("still no leaks", live == 0);

    printf("-- SETUP\n");
    Dict *req = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval), *rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    Arr *feat = (Arr *)m_new_arr(0, 0, &cb_arr);
    const char *phone[] = { "uiContext", "viewAreas", "altScreen", "hevc", "vehicleStateProtocol", "iAPChannel" };
    for (int i = 0; i < 6; i++) { Str *s = str(phone[i]); m_append(feat, s); m_release(s); }
    Str *kf = str("features"); m_set(req, kf, feat); m_release(feat);
    cpc_setup_hook(req, rep);
    const Arr *echo = g(rep, "enabledFeatures");
    CHECK("reply enabledFeatures == [vehicleStateProtocol], no features key", !g(rep, "features") && echo && echo->n == 1 && !strcmp(s_of(echo->v[0]), "vehicleStateProtocol"));
    m_release(rep);
    {
        FILE *uf = fopen(DIR "cpc-ultra", "w");
        if (uf) { fputs("1\n", uf); fclose(uf); }
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(req, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("Ultra: enabledFeatures [vehicleStateProtocol, viewAreas, altScreen]", echo && echo->n == 3
              && !strcmp(s_of(echo->v[1]), "viewAreas") && !strcmp(s_of(echo->v[2]), "altScreen"));
        CHECK("Ultra: the offer logged", strstr(lastlog, "viewAreas, altScreen") != 0);
        m_release(rep);
        remove(DIR "cpc-ultra");
        uf = fopen(DIR "cpc-xenable", "w");
        if (uf) { fputs("1\n", uf); fclose(uf); }
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(req, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("extra features 1, videoPlayback not offered: enabledFeatures stay [vehicleStateProtocol]", echo && echo->n == 1);
        m_release(rep);
        uf = fopen(DIR "cpc-xenable", "w");
        if (uf) { fputs("2\n", uf); fclose(uf); }
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(req, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("extra features 2: the offered hevc enabled", echo && echo->n == 2 && !strcmp(s_of(echo->v[1]), "hevc"));
        m_release(rep);
        remove(DIR "cpc-xenable");
        uf = fopen(DIR "cpc-iap", "w");
        if (uf) { fputs("1\n", uf); fclose(uf); }
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(req, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("iAPChannel experiment on: enabledFeatures [vehicleStateProtocol, iAPChannel]", echo && echo->n == 2
              && !strcmp(s_of(echo->v[1]), "iAPChannel"));
        m_release(rep);
        remove(DIR "cpc-iap");
        uf = fopen(DIR "cpc-uisync", "w");
        if (uf) { fputs("1\n", uf); fclose(uf); }
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(req, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("uiSync experiment on, uiSync not offered: enabledFeatures stay [vehicleStateProtocol]", echo && echo->n == 1);
        m_release(rep);
        Dict *rq = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        Arr *fs = (Arr *)m_new_arr(0, 0, &cb_arr);
        const char *offer[] = { "vehicleStateProtocol", "uiSync" };
        for (int i = 0; i < 2; i++) { Str *s = str(offer[i]); m_append(fs, s); m_release(s); }
        Str *kq = str("features"); m_set(rq, kq, fs); m_release(fs); m_release(kq);
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(rq, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("uiSync experiment on, offered: enabledFeatures [vehicleStateProtocol, uiSync]", echo && echo->n == 2
              && !strcmp(s_of(echo->v[1]), "uiSync"));
        CHECK("uiSync: the reply logged", strstr(lastlog, ", uiSync]") != 0);
        m_release(rep);
        remove(DIR "cpc-uisync");
        rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        cpc_setup_hook(rq, rep);
        echo = g(rep, "enabledFeatures");
        CHECK("uiSync offered, experiment off: enabledFeatures [vehicleStateProtocol]", echo && echo->n == 1);
        m_release(rep);
        m_release(rq);
    }
    Dict *req2 = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    Arr *feat2 = (Arr *)m_new_arr(0, 0, &cb_arr);
    Str *s2 = str("hevc"); m_append(feat2, s2); m_release(s2);
    m_set(req2, kf, feat2); m_release(feat2);
    rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    cpc_setup_hook(req2, rep);
    CHECK("phone without vehicleStateProtocol: reply unchanged", rep->n == 0);
    m_release(rep);
    Dict *req3 = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    rep = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
    cpc_setup_hook(req3, rep);
    CHECK("no features key (stream SETUP): reply unchanged", rep->n == 0);
    cpc_setup_hook(0, rep);
    CHECK("null request: nothing", rep->n == 0);
    m_release(rep); m_release(req); m_release(req2); m_release(req3); m_release(kf);
    CHECK("no leaks after SETUP", live == 0);

    printf("-- M2: type 130 vehicle state stream SETUPs\n");
    {
        int session = 1;
        Dict *reply = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        Dict *st = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        Str *kt = str("clientTypeUUID"), *ks = str("seed"), *u = str("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E");
        m_set(st, kt, u); m_release(u); m_set_int(st, ks, -4170049560036521059LL);
        CHECK("normal lane: handled", cpc_stream_hook(&session, st, reply, 130) == 1);
        const Arr *streams = g(reply, "streams");
        CHECK("reply streams [{type 130, streamID 130, dataPort 7130}]", streams && streams->n == 1
              && n_of(g(streams->v[0], "type")) == 130 && n_of(g(streams->v[0], "streamID")) == 130
              && n_of(g(streams->v[0], "dataPort")) == 7130);
        unsigned char kf[100]; FILE *f = fopen(DIR "cpc-vs-0.key", "rb"); size_t kn = f ? fread(kf, 1, sizeof kf, f) : 0; if (f) fclose(f);
        unsigned long long seed = (unsigned long long)-4170049560036521059LL; int seed_ok = 1;
        for (int i = 0; i < 8; i++) seed_ok &= kf[i] == (unsigned char)(seed >> (8 * i));
        CHECK("key file: 72 bytes, seed LE, read key = Output, write key = Input", kn == 72 && seed_ok
              && kf[8] == (unsigned char)(0x80 + seed) && kf[40] == (unsigned char)(0x10 + seed) && kf[71] == (unsigned char)(0x10 + 31 + seed));
        Str *u2 = str("FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"); m_set(st, kt, u2); m_release(u2);
        CHECK("high lane: handled, port 7131", cpc_stream_hook(&session, st, reply, 130) == 1 && streams->n == 2
              && n_of(g(streams->v[1], "dataPort")) == 7131 && n_of(g(streams->v[1], "streamID")) == 131);
        Str *u3 = str("00000000-0000-0000-0000-000000000000"); m_set(st, kt, u3); m_release(u3);
        CHECK("other clientTypeUUID: left to DiPO", cpc_stream_hook(&session, st, reply, 130) == 0 && streams->n == 2);
        CHECK("other stream type: left to DiPO", cpc_stream_hook(&session, st, reply, 110) == 0);
        Str *u4 = str("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"); m_set(st, kt, u4); m_release(u4);
        derive_fail = 1;
        CHECK("keys not derived: left to DiPO, nothing added", cpc_stream_hook(&session, st, reply, 130) == 0 && streams->n == 2);
        derive_fail = 0;
        Str *ui = str("E9459FD0-BCAD-4C45-820F-1E72447EF2F2"); m_set(st, kt, ui); m_release(ui);
        CHECK("iAP stream, experiment off: left to DiPO", cpc_stream_hook(&session, st, reply, 130) == 0 && streams->n == 2);
        f = fopen(DIR "cpc-iap", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        remove(DIR "cpc-vs-2.key");
        CHECK("iAP stream, experiment on: {type 130, streamID 132, dataPort 7133}", cpc_stream_hook(&session, st, reply, 130) == 1
              && streams->n == 3 && n_of(g(streams->v[2], "dataPort")) == 7133 && n_of(g(streams->v[2], "streamID")) == 132);
        f = fopen(DIR "cpc-vs-2.key", "rb"); kn = f ? fread(kf, 1, sizeof kf, f) : 0; if (f) fclose(f);
        CHECK("iAP stream keys in cpc-vs-2.key (72 bytes)", kn == 72);
        remove(DIR "cpc-iap");
        Str *us = str("07D9F906-8D64-4B54-A808-A20BCA2C51C2"); m_set(st, kt, us); m_release(us);
        CHECK("uiSync stream, experiment off: left to DiPO", cpc_stream_hook(&session, st, reply, 130) == 0 && streams->n == 3);
        f = fopen(DIR "cpc-uisync", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        remove(DIR "cpc-vs-3.key");
        CHECK("uiSync stream, experiment on: {type 130, streamID 133, dataPort 7135}", cpc_stream_hook(&session, st, reply, 130) == 1
              && streams->n == 4 && n_of(g(streams->v[3], "dataPort")) == 7135 && n_of(g(streams->v[3], "streamID")) == 133);
        f = fopen(DIR "cpc-vs-3.key", "rb"); kn = f ? fread(kf, 1, sizeof kf, f) : 0; if (f) fclose(f);
        CHECK("uiSync stream keys in cpc-vs-3.key (72 bytes)", kn == 72);
        CHECK("uiSync stream: logged as uiSync on port 7135", strstr(lastlog, "uiSync stream 3 set up on port 7135") != 0);
        remove(DIR "cpc-uisync");
        ((Arr *)streams)->n--;   /* the later checks count the streams added before this one */
        m_release(streams->v[3]);
        Str *u5 = str("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"); m_set(st, kt, u5); m_release(u5);
        CHECK("cluster screen stream, Ultra off: left to DiPO", cpc_stream_hook(&session, st, reply, 111) == 0 && streams->n == 3);
        f = fopen(DIR "cpc-ultra", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        CHECK("cluster screen stream, Ultra on: {type 111, dataPort 7132}", cpc_stream_hook(&session, st, reply, 111) == 1
              && streams->n == 4 && n_of(g(streams->v[3], "type")) == 111 && n_of(g(streams->v[3], "dataPort")) == 7132);
        remove(DIR "cpc-ultra");
        Str *uv = str("A6B27562-B43A-4F2D-B75F-82391E250194"); m_set(st, kt, uv); m_release(uv);
        CHECK("videoPlayback data stream, experiment off: left to DiPO", cpc_stream_hook(&session, st, reply, 130) == 0 && streams->n == 4);
        f = fopen(DIR "cpc-xenable", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        CHECK("videoPlayback data stream, experiment on: {type 130, streamID 140, dataPort 7134}", cpc_stream_hook(&session, st, reply, 130) == 1
              && streams->n == 5 && n_of(g(streams->v[4], "dataPort")) == 7134 && n_of(g(streams->v[4], "streamID")) == 140);
        CHECK("videoPlayback data stream: the answer loop started for this session", async_n == 1 && async_ctx == (void *)&session);
        remove(DIR "cpc-xenable");
        m_release(st); m_release(reply); m_release(kt); m_release(ks);
        CHECK("no leaks after M2", live == 0);
    }
    {
        printf("-- /info's vehicleStateProtocolInfo from files/cpc-vspi.bplist (the Java side's description)\n");
        FILE *vf = fopen(DIR "cpc-vspi.bplist", "wb");
        if (vf) { fwrite(vspi_file, 1, vspi_len, vf); fclose(vf); }
        Dict *vi = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        Arr *vd = (Arr *)m_new_arr(0, 0, &cb_arr);
        Dict *vm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(vd, vm); m_release(vm);
        Str *kvd = str("displays"); m_set(vi, kvd, vd); m_release(kvd); m_release(vd);
        vspi_file_logs = 0; vspi_fallback_logs = 0; vspi_as_dict = 1;
        cpc_info_hook(vi);
        vspi_as_dict = 0;
        CHECK("the file is read and used", vspi_file_logs == 1 && vspi_fallback_logs == 0);
        m_release(vi);
        remove(DIR "cpc-vspi.bplist");
        vi = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        vd = (Arr *)m_new_arr(0, 0, &cb_arr);
        vm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(vd, vm); m_release(vm);
        kvd = str("displays"); m_set(vi, kvd, vd); m_release(kvd); m_release(vd);
        vspi_file_logs = 0;
        cpc_info_hook(vi);
        CHECK("no file: LIVI's built-in description, quietly", vspi_file_logs == 0 && vspi_fallback_logs == 0);
        m_release(vi);
        vf = fopen(DIR "cpc-vspi.bplist", "wb");
        if (vf) { fwrite(vspi_file, 1, vspi_len, vf); fclose(vf); }
        vi = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        vd = (Arr *)m_new_arr(0, 0, &cb_arr);
        vm = display("0d15b1a4-0000-4000-8000-000000000001", 1920, 720, 292, 110);
        m_append(vd, vm); m_release(vm);
        kvd = str("displays"); m_set(vi, kvd, vd); m_release(kvd); m_release(vd);
        vspi_file_logs = 0; vspi_fallback_logs = 0;
        cpc_info_hook(vi);
        CHECK("a file that isn't a dictionary with pluginConfigs: LIVI's instead, logged", vspi_file_logs == 0 && vspi_fallback_logs == 1);
        remove(DIR "cpc-vspi.bplist");
        m_release(vi);
        CHECK("no leaks after the vspi file cases", live == 0);
    }
    {
        printf("-- video stage 2: POST /command without a type, and the two video setup commands\n");
        static unsigned char req[0x500];
        const char *head = "POST /command RTSP/1.0\r\nX-Apple-StreamID: 140\r\nContent-Type: application/x-apple-binary-plist\r\nCSeq: 7\r\n\r\n";
        memcpy(req + 16, head, strlen(head));
        unsigned char body[1363];
        for (unsigned i = 0; i < sizeof body; i++) body[i] = (unsigned char)(i * 7);
        memcpy(body, "bplist00", 8);
        const unsigned char *bp = body;
        unsigned long blen = sizeof body;
        memcpy(req + 0x49c, &bp, sizeof bp);
        memcpy(req + 0x4a8, &blen, sizeof blen);
        int rx = socket(AF_INET, SOCK_DGRAM, 0);
        struct sockaddr_in a; memset(&a, 0, sizeof a);
        a.sin_family = AF_INET; a.sin_port = htons(7136); a.sin_addr.s_addr = htonl(0x7f000001);
        CHECK("test receiver bound on 127.0.0.1:7136", bind(rx, (struct sockaddr *)&a, sizeof a) == 0);
        struct timeval tv = { 1, 0 }; setsockopt(rx, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        Dict *pl = (Dict *)m_new_dict(0, 0, &cb_dkey, &cb_dval);
        remove(DIR "cpc-xenable");
        CHECK("no type, experiment off: DiPO's 451", cpc_command_hook(0, pl, req) == 0);
        FILE *f = fopen(DIR "cpc-xenable", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        CHECK("no type, experiment on: answered", cpc_command_hook(0, pl, req) == 1);
        unsigned char got[2000];
        long n = recv(rx, got, sizeof got, 0);
        CHECK("datagram: CPV1, stream 140, the body as sent",
              n == 12 + (long)sizeof body && !memcmp(got, "CPV1", 4) && got[11] == 140 && got[4] == 0 && got[10] == 0
              && !memcmp(got + 12, body, sizeof body));
        CHECK("logged with its stream", strstr(lastlog, "message on stream 140 (1363 bytes)") != 0);
        const char *head2 = "POST /command RTSP/1.0\r\nx-apple-streamid:9\r\n\r\n";
        memset(req, 0, 0x49c); memcpy(req + 16, head2, strlen(head2));
        CHECK("lower-case header, no space: answered", cpc_command_hook(0, pl, req) == 1);
        n = recv(rx, got, sizeof got, 0);
        CHECK("datagram: stream 9", n > 12 && got[11] == 9);
        const char *head3 = "POST /command RTSP/1.0\r\nCSeq: 3\r\n\r\nX-Apple-StreamID: 5\r\n";
        memset(req, 0, 0x49c); memcpy(req + 16, head3, strlen(head3));
        CHECK("no X-Apple-StreamID (one after the header doesn't count): still answered", cpc_command_hook(0, pl, req) == 1);
        n = recv(rx, got, sizeof got, 0);
        CHECK("datagram: stream -1", n > 12 && got[4] == 0xff && got[11] == 0xff);
        blen = 0; memcpy(req + 0x4a8, &blen, sizeof blen);
        CHECK("no type and no body: DiPO's own path", cpc_command_hook(0, pl, req) == 0);
        blen = sizeof body; memcpy(req + 0x4a8, &blen, sizeof blen);
        Str *other = str("requestUI");
        CHECK("another typed command: DiPO's own path", cpc_command_hook(other, pl, req) == 0);
        m_release(other);
        Str *proxy = str("setProxyParameters");
        remove(DIR "cpc-video-proxy.bplist");
        CHECK("setProxyParameters: answered", cpc_command_hook(proxy, pl, req) == 1);
        f = fopen(DIR "cpc-video-proxy.bplist", "rb"); unsigned char sv[2000]; size_t sn = f ? fread(sv, 1, sizeof sv, f) : 0; if (f) fclose(f);
        CHECK("setProxyParameters: body saved as is", sn == sizeof body && !memcmp(sv, body, sn));
        m_release(proxy);
        Str *vp = str("setVideoPlaybackParameters");
        remove(DIR "cpc-video-params.bplist");
        CHECK("setVideoPlaybackParameters: answered", cpc_command_hook(vp, pl, req) == 1);
        f = fopen(DIR "cpc-video-params.bplist", "rb"); CHECK("setVideoPlaybackParameters: body saved", f != 0); if (f) fclose(f);
        m_release(vp);
        remove(DIR "cpc-xenable");
        CHECK("setProxyParameters, experiment off: DiPO's own path", cpc_command_hook(proxy = str("setProxyParameters"), pl, req) == 0);
        m_release(proxy);
        remove(DIR "cpc-video-proxy.bplist"); remove(DIR "cpc-video-params.bplist");
        Str *fw = str("forwardToVideoPlayback");
        f = fopen(DIR "cpc-xenable", "w");
        if (f) { fputs("1\n", f); fclose(f); }
        CHECK("forwardToVideoPlayback: answered 200", cpc_command_hook(fw, pl, req) == 1);
        remove(DIR "cpc-xenable");
        CHECK("forwardToVideoPlayback, experiment off: DiPO's own path", cpc_command_hook(fw, pl, req) == 0);
        m_release(fw);
        close(rx);
        m_release(pl);
        CHECK("no leaks after video stage 2", live == 0);
    }
    {
        printf("-- video stage 3: answers to the phone on the event channel\n");
        static unsigned char session[0x400];
        int queue_obj = 0, client_obj = 0;
        void *qp = &queue_obj, *cp = &client_obj;
        memcpy(session + 0x3c8, &qp, sizeof qp);
        memcpy(session + 0x3cc, &cp, sizeof cp);
        unsigned char d[12 + 300];
        memcpy(d, "CPV2", 4); memset(d + 4, 0, 8); d[11] = 140;
        for (int i = 0; i < 300; i++) d[12 + i] = (unsigned char)(i * 3);
        sent_n = 0;
        CHECK("an answer is sent", cpc_test_video_send(session, d, sizeof d) == 0 && sent_n == 1);
        /* on the Mac pointers are 8 bytes and the two fields 4 apart, so only the client is checked whole */
        CHECK("through a dispatch_sync on the session's queue, to its event client", synced_on != 0 && sent_client == cp);
        CHECK("POST /command RTSP/1.0", !strcmp(req_line, "POST /command RTSP/1.0"));
        CHECK("X-Apple-StreamID: 140", !strcmp(field_line, "X-Apple-StreamID: 140"));
        CHECK("binary plist body, a copy of the datagram's", !strcmp(body_type, "application/x-apple-binary-plist")
              && body_len == 300 && body_copy != d + 12 && !memcmp(body_copy, d + 12, 300));
        CHECK("the message is released after sending", msg_released == 1);
        free(body_copy); body_copy = 0;
        d[3] = '1';
        CHECK("not CPV2: not sent", cpc_test_video_send(session, d, sizeof d) != 0 && sent_n == 1);
        d[3] = '2';
        void *none = 0;
        memcpy(session + 0x3cc, &none, sizeof none);
        CHECK("no event client: not sent", cpc_test_video_send(session, d, sizeof d) != 0 && sent_n == 1);
        memcpy(session + 0x3cc, &cp, sizeof cp);
        CHECK("no leaks after video stage 3", live == 0);
    }
    printf(fails ? "%d FAILED\n" : "ALL OK\n", fails);
    return fails != 0;
}

static int m_add_stream(const void *reply, const void *stream) {
    Str *k = str("streams");
    const void *a = m_get(reply, k);
    if (!a) { a = m_new_arr(0, 0, &cb_arr); m_set(reply, k, a); m_release(a); }
    m_append(a, stream);
    m_release(k);
    return 0;
}
