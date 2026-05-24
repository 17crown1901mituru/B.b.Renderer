cmake_minimum_required(VERSION 3.22.1)

project("bb-renderer")

# ネイティブライブラリの作成
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# ログ出力用のライブラリをリンク
find_library(log-lib log)

target_link_libraries(
    native-lib
    ${log-lib}
)
