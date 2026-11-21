"""
把 Kotlin 端 QrCode 生成的矩阵喂给 zxing-cpp 解码，和原文比对。

这是验证"二维码真的能扫"的**唯一硬证据**：形状看着像不够，
必须让一个真实的解码器把它读回来，且内容完全一致。

用法（先跑 gradle test，会产出 build/qr-dump.txt）：
    python tools/verify-qr.py
"""
import sys
import numpy as np
import zxingcpp

DUMP = r"C:/Users/18557/WorkBuddy/2026-09-13-17-29-56/wearbook-native/app/build/qr-dump.txt"
SCALE = 8
BORDER = 4


def to_image(rows, scale=SCALE, border=BORDER):
    n = len(rows)
    size = (n + border * 2) * scale
    img = np.full((size, size), 255, dtype=np.uint8)
    for y, line in enumerate(rows):
        for x, ch in enumerate(line):
            if ch == "1":
                y0 = (y + border) * scale
                x0 = (x + border) * scale
                img[y0:y0 + scale, x0:x0 + scale] = 0
    return img


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    cases = []
    cur_url = None
    rows = []
    with open(DUMP, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("URL\t"):
                if cur_url:
                    cases.append((cur_url, rows))
                parts = line.split("\t")
                cur_url = parts[1]
                rows = []
            elif line and set(line) <= {"0", "1"}:
                rows.append(line)
    if cur_url:
        cases.append((cur_url, rows))

    print(f"读到 {len(cases)} 个用例")
    bad = 0
    for url, rows in cases:
        img = to_image(rows)
        res = zxingcpp.read_barcodes(img)
        got = str(res[0].text) if res else None
        ok = got == url
        if not ok:
            bad += 1
        print(f"  {'OK ' if ok else 'FAIL'} size={len(rows):>2} "
              f"期望={url:32s} 解出={got}")
    print()
    if bad:
        print(f"✗ {bad} 个用例解码失败/不一致")
        sys.exit(1)
    print("✓ 全部用例都能被真实解码器读回原文")


if __name__ == "__main__":
    main()
