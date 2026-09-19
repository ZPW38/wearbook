# -*- coding: utf-8 -*-
"""
按 BookParser.kt 的规则在电脑上模拟解析一个 EPUB，用来判断它在手表上能不能读出来。
   用法：python check_epub.py <某本书.epub>
   背景：手表堆只有 96MB，而 EPUB 里往往 90%%+ 是图片。解析器只逐章读 XHTML，
        所以「文件很大」本身没问题 —— 有问题的是单个章节文件过大或正文总量过大。
规则：
  1) META-INF/container.xml -> rootfile full-path
  2) OPF: manifest / spine / title / creator / cover
  3) 逐章：entry.size > MAX_CHAPTER_ENTRY 就跳过
  4) html -> 段落（去 script/style/head/nav、块级标签转换行、去标签、解实体）
  5) 累计字符数，超过 MAX_TOTAL_CHARS 就停
"""
import io, os, re, sys, zipfile, html as htmlmod

MAX_CHAPTER_ENTRY = 4 * 1024 * 1024
MAX_TOTAL_CHARS = 8 * 1024 * 1024
MAX_COVER_ENTRY = 6 * 1024 * 1024

BLOCK = re.compile(r"(?is)<(script|style|head|nav)[^>]*>.*?</\1>")
BLOCKEND = re.compile(r"(?i)</(p|div|h[1-6]|li|blockquote|tr)>")
BR = re.compile(r"(?i)<br\s*/?>")
TAG = re.compile(r"<[^>]+>")


def html_to_paras(s):
    s = BLOCK.sub(" ", s)
    s = BLOCKEND.sub("\n\n", s)
    s = BR.sub("\n", s)
    s = TAG.sub("", s)
    for a, b in (("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                 ("&quot;", '"'), ("&#39;", "'")):
        s = s.replace(a, b)
    return [p.strip() for p in s.split("\n") if p.strip()]


def resolve(base, href):
    raw = href.split("#")[0]
    parts = (raw if not base else base + "/" + raw).split("/")
    out = []
    for p in parts:
        if p in ("", "."):
            continue
        if p == "..":
            if out:
                out.pop()
        else:
            out.append(p)
    return "/".join(out)


def main(path):
    z = zipfile.ZipFile(path)
    names = z.namelist()
    infos = z.infolist()
    print("=== 压缩包概况 ===")
    print("条目数:", len(names))
    top = sorted(infos, key=lambda i: -i.file_size)[:8]
    print("最大的 8 个条目:")
    for i in top:
        print("   %10.2f MB  %s" % (i.file_size / 1048576, i.filename))

    # 图片 / 文本占比
    img = sum(i.file_size for i in infos if re.search(r"\.(jpe?g|png|gif|webp|bmp)$", i.filename, re.I))
    tot = sum(i.file_size for i in infos)
    print("解压后总大小: %.1f MB，其中图片 %.1f MB (%.0f%%)" % (tot / 1048576, img / 1048576, 100.0 * img / max(tot, 1)))

    print()
    print("=== 1) container.xml ===")
    try:
        cx = z.read("META-INF/container.xml").decode("utf-8", "ignore")
    except KeyError:
        print("!! 缺少 META-INF/container.xml —— 我们的解析器会直接报「不是有效的 EPUB」")
        return
    m = re.search(r'full-path\s*=\s*"([^"]+)"', cx)
    if not m:
        print("!! container.xml 里没有 full-path")
        return
    root = m.group(1)
    print("rootfile:", root)

    print()
    print("=== 2) OPF ===")
    opf = z.read(root).decode("utf-8", "ignore")
    base = root.rsplit("/", 1)[0] if "/" in root else ""
    title = re.search(r"<dc:title[^>]*>(.*?)</dc:title>", opf, re.S)
    creator = re.search(r"<dc:creator[^>]*>(.*?)</dc:creator>", opf, re.S)
    print("书名:", (title.group(1).strip() if title else "(无)"))
    print("作者:", (creator.group(1).strip() if creator else "(无)"))

    manifest = {}
    for it in re.finditer(r"<item\b[^>]*>", opf):
        tag = it.group(0)
        def g(k):
            mm = re.search(k + r'\s*=\s*"([^"]*)"', tag)
            return mm.group(1) if mm else ""
        manifest[g("id")] = (g("href"), g("media-type"), g("properties"))
    spine = [re.search(r'idref\s*=\s*"([^"]*)"', t).group(1)
             for t in re.findall(r"<itemref\b[^>]*>", opf) if re.search(r'idref\s*=\s*"([^"]*)"', t)]
    print("manifest 条目: %d，spine 条目: %d" % (len(manifest), len(spine)))

    docs = [(i, manifest[i]) for i in spine if i in manifest and
            ("html" in manifest[i][1] or "xml" in manifest[i][1])]
    print("会被当作正文章的 spine 条目: %d" % len(docs))

    print()
    print("=== 3) 逐章解析（模拟修复后的逻辑）===")
    chapters, total, skipped, missing = 0, 0, 0, 0
    biggest = (0, "")
    for cid, (href, _t, _p) in docs:
        nm = resolve(base, href)
        try:
            sz = z.getinfo(nm).file_size
        except KeyError:
            missing += 1
            continue
        if sz > biggest[0]:
            biggest = (sz, nm)
        if sz > MAX_CHAPTER_ENTRY:
            skipped += 1
            continue
        raw = z.read(nm)
        s = raw.decode("utf-8", "ignore")
        paras = html_to_paras(s)
        if paras:
            total += sum(len(p) for p in paras)
            chapters += 1
        if total > MAX_TOTAL_CHARS:
            break

    print("成功解析章节: %d" % chapters)
    print("累计正文字符数: %d  (%.1f MB，上限 %d)" % (total, total * 2 / 1048576, MAX_TOTAL_CHARS))
    print("因单个章节 >4MB 被跳过: %d" % skipped)
    print("spine 里找不到对应文件的: %d" % missing)
    print("最大的一章: %.2f MB  %s" % (biggest[0] / 1048576, biggest[1]))
    print()
    if chapters == 0:
        print(">>> 结论：仍会失败 —— 「EPUB 内没有可读正文」")
    elif total > MAX_TOTAL_CHARS:
        print(">>> 结论：能读，但正文被截断到 %d 字符" % MAX_TOTAL_CHARS)
    elif skipped:
        print(">>> 结论：能读，%d 个超大章节被跳过" % skipped)
    else:
        print(">>> 结论：完整可读 ✓")


main(sys.argv[1] if len(sys.argv) > 1 else "t171.epub")
