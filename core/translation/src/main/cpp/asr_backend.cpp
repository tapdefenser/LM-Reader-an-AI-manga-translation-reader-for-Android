#include "asr_backend.h"

#include <whisper.h>
#include <ggml-backend.h>

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <exception>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(LMREADER_ASR_HAS_VULKAN)
#define LMREADER_ASR_VULKAN_COMPILED 1
#else
#define LMREADER_ASR_VULKAN_COMPILED 0
#endif

#if defined(LMREADER_ASR_HAS_OPENCL)
#define LMREADER_ASR_OPENCL_COMPILED 1
#else
#define LMREADER_ASR_OPENCL_COMPILED 0
#endif

namespace lmreader_asr {
namespace {

constexpr const char* kLogTag = "LMReaderAsr";

void silentLog(enum ggml_log_level, const char*, void*) {}

struct LogCapture {
    std::string usedBackend;
    bool gpuInitFailed = false;
    bool noGpu = false;
};

thread_local LogCapture* tlsCapture = nullptr;

/**
 * whisper.cpp logs `whisper_backend_init_gpu: using <name> backend` when it really selected a
 * GPU, and logs a failure/no-GPU line before silently falling back to CPU. We capture only
 * those backend-selection lines; model paths and transcript text are never stored or logged.
 */
void captureLog(enum ggml_log_level, const char* text, void*) {
    if (tlsCapture == nullptr || text == nullptr) {
        return;
    }
    const std::string message(text);
    const std::string usingMarker = ": using ";
    const std::size_t usingAt = message.find(usingMarker);
    if (usingAt != std::string::npos) {
        const std::size_t nameAt = usingAt + usingMarker.size();
        const std::size_t endAt = message.find(" backend", nameAt);
        if (endAt != std::string::npos && endAt > nameAt) {
            tlsCapture->usedBackend = message.substr(nameAt, endAt - nameAt);
        }
    }
    if (message.find("failed to initialize") != std::string::npos) {
        tlsCapture->gpuInitFailed = true;
    }
    if (message.find("no GPU found") != std::string::npos) {
        tlsCapture->noGpu = true;
    }
}

bool startsWith(const std::string& value, const std::string& prefix) {
    return value.size() >= prefix.size() &&
        value.compare(0, prefix.size(), prefix) == 0;
}

const char* backendRegName(Backend backend) {
    switch (backend) {
        case Backend::Vulkan:
            return "Vulkan";
        case Backend::OpenCl:
            return "OpenCL";
        case Backend::Cpu:
            return "CPU";
        default:
            return "";
    }
}

bool isCompiled(Backend backend) {
    switch (backend) {
        case Backend::Cpu:
            return true;
        case Backend::Vulkan:
            return LMREADER_ASR_VULKAN_COMPILED != 0;
        case Backend::OpenCl:
            return LMREADER_ASR_OPENCL_COMPILED != 0;
        default:
            return false;
    }
}

bool isGpuType(enum ggml_backend_dev_type type) {
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

const char* deviceTypeName(enum ggml_backend_dev_type type) {
    switch (type) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:
            return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:
            return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_IGPU:
            return "IGPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL:
            return "ACCEL";
        case GGML_BACKEND_DEVICE_TYPE_META:
            return "META";
        default:
            return "UNKNOWN";
    }
}

struct DeviceEntry {
    ggml_backend_dev_t device = nullptr;
    std::string regName;
    enum ggml_backend_dev_type type = GGML_BACKEND_DEVICE_TYPE_CPU;
    std::string name;
    std::string description;
    std::size_t memoryFree = 0;
    std::size_t memoryTotal = 0;
};

DeviceEntry describeDevice(ggml_backend_dev_t device) {
    DeviceEntry entry;
    entry.device = device;
    entry.type = ggml_backend_dev_type(device);
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(device);
    if (reg != nullptr) {
        const char* name = ggml_backend_reg_name(reg);
        if (name != nullptr) {
            entry.regName = name;
        }
    }
    ggml_backend_dev_props props{};
    ggml_backend_dev_get_props(device, &props);
    const char* name = props.name != nullptr ? props.name : ggml_backend_dev_name(device);
    const char* description = props.description != nullptr
        ? props.description
        : ggml_backend_dev_description(device);
    if (name != nullptr) {
        entry.name = name;
    }
    if (description != nullptr) {
        entry.description = description;
    }
    entry.memoryFree = props.memory_free;
    entry.memoryTotal = props.memory_total;
    return entry;
}

std::vector<DeviceEntry> allDevices() {
    std::vector<DeviceEntry> result;
    const std::size_t count = ggml_backend_dev_count();
    result.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (device != nullptr) {
            result.push_back(describeDevice(device));
        }
    }
    return result;
}

std::vector<DeviceEntry> devicesFor(const std::vector<DeviceEntry>& devices, Backend backend) {
    const std::string wanted = backendRegName(backend);
    std::vector<DeviceEntry> result;
    for (const DeviceEntry& device : devices) {
        if (device.regName == wanted) {
            result.push_back(device);
        }
    }
    return result;
}

bool probeAnyDevice(const std::vector<DeviceEntry>& devices, std::string* reason) {
    if (devices.empty()) {
        if (reason != nullptr) {
            *reason = "未检测到设备";
        }
        return false;
    }
    for (const DeviceEntry& entry : devices) {
        try {
            ggml_backend_t backend = ggml_backend_dev_init(entry.device, nullptr);
            if (backend == nullptr) {
                continue;
            }
            try {
                ggml_backend_free(backend);
            } catch (...) {
                // A cleanup failure does not make an otherwise initialized device unusable.
            }
            return true;
        } catch (...) {
            // Vulkan device creation can throw vk::SystemError; continue with the next device.
        }
    }
    if (reason != nullptr) {
        *reason = "设备初始化失败";
    }
    return false;
}

void appendJsonString(std::string& out, const std::string& value) {
    static const char hex[] = "0123456789abcdef";
    out.push_back('"');
    for (unsigned char character : value) {
        switch (character) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (character < 0x20) {
                    out += "\\u00";
                    out.push_back(hex[(character >> 4) & 0x0F]);
                    out.push_back(hex[character & 0x0F]);
                } else {
                    out.push_back(static_cast<char>(character));
                }
                break;
        }
    }
    out.push_back('"');
}

void appendJsonField(std::string& out, const char* key, const std::string& value, bool first) {
    if (!first) {
        out.push_back(',');
    }
    appendJsonString(out, key);
    out.push_back(':');
    appendJsonString(out, value);
}

void appendNullableJsonField(std::string& out, const char* key, const std::string& value, bool first) {
    if (!first) {
        out.push_back(',');
    }
    appendJsonString(out, key);
    out.push_back(':');
    if (value.empty()) {
        out += "null";
    } else {
        appendJsonString(out, value);
    }
}

std::string cpuDescription(const std::vector<DeviceEntry>& devices) {
    const std::vector<DeviceEntry> cpu = devicesFor(devices, Backend::Cpu);
    if (!cpu.empty() && !cpu.front().description.empty()) {
        return cpu.front().description;
    }
    return "whisper.cpp CPU";
}

struct GpuSelection {
    bool found = false;
    ggml_backend_dev_t device = nullptr;
    int gpuIndex = 0;
    std::string name;
    std::string description;
};

GpuSelection findGpu(const std::vector<DeviceEntry>& devices, Backend backend) {
    const std::string wanted = backendRegName(backend);
    GpuSelection selection;
    int gpuIndex = 0;
    for (const DeviceEntry& entry : devices) {
        if (!isGpuType(entry.type)) {
            continue;
        }
        if (entry.regName == wanted) {
            selection.found = true;
            selection.device = entry.device;
            selection.gpuIndex = gpuIndex;
            selection.name = entry.name;
            selection.description = entry.description;
            return selection;
        }
        ++gpuIndex;
    }
    return selection;
}

struct Attempt {
    bool ok = false;
    whisper_context* context = nullptr;
    LogCapture capture;
};

Attempt createAttempt(
    const std::string& modelPath,
    bool useGpu,
    int gpuDevice,
    Backend backend
) {
    Attempt attempt;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = useGpu;
    params.gpu_device = gpuDevice;

    tlsCapture = &attempt.capture;
    whisper_log_set(captureLog, nullptr);
    try {
        attempt.context = whisper_init_from_file_with_params(modelPath.c_str(), params);
    } catch (...) {
        attempt.context = nullptr;
    }
    tlsCapture = nullptr;
    whisper_log_set(silentLog, nullptr);
    ggml_log_set(silentLog, nullptr);

    if (attempt.context == nullptr) {
        attempt.ok = false;
        return attempt;
    }
    if (useGpu) {
        const std::string expected = backendRegName(backend);
        const bool confirmed =
            !attempt.capture.usedBackend.empty() &&
            startsWith(attempt.capture.usedBackend, expected) &&
            !attempt.capture.gpuInitFailed &&
            !attempt.capture.noGpu;
        if (!confirmed) {
            // whisper.cpp can silently continue on CPU after a GPU init failure. Free that
            // context and report failure instead of exposing it as a GPU session.
            whisper_free(attempt.context);
            attempt.context = nullptr;
            attempt.ok = false;
            return attempt;
        }
    }
    attempt.ok = true;
    return attempt;
}

void logSelected(const LoadInfo& info) {
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "ASR backend requested=%s actual=%s device=%s",
        backendId(info.requested),
        backendId(info.actual),
        info.device.empty() ? "-" : info.device.c_str());
    if (!info.fallbackReason.empty()) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "ASR backend fallback: %s",
            info.fallbackReason.c_str());
    }
}

std::string capabilitiesLocked() {
    const std::vector<DeviceEntry> devices = allDevices();
    struct Spec {
        Backend backend;
        const char* id;
        bool compiled;
        const char* missingReason;
    };
    const Spec specs[] = {
        {Backend::Cpu, "CPU", true, nullptr},
        {Backend::Vulkan, "VULKAN", isCompiled(Backend::Vulkan), "此构建未包含 Vulkan 后端"},
        {Backend::OpenCl, "OPENCL", isCompiled(Backend::OpenCl),
         "此构建未包含 OpenCL 后端（可选后端，默认关闭）"},
    };

    std::string json = "{\"backends\":[";
    bool firstBackend = true;
    for (const Spec& spec : specs) {
        if (!firstBackend) {
            json.push_back(',');
        }
        firstBackend = false;

        const std::vector<DeviceEntry> matched = devicesFor(devices, spec.backend);
        bool available = false;
        std::string reason;
        if (!spec.compiled) {
            reason = spec.missingReason != nullptr ? spec.missingReason : "当前构建未包含该后端";
        } else if (matched.empty()) {
            switch (spec.backend) {
                case Backend::Vulkan:
                    reason = "未检测到 Vulkan 设备（libvulkan.so 或驱动不可用）";
                    break;
                case Backend::OpenCl:
                    reason = "未检测到 OpenCL 设备（系统未提供 libOpenCL.so 或驱动不可用）";
                    break;
                default:
                    reason = "CPU 后端未注册";
                    break;
            }
        } else {
            available = probeAnyDevice(matched, &reason);
        }

        json += "{\"id\":";
        appendJsonString(json, spec.id);
        json += ",\"compiled\":";
        json += spec.compiled ? "true" : "false";
        json += ",\"available\":";
        json += available ? "true" : "false";
        json += ",\"devices\":[";
        bool firstDevice = true;
        for (const DeviceEntry& device : matched) {
            if (!firstDevice) {
                json.push_back(',');
            }
            firstDevice = false;
            json += "{\"name\":";
            appendJsonString(json, device.name);
            json += ",\"description\":";
            if (device.description.empty()) {
                json += "null";
            } else {
                appendJsonString(json, device.description);
            }
            json += ",\"type\":";
            appendJsonString(json, deviceTypeName(device.type));
            json += ",\"memoryFreeBytes\":";
            json += std::to_string(static_cast<unsigned long long>(device.memoryFree));
            json += ",\"memoryTotalBytes\":";
            json += std::to_string(static_cast<unsigned long long>(device.memoryTotal));
            json += "}";
        }
        json += "]";
        appendNullableJsonField(json, "reason", reason, /*first=*/false);
        json += "}";
    }
    json += "]}";
    return json;
}

std::mutex& backendMutex() {
    static std::mutex mutex;
    return mutex;
}

}  // namespace

Backend parseBackendOrThrow(const std::string& value) {
    std::string normalized;
    normalized.reserve(value.size());
    for (unsigned char character : value) {
        if (std::isspace(character) != 0) {
            continue;
        }
        normalized.push_back(static_cast<char>(std::toupper(character)));
    }
    if (normalized.empty() || normalized == "AUTO") {
        return Backend::Auto;
    }
    if (normalized == "CPU") {
        return Backend::Cpu;
    }
    if (normalized == "VULKAN") {
        return Backend::Vulkan;
    }
    if (normalized == "OPENCL") {
        return Backend::OpenCl;
    }
    throw std::invalid_argument("Unsupported ASR backend");
}

const char* backendId(Backend backend) {
    switch (backend) {
        case Backend::Auto:
            return "AUTO";
        case Backend::Cpu:
            return "CPU";
        case Backend::Vulkan:
            return "VULKAN";
        case Backend::OpenCl:
            return "OPENCL";
    }
    return "CPU";
}

std::string capabilitiesJson() {
    std::lock_guard<std::mutex> lock(backendMutex());
    static std::string cached;
    if (cached.empty()) {
        whisper_log_set(silentLog, nullptr);
        ggml_log_set(silentLog, nullptr);
        cached = capabilitiesLocked();
    }
    return cached;
}

std::string LoadInfo::toJson() const {
    std::string json = "{";
    appendJsonField(json, "requestedBackend", backendId(requested), /*first=*/true);
    appendJsonField(json, "actualBackend", backendId(actual), /*first=*/false);
    appendNullableJsonField(json, "device", device, /*first=*/false);
    appendNullableJsonField(json, "deviceDescription", deviceDescription, /*first=*/false);
    appendNullableJsonField(json, "fallbackReason", fallbackReason, /*first=*/false);
    json += "}";
    return json;
}

CreateResult create(const std::string& modelPath, Backend requested) {
    std::lock_guard<std::mutex> lock(backendMutex());
    const std::vector<DeviceEntry> devices = allDevices();

    if (requested == Backend::Auto) {
        // 自动后端只走 CPU。GPU 后端在推理阶段的原生崩溃无法被捕获：一旦厂商驱动
        // 段错误，整个进程会直接消失，回退逻辑来不及执行。GPU 只能显式手动选择。
        const Attempt cpu = createAttempt(modelPath, false, 0, Backend::Cpu);
        if (!cpu.ok) {
            throw std::runtime_error("无法加载 ASR 模型");
        }
        LoadInfo info;
        info.requested = Backend::Auto;
        info.actual = Backend::Cpu;
        info.device = "CPU";
        info.deviceDescription = cpuDescription(devices);
        logSelected(info);
        return CreateResult{cpu.context, info};
    }

    if (requested == Backend::Cpu) {
        const Attempt cpu = createAttempt(modelPath, false, 0, Backend::Cpu);
        if (!cpu.ok) {
            throw std::runtime_error("无法加载 ASR 模型");
        }
        LoadInfo info;
        info.requested = Backend::Cpu;
        info.actual = Backend::Cpu;
        info.device = "CPU";
        info.deviceDescription = cpuDescription(devices);
        logSelected(info);
        return CreateResult{cpu.context, info};
    }

    if (!isCompiled(requested)) {
        const std::string message = requested == Backend::Vulkan
            ? "Vulkan 后端不可用：当前构建未包含 Vulkan 后端"
            : "OpenCL 后端不可用：当前构建未包含 OpenCL 后端";
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
        throw std::runtime_error(message);
    }

    const GpuSelection selection = findGpu(devices, requested);
    if (!selection.found) {
        const std::string message = requested == Backend::Vulkan
            ? "Vulkan 后端不可用：未检测到可用 Vulkan 设备"
            : "OpenCL 后端不可用：未检测到可用 OpenCL 设备";
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
        throw std::runtime_error(message);
    }

    const Attempt attempt = createAttempt(modelPath, true, selection.gpuIndex, requested);
    if (!attempt.ok) {
        const std::string message = requested == Backend::Vulkan
            ? "Vulkan 后端初始化失败；请更新 GPU 驱动或改用 CPU 后端"
            : "OpenCL 后端初始化失败；请检查系统 OpenCL 驱动或改用 CPU 后端";
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
        throw std::runtime_error(message);
    }

    LoadInfo info;
    info.requested = requested;
    info.actual = requested;
    info.device = attempt.capture.usedBackend.empty() ? selection.name : attempt.capture.usedBackend;
    info.deviceDescription = selection.description;
    logSelected(info);
    return CreateResult{attempt.context, info};
}

}  // namespace lmreader_asr
