cmake_minimum_required(VERSION 3.18.1)
project("bb-renderer")

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)
find_library(gles-lib GLESv3)

target_link_libraries(native-lib ${log-lib} ${gles-lib})
