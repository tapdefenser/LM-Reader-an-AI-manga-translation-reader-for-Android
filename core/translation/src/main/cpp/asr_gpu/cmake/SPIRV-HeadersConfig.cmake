# Minimal package config for the pinned Khronos SPIRV-Headers source archive.
# The upstream project only installs this file; the whisper.cpp build calls
# find_package(SPIRV-Headers CONFIG REQUIRED) before its compile step.
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    set(_lmreader_spirv_include
        "${CMAKE_CURRENT_LIST_DIR}/../../third_party/spirv-headers/include")
    if(NOT EXISTS "${_lmreader_spirv_include}/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR
            "Pinned SPIRV-Headers are missing. Run python tools/fetch_asr_sources.py first.")
    endif()
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_lmreader_spirv_include}")
endif()
set(SPIRV-Headers_FOUND TRUE)
