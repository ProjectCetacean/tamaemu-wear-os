#ifndef WEAR_SHA256_H
#define WEAR_SHA256_H
#include <stddef.h>
#include <stdint.h>
void wear_sha256(const void *data, size_t size, uint8_t out[32]);
#endif
