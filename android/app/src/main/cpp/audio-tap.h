#ifndef CHIAKI_JNI_AUDIO_TAP_H
#define CHIAKI_JNI_AUDIO_TAP_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

void android_chiaki_audio_tap_settings(uint32_t channels, uint32_t rate);
void android_chiaki_audio_tap_push(const int16_t *buf, size_t samples_count);

#ifdef __cplusplus
}
#endif

#endif
