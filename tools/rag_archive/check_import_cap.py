#!/usr/bin/env python3
"""判「导入条数上限」与「单条块数上限」是不是**同一个数**。

═══════════════════════════════════════════════════════════════════════════
为什么要判"相等"而不是"等于 16384"
═══════════════════════════════════════════════════════════════════════════
原先的判据是 `grep -q 'const val MAX_IMPORT_ENTRIES = 4096'` —— 它锚的是一个
**写死的数字**。这有两个后果：

  · 两边一起改（4096 -> 16384）时，只要有人记得同时改判据，它就照旧全绿 ——
    而"它们相等"这件事**从来没有被验过**：它们完全可能被改成 16384 / 8192，
    判据还是绿的（只要把 grep 的字面量也跟着改）；
  · 两个数的**契约**是"索引装不下更多，所以收也没用"。这个契约一旦破，
    症状是"导入通过了但建库被截断"—— 又一条静默路径（用户以为全进来了）。

所以这里**从两边各自的源码里把数值提出来比**，而不是比某个字面量。

用法：check_import_cap.py <RagArchive.kt> <RagIndexStore.kt>
"""
import re
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0] + '/../rag_index')
from strip_comments import strip_kt  # noqa: E402


def import_cap(archive_src):
    m = re.search(r'const\s+val\s+MAX_IMPORT_ENTRIES\s*(?::\s*Int)?\s*=\s*(\d+)',
                  strip_kt(archive_src))
    return int(m.group(1)) if m else None


def chunk_cap(store_src):
    """从 `ChunkParams` 的 `maxChunks` 默认值取数。"""
    src = strip_kt(store_src)
    m = re.search(r'data\s+class\s+ChunkParams\s*\(', src)
    if not m:
        return None
    i = src.find(')', m.end())
    body = src[m.end():i if i > 0 else m.end() + 1200]
    m2 = re.search(r'val\s+maxChunks\s*:\s*Int\s*=\s*(\d+)', body)
    return int(m2.group(1)) if m2 else None


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    a = import_cap(open(sys.argv[1], encoding='utf-8').read())
    b = chunk_cap(open(sys.argv[2], encoding='utf-8').read())
    if a is None:
        print('FAIL  取不到 RagArchive.MAX_IMPORT_ENTRIES（改名/改成非字面量了？）')
        return 1
    if b is None:
        print('FAIL  取不到 ChunkParams.maxChunks 的默认值（改名/拆走了？）')
        return 1
    if a != b:
        print('FAIL  两个上限不相等：MAX_IMPORT_ENTRIES=%d ≠ maxChunks=%d —— '
              '不一致时"导入通过了但建库被截断"，用户以为全进来了' % (a, b))
        return 1
    print('PASS  导入条数上限与单条块数上限相等（%d）' % a)
    return 0


if __name__ == '__main__':
    sys.exit(main())
