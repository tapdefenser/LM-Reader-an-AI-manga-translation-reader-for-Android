#pragma once

#include <string>

struct whisper_context;

namespace lmreader_asr {

enum class Backend {
    Auto,
    Cpu,
    Vulkan,
    OpenCl,
};

Backend parseBackendOrThrow(const std::string& value);
const char* backendId(Backend backend);

/** Honest, cached capability report for every backend compiled into this library. */
std::string capabilitiesJson();

struct LoadInfo {
    Backend requested = Backend::Cpu;
    Backend actual = Backend::Cpu;
    std::string device;
    std::string deviceDescription;
    std::string fallbackReason;

    std::string toJson() const;
};

struct CreateResult {
    whisper_context* context = nullptr;
    LoadInfo info;
};

/**
 * Creates one whisper.cpp context with the requested backend. AUTO tries Vulkan/OpenCL and
 * falls back to CPU only after a real GPU initialization failure. An explicitly requested GPU
 * backend either succeeds or throws; it is never silently replaced by CPU.
 */
CreateResult create(const std::string& modelPath, Backend requested);

}  // namespace lmreader_asr
