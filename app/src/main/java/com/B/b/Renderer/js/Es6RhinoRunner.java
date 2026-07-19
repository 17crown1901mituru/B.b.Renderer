package com.B.b.Renderer.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * ES6+で書かれたJavaScriptソースを、babel.min.js(MIT License)を使ってES5に変換したうえで、
 * Rhinoエンジンで実行するためのブリッジ。
 *
 * WebView(V8)を一切経由せず、Rhino自身の中でBabelを動かして変換するので、
 * 完全にRhino/JVMの中で完結する。ページ側が返すJS(<script>タグの中身)がES6構文を
 * 含んでいた場合に、JsEngine側でこれを通してからevaluateする形で使う想定。
 *
 * 使い方:
 *   Context cx = Context.enter();
 *   try {
 *       Scriptable globalScope = cx.initStandardObjects();
 *       Es6RhinoRunner.init(cx, globalScope, context.getAssets().open("libs/babel.min.js"));
 *       String es5 = Es6RhinoRunner.transpileToES5(cx, "let x = (a, b) => a + b;");
 *   } finally {
 *       Context.exit();
 *   }
 */
public final class Es6RhinoRunner {

    private static Function babelTransformFn;
    private static Scriptable babelScope;
    private static boolean initialized = false;

    private Es6RhinoRunner() {
    }

    /**
     * babel.min.jsを一度だけロードする。JsEngine初期化時に1回呼べば良い
     * (数MBあるので毎回ロードするとパフォーマンスに響く)。
     *
     * @param cx             有効なRhino Context
     * @param parentScope    親スコープ(通常はcx.initStandardObjects()で作ったグローバルスコープ)
     * @param babelJsStream  babel.min.jsの中身を読むストリーム(Android assetsからのopen等)
     */
    public static synchronized void init(Context cx, Scriptable parentScope, InputStream babelJsStream) throws IOException {
        if (initialized) {
            return;
        }

        // Babel専用の隔離されたスコープを作る(実行対象スクリプトのスコープとは分離する)
        babelScope = cx.newObject(parentScope);
        babelScope.setPrototype(parentScope);
        babelScope.setParentScope(null);

        // babel.min.jsはUMDラッパー形式で、globalThisの有無で挙動が分岐する。
        // Rhinoの標準スコープにはglobalThisが無いため明示的に用意し、
        // babelScope自身をglobalThisとして使わせることで確実にBabelプロパティの置き場所を掌握する。
        cx.evaluateString(babelScope,
                "var console = {" +
                "  log: function() {}, warn: function() {}, error: function() {}, info: function() {}" +
                "};" +
                "var globalThis = this;",
                "console-shim", 1, null);

        try (Reader reader = new InputStreamReader(babelJsStream, StandardCharsets.UTF_8)) {
            cx.evaluateReader(babelScope, reader, "babel.min.js", 1, null);
        }

        Object babelObj = babelScope.get("Babel", babelScope);
        if (!(babelObj instanceof Scriptable)) {
            throw new IllegalStateException(
                    "babel.min.js を評価しても Babel グローバルオブジェクトが見つかりませんでした。" +
                    "バンドルされている babel.min.js のバージョンが変わっている可能性があります。");
        }

        Object transformFnObj = ((Scriptable) babelObj).get("transform", (Scriptable) babelObj);
        if (!(transformFnObj instanceof Function)) {
            throw new IllegalStateException("Babel.transform 関数が見つかりませんでした。");
        }

        babelTransformFn = (Function) transformFnObj;
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * ES6+ソースコードをES5に変換する(実行はしない)。
     *
     * @param cx        有効なRhino Context
     * @param es6Source ES6+で書かれたソース(let/const/アロー関数等を含んでよい)
     * @return ES5に変換されたソースコード文字列
     */
    public static String transpileToES5(Context cx, String es6Source) {
        requireInitialized();

        Scriptable options = cx.newObject(babelScope);
        Scriptable presetsArray = cx.newArray(babelScope, new Object[]{"env"});
        ScriptableObject.putProperty(options, "presets", presetsArray);

        Object result = babelTransformFn.call(cx, babelScope, babelScope,
                new Object[]{es6Source, options});

        if (!(result instanceof Scriptable)) {
            throw new RuntimeException("Babel.transform の戻り値がオブジェクトではありません: " + result);
        }

        Object code = ((Scriptable) result).get("code", (Scriptable) result);
        if (code == Scriptable.NOT_FOUND) {
            throw new RuntimeException("Babel.transform の戻り値に code プロパティがありません。" +
                    "変換元のコードに構文エラーがある可能性があります。");
        }

        return Context.toString(code);
    }

    private static void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "Es6RhinoRunner.init() が呼ばれていません。先にinit()でbabel.min.jsをロードしてください。");
        }
    }
}
