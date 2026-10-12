#!/bin/sh
# 把「用户上传到 Issue/PR 的附件 + CI 贴上去的 APK」按寿命清掉。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么不能"上传时就设 ttl"
# ═══════════════════════════════════════════════════════════════════════════
# 附件插件（cnbcool/attachments）的 `ttl` 只作用于 **release / commit 附件** ——
# 那是它自己的上传通道。而本仓库真正长期占对象存储的两样东西都**不走那条通道**：
#
#   · 用户在 Issue 里传的文件   -> `POST /{repo}/-/issues/{n}/comment-file-asset-upload-url`
#   · CI 贴到 Issue 的 APK      -> 同上（scripts/upload-apk-to-issue.sh）
#
# 那个上传表单**只有 name / size / content_type 三个字段**，没有 ttl
#（见平台 OpenAPI 的 `api.PostIssueAssetUploadURLForm`）。所以在 Issue 附件上
# 设 ttl 这件事**接口层面就不存在**，不是"没接线"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 能做的是"到期清扫"，等效但机制不同
# ═══════════════════════════════════════════════════════════════════════════
# 平台另开了一对接口：
#   · `GET  /{slug}/-/list-assets`      —— 列出仓库里**所有**资源附件的上传记录
#   · `DELETE /{repo}/-/assets/{id}`    —— 按记录 ID 删，且原文写明
#                                          "Release 和 Commit 附件不适用"
#                                          （即：**Issue/PR 附件适用**）
# 本脚本把前者筛成"早于 N 天的 Issue/PR 附件"，再逐个交给后者。
#
# ═══════════════════════════════════════════════════════════════════════════
# 三条边界，每条都"错了不响"
# ═══════════════════════════════════════════════════════════════════════════
#   ① **只删可删的那几类**。记录类型枚举里 `repo_release` / `repo_commit`
#      是**不可**用这个接口删的（平台会回 422）。若不筛类型，一次清扫会让
#      整批 release/commit 附件全 422 —— 而脚本若把 422 当成"这批都没有"
#      就会**静默什么都不删**，看起来像"没什么可清"。所以只放行
#      `slug_file`/`slug_img`/`issue_file`/`issue_img`/`pull_file`/`pull_img`，
#      其余既不发请求也不计入"已清"。
#   ② **不删 0 字节 / 缺时间戳的记录**。`created_at` 缺失或解析不出来时，
#      "它多老"是未知的。把未知当成"很老"去删 = 拿不准就毁；当成"很新"留下
#      才是安全方向。同理，ID 缺失的跳过（发出去就是一个删别的东西的请求）。
#   ③ **先看后删，且默认 dry-run**。真删要显式 `--apply`。这条不是洁癖：
#      本脚本挂的是**每天自动跑**的定时任务，写错一行的代价是"某天早上
#      用户的附件全没了"，而它没有任何撤销。定时任务里传 `--apply`，
#      人在终端里默认只看清单。
#
# 用法：
#   sh scripts/prune-issue-assets.sh                 # 只看：列出会删什么
#   sh scripts/prune-issue-assets.sh --apply         # 真删
#   DAYS=7 sh scripts/prune-issue-assets.sh          # 改寿命（默认 3 天）
#   sh scripts/prune-issue-assets.sh --now 2026-10-11T00:00:00Z   # 钉住"现在"（自测用）
#
# 环境变量（CI 里天然就有）：
#   CNB_TOKEN       访问令牌，需 repo-manage:r（列）与 repo-manage:rw（删）
#   CNB_API_HOST    默认 https://api.cnb.cool
#   CNB_REPO_SLUG   形如 group/repo
set -e

API=${CNB_API_HOST:-https://api.cnb.cool}
SLUG=${CNB_REPO_SLUG:?需要 CNB_REPO_SLUG}
TOKEN=${CNB_TOKEN:?需要 CNB_TOKEN}
DAYS=${DAYS:-3}
APPLY=0
NOW=

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --now) shift; NOW=$1 ;;
    --days) shift; DAYS=$1 ;;
    --api) shift; API=$1 ;;
    --slug) shift; SLUG=$1 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

# 列表接口的分页与"扫多久"上界。为什么要有上界：一个攒了很久的仓库，
# 记录数可能上千 —— 无上界地翻页会让这条**每天跑**的任务在某天悄悄跑成
# 无限循环（平台对深分页的行为没有契约）。上界到了就**说出来**，不静默截断。
PAGE_SIZE=${PAGE_SIZE:-100}
MAX_PAGES=${MAX_PAGES:-50}

python3 - "$API" "$SLUG" "$TOKEN" "$DAYS" "$APPLY" "$NOW" "$PAGE_SIZE" "$MAX_PAGES" <<'PY'
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

api, slug, token, days, apply_, now_raw, page_size, max_pages = sys.argv[1:9]
days = int(days)
page_size = int(page_size)
max_pages = int(max_pages)
apply_ = (apply_ == '1')

# 可删的六类。其余（repo_release / repo_commit / unknown）**不发请求**：
# 平台对前两类明确回 422，对 unknown 更是没有契约 —— 拿不准就不动。
DELETABLE = {'slug_file', 'slug_img', 'issue_file', 'issue_img',
             'pull_file', 'pull_img'}

def get(url):
    req = urllib.request.Request(url, headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/json'})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b'null')

def delete(asset_id):
    req = urllib.request.Request(
        f'{api}/{slug}/-/assets/{asset_id}',
        headers={'Authorization': f'Bearer {token}',
                 'Accept': 'application/json'},
        method='DELETE')
    with urllib.request.urlopen(req) as r:
        return r.status

def parse_time(s):
    """把平台的时间戳解析成 aware datetime；解析不出来返回 None。

    返回 None 的语义是「这个记录多老**未知**」，调用方必须把它留下（见文件头 ②）。
    Go 的 JSON 时间默认是 RFC3339（带 Z 或偏移），但平台也可能给
    "YYYY-MM-DD hh:mm:ss" 这种无时区的形态 —— 无时区按 UTC 解读，
    宁可把它算新一点，也不要在时区上赌一把把它算老。
    """
    if not s or not isinstance(s, str):
        return None
    t = s.strip().replace('Z', '+00:00')
    try:
        dt = datetime.fromisoformat(t)
    except ValueError:
        for fmt in ('%Y-%m-%d %H:%M:%S', '%Y-%m-%dT%H:%M:%S'):
            try:
                dt = datetime.strptime(s.strip(), fmt)
                break
            except ValueError:
                continue
        else:
            return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)

# 「现在」：默认取系统 UTC。`--now` 只是为了让自测能钉住这个值 ——
# 拿真实时钟跑的自测会随运行时刻漂，边界那一天就随缘。
if now_raw:
    now = parse_time(now_raw)
    if now is None:
        print(f'--now 解析不了：{now_raw}', file=sys.stderr)
        sys.exit(2)
else:
    now = datetime.now(timezone.utc)

cutoff = now - timedelta(days=days)
print(f'# 仓库 {slug}')
print(f'# 现在 {now.isoformat()}，寿命 {days} 天，早于 {cutoff.isoformat()} 的附件到期')
print(f'# 模式：{"真删（--apply）" if apply_ else "只看（dry-run，加 --apply 才真删）"}')

# ① 翻页列出全部记录。任何一页失败都**整体失败**（不吞）：
#    清扫任务最坏的形态是"某天开始有一半页拉不到，于是只清了一半，
#    而日志每天照旧写『已清 0 条』" —— 那比不跑更坏。
records = []
page = 1
truncated = False
while True:
    if page > max_pages:
        truncated = True
        break
    try:
        got = get(f'{api}/{slug}/-/list-assets?page={page}&page_size={page_size}')
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf8', 'replace')[:300]
        print(f'# 拉取 list-assets 第 {page} 页失败：HTTP {e.code} {body}', file=sys.stderr)
        if e.code in (401, 403):
            print('# 这个令牌缺 repo-manage:r —— 清扫任务需以**仓库 Owner** 身份触发'
                  '（定时任务的 CNB_TOKEN 以配置修改者身份签发）。', file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        # 网络层失败（DNS / 连接被拒 / TLS）**同样整体失败**。不能让一个连不上的
        # 早晨写成"共 0 条记录 / 已清 0 条" —— 那看起来和"仓库很干净"一模一样。
        print(f'# 拉取 list-assets 第 {page} 页失败：{e}', file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f'# list-assets 第 {page} 页返回的不是 JSON：{e}', file=sys.stderr)
        sys.exit(1)
    if not got:
        break
    records.extend(got)
    if len(got) < page_size:
        break
    page += 1

print(f'# 共 {len(records)} 条记录')
if truncated:
    print(f'# ⚠ 到 {max_pages} 页上界就停了 —— 明细可能不全，'
          f'提高 MAX_PAGES 或先手工清一批', file=sys.stderr)

# ② 筛选。每一类"落选"都说得出理由（谁也不静默消失）。
due, skipped = [], {'类型不可删': 0, '缺时间戳': 0, '还没到期': 0, '缺 ID': 0}
for r in records:
    if not isinstance(r, dict):
        skipped['缺 ID'] += 1
        continue
    rid = r.get('id')
    if rid in (None, '', 0, '0'):
        skipped['缺 ID'] += 1
        continue
    if r.get('record_type') not in DELETABLE:
        skipped['类型不可删'] += 1
        continue
    t = parse_time(r.get('created_at'))
    if t is None:
        skipped['缺时间戳'] += 1
        continue
    # 闭区间：`created_at == cutoff` 的那一条**算到期**。
    # 开闭在真机上差一天的边界上只差一条，但两条线各自自洽才不会出现
    # "同一个附件今天该删、明天不该删"的抖动。
    if t > cutoff:
        skipped['还没到期'] += 1
        continue
    due.append(r)

for why, n in skipped.items():
    if n:
        print(f'# 跳过 {n} 条：{why}')

if not due:
    print('# 没有到期的 Issue/PR 附件，收工')
    sys.exit(0)

total_bytes = sum(int(r.get('size_in_byte') or 0) for r in due)
print(f'# 到期 {len(due)} 条，合计 {total_bytes} 字节：')
for r in due:
    print(f'#   {r.get("id")}  {r.get("record_type"):<10} '
          f'{r.get("created_at")}  {r.get("size_in_byte") or 0:>10}  {r.get("path")}')

if not apply_:
    print('# dry-run：以上一条都没删。加 --apply 才动手。')
    sys.exit(0)

# ③ 逐条删。单条失败**不中断整批**（一两条指向已消失的对象不该让其余的全留下），
#    但失败数非 0 时退出码非 0 —— 定时任务的红/绿必须反映"这次清干净了没有"。
ok = fail = 0
for r in due:
    try:
        delete(r['id'])
        ok += 1
        print(f'已删 {r["id"]} {r.get("path")}')
    except urllib.error.HTTPError as e:
        fail += 1
        print(f'删 {r["id"]} 失败：HTTP {e.code} {e.read().decode("utf8", "replace")[:200]}',
              file=sys.stderr)
    except urllib.error.URLError as e:
        fail += 1
        print(f'删 {r["id"]} 失败：{e}', file=sys.stderr)

print(f'# 完成：删掉 {ok} 条，失败 {fail} 条')
sys.exit(1 if fail else 0)
PY
