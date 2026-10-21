# The ASR binary calls an optional Android vendor driver through a static forwarding
# shim. No hard DT_NEEDED dependency on a vendor libOpenCL is introduced.
set(OpenCL_INCLUDE_DIRS "${CMAKE_CURRENT_LIST_DIR}/../../third_party/opencl-headers")
if(NOT EXISTS "${OpenCL_INCLUDE_DIRS}/CL/cl.h" OR NOT TARGET lmreader_opencl_loader)
  message(FATAL_ERROR "Pinned OpenCL headers or the optional-driver loader are missing")
endif()
set(OpenCL_INCLUDE_DIR "${OpenCL_INCLUDE_DIRS}")
set(OpenCL_LIBRARIES lmreader_opencl_loader)
set(OpenCL_VERSION_STRING "3.0")
set(OpenCL_FOUND TRUE)
if(NOT TARGET OpenCL::OpenCL)
  add_library(OpenCL::OpenCL ALIAS lmreader_opencl_loader)
endif()
