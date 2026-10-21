# Upstream kernel sources contain UTF-8 punctuation. Chinese Windows otherwise
# decodes them as GBK in embed_kernel.py. Keep the pinned upstream tree untouched.
include("${CMAKE_ROOT}/Modules/FindPython3.cmake")
if(Python3_Interpreter_FOUND)
  if(CMAKE_HOST_WIN32)
    set(_lmreader_python_utf8 "${CMAKE_CURRENT_BINARY_DIR}/lmreader-python-utf8.cmd")
    file(WRITE "${_lmreader_python_utf8}" "@\"${Python3_EXECUTABLE}\" -X utf8 %*\r\n")
  else()
    set(_lmreader_python_utf8 "${CMAKE_CURRENT_BINARY_DIR}/lmreader-python-utf8.sh")
    file(WRITE "${_lmreader_python_utf8}" "#!/bin/sh\nexec \"${Python3_EXECUTABLE}\" -X utf8 \"$@\"\n")
    file(CHMOD "${_lmreader_python_utf8}" PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
  endif()
  set(Python3_EXECUTABLE "${_lmreader_python_utf8}")
endif()
