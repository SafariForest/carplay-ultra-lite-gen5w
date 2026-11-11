/* cp-climate M1: shared between seg.S and hooks.c. The table holds 32-bit offsets, each
 * relative to the table itself, to places in libCarPlayCore (CEEV.USA 260507, md5
 * d3a47296437df56fb1ffc7ac3dfca4cb). The recipe re-points them wherever the code lands. */
#ifndef CPC_H
#define CPC_H

#define T_ORIG_INFO   0   /* AirPlayCopyServerInfo (Thumb)                         */
#define T_RET_INFO    1   /* after its call in _requestProcessInfo (Thumb)         */
#define T_RET_SETUP   2   /* after the eventPort reply in AirPlayReceiverSessionSetup */
#define T_LOGCAT      3   /* gLogCategory_AirPlayReceiverCore                      */
#define T_GOT_DKEY    4   /* GOT slot: kCFLDictionaryKeyCallBacksCFLTypes          */
#define T_GOT_DVAL    5   /* GOT slot: kCFLDictionaryValueCallBacksCFLTypes        */
#define T_GOT_ARR     6   /* GOT slot: kCFLArrayCallBacksCFLTypes                  */
#define T_CFDictionaryGetValue          7
#define T_CFDictionarySetValue          8
#define T_CFRelease                     9
#define T_CFBinaryPlistV0CreateWithData 10
#define T_CFArrayGetCount               11
#define T_CFArrayGetValueAtIndex        12
#define T_CFEqual                       13
#define T_CFArrayCreateMutable          14
#define T_CFArrayAppendValue            15
#define T_CFDictionaryCreateMutable     16
#define T_CFDictionarySetInt64          17
#define T_CFStringGetCStringPtr         18
#define T_CFStringCreateWithCString     19
#define T_LogPrintF                     20
#define T_CFGetTypeID                   21
#define T_CFArrayGetTypeID              22
#define T_CFStringGetTypeID             23
#define T_CFDictionaryGetTypeID         24
#define T_CFBinaryPlistV0Create         25   /* diagnostic variant d */
#define T_free                          26
#define T_CFDictionaryApplyFunction     27
#define T_RET_STREAM_STOCK              28   /* 0x1171c: DiPO's own unsupported-stream path (Thumb) */
#define T_RET_STREAM_DONE               29   /* 0x11724: next stream in the SETUP (Thumb)          */
#define T_DERIVE_STREAM_KEYS            30   /* 0xf014: DataStream-Salt<seed> keys (Thumb)         */
#define T_ADD_STREAM_REPLY              31   /* 0xd59c: append to the reply's "streams" (Thumb)    */
#define T_CFDictionaryGetInt64          32
#define T_CMD_TYPED                     33   /* 0xaf18: /command with a type, on to AirPlayReceiverSessionControl (Thumb) */
#define T_CMD_451                       34   /* 0xb6d0: /command without a type, DiPO's 451 (Thumb)                  */
#define T_CMD_DONE                      35   /* 0xaf6c: /command answered, status in r4, err at [sp, #0x7c] (Thumb)   */
/* video stage 3: sending the phone video in car messages (POST /command with X-Apple-StreamID) */
#define T_dispatch_get_global_queue     36
#define T_dispatch_async_f              37
#define T_dispatch_sync_f               38
#define T_HTTPMessageCreate             39
#define T_HTTPHeader_InitRequest        40
#define T_HTTPHeader_AddFieldF          41
#define T_HTTPMessageSetBodyPtr         42
#define T_HTTPClientSendMessage         43
#define T_CFRetain                      44
#define T_malloc                        45
#define T_memcpy                        46
#define T_COUNT                         47

#endif
