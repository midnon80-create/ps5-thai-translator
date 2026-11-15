// Audio tap for the translator: copies the decoded game audio, downmixed to
// mono 16 kHz PCM, into a ring buffer that the Kotlin speech recognizer polls.

#include "audio-tap.h"

#include <jni.h>
#include <pthread.h>
#include <string.h>

#define TAP_RATE 16000
#define TAP_CAPACITY (TAP_RATE * 4) // 4 seconds; older audio is dropped

static pthread_mutex_t tap_mutex = PTHREAD_MUTEX_INITIALIZER;
static int16_t tap_buf[TAP_CAPACITY];
static size_t tap_read_pos = 0;
static size_t tap_count = 0;
static volatile int tap_enabled = 0;

static uint32_t src_channels = 2;
static uint32_t src_rate = 48000;
static int64_t acc_sum = 0;
static int32_t acc_n = 0;
static uint32_t acc_phase = 0;

void android_chiaki_audio_tap_settings(uint32_t channels, uint32_t rate)
{
	pthread_mutex_lock(&tap_mutex);
	src_channels = channels ? channels : 2;
	src_rate = rate ? rate : 48000;
	acc_sum = 0;
	acc_n = 0;
	acc_phase = 0;
	tap_read_pos = 0;
	tap_count = 0;
	pthread_mutex_unlock(&tap_mutex);
}

static void tap_put(int16_t s)
{
	size_t write_pos = (tap_read_pos + tap_count) % TAP_CAPACITY;
	tap_buf[write_pos] = s;
	if(tap_count < TAP_CAPACITY)
		tap_count++;
	else
		tap_read_pos = (tap_read_pos + 1) % TAP_CAPACITY; // overwrite oldest
}

void android_chiaki_audio_tap_push(const int16_t *buf, size_t samples_count)
{
	if(!tap_enabled || !buf)
		return;
	pthread_mutex_lock(&tap_mutex);
	uint32_t ch = src_channels;
	size_t frames = samples_count / ch;
	for(size_t f = 0; f < frames; f++)
	{
		int32_t mono = 0;
		for(uint32_t c = 0; c < ch; c++)
			mono += buf[f * ch + c];
		mono /= (int32_t)ch;
		// boxcar decimation from src_rate to TAP_RATE
		acc_sum += mono;
		acc_n++;
		acc_phase += TAP_RATE;
		if(acc_phase >= src_rate)
		{
			acc_phase -= src_rate;
			tap_put((int16_t)(acc_sum / acc_n));
			acc_sum = 0;
			acc_n = 0;
		}
	}
	pthread_mutex_unlock(&tap_mutex);
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_translate_AudioTap_nativeSetEnabled(JNIEnv *env, jclass cls, jboolean enabled)
{
	pthread_mutex_lock(&tap_mutex);
	tap_enabled = enabled ? 1 : 0;
	tap_read_pos = 0;
	tap_count = 0;
	pthread_mutex_unlock(&tap_mutex);
}

JNIEXPORT jint JNICALL Java_com_metallic_chiaki_translate_AudioTap_nativeRead(JNIEnv *env, jclass cls, jshortArray out, jint max)
{
	jsize len = (*env)->GetArrayLength(env, out);
	if(max > len)
		max = len;
	if(max <= 0)
		return 0;
	int16_t tmp[4096];
	if(max > 4096)
		max = 4096;
	pthread_mutex_lock(&tap_mutex);
	jint n = (jint)(tap_count < (size_t)max ? tap_count : (size_t)max);
	for(jint i = 0; i < n; i++)
		tmp[i] = tap_buf[(tap_read_pos + i) % TAP_CAPACITY];
	tap_read_pos = (tap_read_pos + n) % TAP_CAPACITY;
	tap_count -= n;
	pthread_mutex_unlock(&tap_mutex);
	if(n > 0)
		(*env)->SetShortArrayRegion(env, out, 0, n, tmp);
	return n;
}

// Used by the video test screen: its player hands decoded PCM here instead of the PS5 stream.
JNIEXPORT void JNICALL Java_com_metallic_chiaki_translate_AudioTap_nativeConfigure(JNIEnv *env, jclass cls, jint channels, jint rate)
{
	android_chiaki_audio_tap_settings((uint32_t)channels, (uint32_t)rate);
}

JNIEXPORT void JNICALL Java_com_metallic_chiaki_translate_AudioTap_nativePush(JNIEnv *env, jclass cls, jshortArray data, jint count)
{
	if(count <= 0) return;
	jshort *buf = (*env)->GetShortArrayElements(env, data, NULL);
	if(!buf) return;
	android_chiaki_audio_tap_push((const int16_t *)buf, (size_t)count);
	(*env)->ReleaseShortArrayElements(env, data, buf, JNI_ABORT);
}
