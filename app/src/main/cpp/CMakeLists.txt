cmake_minimum_required(VERSION 3.22.1)
project("bb-renderer")

# CMakeが実行される場所を起点とした明示的なソースパス指定
add_library(
    native-lib
    SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp
)

find_library(log-lib log)

target_link_libraries(
    native-lib
    ${log-lib}
)
