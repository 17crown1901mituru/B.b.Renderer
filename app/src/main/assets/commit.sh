#!/bin/bash

mkdir -p /data/data/com.termux/files/home/B.b.Renderer

# 常に自分自身が置かれているディレクトリを基準に動くようにする
cd "$(dirname "$(readlink -f "$0")")"

#!/bin/bash

SOURCE_DIR="/storage/emulated/0/Download/B.b.Renderer"
REPO_DIR="/data/data/com.termux/files/home/B.b.Renderer"

# 不正な空白を除去
find "$SOURCE_DIR" -type f ! -path '*/.git/*' ! -path '*/assets/*' \
  \( -name "*.kt" -o -name "*.java" -o -name "*.gradle" -o -name "*.xml" \
  -o -name "*.txt" -o -name "*.json" -o -name "*.sh" -o -name "*.md" \
  -o -name "*.properties" -o -name "*.pro" \) \
  -exec sed -i 's/\xc2\xa0/ /g' {} \;

cd "$REPO_DIR" || exit 1

echo "Syncing..."
rsync -a --delete --exclude='.git' "$SOURCE_DIR/" ./

git add -A

if [ -n "$(git status --porcelain)" ]; then
    # プッシュ直前の時刻をUTCで取得
    START_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    echo "Changes detected. Committing..."
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
        # 【統合ポイント1】number（ビルド番号）も一緒に取得するように json オプションに追加
        RUN_DATA=$(gh run view "$RUN_ID" --json status,conclusion,number,jobs)
        STATUS=$(echo "$RUN_DATA" | jq -r '.status')
        CONCLUSION=$(echo "$RUN_DATA" | jq -r '.conclusion')
        RUN_NUMBER=$(echo "$RUN_DATA" | jq -r '.number') # 変数に代入
        
        # 全ステップ数と完了済みのステップ数をカウント
        TOTAL_STEPS=$(echo "$RUN_DATA" | jq '[.jobs[].steps[]] | length')
        DONE_STEPS=$(echo "$RUN_DATA" | jq '[.jobs[].steps[] | select(.status=="completed")] | length')
        
        # 進捗率を計算
        if [ "$TOTAL_STEPS" -gt 0 ]; then
            PERCENT=$(( DONE_STEPS * 100 / TOTAL_STEPS ))
        else
            PERCENT=0
        fi
        
        # 現在実行中のステップ名
        CURRENT=$(echo "$RUN_DATA" | jq -r '.jobs[0].steps[] | select(.status=="in_progress") | .name' | tail -n 1)
        
        # 表示
        clear -x
        echo "--- Build Progress: $PERCENT% ---"
        echo "Status: $STATUS"
        [ -n "$CURRENT" ] && echo "Active: $CURRENT"
        echo "Steps: $DONE_STEPS / $TOTAL_STEPS"
        
        [ "$STATUS" = "completed" ] && [ "$CONCLUSION" != "null" ] && break
        sleep 8
    done
    
    # 最終判定
    if [ "$CONCLUSION" = "success" ]; then
        echo -e "\n✅ Build Success!"
        
        # --- 【統合ポイント2】成功したビルド番号のアーティファクトを自動ダウンロード ---
        echo "Downloading APK artifact..."
        # 先ほど設定した Zip名「bb-renderer-debug-#番号」を指定してダウンロード
        gh run download "$RUN_ID" --name "bb-renderer-debug-#$RUN_NUMBER" --dir "$SOURCE_DIR"
        
        if [ $? -eq 0 ]; then
            echo "📦 APK successfully downloaded to: $SOURCE_DIR/app-debug-#$RUN_NUMBER.apk"
        else
            echo "⚠️ Failed to auto-download artifact via CLI."
        fi
        # ------------------------------------------------------------------
        
        # --- アーティファクトページ（RunのWebビュー）をブラウザで開く ---
        echo "Opening Artifacts page in browser..."
        RUN_URL=$(gh run view "$RUN_ID" --json url -q '.url')
        
        if [ -n "$RUN_URL" ]; then
            termux-open "$RUN_URL"
        else
            echo "⚠️ Could not retrieve Run URL."
        fi
        # --------------------------------------------------
        
    else
        echo -e "\n❌ Build Failed! [Fix targets below]\n"
        sleep 2
        gh run view "$RUN_ID" --log-failed | grep "e: file" | sed 's|/.*/app/|app/|'
    fi
else
    echo "No changes detected. Nothing to do."
fi

