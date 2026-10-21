// OpenCL forwarding shim for the static GGML_OPENCL backend.
//
// The app never links against a vendor OpenCL library.  This translation unit
// owns the cl* symbols and resolves them from a vendor driver at runtime.  The
// selected dlopen handle is intentionally retained for the process lifetime so
// the cached function pointers cannot dangle.

#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS

#if defined(__GNUC__)
#pragma GCC visibility push(hidden)
#endif
#include <CL/cl.h>
#include <CL/cl_ext.h>
#if defined(__GNUC__)
#pragma GCC visibility pop
#endif

#include <dlfcn.h>

#include <mutex>

#if defined(__GNUC__)
#define LMREADER_OPENCL_EXPORT __attribute__((visibility("default")))
#else
#define LMREADER_OPENCL_EXPORT
#endif

namespace {

struct OpenClApi {
    decltype(&::clGetPlatformIDs) p_clGetPlatformIDs = nullptr;
    decltype(&::clGetPlatformInfo) p_clGetPlatformInfo = nullptr;
    decltype(&::clGetDeviceIDs) p_clGetDeviceIDs = nullptr;
    decltype(&::clGetDeviceInfo) p_clGetDeviceInfo = nullptr;
    decltype(&::clCreateContext) p_clCreateContext = nullptr;
    decltype(&::clCreateCommandQueue) p_clCreateCommandQueue = nullptr;
    decltype(&::clCreateBuffer) p_clCreateBuffer = nullptr;
    decltype(&::clCreateSubBuffer) p_clCreateSubBuffer = nullptr;
    decltype(&::clCreateImage) p_clCreateImage = nullptr;
    decltype(&::clCreateBufferWithProperties) p_clCreateBufferWithProperties = nullptr;
    decltype(&::clReleaseMemObject) p_clReleaseMemObject = nullptr;
    decltype(&::clCreateProgramWithSource) p_clCreateProgramWithSource = nullptr;
    decltype(&::clCreateProgramWithBinary) p_clCreateProgramWithBinary = nullptr;
    decltype(&::clReleaseProgram) p_clReleaseProgram = nullptr;
    decltype(&::clBuildProgram) p_clBuildProgram = nullptr;
    decltype(&::clGetProgramInfo) p_clGetProgramInfo = nullptr;
    decltype(&::clGetProgramBuildInfo) p_clGetProgramBuildInfo = nullptr;
    decltype(&::clCreateKernel) p_clCreateKernel = nullptr;
    decltype(&::clReleaseKernel) p_clReleaseKernel = nullptr;
    decltype(&::clSetKernelArg) p_clSetKernelArg = nullptr;
    decltype(&::clGetKernelInfo) p_clGetKernelInfo = nullptr;
    decltype(&::clGetKernelWorkGroupInfo) p_clGetKernelWorkGroupInfo = nullptr;
    decltype(&::clGetKernelSubGroupInfo) p_clGetKernelSubGroupInfo = nullptr;
    decltype(&::clWaitForEvents) p_clWaitForEvents = nullptr;
    decltype(&::clReleaseEvent) p_clReleaseEvent = nullptr;
    decltype(&::clGetEventProfilingInfo) p_clGetEventProfilingInfo = nullptr;
    decltype(&::clFlush) p_clFlush = nullptr;
    decltype(&::clFinish) p_clFinish = nullptr;
    decltype(&::clEnqueueReadBuffer) p_clEnqueueReadBuffer = nullptr;
    decltype(&::clEnqueueWriteBuffer) p_clEnqueueWriteBuffer = nullptr;
    decltype(&::clEnqueueFillBuffer) p_clEnqueueFillBuffer = nullptr;
    decltype(&::clEnqueueCopyBuffer) p_clEnqueueCopyBuffer = nullptr;
    decltype(&::clEnqueueMapBuffer) p_clEnqueueMapBuffer = nullptr;
    decltype(&::clEnqueueUnmapMemObject) p_clEnqueueUnmapMemObject = nullptr;
    decltype(&::clEnqueueNDRangeKernel) p_clEnqueueNDRangeKernel = nullptr;
    decltype(&::clEnqueueMarkerWithWaitList) p_clEnqueueMarkerWithWaitList = nullptr;
    decltype(&::clEnqueueBarrierWithWaitList) p_clEnqueueBarrierWithWaitList = nullptr;
};

std::once_flag g_load_once;
void *g_driver_handle = nullptr;
OpenClApi g_api{};
bool g_driver_available = false;

OpenClApi load_api(void *handle) {
    OpenClApi api{};

#define LMREADER_LOAD_API_SYMBOL(field, symbol_name) \
    api.field = reinterpret_cast<decltype(api.field)>(dlsym(handle, (symbol_name)))

    LMREADER_LOAD_API_SYMBOL(p_clGetPlatformIDs, "clGetPlatformIDs");
    LMREADER_LOAD_API_SYMBOL(p_clGetPlatformInfo, "clGetPlatformInfo");
    LMREADER_LOAD_API_SYMBOL(p_clGetDeviceIDs, "clGetDeviceIDs");
    LMREADER_LOAD_API_SYMBOL(p_clGetDeviceInfo, "clGetDeviceInfo");
    LMREADER_LOAD_API_SYMBOL(p_clCreateContext, "clCreateContext");
    LMREADER_LOAD_API_SYMBOL(p_clCreateCommandQueue, "clCreateCommandQueue");
    LMREADER_LOAD_API_SYMBOL(p_clCreateBuffer, "clCreateBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clCreateSubBuffer, "clCreateSubBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clCreateImage, "clCreateImage");
    LMREADER_LOAD_API_SYMBOL(p_clCreateBufferWithProperties, "clCreateBufferWithProperties");
    LMREADER_LOAD_API_SYMBOL(p_clReleaseMemObject, "clReleaseMemObject");
    LMREADER_LOAD_API_SYMBOL(p_clCreateProgramWithSource, "clCreateProgramWithSource");
    LMREADER_LOAD_API_SYMBOL(p_clCreateProgramWithBinary, "clCreateProgramWithBinary");
    LMREADER_LOAD_API_SYMBOL(p_clReleaseProgram, "clReleaseProgram");
    LMREADER_LOAD_API_SYMBOL(p_clBuildProgram, "clBuildProgram");
    LMREADER_LOAD_API_SYMBOL(p_clGetProgramInfo, "clGetProgramInfo");
    LMREADER_LOAD_API_SYMBOL(p_clGetProgramBuildInfo, "clGetProgramBuildInfo");
    LMREADER_LOAD_API_SYMBOL(p_clCreateKernel, "clCreateKernel");
    LMREADER_LOAD_API_SYMBOL(p_clReleaseKernel, "clReleaseKernel");
    LMREADER_LOAD_API_SYMBOL(p_clSetKernelArg, "clSetKernelArg");
    LMREADER_LOAD_API_SYMBOL(p_clGetKernelInfo, "clGetKernelInfo");
    LMREADER_LOAD_API_SYMBOL(p_clGetKernelWorkGroupInfo, "clGetKernelWorkGroupInfo");
    LMREADER_LOAD_API_SYMBOL(p_clGetKernelSubGroupInfo, "clGetKernelSubGroupInfo");
    LMREADER_LOAD_API_SYMBOL(p_clWaitForEvents, "clWaitForEvents");
    LMREADER_LOAD_API_SYMBOL(p_clReleaseEvent, "clReleaseEvent");
    LMREADER_LOAD_API_SYMBOL(p_clGetEventProfilingInfo, "clGetEventProfilingInfo");
    LMREADER_LOAD_API_SYMBOL(p_clFlush, "clFlush");
    LMREADER_LOAD_API_SYMBOL(p_clFinish, "clFinish");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueReadBuffer, "clEnqueueReadBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueWriteBuffer, "clEnqueueWriteBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueFillBuffer, "clEnqueueFillBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueCopyBuffer, "clEnqueueCopyBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueMapBuffer, "clEnqueueMapBuffer");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueUnmapMemObject, "clEnqueueUnmapMemObject");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueNDRangeKernel, "clEnqueueNDRangeKernel");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueMarkerWithWaitList, "clEnqueueMarkerWithWaitList");
    LMREADER_LOAD_API_SYMBOL(p_clEnqueueBarrierWithWaitList, "clEnqueueBarrierWithWaitList");

#undef LMREADER_LOAD_API_SYMBOL

    return api;
}

bool has_essential_symbols(const OpenClApi &api) {
    return api.p_clGetPlatformIDs != nullptr &&
           api.p_clGetPlatformInfo != nullptr &&
           api.p_clGetDeviceIDs != nullptr &&
           api.p_clGetDeviceInfo != nullptr &&
           api.p_clCreateContext != nullptr &&
           api.p_clCreateCommandQueue != nullptr &&
           api.p_clCreateBuffer != nullptr &&
           api.p_clCreateSubBuffer != nullptr &&
           api.p_clCreateImage != nullptr &&
           api.p_clCreateProgramWithSource != nullptr &&
           api.p_clCreateProgramWithBinary != nullptr &&
           api.p_clBuildProgram != nullptr &&
           api.p_clCreateKernel != nullptr &&
           api.p_clSetKernelArg != nullptr &&
           api.p_clGetKernelInfo != nullptr &&
           api.p_clGetKernelWorkGroupInfo != nullptr &&
           api.p_clGetProgramInfo != nullptr &&
           api.p_clGetProgramBuildInfo != nullptr &&
           api.p_clGetEventProfilingInfo != nullptr &&
           api.p_clEnqueueNDRangeKernel != nullptr &&
           api.p_clEnqueueReadBuffer != nullptr &&
           api.p_clEnqueueWriteBuffer != nullptr &&
           api.p_clEnqueueCopyBuffer != nullptr &&
           api.p_clEnqueueFillBuffer != nullptr &&
           api.p_clEnqueueMapBuffer != nullptr &&
           api.p_clEnqueueUnmapMemObject != nullptr &&
           api.p_clEnqueueMarkerWithWaitList != nullptr &&
           api.p_clEnqueueBarrierWithWaitList != nullptr &&
           api.p_clFinish != nullptr &&
           api.p_clFlush != nullptr &&
           api.p_clWaitForEvents != nullptr &&
           api.p_clReleaseEvent != nullptr &&
           api.p_clReleaseKernel != nullptr &&
           api.p_clReleaseMemObject != nullptr &&
           api.p_clReleaseProgram != nullptr;
}

void load_driver() {
    const char *const candidates[] = {
        "libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
    };

    for (const char *candidate : candidates) {
        void *handle = dlopen(candidate, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            continue;
        }

        const OpenClApi api = load_api(handle);
        if (!has_essential_symbols(api)) {
            dlclose(handle);
            continue;
        }

        // Never close this handle or call atexit handlers for it: the cached
        // pointers above are used for the rest of the process lifetime.
        g_driver_handle = handle;
        g_api = api;
        g_driver_available = true;
        return;
    }
}

void ensure_driver_loaded() {
    std::call_once(g_load_once, load_driver);
}

void set_error(cl_int *errcode_ret, cl_int error) {
    if (errcode_ret != nullptr) {
        *errcode_ret = error;
    }
}

} // namespace

#if defined(__GNUC__)
#pragma GCC visibility push(hidden)
#endif

extern "C" cl_int CL_API_CALL
clGetPlatformIDs(cl_uint num_entries,
                 cl_platform_id *platforms,
                 cl_uint *num_platforms) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetPlatformIDs == nullptr) {
        if (num_platforms != nullptr) {
            *num_platforms = 0;
        }
        return CL_PLATFORM_NOT_FOUND_KHR;
    }
    return g_api.p_clGetPlatformIDs(num_entries, platforms, num_platforms);
}

extern "C" cl_int CL_API_CALL
clGetPlatformInfo(cl_platform_id platform,
                  cl_platform_info param_name,
                  size_t param_value_size,
                  void *param_value,
                  size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetPlatformInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetPlatformInfo(platform, param_name, param_value_size,
                                     param_value, param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clGetDeviceIDs(cl_platform_id platform,
               cl_device_type device_type,
               cl_uint num_entries,
               cl_device_id *devices,
               cl_uint *num_devices) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetDeviceIDs == nullptr) {
        if (num_devices != nullptr) {
            *num_devices = 0;
        }
        return CL_DEVICE_NOT_FOUND;
    }
    return g_api.p_clGetDeviceIDs(platform, device_type, num_entries, devices,
                                  num_devices);
}

extern "C" cl_int CL_API_CALL
clGetDeviceInfo(cl_device_id device,
                cl_device_info param_name,
                size_t param_value_size,
                void *param_value,
                size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetDeviceInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetDeviceInfo(device, param_name, param_value_size,
                                   param_value, param_value_size_ret);
}

extern "C" cl_context CL_API_CALL
clCreateContext(const cl_context_properties *properties,
                cl_uint num_devices,
                const cl_device_id *devices,
                void (CL_CALLBACK *pfn_notify)(const char *errinfo,
                                               const void *private_info,
                                               size_t cb,
                                               void *user_data),
                void *user_data,
                cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateContext == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateContext(properties, num_devices, devices, pfn_notify,
                                   user_data, errcode_ret);
}

extern "C" cl_command_queue CL_API_CALL
clCreateCommandQueue(cl_context context,
                     cl_device_id device,
                     cl_command_queue_properties properties,
                     cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateCommandQueue == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateCommandQueue(context, device, properties, errcode_ret);
}

extern "C" cl_mem CL_API_CALL
clCreateBuffer(cl_context context,
               cl_mem_flags flags,
               size_t size,
               void *host_ptr,
               cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateBuffer == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
}

extern "C" cl_mem CL_API_CALL
clCreateSubBuffer(cl_mem buffer,
                  cl_mem_flags flags,
                  cl_buffer_create_type buffer_create_type,
                  const void *buffer_create_info,
                  cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateSubBuffer == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateSubBuffer(buffer, flags, buffer_create_type,
                                     buffer_create_info, errcode_ret);
}

extern "C" cl_mem CL_API_CALL
clCreateImage(cl_context context,
              cl_mem_flags flags,
              const cl_image_format *image_format,
              const cl_image_desc *image_desc,
              void *host_ptr,
              cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateImage == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateImage(context, flags, image_format, image_desc,
                                 host_ptr, errcode_ret);
}

extern "C" cl_mem CL_API_CALL
clCreateBufferWithProperties(cl_context context,
                             const cl_mem_properties *properties,
                             cl_mem_flags flags,
                             size_t size,
                             void *host_ptr,
                             cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }

    if (g_api.p_clCreateBufferWithProperties != nullptr) {
        return g_api.p_clCreateBufferWithProperties(context, properties, flags,
                                                    size, host_ptr, errcode_ret);
    }

    if (properties == nullptr || properties[0] == 0) {
        if (g_api.p_clCreateBuffer == nullptr) {
            set_error(errcode_ret, CL_INVALID_OPERATION);
            return nullptr;
        }
        return g_api.p_clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
    }

    // The driver cannot honor a non-empty property list without the 3.0 API.
    set_error(errcode_ret, CL_INVALID_OPERATION);
    return nullptr;
}

extern "C" cl_int CL_API_CALL
clReleaseMemObject(cl_mem memobj) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clReleaseMemObject == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clReleaseMemObject(memobj);
}

extern "C" cl_program CL_API_CALL
clCreateProgramWithSource(cl_context context,
                          cl_uint count,
                          const char **strings,
                          const size_t *lengths,
                          cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateProgramWithSource == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateProgramWithSource(context, count, strings, lengths,
                                             errcode_ret);
}

extern "C" cl_program CL_API_CALL
clCreateProgramWithBinary(cl_context context,
                          cl_uint num_devices,
                          const cl_device_id *device_list,
                          const size_t *lengths,
                          const unsigned char **binaries,
                          cl_int *binary_status,
                          cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateProgramWithBinary == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateProgramWithBinary(context, num_devices, device_list,
                                             lengths, binaries, binary_status,
                                             errcode_ret);
}

extern "C" cl_int CL_API_CALL
clReleaseProgram(cl_program program) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clReleaseProgram == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clReleaseProgram(program);
}

extern "C" cl_int CL_API_CALL
clBuildProgram(cl_program program,
               cl_uint num_devices,
               const cl_device_id *device_list,
               const char *options,
               void (CL_CALLBACK *pfn_notify)(cl_program program,
                                              void *user_data),
               void *user_data) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clBuildProgram == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clBuildProgram(program, num_devices, device_list, options,
                                  pfn_notify, user_data);
}

extern "C" cl_int CL_API_CALL
clGetProgramInfo(cl_program program,
                 cl_program_info param_name,
                 size_t param_value_size,
                 void *param_value,
                 size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetProgramInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetProgramInfo(program, param_name, param_value_size,
                                    param_value, param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clGetProgramBuildInfo(cl_program program,
                      cl_device_id device,
                      cl_program_build_info param_name,
                      size_t param_value_size,
                      void *param_value,
                      size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetProgramBuildInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetProgramBuildInfo(program, device, param_name,
                                         param_value_size, param_value,
                                         param_value_size_ret);
}

extern "C" cl_kernel CL_API_CALL
clCreateKernel(cl_program program,
               const char *kernel_name,
               cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clCreateKernel == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clCreateKernel(program, kernel_name, errcode_ret);
}

extern "C" cl_int CL_API_CALL
clReleaseKernel(cl_kernel kernel) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clReleaseKernel == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clReleaseKernel(kernel);
}

extern "C" cl_int CL_API_CALL
clSetKernelArg(cl_kernel kernel,
               cl_uint arg_index,
               size_t arg_size,
               const void *arg_value) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clSetKernelArg == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clSetKernelArg(kernel, arg_index, arg_size, arg_value);
}

extern "C" cl_int CL_API_CALL
clGetKernelInfo(cl_kernel kernel,
                cl_kernel_info param_name,
                size_t param_value_size,
                void *param_value,
                size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetKernelInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetKernelInfo(kernel, param_name, param_value_size,
                                   param_value, param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clGetKernelWorkGroupInfo(cl_kernel kernel,
                         cl_device_id device,
                         cl_kernel_work_group_info param_name,
                         size_t param_value_size,
                         void *param_value,
                         size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetKernelWorkGroupInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetKernelWorkGroupInfo(kernel, device, param_name,
                                            param_value_size, param_value,
                                            param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clGetKernelSubGroupInfo(cl_kernel kernel,
                        cl_device_id device,
                        cl_kernel_sub_group_info param_name,
                        size_t input_value_size,
                        const void *input_value,
                        size_t param_value_size,
                        void *param_value,
                        size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetKernelSubGroupInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetKernelSubGroupInfo(kernel, device, param_name,
                                           input_value_size, input_value,
                                           param_value_size, param_value,
                                           param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clWaitForEvents(cl_uint num_events,
                const cl_event *event_list) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clWaitForEvents == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clWaitForEvents(num_events, event_list);
}

extern "C" cl_int CL_API_CALL
clReleaseEvent(cl_event event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clReleaseEvent == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clReleaseEvent(event);
}

extern "C" cl_int CL_API_CALL
clGetEventProfilingInfo(cl_event event,
                        cl_profiling_info param_name,
                        size_t param_value_size,
                        void *param_value,
                        size_t *param_value_size_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clGetEventProfilingInfo == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clGetEventProfilingInfo(event, param_name, param_value_size,
                                           param_value, param_value_size_ret);
}

extern "C" cl_int CL_API_CALL
clFlush(cl_command_queue command_queue) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clFlush == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clFlush(command_queue);
}

extern "C" cl_int CL_API_CALL
clFinish(cl_command_queue command_queue) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clFinish == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clFinish(command_queue);
}

extern "C" cl_int CL_API_CALL
clEnqueueReadBuffer(cl_command_queue command_queue,
                    cl_mem buffer,
                    cl_bool blocking_read,
                    size_t offset,
                    size_t size,
                    void *ptr,
                    cl_uint num_events_in_wait_list,
                    const cl_event *event_wait_list,
                    cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueReadBuffer == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueReadBuffer(command_queue, buffer, blocking_read,
                                       offset, size, ptr,
                                       num_events_in_wait_list,
                                       event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueWriteBuffer(cl_command_queue command_queue,
                     cl_mem buffer,
                     cl_bool blocking_write,
                     size_t offset,
                     size_t size,
                     const void *ptr,
                     cl_uint num_events_in_wait_list,
                     const cl_event *event_wait_list,
                     cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueWriteBuffer == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueWriteBuffer(command_queue, buffer, blocking_write,
                                        offset, size, ptr,
                                        num_events_in_wait_list,
                                        event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueFillBuffer(cl_command_queue command_queue,
                    cl_mem buffer,
                    const void *pattern,
                    size_t pattern_size,
                    size_t offset,
                    size_t size,
                    cl_uint num_events_in_wait_list,
                    const cl_event *event_wait_list,
                    cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueFillBuffer == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueFillBuffer(command_queue, buffer, pattern,
                                       pattern_size, offset, size,
                                       num_events_in_wait_list,
                                       event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueCopyBuffer(cl_command_queue command_queue,
                    cl_mem src_buffer,
                    cl_mem dst_buffer,
                    size_t src_offset,
                    size_t dst_offset,
                    size_t size,
                    cl_uint num_events_in_wait_list,
                    const cl_event *event_wait_list,
                    cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueCopyBuffer == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueCopyBuffer(command_queue, src_buffer, dst_buffer,
                                       src_offset, dst_offset, size,
                                       num_events_in_wait_list,
                                       event_wait_list, event);
}

extern "C" void * CL_API_CALL
clEnqueueMapBuffer(cl_command_queue command_queue,
                   cl_mem buffer,
                   cl_bool blocking_map,
                   cl_map_flags map_flags,
                   size_t offset,
                   size_t size,
                   cl_uint num_events_in_wait_list,
                   const cl_event *event_wait_list,
                   cl_event *event,
                   cl_int *errcode_ret) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueMapBuffer == nullptr) {
        set_error(errcode_ret, CL_INVALID_OPERATION);
        return nullptr;
    }
    return g_api.p_clEnqueueMapBuffer(command_queue, buffer, blocking_map,
                                      map_flags, offset, size,
                                      num_events_in_wait_list,
                                      event_wait_list, event, errcode_ret);
}

extern "C" cl_int CL_API_CALL
clEnqueueUnmapMemObject(cl_command_queue command_queue,
                        cl_mem memobj,
                        void *mapped_ptr,
                        cl_uint num_events_in_wait_list,
                        const cl_event *event_wait_list,
                        cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueUnmapMemObject == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueUnmapMemObject(command_queue, memobj, mapped_ptr,
                                           num_events_in_wait_list,
                                           event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueNDRangeKernel(cl_command_queue command_queue,
                       cl_kernel kernel,
                       cl_uint work_dim,
                       const size_t *global_work_offset,
                       const size_t *global_work_size,
                       const size_t *local_work_size,
                       cl_uint num_events_in_wait_list,
                       const cl_event *event_wait_list,
                       cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueNDRangeKernel == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueNDRangeKernel(command_queue, kernel, work_dim,
                                          global_work_offset, global_work_size,
                                          local_work_size,
                                          num_events_in_wait_list,
                                          event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueMarkerWithWaitList(cl_command_queue command_queue,
                            cl_uint num_events_in_wait_list,
                            const cl_event *event_wait_list,
                            cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueMarkerWithWaitList == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueMarkerWithWaitList(command_queue,
                                               num_events_in_wait_list,
                                               event_wait_list, event);
}

extern "C" cl_int CL_API_CALL
clEnqueueBarrierWithWaitList(cl_command_queue command_queue,
                             cl_uint num_events_in_wait_list,
                             const cl_event *event_wait_list,
                             cl_event *event) {
    ensure_driver_loaded();
    if (!g_driver_available || g_api.p_clEnqueueBarrierWithWaitList == nullptr) {
        return CL_INVALID_OPERATION;
    }
    return g_api.p_clEnqueueBarrierWithWaitList(command_queue,
                                                num_events_in_wait_list,
                                                event_wait_list, event);
}

#if defined(__GNUC__)
#pragma GCC visibility pop
#endif

extern "C" LMREADER_OPENCL_EXPORT bool
lmreader_opencl_driver_available(void) {
    ensure_driver_loaded();
    return g_driver_available && g_driver_handle != nullptr;
}
