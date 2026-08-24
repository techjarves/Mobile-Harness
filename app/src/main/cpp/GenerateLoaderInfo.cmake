if(NOT DEFINED READELF OR NOT DEFINED LOADER OR NOT DEFINED OUTPUT)
    message(FATAL_ERROR "READELF, LOADER, and OUTPUT are required")
endif()

execute_process(
    COMMAND "${READELF}" -s "${LOADER}"
    RESULT_VARIABLE readelf_result
    OUTPUT_VARIABLE symbols
    ERROR_VARIABLE readelf_error
)
if(NOT readelf_result EQUAL 0)
    message(FATAL_ERROR "llvm-readelf failed: ${readelf_error}")
endif()

string(REPLACE "\n" ";" symbol_lines "${symbols}")
foreach(line IN LISTS symbol_lines)
    if(line MATCHES "[ \t]_start$")
        set(start_line "${line}")
    elseif(line MATCHES "[ \t]pokedata_workaround$")
        set(workaround_line "${line}")
    endif()
endforeach()
if(NOT DEFINED start_line OR NOT DEFINED workaround_line)
    message(FATAL_ERROR "Could not locate loader symbols")
endif()
string(REGEX MATCH ":[ \t]+([0-9a-fA-F]+)" ignored "${start_line}")
set(start_hex "${CMAKE_MATCH_1}")
string(REGEX MATCH ":[ \t]+([0-9a-fA-F]+)" ignored "${workaround_line}")
set(workaround_hex "${CMAKE_MATCH_1}")
math(EXPR offset "0x${workaround_hex} - 0x${start_hex}")
file(WRITE "${OUTPUT}" "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=${offset};\n")
