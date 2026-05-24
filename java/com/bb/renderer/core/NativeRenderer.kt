package com.bb.renderer.core

class NativeRenderer {
    init {
        System.loadLibrary("native-lib")
    }

    // 接続テスト用
    external fun testConnection()
    
    // GPU命令用（後続の実装で利用）
    external fun applyHtxmPatch(patchId: Int, data: FloatArray)
}
