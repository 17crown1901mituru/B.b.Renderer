#!/usr/bin/env bash

# ==============================================================================
# Termux 開発環境構築 & SSH・GitHub CLI自動連携スクリプト (Intent起動版)
# ==============================================================================

# エラー発生時にスクリプトを終了する
set -e

# 常に自分自身が置かれているディレクトリを基準に動くようにする
cd "$(dirname "$(readlink -f "$0")")"

echo "[*] Termux 開発環境の構築・チェックを開始します..."

# ------------------------------------------------------------------------------
# 1. ストレージ権限の要求
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

# ミラーの自動チェックと更新を一括実行
pkg --check-mirror update -y -o Dpkg::Options::="--force-confold"
pkg upgrade -y -o Dpkg::Options::="--force-confold"

echo "[✓] すべてのシステムパッケージが最新の状態になりました。"

# ------------------------------------------------------------------------------
# 3. 主要開発ツールの一括導入・更新
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
    cmake \
    ninja \
    pkg-config \
    libffi \
    openssl \
    zlib \
    curl \
    wget \
    jq \
    gh \
    termux-tools \
    termux-api \
    bash \
    tmux \
    rsync \
    vim \
    ripgrep \
    zip \
    unzip \
    tar


# ------------------------------------------------------------------------------
# 4. 引数のチェックと取得
# ------------------------------------------------------------------------------
GIT_USER="$1"
GIT_EMAIL="$2"

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
git config --global user.name "$GIT_USER"
git config --global user.email "$GIT_EMAIL"
echo "[✓] Git Config 設定完了"

# ------------------------------------------------------------------------------
# 6. SSH鍵ペアの自動生成とクリップボード格納
# ------------------------------------------------------------------------------
mkdir -p ~/.ssh
chmod 700 ~/.ssh

SSH_KEY="$HOME/.ssh/id_ed25519"
NEED_SSH_REGISTRATION=false

if [ ! -f "$SSH_KEY" ]; then
    echo "[*] SSH鍵ペア（Ed25519）を新規自動生成しています..."
    ssh-keygen -t ed25519 -N "" -f "$SSH_KEY"
    NEED_SSH_REGISTRATION=true
else
    echo "[✓] 既存のSSH鍵を発見しました。"
    if ssh -T git@github.com -o StrictHostKeyChecking=no 2>&1 | grep -q "successfully authenticated"; then
        echo "[✓] GitHubへのSSH接続テストに成功しました。"
        NEED_SSH_REGISTRATION=false
    else
        echo "[!] SSH鍵はありますが、GitHubに未登録か認証が通りません。"
        NEED_SSH_REGISTRATION=true
    fi
fi

ssh-keyscan github.com >> ~/.ssh/known_hosts 2>/dev/null

# ------------------------------------------------------------------------------
# 7. GitHub CLI (`gh`) のログイン状態チェック
# ------------------------------------------------------------------------------
NEED_GH_AUTH=false
if gh auth status >/dev/null 2>&1; then
    echo "[✓] GitHub CLI (gh) は既にログイン済みです。"
else
    echo "[!] GitHub CLI (gh) が未認証です。"
    NEED_GH_AUTH=true
fi

# ------------------------------------------------------------------------------
# 8. 必要時のみIntentでブラウザを起動して認証を一元処理
# ------------------------------------------------------------------------------
if [ "$NEED_SSH_REGISTRATION" = true ] || [ "$NEED_GH_AUTH" = true ]; then
    echo "[*] 認証手続きを開始します。Androidブラウザを起動します..."

    PUB_KEY=$(cat "${SSH_KEY}.pub")
    echo "$PUB_KEY" | termux-clipboard-set
    echo "[*] 公開鍵をクリップボードにコピーしました。"

    if [ "$NEED_SSH_REGISTRATION" = true ] ; then
        echo "[*] SSH鍵登録ページをブラウザで開きます..."
        termux-open-url "https://github.com/settings/ssh/new"
        sleep 2
    fi

    if [ "$NEED_GH_AUTH" = true ]; then
        echo "[*] GitHub CLI 認証を開始します..."
        gh auth login --hostname github.com --git-protocol ssh --web
    fi

    echo ""
    read -p "[*] 認証作業が完了したら、Termux側で [Enter] を押してください。" dummy
else
    echo "[✓] すべての認証は完了しているため、ブラウザ起動をスキップします。"
fi

# ------------------------------------------------------------------------------
# 9. リポジトリの初期設定
# ------------------------------------------------------------------------------
REPO_DIR="/data/data/com.termux/files/home/B.b.Renderer"
mkdir -p "$REPO_DIR"
cd "$REPO_DIR" || exit 1

if [ ! -d ".git" ]; then
    git init
fi

git branch -M main

if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "https://github.com/17crown1901mituru/B.b.Renderer.git"
else
    git remote add origin "https://github.com/17crown1901mituru/B.b.Renderer.git"
fi

# ------------------------------------------------------------------------------
# 10. 最終チェックと終了処理
# ------------------------------------------------------------------------------
echo "[*] リポジトリのSSH切り替え中..."
CURRENT_REMOTE=$(git remote get-url origin 2>/dev/null || true)
if [[ "$CURRENT_REMOTE" == https://github.com/* ]]; then
    SSH_REMOTE=$(echo "$CURRENT_REMOTE" | sed -E 's#https://github.com/#git@github.com:#; s#$#.git#; s#\.git\.git$#.git#')
    git remote set-url origin "$SSH_REMOTE"
fi

COMMIT_SCRIPT="/storage/emulated/0/Download/B.b.Renderer/app/src/main/assets/commit.sh"
if [ -f "$COMMIT_SCRIPT" ]; then
    bash "$COMMIT_SCRIPT"
else
    echo "[!] $COMMIT_SCRIPT が見つかりません。"
fi
