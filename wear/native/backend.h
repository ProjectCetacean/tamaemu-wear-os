#ifndef WEAR_BACKEND_H
#define WEAR_BACKEND_H
#include "emu.h"
/* All calls serialized by caller. Diagnostic errors never contain paths/data. */
typedef struct WearBackend WearBackend;
WearBackend *wear_open(const char *rom_path, const char *save_path, char error[160]);
void wear_close(WearBackend *b);
/* Runtime presentation only; never included in guest/save/pacing state. */
int wear_audio_set_enabled(WearBackend *b, int enabled);
size_t wear_audio_drain(WearBackend *b, int16_t *out, size_t capacity);
int wear_advance(WearBackend *b, int elapsed_ms, uint32_t *argb, size_t pixels, char error[160]);
/* Same capped execution and pacing as wear_advance, without presentation. */
int wear_advance_headless(WearBackend *b, int elapsed_ms, char error[160]);
int wear_button(WearBackend *b, int button, int pressed);
int wear_save(WearBackend *b, char error[160]);
/* Read-only host verification access; not exposed through JNI. */
const Emu *wear_state(const WearBackend *b);
/* Eight host slots: schema, wall UTC ms, elapsed ms, boot count (INT64_MAX
 * unknown), debt ms, mode, generation, clock quality. Readers copy; writes
 * validate the complete candidate before mutation. Older files read all zero. */
int wear_read_life(const WearBackend *b, uint64_t out[8]);
int wear_write_life(WearBackend *b, const uint64_t fields[8], char error[160]);
/* Last validated restore or durable save's actual on-disk payload checksum.
 * Returns false without touching out before a commit or on invalid arguments. */
int wear_committed_payload_hash(const WearBackend *b, uint8_t out[32]);
/* Digest covers canonical guest/NOR, pacing and the working v3 life trailer. */
void wear_state_digest(const WearBackend *b, uint8_t out[32]);
#endif
