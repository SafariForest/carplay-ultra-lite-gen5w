@ cp-climate M1: the code segment's entry stubs and its table into libCarPlayCore.
@ Each stub is reached by a b.w that replaces one instruction in the library, does what
@ that instruction did, calls the C hook, and jumps back. Registers and the stack are as
@ the library left them.
#include "cpc.h"

    .syntax unified
    .thumb
    .section .text.entry, "ax", %progbits

@ b.w at 0x9c20, in place of "bl AirPlayCopyServerInfo" (args r0-r2 untouched)
    .global cpc_stub_info
    .thumb_func
cpc_stub_info:
    push    {r4, r5}
    adr.w   r12, cpc_table
    ldr     r3, [r12, #(T_ORIG_INFO * 4)]
    add     r3, r12
    blx     r3                      @ the stock AirPlayCopyServerInfo
    mov     r4, r0
    cbz     r0, 1f
    bl      cpc_info_hook           @ r0 = the /info dictionary
1:  mov     r0, r4
    pop     {r4, r5}
    adr.w   r12, cpc_table
    ldr     r3, [r12, #(T_RET_INFO * 4)]
    add     r3, r12
    bx      r3                      @ 0x9c24: "ldr r3, [sp, #0x1c]"

@ b.w at 0x12430, in place of "ldr.w r0, [pc, #0x7f0]" (+ the "add r0, pc" after it):
@ the session SETUP has just put eventPort in its reply. Request [sp, #0x70], reply [sp, #0x58].
    .global cpc_stub_setup
    .thumb_func
cpc_stub_setup:
    push    {r4, lr}
    ldr     r0, [sp, #(8 + 0x70)]
    ldr     r1, [sp, #(8 + 0x58)]
    bl      cpc_setup_hook
    pop     {r4, lr}
    adr.w   r12, cpc_table
    ldr     r0, [r12, #(T_LOGCAT * 4)]
    add     r0, r12                 @ what the two replaced instructions computed
    ldr     r3, [r12, #(T_RET_SETUP * 4)]
    add     r3, r12
    bx      r3                      @ 0x12436: "ldr r3, [r0]"

@ b.w at 0x11718, in place of "ldr r0, [pc, #0x3ec]" + "add r0, pc": a stream SETUP of a type
@ DiPO doesn't handle. Session in fp, the stream's dictionary in r7, its type in sl, the reply
@ at [sp, #0x58]. Handled: on to the next stream; not: DiPO's own "Unsupported" path.
    .global cpc_stub_stream
    .thumb_func
cpc_stub_stream:
    push    {r4, lr}
    mov     r0, fp
    mov     r1, r7
    ldr     r2, [sp, #(8 + 0x58)]
    mov     r3, sl
    bl      cpc_stream_hook
    pop     {r4, lr}
    adr.w   r12, cpc_table
    cbnz    r0, 1f
    ldr     r0, [r12, #(T_LOGCAT * 4)]
    add     r0, r12                 @ what the two replaced instructions computed
    ldr     r3, [r12, #(T_RET_STREAM_STOCK * 4)]
    add     r3, r12
    bx      r3                      @ 0x1171c: "ldr r6, [r0]"
1:  ldr     r3, [r12, #(T_RET_STREAM_DONE * 4)]
    add     r3, r12
    bx      r3                      @ 0x11724: next stream

@ b.w at 0xaf10, in place of "mov r9, r0" + "cmp r0, #0": POST /command has just looked up the
@ request's "type" (r0, NULL if none). The request plist is in r7, the HTTP request in r4.
@ cpc_command_hook answers 1 for a command it takes (the video in car messages, which have no
@ type, and the two video setup commands): a 200 with no body. 0: DiPO's own path.
    .global cpc_stub_command
    .thumb_func
cpc_stub_command:
    mov     r9, r0                  @ the first replaced instruction
    push    {r0, lr}
    mov     r1, r7
    mov     r2, r4
    bl      cpc_command_hook        @ r0 = type, r1 = plist, r2 = HTTP request
    mov     r3, r0
    pop     {r0, lr}
    adr.w   r12, cpc_table
    cbnz    r3, 2f
    cbz     r0, 1f
    ldr     r3, [r12, #(T_CMD_TYPED * 4)]
    add     r3, r12
    bx      r3                      @ 0xaf18: typed, as stock
1:  ldr     r3, [r12, #(T_CMD_451 * 4)]
    add     r3, r12
    bx      r3                      @ 0xb6d0: no type, as stock (451)
2:  mov.w   r4, #200                @ status, as the stock error paths set it
    movs    r3, #0
    str     r3, [sp, #0x7c]         @ err = 0
    ldr     r3, [r12, #(T_CMD_DONE * 4)]
    add     r3, r12
    bx      r3                      @ 0xaf6c: release the plist, send the reply

@ long cpc_sys3(long nr, long a, long b, long c): a Linux system call (diagnostic variant).
@ In assembly because r7, which carries the call number, is the Thumb frame pointer.
    .global cpc_sys3
    .thumb_func
cpc_sys3:
    push    {r7, lr}
    mov     r7, r0
    mov     r0, r1
    mov     r1, r2
    mov     r2, r3
    svc     #0
    pop     {r7, pc}

    .balign 4
    .global cpc_table
cpc_table:
    .rept T_COUNT
    .word   0                       @ filled by tools/build.py: target - cpc_table
    .endr

    .section .rodata.vspi, "a", %progbits
    .balign 4
    .global cpc_vspi
cpc_vspi:
    .incbin VSPI_FILE
    .global cpc_vspi_end
cpc_vspi_end:
    .balign 4
