#!/usr/bin/env python3
import os
import subprocess
import sys
import time
import threading

# watchdog が未インストールの場合は自動で pip install を実行
try:
    from watchdog.events import FileSystemEventHandler
    from watchdog.observers.polling import PollingObserver
except ImportError:
    print("[*] watchdog ライブラリが見つかりません。自動インストールを開始します...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "watchdog"])
    from watchdog.events import FileSystemEventHandler
    from watchdog.observers.polling import PollingObserver


# 監視対象（マスターソース）と実行スクリプトのパス
WATCH_DIR = "/storage/emulated/0/Download/B.b.Renderer"
SCRIPT_PATH = "/storage/emulated/0/Download/B.b.Renderer/app/src/main/assets/commit.sh"

# 変更検知後、実行までの待機時間（秒）
IDLE_SECONDS = 60

def set_wake_lock(enable=True):
    """Termuxのウェイクロックを制御してCPUのスリープを防ぐ"""
    cmd = "termux-wake-lock" if enable else "termux-wake-unlock"
    try:
        subprocess.run([cmd], check=True)
        status = "取得" if enable else "解除"
        print(f"[{time.strftime('%H:%M:%S')}] 🔒 Termux ウェイクロックを{status}しました。")
    except Exception as e:
        print(f"[{time.strftime('%H:%M:%S')}] ⚠️ ウェイクロックの操作に失敗しました (Termux:APIが必要な場合があります): {e}")

class DebouncedHandler(FileSystemEventHandler):
    def __init__(self):
        super().__init__()
        self.last_modified_time = 0
        self.timer_running = False

    def on_any_event(self, event):
        if event.is_directory or ".git" in event.src_path or event.src_path.endswith(".py"):
            return

        self.last_modified_time = time.time()
        print(f"[{time.strftime('%H:%M:%S')}] 変更検知: {event.src_path}")

        if not self.timer_running:
            self.timer_running = True
            threading.Thread(target=self._wait_and_run, daemon=True).start()

    def _wait_and_run(self):
        print(f"[{time.strftime('%H:%M:%S')}] タイマー開始: {IDLE_SECONDS} 秒後にコミット処理を実行します...")
        
        while True:
            time.sleep(1)
            elapsed = time.time() - self.last_modified_time
            
            if elapsed >= IDLE_SECONDS:
                print(f"[{time.strftime('%H:%M:%S')}] {IDLE_SECONDS} 秒間動きがなかったため、commit.sh を実行します。")
                try:
                    subprocess.run(["bash", SCRIPT_PATH], check=True)
                    print(f"[{time.strftime('%H:%M:%S')}] ✅ commit.sh の実行が正常完了しました。")
                except subprocess.CalledProcessError as e:
                    print(f"[{time.strftime('%H:%M:%S')}] ❌ commit.sh の実行エラー (exit code: {e.returncode})")
                except Exception as e:
                    print(f"[{time.strftime('%H:%M:%S')}] ❌ 予期せぬエラー: {e}")
                
                self.timer_running = False
                break

if __name__ == "__main__":
    if not os.path.exists(WATCH_DIR):
        print(f"[エラー] 監視ディレクトリが存在しません: {WATCH_DIR}")
        exit(1)

    # 起動時に CPU スリープを防止
    set_wake_lock(True)

    print("==================================================")
    print(f" 監視起動: {WATCH_DIR}")
    print(f" 実行対象: {SCRIPT_PATH}")
    print("==================================================")

    observer = PollingObserver(timeout=2)
    observer.schedule(DebouncedHandler(), path=WATCH_DIR, recursive=True)
    observer.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n監視を終了します。")
        observer.stop()
        # 終了時にロックを解除
        set_wake_lock(False)
    observer.join()
