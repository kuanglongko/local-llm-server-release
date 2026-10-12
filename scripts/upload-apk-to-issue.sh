#!/bin/sh
# 把编好的 APK 挂到 Issue 上，换一个能点的下载地址。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要
# ═══════════════════════════════════════════════════════════════════════════
# 构建阶段只是把 APK `cp` 到容器的 `/workspace/dist/`，而容器在任务结束就
# 销毁 —— 日志里写着"已存档"，磁盘上什么也不剩，用户看到的只有一句
# "编出来了"却拿不到文件。这里补最后一公里：上传 → 把 `asset_link` 写进评论。
#
# ⚠ 这段接线在 `4f20160`（"stage 脚本挪出 .cnb.yml"）时**丢过一次**：
#   那一次挪动是照着**更早**的一份正文重新落盘的，而"上传到 Issue"这几行
#   是在它之后加的，于是整段连同这个脚本一起从树里消失了 —— 而构建照旧
#   全绿（少一步不报错）。`tools/run_prune_assets_guard.sh` 的同族判据就
#   是照着这种"挪个文件把一段接线挪没了"写的。
#
# ═══════════════════════════════════════════════════════════════════════════
# 与「到期清扫」的接口
# ═══════════════════════════════════════════════════════════════════════════
# 走的是 `POST /{repo}/-/issues/{n}/comment-file-asset-upload-url` ——
# 也就是 **Issue 评论附件**，不是 release/commit 附件。这条路**没有 ttl**
#（表单只有 name / size / content_type），所以"设 3 天"这件事落在
# `scripts/prune-issue-assets.sh` 的每日清扫上，两边是配套的：
# 这里负责放上去，那边负责到点收走。改了这里的上传方式（比如换成 release
# 附件），那边的 `DELETABLE` 放行集与 `list-assets` 过滤就得跟着改 ——
# 否则上传的落不进"可删"那几类，就再也没人清得掉。
#
# 环境变量（CI 里天然就有）：
#   CNB_TOKEN       访问令牌，需 repo-notes:rw
#   CNB_API_HOST    默认 https://api.cnb.cool
#   CNB_REPO_SLUG   形如 group/repo
#   CNB_ISSUE_IID   目标 Issue 编号；缺了就跳过（纯 push 构建没有 Issue 上下文）
set -e

APK=${APK:-/workspace/dist/app-debug.apk}
NAME=${NAME:-app-debug.apk}
ISSUE=${CNB_ISSUE_IID:?需要 CNB_ISSUE_IID}
SLUG=${CNB_REPO_SLUG:?需要 CNB_REPO_SLUG}
API=${CNB_API_HOST:-https://api.cnb.cool}
TOKEN=${CNB_TOKEN:?需要 CNB_TOKEN}

[ -f "$APK" ] || { echo "找不到 APK：$APK" >&2; exit 1; }

SIZE=$(wc -c < "$APK" | tr -d ' ')
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
echo "上传 $NAME（$SIZE 字节，sha256=$SHA）到 Issue #$ISSUE"

# ① 申请一条上传链接（顺带拿到 asset 链接与下载地址）
RESP=$(curl -sS -X POST "$API/$SLUG/-/issues/$ISSUE/comment-file-asset-upload-url" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$NAME\",\"size\":$SIZE,\"contentType\":\"application/vnd.android.package-archive\"}")

UPLOAD_URL=$(printf '%s' "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["upload_url"])')
ASSET_LINK=$(printf '%s' "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["asset_link"])')

# ② PUT 实体
code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" "$UPLOAD_URL")
[ "$code" = "200" ] || { echo "上传失败 HTTP $code" >&2; exit 1; }

# ③ 把链接写进评论（附件要挂在评论上才下载得到）
# 取「赋值那一行」，不是任何提到 versionName 的注释行（注释里也写着这个词）
VERSION=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
  app/build.gradle.kts | head -1)
# 寿命那一行写进评论：让下载的人**当场就知道**这个包几天后会被自动收走，
# 而不是几天后回来发现链接 404（那时他只会以为是构建挂了）。
TTL_DAYS=${DAYS:-3}
BODY=$(printf '%s\n\n- 大小 %s 字节\n- sha256 `%s`\n- 版本 %s\n- 提交 `%s`\n- 保留 %s 天（到期由每日定时任务自动回收）\n' \
  "$ASSET_LINK" "$SIZE" "$SHA" "$VERSION" "${CNB_COMMIT:-unknown}" "$TTL_DAYS")

python3 - "$API" "$SLUG" "$ISSUE" "$TOKEN" "$BODY" <<'PY'
import json, sys, urllib.request
api, slug, issue, token, body = sys.argv[1:6]
req = urllib.request.Request(
    f'{api}/{slug}/-/issues/{issue}/comments',
    data=json.dumps({'body': body}).encode(),
    headers={'Authorization': f'Bearer {token}',
             'Accept': 'application/json',
             'Content-Type': 'application/json'},
    method='POST')
try:
    with urllib.request.urlopen(req) as r:
        print('评论已发：', json.loads(r.read()).get('id'))
except urllib.error.HTTPError as e:
    print('发评论失败:', e.code, e.read().decode()[:300], file=sys.stderr)
    sys.exit(1)
PY
