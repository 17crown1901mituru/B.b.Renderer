#!/bin/bash

# 常に自分自身が置かれているディレクトリを基準に動くようにする
cd "$(dirname "$(readlink -f "$0")")"

SOURCE_DIR="/storage/emulated/0/Download/B.b.Renderer"
REPO_DIR="/data/data/com.termux/files/home/B.b.Renderer"
mkdir -p "$REPO_DIR"
# 権限エラー防止のため、同期先ディレクトリに確実な書き込み権限を付与
chmod -R 755 "$REPO_DIR"

cd "$REPO_DIR" || exit 1
# ビルド成果物(APK)の保存先。SOURCE_DIR(git syncの元になる作業ディレクトリ)とは
# 別にしておき、ダウンロードしたAPKで作業ツリーが汚れないようにする。
APK_DOWNLOAD_DIR="/storage/emulated/0/Download/GitHub_Store"

# 不正な空白を除去
find "$SOURCE_DIR" -type f ! -path '*/.git/*' ! -path '*/assets/*' \
  \( -name "*.kt" -o -name "*.java" -o -name "*.gradle" -o -name "*.xml" \
  -o -name "*.txt" -o -name "*.json" -o -name "*.sh" -o -name "*.md" \
  -o -name "*.properties" -o -name "*.pro" \) \
  -exec sed -i 's/\xc2\xa0/ /g' {} \;

cd "$REPO_DIR" || exit 1

echo "Syncing..."
# 2026-08対応: rsync(3.5.0)がPermission Deniedで詰まり続ける問題が解決しなかったため、
# rsyncに依存しない同期方式に置き換える。所有者・パーミッション・SELinuxコンテキストを
# 確認しても異常が見当たらず、rsync自体の内部プロセス(sender/receiverに分かれて
# 動作する)とTermux/Android環境の相性問題を疑わざるを得なかったため。
# ".git"だけ残して中身を全部消し、SOURCE_DIRの中身をまるごとコピーし直すことで、
# rsync --delete --exclude='.git' と同じ「SOURCE_DIRの状態にREPO_DIRを一致させる」
# 結果を、より単純なrm/cpの組み合わせで実現する。
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp -r "$SOURCE_DIR/." .
SYNC_STATUS=$?
if [ $SYNC_STATUS -ne 0 ]; then
    echo "❌ Sync failed (exit code $SYNC_STATUS). REPO_DIR may now be incomplete —"
    echo "   check its contents before re-running."
    exit 1
fi

git add -A

# 未コミットの変更があるかどうかを確認する
if [ -n "$(git status --porcelain)" ]; then
    # プッシュ直前の時刻をUTCで取得
    START_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "Changes detected (or uncommitted changes exist). Committing..."
    git commit -m "Sync: $(date '+%Y-%m-%d %H:%M:%S')" > /dev/null
    
    echo "Pushing to GitHub..."
    git push origin main --force || { echo "❌ Push failed!"; exit 1; }
    
    echo "Waiting for Actions to start..."
    RUN_ID=""
    for i in {1..20}; do
        RUN_ID=$(gh run list --created ">$START_TIME" --limit 1 --json databaseId -q '.[0].databaseId')
        [ -n "$RUN_ID" ] && break
        echo -n "."
        sleep 3
    done
    echo ""
    
    if [ -z "$RUN_ID" ]; then
        echo "❌ No new run detected."; exit 1; fi
    
    echo "Targeting Run ID: $RUN_ID"
    
    # --- 進捗率（%）とステップの表示 ---
    while true; do
        RUN_DATA=$(gh run view "$RUN_ID" --json status,conclusion,number,jobs)
        STATUS=$(echo "$RUN_DATA" | jq -r '.status')
        CONCLUSION=$(echo "$RUN_DATA" | jq -r '.conclusion')
        RUN_NUMBER=$(echo "$RUN_DATA" | jq -r '.number')
        
        TOTAL_STEPS=$(echo "$RUN_DATA" | jq '[.jobs[].steps[]] | length')
        DONE_STEPS=$(echo "$RUN_DATA" | jq '[.jobs[].steps[] | select(.status=="completed")] | length')
        
        if [ "$TOTAL_STEPS" -gt 0 ]; then
            PERCENT=$(( DONE_STEPS * 100 / TOTAL_STEPS ))
        else
            PERCENT=0
        fi
        
        CURRENT=$(echo "$RUN_DATA" | jq -r '.jobs[0].steps[] | select(.status=="in_progress") | .name' | tail -n 1)
        
        clear -x
        echo "--- Build Progress: $PERCENT% ---"
        echo "Status: $STATUS"
        [ -n "$CURRENT" ] && echo "Active: $CURRENT"
        echo "Steps: $DONE_STEPS / $TOTAL_STEPS"
        
        [ "$STATUS" = "completed" ] && [ "$CONCLUSION" != "null" ] && break
        sleep 8
    done
    
    if [ "$CONCLUSION" = "success" ]; then
        echo -e "\n✅ Build Success!"
        
        echo "Downloading APK artifact..."
        mkdir -p "$APK_DOWNLOAD_DIR"
        gh run download "$RUN_ID" --dir "$APK_DOWNLOAD_DIR"
        DOWNLOAD_STATUS=$?

        APK_PATH=""
        if [ $DOWNLOAD_STATUS -eq 0 ]; then
            APK_PATH=$(find "$APK_DOWNLOAD_DIR" -name "*.apk" -newermt "$START_TIME" -printf '%T@ %p\n' 2>/dev/null \
                | sort -n | tail -n 1 | cut -d' ' -f2-)
        fi

        if [ -n "$APK_PATH" ]; then
            echo "📦 APK successfully downloaded to: $APK_PATH"
        else
            echo "⚠️ Failed to auto-download artifact via CLI."
            echo "Opening Artifacts page in browser..."
            RUN_URL=$(gh run view "$RUN_ID" --json url -q '.url')

            if [ -n "$RUN_URL" ]; then
                termux-open "$RUN_URL"
            else
                echo "⚠️ Could not retrieve Run URL."
            fi
        fi
        
    else
        echo -e "\n❌ Build Failed! [Fix targets below]\n"
        sleep 2
        gh run view "$RUN_ID" --log-failed | grep "e: file" | sed 's|/.*/app/|app/|'
    fi
else
    echo "No changes or uncommitted work detected. Nothing to do."
    exit 0
fi

# --- watcher.py の二重起動防止 & 常駐化処理 ---
WATCHER_SCRIPT="$REPO_DIR/app/src/main/assets/watcher.py"

if [ -f "$WATCHER_SCRIPT" ]; then
    if ! pgrep -f "python3.*watcher.py" > /dev/null; then
        echo "Watcher process is not running. Starting watcher.py..."
        nohup python3 "$WATCHER_SCRIPT" > ~/watcher.log 2>&1 &
    fi
fi


