#!/usr/bin/env bash
# AskFlow 前端一键部署脚本
#
# 用法：
#   ./deploy-frontend.sh root@1.2.3.4
#   ./deploy-frontend.sh root@1.2.3.4 /var/www/askflow
#
# 前置条件：
#   1. 已能免密 ssh 到服务器（推荐，否则会多次提示输密码）
#   2. 服务器已装 nginx，且 /etc/nginx/conf.d/askflow.conf 已就位
#   3. 服务器 root 目录 /var/www/askflow 可写
#
# 做的事：本地 build → 打包 → 上传 → 服务器原子替换 → nginx reload

set -euo pipefail

HOST="${1:?用法: ./deploy-frontend.sh root@服务器IP [远程目录]}"
REMOTE_DIR="${2:-/var/www/askflow}"
TARBALL="askflow-dist.tar.gz"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$(cd "$SCRIPT_DIR/../frontend" && pwd)"

echo "==> 前端目录: $FRONTEND_DIR"
echo "==> 目标服务器: $HOST:$REMOTE_DIR"

# ---------- 1. 构建 ----------
cd "$FRONTEND_DIR"

if [ ! -d node_modules ]; then
  echo "==> 安装依赖"
  npm install --no-audit --no-fund
fi

echo "==> 构建"
npm run build

if [ ! -f dist/index.html ]; then
  echo "!! 构建产物缺失，dist/index.html 不存在" >&2
  exit 1
fi

# 构建期注入检查：必须是相对路径，否则线上会跨域
if grep -rq "VITE_API_BASE_URL" .env; then
  echo "==> 当前 API 基址: $(grep VITE_API_BASE_URL .env)"
fi

# ---------- 2. 打包 ----------
echo "==> 打包"
rm -f "$TARBALL"
tar czf "$TARBALL" -C dist .
echo "    体积: $(du -h "$TARBALL" | cut -f1)"

# ---------- 3. 上传 ----------
echo "==> 上传"
scp "$TARBALL" "$HOST:/tmp/$TARBALL"

# ---------- 4. 远端原子替换 ----------
echo "==> 服务器端解压替换"
ssh "$HOST" REMOTE_DIR="$REMOTE_DIR" TARBALL="$TARBALL" 'bash -s' <<'REMOTE_EOF'
set -euo pipefail
NEW="${REMOTE_DIR}.new"
OLD="${REMOTE_DIR}.old"

rm -rf "$NEW"
mkdir -p "$NEW"
tar xzf "/tmp/${TARBALL}" -C "$NEW"

# 原子切换：先挪旧的，再把新的换上去，最后清理
rm -rf "$OLD"
if [ -d "$REMOTE_DIR" ]; then
  mv "$REMOTE_DIR" "$OLD"
fi
mv "$NEW" "$REMOTE_DIR"
rm -rf "$OLD"
rm -f "/tmp/${TARBALL}"

echo "    文件数: $(find "$REMOTE_DIR" -type f | wc -l)"
REMOTE_EOF

# ---------- 5. reload ----------
echo "==> 校验并 reload nginx"
ssh "$HOST" 'nginx -t && systemctl reload nginx'

rm -f "$TARBALL"

echo ""
echo "✅ 部署完成 → http://${HOST#*@}/"
echo "   自测：curl -i http://${HOST#*@}/api/askflow/actuator/health"
