cmake_minimum_required(VERSION 3.0)

# Get the git hash of the clash core.
# Upstream reads it from a registered git submodule. When the core is vendored
# as plain files (no submodule), `git submodule foreach` prints nothing and
# list(GET) would abort the configure step, so fall back to the parent repo
# and finally to "unknown".

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")
execute_process(
  COMMAND git submodule foreach git log -1 --format=%H
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  OUTPUT_VARIABLE COMMIT_HASH
  ERROR_QUIET
)
string(REPLACE "\n" ";" COMMIT_HASH "${COMMIT_HASH}")
list(LENGTH COMMIT_HASH COMMIT_HASH_LEN)
if(COMMIT_HASH_LEN GREATER 1)
  list(GET COMMIT_HASH 1 COMMIT_HASH)
else()
  execute_process(
    COMMAND git rev-parse HEAD
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE COMMIT_HASH
    ERROR_QUIET
  )
endif()
string(REGEX REPLACE "[\n\t\r ]" "" COMMIT_HASH "${COMMIT_HASH}")
if("${COMMIT_HASH}" STREQUAL "")
  set(COMMIT_HASH "unknown")
endif()
string(SUBSTRING "${COMMIT_HASH}" 0 7 COMMIT_HASH)
message(STATUS "git hash= ${COMMIT_HASH}")

# Get the branch name (same fallback strategy)
execute_process(
  COMMAND git submodule foreach git branch -r --contains ${COMMIT_HASH}
  WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  OUTPUT_VARIABLE CURRENT_BRANCH
  ERROR_QUIET
)
string(REPLACE "\n" ";" CURRENT_BRANCH "${CURRENT_BRANCH}")
list(LENGTH CURRENT_BRANCH CURRENT_BRANCH_LEN)
if(CURRENT_BRANCH_LEN GREATER 1)
  list(GET CURRENT_BRANCH 1 CURRENT_BRANCH)
else()
  execute_process(
    COMMAND git rev-parse --abbrev-ref HEAD
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE CURRENT_BRANCH
    ERROR_QUIET
  )
endif()
string(REGEX REPLACE "origin/" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
string(REGEX REPLACE "[\n\t\r]" "" CURRENT_BRANCH "${CURRENT_BRANCH}")
if("${CURRENT_BRANCH}" STREQUAL "")
  set(CURRENT_BRANCH "unknown")
endif()
message(STATUS "git current branch = ${CURRENT_BRANCH}")

# 获取生成时间
string(TIMESTAMP COMPILE_TIME "%y%m%d")
string (REGEX REPLACE "[\n\t\r]" "" COMPILE_TIME ${COMPILE_TIME})
string(REGEX REPLACE "\"" "" COMPILE_TIME ${COMPILE_TIME})

# 生成版本信息
set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
message(STATUS "version info = ${GIT_VERSION}")

# 去除空格
string(REGEX REPLACE "[ ]+" "" GIT_VERSION "${GIT_VERSION}")

# 保存变量到文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
