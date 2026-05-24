cmake_minimum_required(VERSION 3.22.1)

project("bb-renderer")

# ネイティブライブラリの作成
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# ライブラリのリンク（ログ出力とOpenGL ES）
find_library(log-lib log)
find_library(gles-lib GLESv3)

target_link_libraries(
    native-lib
    ${log-lib}
    ${gles-lib}
)
