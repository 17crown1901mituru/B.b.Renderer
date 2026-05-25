cmake_minimum_required(VERSION 3.22.1)
project("bb-renderer")

# パスを絶対的に指定して確実に読み込ませる
set(native_cpp_path "${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp")

add_library(
    native-lib
    SHARED
    ${native_cpp_path}
)

find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})
