#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool ais_direct_probe(const char* log_path, char* status, size_t status_len);
bool ais_direct_is_ready(void);
bool ais_direct_open_stream(int cam_id, int width, int height);
const uint8_t* ais_direct_acquire(int* width, int* height, int* fmt, int* idx,
                                  unsigned timeout_ms);
void ais_direct_release(int idx);
void ais_direct_close_stream(void);

#ifdef __cplusplus
}
#endif
