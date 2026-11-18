# Bionic's genuine 113-bit strtold parser, from the existing pinned AOSP
# checkout. Function bodies and original license notices are unchanged.
configure_file(${CMAKE_CURRENT_LIST_DIR}/../../tools/package/licenses/BIONIC-GDTOA-NOTICES.txt
               ${CMAKE_BINARY_DIR}/BIONIC-GDTOA-NOTICES.txt COPYONLY)
set(_gesher_gdtoa "${AOSP_DIR}/bionic/libc/upstream-openbsd/lib/libc/gdtoa")
add_library(gesher_bionic_binary128 STATIC
  ${CMAKE_CURRENT_LIST_DIR}/binary128_parse.cc
  ${_gesher_gdtoa}/strtorQ.c
  ${_gesher_gdtoa}/strtodg.c
  ${_gesher_gdtoa}/gethex.c
  ${_gesher_gdtoa}/hexnan.c
  ${_gesher_gdtoa}/hd_init.c
  ${_gesher_gdtoa}/misc.c
  ${_gesher_gdtoa}/smisc.c
  ${_gesher_gdtoa}/gmisc.c
  ${_gesher_gdtoa}/sum.c)
target_include_directories(gesher_bionic_binary128 PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}/gdtoa)
target_compile_options(gesher_bionic_binary128 PRIVATE
  -fno-strict-aliasing -frounding-math -ffp-contract=off
  -Wall -Wextra -Wno-sign-compare -Wno-unused-parameter)
