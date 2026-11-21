// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"
#include "audio-tap.h"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <time.h>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;
	uint32_t channels = 0, rate = 0;
	// set when the sound device goes away (headphones / Bluetooth / another app took the output):
	// the decoder thread reopens the stream on its next frame instead of staying silent for the rest of the game
	std::atomic<bool> reopen{false};
	int64_t next_try_ms = 0;
	int overflows = 0;

	AudioOutput() : stream_callback(this) {}
};

static int64_t now_ms()
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/** Opens and starts the output. Shared mode first (works on every phone and TV), exclusive low-latency is not worth the failures. */
static bool open_stream(AudioOutput *ao)
{
	ao->stream = nullptr;
	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Shared)
		->setUsage(oboe::Usage::Game)
		->setContentType(oboe::ContentType::Music)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(ao->channels)
		->setSampleRate(ao->rate)
		->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
		->setCallback(&ao->stream_callback);

	auto result = builder.openManagedStream(ao->stream);
	if(result != oboe::Result::OK || !ao->stream)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));
		ao->stream = nullptr;
		return false;
	}
	CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream");
	result = ao->stream->requestStart();
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
		ao->stream = nullptr;
		return false;
	}
	CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	return true;
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	android_chiaki_audio_tap_settings(channels, rate);
	ao->channels = channels;
	ao->rate = rate;
	ao->reopen = false;
	if(!open_stream(ao))
	{
		ao->reopen = true;              // try again from the frame callback
		ao->next_try_ms = now_ms() + 1000;
	}
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	android_chiaki_audio_tap_push(buf, samples_count);

	// sound device lost or never opened: reopen (at most once a second)
	if(ao->rate && (ao->reopen || !ao->stream || ao->stream->getState() == oboe::StreamState::Disconnected) && now_ms() >= ao->next_try_ms)
	{
		CHIAKI_LOGW(ao->log, "Audio Output reopening Oboe stream");
		ao->next_try_ms = now_ms() + 1000;
		ao->reopen = !open_stream(ao);
	}

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
	{
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
		// the output stopped pulling (stream stalled without reporting it): restart it after a short while
		if(++ao->overflows >= 25) { ao->overflows = 0; ao->reopen = true; }
	}
	else
		ao->overflows = 0;
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
	audio_output->reopen = true;   // reopened from the decoder thread (never inside this callback)
}
