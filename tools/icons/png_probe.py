#!/usr/bin/env python
# -*- coding: utf-8 -*-
# 纯 Python PNG 解码器 + 探针：查看 PNG 像素统计并拼接成对照图
import sys, zlib, struct, os

def decode_png(data):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos = 8
    idat = b''
    w = h = bd = ct = None
    plte = None
    trns = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', chunk)
            assert inter == 0, 'interlaced not supported'
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'PLTE':
            plte = chunk
        elif typ == b'tRNS':
            trns = chunk
        elif typ == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    nch = {0:1, 2:3, 3:1, 4:2, 6:4}[ct]
    if bd == 8:
        bpp = nch
    elif bd == 4:
        bpp = 1
    else:
        raise ValueError('bitdepth %s unsupported' % bd)
    stride = (w * nch * bd + 7) // 8
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i+stride]); i += stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x-bpp]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x-bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x-bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x-bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line
    # 转 RGBA 像素列表
    px = []
    if ct == 6:
        for y in range(h):
            row = []
            for x in range(w):
                o = (y*w+x)*4
                row.append((out[o], out[o+1], out[o+2], out[o+3]))
            px.append(row)
    elif ct == 2:
        for y in range(h):
            row = []
            for x in range(w):
                o = (y*w+x)*3
                row.append((out[o], out[o+1], out[o+2], 255))
            px.append(row)
    elif ct == 0:
        for y in range(h):
            row = []
            for x in range(w):
                v = out[y*w+x]
                row.append((v, v, v, 255))
            px.append(row)
    elif ct == 4:
        for y in range(h):
            row = []
            for x in range(w):
                o = (y*w+x)*2
                row.append((out[o], out[o], out[o], out[o+1]))
            px.append(row)
    elif ct == 3:
        for y in range(h):
            row = []
            for x in range(w):
                idx = out[y*w+x]
                r, g, b = plte[idx*3], plte[idx*3+1], plte[idx*3+2]
                a = trns[idx] if (trns and idx < len(trns)) else 255
                row.append((r, g, b, a))
            px.append(row)
    return w, h, px

def stats(px):
    tot = sum(len(r) for r in px)
    opaque = 0
    colors = {}
    for row in px:
        for (r, g, b, a) in row:
            if a > 8:
                opaque += 1
                key = (r//24*24, g//24*24, b//24*24)
                colors[key] = colors.get(key, 0)+1
    top = sorted(colors.items(), key=lambda kv: -kv[1])[:4]
    return opaque, tot, top

def write_png(path, px):
    h = len(px); w = len(px[0])
    raw = bytearray()
    for row in px:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t+d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 6))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)

def scale_nn(px, tw, th):
    h = len(px); w = len(px[0])
    out = []
    for y in range(th):
        sy = min(h-1, y*h//th)
        row = []
        for x in range(tw):
            sx = min(w-1, x*w//tw)
            row.append(px[sy][sx])
        out.append(row)
    return out

def checker(px, cs=8):
    # 在透明处铺棋盘格, 便于肉眼判断空白
    out = []
    for y, row in enumerate(px):
        nr = []
        for x, (r, g, b, a) in enumerate(row):
            if a < 250:
                base = 200 if ((x//cs + y//cs) % 2 == 0) else 150
                nr.append((base, base, base, 255))
            else:
                nr.append((r, g, b, a))
        out.append(nr)
    return out

if __name__ == '__main__':
    paths = sys.argv[1:]
    tiles = []
    for p in paths:
        data = open(p, 'rb').read()
        w, h, px = decode_png(data)
        o, t, top = stats(px)
        print('%-46s %3dx%-4d opaque=%.1f%% top=%s' % (os.path.basename(p), w, h, o*100.0/t, top))
        tiles.append(checker(scale_nn(px, 160, 160)))
    if tiles:
        sheet = []
        for y in range(160):
            row = []
            for t in tiles:
                row += t[y] + [(255, 255, 255, 255)]*6
            sheet.append(row)
        write_png(os.path.join(os.path.dirname(paths[0]) or '.', 'probe_sheet.png'), sheet)
        print('sheet ->', os.path.join(os.path.dirname(paths[0]) or '.', 'probe_sheet.png'))
