#!/usr/bin/env bash

# ==============================================================================
# Termux 開発環境構築 & SSH・GitHub CLI自動連携スクリプト (スキップ・完全更新版)
# ==============================================================================

# エラー発生時にスクリプトを終了する
set -e

# 常に自分自身が置かれているディレクトリを基準に動くようにする
cd "$(dirname "$(readlink -f "$0")")"



echo "[*] Termux 開発環境の構築・チェックを開始します..."

# ------------------------------------------------------------------------------
# 1. ストレージ権限の要求（非同期バックグラウンド実行で処理を止めない）
# ------------------------------------------------------------------------------
if [ ! -d "$STORAGE" ] && [ ! -d "$HOME/storage" ]; then
    echo "[*] Androidストレージへのアクセス権限を要求しています..."
    termux-setup-storage &
else
    echo "[✓] ストレージ権限は既に設定されています。スキップします。"
fi

# ------------------------------------------------------------------------------
# 2. リポジトリの最適化とシステムアップデート・完全アップグレード
# ------------------------------------------------------------------------------
echo "[*] パッケージカタログの同期、および既存ツールの完全アップグレードを開始します..."
echo "[*] 進捗ログが流れます。すべてのパッケージを最新化するため少々お待ちください。"

# カタログ更新（確認プロンプトを自動突破）
pkg update -y -o Dpkg::Options::="--force-confold"

# 既存パッケージの完全アップグレード（設定ファイルの競合時は既存設定を維持して自動突破）
pkg upgrade -y -o Dpkg::Options::="--force-confold"

echo "[✓] すべてのシステムパッケージが最新の状態になりました。"

# ------------------------------------------------------------------------------
# 3. X11リポジトリの有効化
# ------------------------------------------------------------------------------
echo "[*] X11リポジトリの状態をチェック中..."
pkg install -y x11-repo

# ------------------------------------------------------------------------------
# 4. 主要開発ツールの一括導入・更新
# ------------------------------------------------------------------------------
echo "[*] 必要な主要パッケージのインストール・更新を確認中..."
pkg install -y \
    git \
    openssh \
    android-tools \
    python \
    nodejs \
    openjdk-17 \
    clang \
    make \
    curl \
    jq \
    gh \
    termux-x11 \
    xclip \
    chromium \
    termux-api \
    bash \
    tmux \
    rsync \
    chromium \

# ------------------------------------------------------------------------------
# 0. 引数のチェックと取得
# ------------------------------------------------------------------------------
GIT_USER="$1"
GIT_EMAIL="$2"

# 引数が足りない場合は、現在の設定を確認するか入力を促す
if [ -z "$GIT_USER" ] || [ -z "$GIT_EMAIL" ]; then
    echo "[*] 引数が指定されていないため、現在のGit設定を確認します..."
    CURRENT_USER=$(git config --global user.name || true)
    CURRENT_EMAIL=$(git config --global user.email || true)

    if [ -n "$CURRENT_USER" ] && [ -n "$CURRENT_EMAIL" ]; then
        echo "--> 既にGit設定が存在するため、それを使用します: $CURRENT_USER ($CURRENT_EMAIL)"
        GIT_USER="$CURRENT_USER"
        GIT_EMAIL="$CURRENT_EMAIL"
    else
        echo "【設定エラー】Gitの識別情報が必要です。"
        echo "使用例: bash setup.sh \"あなたのユーザー名\" \"あなたのメールアドレス\""
        echo "------------------------------------------------------------------"
        read -p "Git ユーザー名を入力してください: " GIT_USER
        read -p "Git メールアドレスを入力してください: " GIT_EMAIL
        if [ -z "$GIT_USER" ] || [ -z "$GIT_EMAIL" ]; then
            echo "❌ 入力が空のため、処理を中断します。"
            exit 1
        fi
    fi
fi
# ------------------------------------------------------------------------------
# 5. Git Config（識別情報）の自動設定
# ------------------------------------------------------------------------------
echo "[*] Git グローバル設定を反映中..."
git config --global user.name "$GIT_USER"
git config --global user.email "$GIT_EMAIL"
echo "[✓] Git Config 設定完了: $(git config --global user.name) / $(git config --global user.email)"

# ------------------------------------------------------------------------------
# 6. SSH鍵ペアの自動生成とクリップボード格納（既存の鍵は絶対リセットしない）
# ------------------------------------------------------------------------------
mkdir -p ~/.ssh
chmod 700 ~/.ssh

SSH_KEY="$HOME/.ssh/id_ed25519"
NEED_SSH_REGISTRATION=false

if [ ! -f "$SSH_KEY" ]; then
    echo "[*] SSH鍵ペア（Ed25519）を新規自動生成しています（パスフレーズなし）..."
    ssh-keygen -t ed25519 -N "" -f "$SSH_KEY"
    NEED_SSH_REGISTRATION=true
else
    echo "[✓] 既存のSSH鍵を発見しました。再生成せず維持します。"
    if ssh -T git@github.com -o StrictHostKeyChecking=no 2>&1 | grep -q "successfully authenticated"; then
        echo "[✓] GitHubへのSSH接続テストに成功しました。SSHのWeb登録をスキップします。"
        NEED_SSH_REGISTRATION=false
    else
        echo "[!] SSH鍵はありますが、GitHubに未登録か認証が通りません。再登録プロセスを有効にします。"
        NEED_SSH_REGISTRATION=true
    fi
fi

ssh-keyscan github.com >> ~/.ssh/known_hosts 2>/dev/null

# ------------------------------------------------------------------------------
# 7. GitHub CLI (`gh`) のログイン状態チェック
# ------------------------------------------------------------------------------
NEED_GH_AUTH=false
if gh auth status >/dev/null 2>&1; then
    echo "[✓] GitHub CLI (gh) は既にログイン済みです。認証をスキップします。"
else
    echo "[!] GitHub CLI (gh) が未認証です。認証プロセスを有効にします。"
    NEED_GH_AUTH=true
fi

# ------------------------------------------------------------------------------
# 8. 必要時のみブラウザを起動して認証を一元処理
# ------------------------------------------------------------------------------
if [ "$NEED_SSH_REGISTRATION" = true ] || [ "$NEED_GH_AUTH" = true ]; then
    echo "[*] 認証手続きを開始します。X11環境下でのブラウザ自動起動を準備中..."


    termux-x11 :1 -xstartup "xfce4-session"
    PUB_KEY=$(cat "${SSH_KEY}.pub")
    echo "$PUB_KEY" | termux-clipboard-set 2>/dev/null || true
    export DISPLAY=:1
    echo "$PUB_KEY" | xclip -selection clipboard 2>/dev/null || true

    if [ "$NEED_SSH_REGISTRATION" = true ] ; then
        echo "=================================================================="
        echo "【重要：SSH鍵の登録】"
        echo "公開鍵をクリップボードにコピーしました。"
        echo "ブラウザが開いたら『New SSH Key』のKey欄にそのまま貼り付けてください。"
        echo "=================================================================="
        chromium --no-sandbox "https://github.com/settings/ssh/new" &
        sleep 2
    fi

    if [ "$NEED_GH_AUTH" = true ]; then
        echo "=================================================================="
        echo "【重要：GitHub CLI (gh) の認証】"
        echo "これより別プロセスで 'gh auth login' を起動します。"
        echo "画面に表示される 8 桁のワンタイムコードを、ブラウザに入力してください。"
        echo "=================================================================="
        gh auth login --hostname github.com --git-protocol ssh --web
    fi

    echo ""
    read -p "[*] 全ての登録・認証作業が完了したら、Termux側で [Enter] を押してください。" dummy
else
    echo "[✓] SSHおよびGitHub CLI of 認証はすべて完了しているため、ブラウザ起動をスキップします。"
fi


# 1. ディレクトリの作成
REPO_DIR="/data/data/com.termux/files/home/B.b.Renderer"
mkdir -p "$REPO_DIR"
cd "$REPO_DIR" || exit 1

# 2. Gitリポジトリの初期化
git init

# 3. メインブランチ名を 'main' に設定
git branch -M main

# 4. GitHubのリモートリポジトリを登録（提供されたURLを設定）
git remote add origin "https://github.com/17crown1901mituru/B.b.Renderer.git"

echo "✅ リポジトリディレクトリの作成と初期設定が完了しました！"
echo "現在のディレクトリ: $(pwd)"
git remote -v

# ------------------------------------------------------------------------------
# 9. 最終確認（失敗を握りつぶさず、はっきり表示する）
# ------------------------------------------------------------------------------
echo "[*] 最終接続テストを実行中..."

SSH_OK=false
if ssh -T git@github.com -o StrictHostKeyChecking=no 2>&1 | grep -q "successfully authenticated"; then
    echo "[✓] SSH接続テスト: 成功"
    SSH_OK=true
else
    echo "[✗] SSH接続テストに失敗しました。GitHubの Settings > SSH and GPG keys に公開鍵が登録されているか確認してください。"
fi

GH_OK=false
if gh auth status >/dev/null 2>&1; then
    echo "[✓] gh CLI 認証: 成功"
    GH_OK=true
else
    echo "[✗] gh CLI が未認証です。'gh auth login --hostname github.com --git-protocol ssh --web' を再実行してください。"
fi

# ------------------------------------------------------------------------------
# 9.5. リポジトリのorigin remoteをHTTPS→SSHへ切り替え
# ------------------------------------------------------------------------------
REPO_DIR="/data/data/com.termux/files/home/B.b.Renderer"
if [ -d "$REPO_DIR/.git" ]; then
    CURRENT_REMOTE=$(git -C "$REPO_DIR" remote get-url origin 2>/dev/null || true)
    if [[ "$CURRENT_REMOTE" == https://github.com/* ]]; then
        SSH_REMOTE=$(echo "$CURRENT_REMOTE" | sed -E 's#https://github.com/#git@github.com:#; s#$#.git#; s#\.git\.git$#.git#')
        echo "[*] origin を HTTPS から SSH へ切り替えます: $SSH_REMOTE"
        git -C "$REPO_DIR" remote set-url origin "$SSH_REMOTE"
    elif [[ "$CURRENT_REMOTE" == git@github.com:* ]]; then
        echo "[✓] origin は既にSSH形式です: $CURRENT_REMOTE"
    else
        echo "[!] origin の形式を認識できませんでした: $CURRENT_REMOTE"
    fi
else
    echo "[!] $REPO_DIR が見つからないため、origin remoteの切り替えはスキップします（先にcloneしてください）。"
fi

if [ "$SSH_OK" = false ] || [ "$GH_OK" = false ]; then
    echo ""
    echo "⚠️  SSHまたはgh認証が未完了です。このままcommit.shを実行しても push/Actions連携でエラーになります。"
    read -p "それでも続行しますか？ (y/N): " CONTINUE_ANYWAY
    if [ "$CONTINUE_ANYWAY" != "y" ] && [ "$CONTINUE_ANYWAY" != "Y" ]; then
        echo "中断しました。認証を済ませてから再実行してください。"
        exit 1
    fi
fi

echo "[*] すべての開発環境構築、スキップチェック、および設定が完了しました！"

COMMIT_SCRIPT="/storage/emulated/0/Download/B.b.Renderer/app/src/main/assets/commit.sh"
if [ -f "$COMMIT_SCRIPT" ]; then
    echo "[*] リポジトリ同期スクリプトを起動します..."
    bash "$COMMIT_SCRIPT"
else
    echo "[!] $COMMIT_SCRIPT が見つかりません(現在地: $(pwd))。"
    echo "    setup.sh と commit.sh が同じフォルダに置かれているかご確認ください。"
fi

