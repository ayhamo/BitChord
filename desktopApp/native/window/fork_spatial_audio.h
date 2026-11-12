// fork (ForkMultichannel): the Windows Spatial Sound output — ISpatialAudioClient with a static bed.
//
// Included by window_jni.cpp inside its anonymous namespace, after the WASAPI sink, so it shares
// that sink's device resolution, error text and endpoint watcher. Nothing here is upstream's.
//
// Why: with Dolby Atmos for Headphones, Windows Sonic or DTS on, a headphone endpoint's shared-mode
// mix format stays stereo. A plain 7.1 WASAPI stream is accepted (IsFormatSupported S_OK on the
// owner's A50), but whether the audio engine hands its eight channels to the spatial renderer or
// folds them to the stereo mix first is undocumented, and reports differ. Microsoft's documented
// route to the renderer is Spatial Sound (learn.microsoft.com, "Render spatial sound using spatial
// audio objects"): the app opens an ISpatialAudioObjectRenderStream and writes one mono buffer per
// static bed channel (FrontLeft … SideRight, LowFrequency), which the renderer places as virtual
// speakers. That is what OpenAL Soft's WASAPI backend does ("spatial-api" option), and what the
// open mpv "wasapi-spatial" output pull requests do. This file follows OpenAL Soft's shape:
//
// - object format: the client's own supported format 0 (on Windows 10/11, 48 kHz mono float);
//   Kotlin opens the line at that rate and the engine's rate converter feeds it;
// - StaticObjectTypeMask from the speaker mask the decks decode to, Category Media, an event handle;
// - a render thread: wait for the event, BeginUpdatingAudioObjects, activate each static object
//   once, GetBuffer on each, fill, EndUpdatingAudioObjects.
//
// The engine writes interleaved blocks as it always has (a push model, blocking when full); they go
// into a ring here and the render thread deinterleaves them into the objects. While paused, or when
// the ring runs dry, the objects get silence and nothing is counted as played.

struct ForkSpatialState {
    std::mutex mutex; // the ring, counters and flags below; never held while waiting on the device
    ISpatialAudioClient* client = nullptr;
    ISpatialAudioObjectRenderStream* stream = nullptr;
    HANDLE event = nullptr;
    HANDLE thread = nullptr;
    std::atomic<bool> stop{false};
    std::atomic<bool> broken{false}; // the render thread lost the device; the next write rebuilds

    std::vector<AudioObjectType> types; // one per channel, in interleaving (mask bit) order
    UINT32 channels = 0;
    DWORD mask = 0;
    int rate = 0;
    std::vector<float> ring; // interleaved
    size_t capacity = 0;     // frames
    uint64_t written = 0;    // frames ever pushed (since the last flush)
    uint64_t read = 0;       // frames ever taken by the render thread
    uint64_t played = 0;     // frames rendered, across flushes and rebuilds
    UINT32 period = 0;       // frames the renderer asks for at a time: what it holds beyond "played"
    bool paused = false;

    std::wstring wanted;
    std::wstring endpoint;
    std::wstring name;
};

ForkSpatialState g_fork_spatial;
// Whether the spatial line owns the output; the DesktopWindowsAudio exports check it first.
std::atomic<bool> g_fork_spatial_on{false};

/** The static bed object for one WAVEFORMATEXTENSIBLE speaker bit; AudioObjectType_None when it has none. */
AudioObjectType fork_spatial_type(DWORD speaker) {
    switch (speaker) {
        case SPEAKER_FRONT_LEFT: return AudioObjectType_FrontLeft;
        case SPEAKER_FRONT_RIGHT: return AudioObjectType_FrontRight;
        case SPEAKER_FRONT_CENTER: return AudioObjectType_FrontCenter;
        case SPEAKER_LOW_FREQUENCY: return AudioObjectType_LowFrequency;
        case SPEAKER_BACK_LEFT: return AudioObjectType_BackLeft;
        case SPEAKER_BACK_RIGHT: return AudioObjectType_BackRight;
        case SPEAKER_SIDE_LEFT: return AudioObjectType_SideLeft;
        case SPEAKER_SIDE_RIGHT: return AudioObjectType_SideRight;
        case SPEAKER_BACK_CENTER: return AudioObjectType_BackCenter;
        default: return AudioObjectType_None;
    }
}

/** Format 0 of the client's supported object formats, copied; false unless it is mono float. */
bool fork_spatial_object_format(ISpatialAudioClient* client, WAVEFORMATEX* out) {
    IAudioFormatEnumerator* formats = nullptr;
    if (FAILED(client->GetSupportedAudioObjectFormatEnumerator(&formats)) || formats == nullptr) return false;
    WAVEFORMATEX* format = nullptr;
    const bool got = SUCCEEDED(formats->GetFormat(0, &format)) && format != nullptr;
    bool usable = false;
    if (got) {
        const bool floating = format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
            (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
             format->cbSize >= sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX) &&
             reinterpret_cast<WAVEFORMATEXTENSIBLE*>(format)->SubFormat.Data1 == 3);
        usable = floating && format->nChannels == 1 && format->wBitsPerSample == 32 && format->nSamplesPerSec > 0;
        // A plain WAVEFORMATEX of the same thing: what the activation parameters are given.
        *out = WAVEFORMATEX{};
        out->wFormatTag = WAVE_FORMAT_IEEE_FLOAT;
        out->nChannels = 1;
        out->nSamplesPerSec = format->nSamplesPerSec;
        out->wBitsPerSample = 32;
        out->nBlockAlign = 4;
        out->nAvgBytesPerSec = format->nSamplesPerSec * 4;
        out->cbSize = 0;
    }
    // The enumerator owns the format it hands out.
    formats->Release();
    return usable;
}

/**
 * The object rate Spatial Sound takes on [device] while a spatial renderer is on there, else 0.
 * Microsoft: GetMaxDynamicObjectCount is 0 when the user has no spatial format selected for the
 * endpoint; IsSpatialAudioStreamAvailable says whether a stream could be opened now.
 */
int fork_spatial_rate_on(IMMDevice* device) {
    ISpatialAudioClient* client = nullptr;
    if (FAILED(device->Activate(__uuidof(ISpatialAudioClient), CLSCTX_INPROC_SERVER, nullptr,
                                reinterpret_cast<void**>(&client))) || client == nullptr) {
        return 0;
    }
    UINT32 dynamic = 0;
    WAVEFORMATEX format{};
    int rate = 0;
    if (SUCCEEDED(client->GetMaxDynamicObjectCount(&dynamic)) && dynamic > 0 &&
        client->IsSpatialAudioStreamAvailable(__uuidof(ISpatialAudioObjectRenderStream), nullptr) == S_OK &&
        fork_spatial_object_format(client, &format)) {
        rate = static_cast<int>(format.nSamplesPerSec);
    }
    client->Release();
    return rate;
}

int fork_spatial_rate(const std::wstring& wanted) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    ComScope com;
    if (!com.ready()) return 0;
    IMMDevice* device = resolve_device_locked(wanted);
    if (device == nullptr) return 0;
    // Our own bed already open there answers for itself: asked again while it holds the renderer,
    // IsSpatialAudioStreamAvailable may call it busy and flip the next song to another line.
    if (g_fork_spatial_on && g_fork_spatial.stream != nullptr && !g_fork_spatial.broken &&
        endpoint_id(device) == g_fork_spatial.endpoint) {
        device->Release();
        return g_fork_spatial.rate;
    }
    const int rate = fork_spatial_rate_on(device);
    device->Release();
    return rate;
}

DWORD WINAPI fork_spatial_render(LPVOID) {
    ComScope com;
    ForkSpatialState& s = g_fork_spatial;
    std::vector<ISpatialAudioObject*> objects(s.types.size(), nullptr);
    std::vector<float*> buffers(s.types.size(), nullptr);
    HRESULT status = s.stream->Start();
    if (FAILED(status)) s.broken = true;
    while (!s.stop && !s.broken) {
        // Signalled each period the renderer wants data. A timeout means the device went quiet
        // under us (OpenAL Soft resets here); Begin… below then reports what happened.
        WaitForSingleObject(s.event, 1000);
        if (s.stop) break;
        UINT32 dynamic = 0;
        UINT32 frames = 0;
        status = s.stream->BeginUpdatingAudioObjects(&dynamic, &frames);
        if (FAILED(status)) {
            s.broken = true;
            break;
        }
        bool ok = true;
        for (size_t index = 0; index < objects.size() && ok; ++index) {
            if (objects[index] == nullptr) {
                ok = SUCCEEDED(s.stream->ActivateSpatialAudioObject(s.types[index], &objects[index])) &&
                    objects[index] != nullptr;
                if (!ok) break;
            }
            BYTE* buffer = nullptr;
            UINT32 length = 0;
            ok = SUCCEEDED(objects[index]->GetBuffer(&buffer, &length)) && buffer != nullptr;
            buffers[index] = reinterpret_cast<float*>(buffer);
            if (ok && length / sizeof(float) < frames) frames = length / sizeof(float);
        }
        if (ok) {
            std::lock_guard<std::mutex> guard(s.mutex);
            const uint64_t queued = s.written - s.read;
            const UINT32 take = s.paused ? 0 : static_cast<UINT32>(std::min<uint64_t>(queued, frames));
            const UINT32 ch = s.channels;
            for (UINT32 frame = 0; frame < take; ++frame) {
                const float* source = &s.ring[((s.read + frame) % s.capacity) * ch];
                for (UINT32 c = 0; c < ch; ++c) buffers[c][frame] = source[c];
            }
            for (UINT32 c = 0; c < ch; ++c) {
                if (take < frames) std::memset(buffers[c] + take, 0, (frames - take) * sizeof(float));
            }
            s.read += take;
            s.played += take;
        }
        status = s.stream->EndUpdatingAudioObjects();
        if (!ok || FAILED(status)) {
            s.broken = true;
            break;
        }
    }
    s.stream->Stop();
    for (ISpatialAudioObject* object : objects) {
        if (object != nullptr) object->Release();
    }
    return 0;
}

/** Stops the render thread and drops the stream; the format, request and counters stay for a rebuild. */
void fork_spatial_release_stream() {
    ForkSpatialState& s = g_fork_spatial;
    if (s.thread != nullptr) {
        s.stop = true;
        if (s.event != nullptr) SetEvent(s.event);
        const DWORD joined = WaitForSingleObject(s.thread, 5000);
        CloseHandle(s.thread);
        s.thread = nullptr;
        if (joined != WAIT_OBJECT_0) {
            // The render thread is stuck inside the renderer: releasing what it is using would be a
            // use-after-free. Leak the stream and its event instead, and start clean.
            s.stream = nullptr;
            s.client = nullptr;
            s.event = nullptr;
        }
    }
    if (s.stream != nullptr) {
        s.stream->Reset();
        s.stream->Release();
        s.stream = nullptr;
    }
    if (s.client != nullptr) {
        s.client->Release();
        s.client = nullptr;
    }
    if (s.event != nullptr) {
        CloseHandle(s.event);
        s.event = nullptr;
    }
    s.stop = false;
    s.broken = false;
    s.endpoint.clear();
    s.name.clear();
}

/** Opens the bed on [device] in the stored layout and starts its render thread. Under g_audio_mutex. */
bool fork_spatial_start_on(IMMDevice* device) {
    ForkSpatialState& s = g_fork_spatial;
    HRESULT status = device->Activate(__uuidof(ISpatialAudioClient), CLSCTX_INPROC_SERVER, nullptr,
                                      reinterpret_cast<void**>(&s.client));
    if (FAILED(status) || s.client == nullptr) {
        s.client = nullptr;
        set_audio_error(L"Opening Windows Spatial Sound", status);
        return false;
    }
    UINT32 dynamic = 0;
    if (FAILED(s.client->GetMaxDynamicObjectCount(&dynamic)) || dynamic == 0) {
        g_audio_error = L"Spatial sound is off for this output in Windows";
        fork_spatial_release_stream();
        return false;
    }
    WAVEFORMATEX format{};
    if (!fork_spatial_object_format(s.client, &format)) {
        g_audio_error = L"Windows Spatial Sound offers no mono float object format";
        fork_spatial_release_stream();
        return false;
    }
    if (s.rate != 0 && static_cast<int>(format.nSamplesPerSec) != s.rate) {
        // Rebuilt on another endpoint whose objects run at another rate: Kotlin's converter is set
        // for the old one, so this has to go back through a reopen.
        g_audio_error = L"Windows Spatial Sound changed its object rate";
        fork_spatial_release_stream();
        return false;
    }
    UINT32 mask = 0;
    for (AudioObjectType type : s.types) mask |= static_cast<UINT32>(type);
    s.event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    SpatialAudioObjectRenderStreamActivationParams params{};
    params.ObjectFormat = &format;
    params.StaticObjectTypeMask = static_cast<AudioObjectType>(mask);
    params.MinDynamicObjectCount = 0;
    params.MaxDynamicObjectCount = 0;
    params.Category = AudioCategory_Media;
    params.EventHandle = s.event;
    params.NotifyObject = nullptr;
    PROPVARIANT activation;
    PropVariantInit(&activation);
    activation.vt = VT_BLOB;
    activation.blob.cbSize = sizeof(params);
    activation.blob.pBlobData = reinterpret_cast<BYTE*>(&params);
    // The blob points at the stack: no PropVariantClear.
    status = s.client->ActivateSpatialAudioStream(
        &activation, __uuidof(ISpatialAudioObjectRenderStream), reinterpret_cast<void**>(&s.stream));
    if (FAILED(status) || s.stream == nullptr) {
        s.stream = nullptr;
        set_audio_error(L"Starting the Spatial Sound stream", status);
        fork_spatial_release_stream();
        return false;
    }
    UINT32 period = 0;
    if (FAILED(s.client->GetMaxFrameCount(&format, &period)) || period == 0) period = format.nSamplesPerSec / 100;
    s.rate = static_cast<int>(format.nSamplesPerSec);
    s.period = period;
    if (s.capacity == 0) {
        // A fifth of a second, as the WASAPI line holds, and never under four periods.
        s.capacity = std::max<size_t>(static_cast<size_t>(s.rate) / 5, size_t{period} * 4);
        s.ring.assign(s.capacity * s.channels, 0.0f);
    }
    s.thread = CreateThread(nullptr, 0, fork_spatial_render, nullptr, 0, nullptr);
    if (s.thread == nullptr) {
        g_audio_error = L"Starting the Spatial Sound thread failed";
        fork_spatial_release_stream();
        return false;
    }
    s.endpoint = endpoint_id(device);
    s.name = property_string(device, PKEY_Device_FriendlyName);
    return true;
}

/** Drops the whole spatial line, counters included; a no-op when it isn't open. */
void fork_spatial_close() {
    if (!g_fork_spatial_on.exchange(false)) return;
    ForkSpatialState& s = g_fork_spatial;
    fork_spatial_release_stream();
    std::lock_guard<std::mutex> guard(s.mutex);
    s.types.clear();
    s.channels = 0;
    s.mask = 0;
    s.rate = 0;
    s.ring.clear();
    s.capacity = 0;
    s.written = s.read = s.played = 0;
    s.period = 0;
    s.paused = false;
    s.wanted.clear();
}

/**
 * Opens the spatial line for [channels] interleaved channels in [mask]'s speaker order. Returns
 * the ring's size in bytes and sets [rate] to the object rate the samples must be in; 0 on failure,
 * with the reason in the shared error text.
 */
int fork_spatial_open(const std::wstring& wanted, int channels, DWORD mask, int* rate) {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    release_audio_locked();
    fork_spatial_close();
    g_audio_error.clear();
    ComScope com;
    if (!com.ready()) {
        set_audio_error(L"COM initialization", com.result);
        return 0;
    }
    ForkSpatialState& s = g_fork_spatial;
    std::vector<AudioObjectType> types;
    for (DWORD bit = 1; bit != 0 && bit <= mask; bit <<= 1) {
        if ((mask & bit) == 0) continue;
        const AudioObjectType type = fork_spatial_type(bit);
        if (type == AudioObjectType_None) {
            g_audio_error = L"A speaker in this layout has no Spatial Sound bed channel";
            return 0;
        }
        types.push_back(type);
    }
    if (channels <= 0 || types.size() != static_cast<size_t>(channels)) {
        g_audio_error = L"The speaker mask doesn't match the channel count";
        return 0;
    }
    IMMDevice* device = resolve_device_locked(wanted);
    if (device == nullptr) {
        if (g_audio_error.empty()) g_audio_error = L"Windows has no audio endpoint to play on";
        return 0;
    }
    {
        std::lock_guard<std::mutex> ring(s.mutex);
        s.types = types;
        s.channels = static_cast<UINT32>(channels);
        s.mask = mask;
        s.rate = 0;
        s.capacity = 0;
        s.written = s.read = s.played = 0;
        s.paused = false;
        s.wanted = wanted;
    }
    const bool opened = fork_spatial_start_on(device);
    device->Release();
    if (!opened) {
        std::lock_guard<std::mutex> ring(s.mutex);
        s.types.clear();
        s.channels = 0;
        s.ring.clear();
        s.capacity = 0;
        s.rate = 0;
        return 0;
    }
    g_audio_reroute = false;
    g_fork_spatial_on = true;
    *rate = s.rate;
    return static_cast<int>(s.capacity * s.channels * sizeof(float));
}

/**
 * Moves the bed to wherever the request resolves now, or rebuilds it after the device was lost.
 * What was queued is dropped, as the WASAPI line drops its buffer. Under g_audio_mutex.
 */
/**
 * Spatial Sound can't be had on [device] any more (switched off in Windows mid-song, another device,
 * another object rate): the same samples go on as a plain WASAPI line in the same layout and rate,
 * the played count carried over, so playback continues instead of passing in silence. The next song
 * decides its line afresh. Under g_audio_mutex.
 */
void fork_spatial_fallback_locked(IMMDevice* device) {
    ForkSpatialState& s = g_fork_spatial;
    uint64_t played = 0;
    bool paused = false;
    {
        std::lock_guard<std::mutex> ring(s.mutex);
        played = s.played;
        paused = s.paused;
    }
    const std::wstring wanted = s.wanted;
    const int rate = s.rate;
    const UINT32 channels = s.channels;
    const DWORD mask = s.mask;
    fork_spatial_close();
    g_audio_wanted = wanted;
    g_audio_rate = rate;
    g_audio_channels = channels;
    g_audio_bytes_per_sample = 4;
    g_audio_float = true;
    g_audio_channel_mask = mask;
    g_audio_started = !paused;
    g_audio_played_offset = played;
    g_audio_submitted_frames = 0;
    start_client_locked(device);
}

void fork_spatial_reroute_locked() {
    ForkSpatialState& s = g_fork_spatial;
    g_audio_reroute = false;
    // Closed while this writer waited for the lock: nothing to rebuild, and nothing may be started.
    if (!g_fork_spatial_on) return;
    IMMDevice* device = resolve_device_locked(s.wanted);
    if (device != nullptr && s.stream != nullptr && !s.broken && endpoint_id(device) == s.endpoint) {
        device->Release();
        return;
    }
    fork_spatial_release_stream();
    {
        std::lock_guard<std::mutex> ring(s.mutex);
        s.read = s.written = 0;
    }
    if (device == nullptr) {
        g_audio_error = L"Windows has no audio endpoint to play on";
        return;
    }
    if (!fork_spatial_start_on(device)) fork_spatial_fallback_locked(device);
    device->Release();
}

int fork_spatial_write(const float* samples, int sample_count, float gain) {
    ForkSpatialState& s = g_fork_spatial;
    if (samples == nullptr || sample_count <= 0 || s.channels == 0) return 0;
    const UINT32 ch = s.channels;
    const uint64_t total = static_cast<uint64_t>(sample_count) / ch;
    uint64_t at = 0;
    while (at < total && g_fork_spatial_on) {
        if (g_audio_reroute || s.broken || s.stream == nullptr) {
            std::lock_guard<std::mutex> guard(g_audio_mutex);
            ComScope com;
            fork_spatial_reroute_locked();
        }
        // Fell back to a WASAPI line (or was closed): the rest of the block goes there, through the caller.
        if (!g_fork_spatial_on) break;
        if (s.stream == nullptr) {
            // Nowhere to play: the block passes in real time, counted as played, as on the WASAPI line.
            const uint64_t skipped = total - at;
            {
                std::lock_guard<std::mutex> ring(s.mutex);
                s.played += skipped;
            }
            at = total;
            Sleep(static_cast<DWORD>(std::max<uint64_t>(1, skipped * 1000 / std::max(1, s.rate))));
            break;
        }
        uint64_t pushed = 0;
        {
            std::lock_guard<std::mutex> ring(s.mutex);
            const uint64_t room = s.capacity - (s.written - s.read);
            pushed = std::min<uint64_t>(room, total - at);
            for (uint64_t frame = 0; frame < pushed; ++frame) {
                float* target = &s.ring[((s.written + frame) % s.capacity) * ch];
                const float* source = &samples[(at + frame) * ch];
                for (UINT32 c = 0; c < ch; ++c) target[c] = std::clamp(source[c] * gain, -1.0f, 1.0f);
            }
            s.written += pushed;
        }
        at += pushed;
        if (pushed == 0) Sleep(2);
    }
    return static_cast<int>(at * ch * sizeof(float));
}

/**
 * nativeWrite's path: the spatial line while it owns the output, and whatever of the block is left
 * once it fell back to WASAPI mid-block goes on there, so no samples are dropped at the switch.
 */
int fork_spatial_write_then_wasapi(const float* samples, int sample_count, float gain) {
    if (!g_fork_spatial_on) return write_audio(samples, sample_count, gain);
    const int bytes = fork_spatial_write(samples, sample_count, gain);
    if (g_fork_spatial_on) return bytes;
    const int done = bytes / static_cast<int>(sizeof(float));
    if (done >= sample_count) return bytes;
    return bytes + write_audio(samples + done, sample_count - done, gain);
}

/** Frames heard: those handed to the renderer, less the period it holds before they reach the device. */
uint64_t fork_spatial_played() {
    std::lock_guard<std::mutex> ring(g_fork_spatial.mutex);
    const uint64_t held = g_fork_spatial.paused ? 0 : g_fork_spatial.period;
    return g_fork_spatial.played > held ? g_fork_spatial.played - held : 0;
}

void fork_spatial_pause(bool paused) {
    std::lock_guard<std::mutex> ring(g_fork_spatial.mutex);
    g_fork_spatial.paused = paused;
}

void fork_spatial_flush() {
    std::lock_guard<std::mutex> ring(g_fork_spatial.mutex);
    g_fork_spatial.read = g_fork_spatial.written = 0;
}

void fork_spatial_drain() {
    ForkSpatialState& s = g_fork_spatial;
    while (g_fork_spatial_on && s.stream != nullptr && !s.broken) {
        UINT32 held = 0;
        {
            std::lock_guard<std::mutex> ring(s.mutex);
            if (s.paused) return;
            if (s.written == s.read) held = std::max<UINT32>(s.period, 1);
        }
        if (held != 0) {
            // The ring is empty; the renderer still has its last period to play.
            Sleep(static_cast<DWORD>(uint64_t{held} * 2 * 1000 / std::max(1, s.rate)) + 1);
            return;
        }
        Sleep(2);
    }
}

std::wstring fork_spatial_name() {
    std::lock_guard<std::mutex> guard(g_audio_mutex);
    return g_fork_spatial.name;
}
