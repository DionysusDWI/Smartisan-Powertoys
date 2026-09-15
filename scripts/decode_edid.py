#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EDID 解码器（任务 AI 副产）—— 专为 TNT GO 那块屏写的，但通用。

## 为什么要它

2026-09-13 为了回答「TNT GO 屏幕亮度范围多少尼特 / 能不能承载 HDR」，
需要看**显示器自己在 EDID 里申报了什么**。而 `/sys/class/drm/card*-DP-*/edid`
在坚果 Pro 3 上**读不了**（`Permission denied`，SELinux，要 root）。

★ **但内核 dmesg 里已经把 EDID 原样打出来了**：
```
[drm-dp] SINK EDID: 00 ff ff ff ff ff ff 00 25 d8 01 78 00 00 00 00
                   19 1e 01 04 a5 19 11 78 02 de cb a3 5a 55 9e 27
                   ...
```
⇒ 本脚本吃那种 dmesg 片段（或纯十六进制），把 **base block** 解出来。

## 用法

    # 直接把 dmesg 片段喂进来
    adb shell 'dmesg | grep "SINK EDID"' > edid.txt
    python scripts/decode_edid.py edid.txt

    # 或给一串十六进制
    python scripts/decode_edid.py --hex "00ffffffffffff0025d8017800000000..."

## ⚠️ 本脚本只解【base block 128 字节】

**这恰恰是最关键的信息**：EDID 的 **byte 126 = 扩展块数量**。
- `0` ⇒ **没有任何 CTA-861 扩展块** ⇒ **不可能有 HDR 静态元数据块（HDR Static Metadata Data Block）**
  ⇒ ★ **这块屏在 EDID 层面【没有申报 HDR】**。
- 平台（Android/SurfaceFlinger）此时会**回落到内置默认值**（HDR10/HLG + 500 nit 之类），
  ★ **那不是屏的申报，别当成屏的能力**。
"""

import re
import sys

# ---------------------------------------------------------------- 基础工具

def parse_hex(text: str) -> bytes:
    """从任意文本里抠出十六进制字节流（能吃 dmesg 的 `SINK EDID:` 行）"""
    out = bytearray()
    for line in text.splitlines():
        if not line.strip():
            continue
        # dmesg 行形如:  [123.456] (3)[ pid|comm ] [drm-dp] SINK EDID: 00 ff ff ...
        m = re.search(r'SINK EDID:\s*(.*)$', line)
        payload = m.group(1) if m else line
        for tok in re.findall(r'\b[0-9a-fA-F]{2}\b', payload):
            out.append(int(tok, 16))
    return bytes(out)


def manufacturer(b8: int, b9: int) -> str:
    """EDID 厂商 ID：2 字节大端，3 个 5-bit 字母（1='A'）"""
    v = (b8 << 8) | b9
    return ''.join(chr(((v >> s) & 0x1F) + 64) for s in (10, 5, 0))


DIGITAL_INTERFACE = {
    0: '未定义', 1: 'DVI', 2: 'HDMI-a', 3: 'HDMI-b', 4: 'MDDI', 5: 'DisplayPort',
}
BIT_DEPTH = {0: '未定义', 1: '6 bit', 2: '8 bit', 3: '10 bit', 4: '12 bit', 5: '14 bit', 6: '16 bit'}

# CTA-861.3 HDR 静态元数据块里亮度的编码：value = 50 * 2^(code/32)  cd/m²
# ★ 用它来判断"某个亮度值是否可能来自真实 EDID"
def cta_luminance_codes():
    return {round(50 * 2 ** (c / 32), 2) for c in range(256)}


def decode(data: bytes) -> None:
    if len(data) < 128:
        print(f'!! 只有 {len(data)} 字节，不足一个 base block（128）')
        return

    print('=' * 68)
    print('EDID base block 解码')
    print('=' * 68)

    if data[:8] != bytes([0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00]):
        print('!! 头 8 字节不是 EDID magic，可能解析错了')

    print(f'厂商            : {manufacturer(data[8], data[9])}   (raw {data[8]:02x} {data[9]:02x})')
    print(f'产品码          : 0x{data[11]:02x}{data[10]:02x}')
    print(f'序列号          : {(data[15]<<24)|(data[16-1]<<16)|(data[13]<<8)|data[12]}')
    week, year = data[16], data[17]
    print(f'生产日期        : {year + 1990} 年' + (f' 第 {week} 周' if week else ' (年)'))
    print(f'EDID 版本       : {data[18]}.{data[19]}')

    sig = data[20]
    if sig & 0x80:
        depth = BIT_DEPTH.get((sig >> 4) & 0x07, '?')
        iface = DIGITAL_INTERFACE.get(sig & 0x0F, '?')
        print(f'输入            : 数字 / {iface} / ★ 每基色 {depth}')
    else:
        print(f'输入            : 模拟')

    h_cm, v_cm = data[21], data[22]
    if h_cm and v_cm:
        diag_mm = (h_cm ** 2 + v_cm ** 2) ** 0.5 * 10
        print(f'物理尺寸        : {h_cm} cm × {v_cm} cm  ⇒ 对角线 {diag_mm/25.4:.2f} 英寸')
    print(f'Gamma           : {(data[23] + 100) / 100:.2f}')

    feat = data[24]
    print(f'特性位(byte24)  : 0x{feat:02x}  '
          f'[sRGB默认={bool(feat & 0x04)} 首选时序=原生={bool(feat & 0x02)}]')

    # 色度坐标
    lo = data[25:27]
    rx = (data[27] << 2) | ((lo[0] >> 6) & 3)
    ry = (data[28] << 2) | ((lo[0] >> 4) & 3)
    gx = (data[29] << 2) | ((lo[0] >> 2) & 3)
    gy = (data[30] << 2) | ((lo[0] >> 0) & 3)
    bx = (data[31] << 2) | ((lo[1] >> 6) & 3)
    by = (data[32] << 2) | ((lo[1] >> 4) & 3)
    wx = (data[33] << 2) | ((lo[1] >> 2) & 3)
    wy = (data[34] << 2) | ((lo[1] >> 0) & 3)
    pts = {k: (v / 1024, w / 1024) for k, v, w in
           (('R', rx, ry), ('G', gx, gy), ('B', bx, by), ('W', wx, wy))}
    for k in 'RGBW':
        x, y = pts[k]
        print(f'  色度 {k}        : ({x:.4f}, {y:.4f})')

    def area(p):
        (rx, ry), (gx, gy), (bx, by) = p['R'], p['G'], p['B']
        return abs(rx * (gy - by) + gx * (by - ry) + bx * (ry - gy)) / 2

    def inside(pt, tri):
        """点是否在三角形内（同向叉积法）"""
        (x, y) = pt
        (ax, ay), (bx, by), (cx, cy) = tri
        d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
        d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
        d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
        has_neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
        has_pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
        return not (has_neg and has_pos)

    def coverage(panel, ref, n=600):
        """
        ★ ref 色域的【覆盖率】：ref 三角形里有多少比例的点也落在 panel 三角形里。

        ⚠️ **覆盖率和面积比是两个不同的指标,别对撞**：
        - **面积比(area)** = 两个三角形面积之比 —— 可能 >100%
        - **覆盖率(coverage)** = 交集 / ref 面积 —— **永远 ≤100%**
        一个三角形完全可能**面积比 sRGB 大、覆盖率却只有 88%**（形状/位置偏了,盖不住 sRGB 的角）。
        """
        (ax, ay), (bx, by), (cx, cy) = ref['R'], ref['G'], ref['B']
        hit = tot = 0
        for i in range(n + 1):
            for j in range(n + 1 - i):
                u, v = i / n, j / n
                x = ax + u * (bx - ax) + v * (cx - ax)
                y = ay + u * (by - ay) + v * (cy - ay)
                tot += 1
                if inside((x, y), (panel['R'], panel['G'], panel['B'])):
                    hit += 1
        return hit / tot

    srgb = {'R': (0.640, 0.330), 'G': (0.300, 0.600), 'B': (0.150, 0.060)}
    ntsc = {'R': (0.670, 0.330), 'G': (0.210, 0.710), 'B': (0.140, 0.080)}
    dci = {'R': (0.680, 0.320), 'G': (0.265, 0.690), 'B': (0.150, 0.060)}
    a_panel, a_srgb = area(pts), area(srgb)
    print(f'★ 色域【面积比】: {a_panel:.5f} vs sRGB {a_srgb:.5f} '
          f'⇒ 约 {a_panel / a_srgb * 100:.1f}% sRGB 面积'
          f'（≈ {a_panel / area(ntsc) * 100:.0f}% NTSC 面积）')
    print(f'★ 色域【覆盖率】: sRGB {coverage(pts, srgb, 400) * 100:.1f}%  '
          f'NTSC {coverage(pts, ntsc, 400) * 100:.1f}%  '
          f'DCI-P3 {coverage(pts, dci, 400) * 100:.1f}%')

    # McCamy 近似色温
    wx, wy = pts['W']
    nn = (wx - 0.3320) / (0.1858 - wy)
    cct = 449 * nn ** 3 + 3525 * nn ** 2 + 6823.3 * nn + 5520.33
    print(f'★ 白点色温      : 约 {cct:.0f} K（McCamy 近似；屏库标 7435K）')

    # 详细时序
    print('-' * 68)
    for i, off in enumerate((54, 72, 90, 108), start=1):
        d = data[off:off + 18]
        if d[0] == 0 and d[1] == 0:
            tag = d[3]
            if tag == 0xFC:
                name = d[5:18].split(b'\n')[0].split(b'\x00')[0].decode('ascii', 'replace')
                print(f'描述符 #{i}      : ★ 显示器名称 = "{name}"')
            elif tag == 0xFE:
                print(f'描述符 #{i}      : 文本 "{d[5:18].decode("ascii", "replace").strip()}"')
            elif tag == 0xFF:
                print(f'描述符 #{i}      : 序列号 "{d[5:18].decode("ascii", "replace").strip()}"')
            elif tag == 0xFD:
                print(f'描述符 #{i}      : 范围限制')
            elif tag == 0x10:
                print(f'描述符 #{i}      : dummy')
            else:
                print(f'描述符 #{i}      : 未知 tag 0x{tag:02x}')
            continue
        pclk = ((d[1] << 8) | d[0]) / 100.0
        hact = ((d[4] >> 4) << 8) | d[2]
        hbl = ((d[4] & 0x0F) << 8) | d[3]
        vact = ((d[7] >> 4) << 8) | d[5]
        vbl = ((d[7] & 0x0F) << 8) | d[6]
        hmm = ((d[14] >> 4) << 8) | d[12]
        vmm = ((d[14] & 0x0F) << 8) | d[13]
        print(f'描述符 #{i}      : ★ {hact}×{vact} @ {pclk:.2f} MHz  '
              f'(blank {hbl}×{vbl})  画面 {hmm}×{vmm} mm')

    # ★★★ 最关键的一行
    ext = data[126]
    print('=' * 68)
    print(f'★ EDID 扩展块数量 : {ext}')
    if ext == 0:
        print('  ⇒ ★★ **没有任何 CTA-861 扩展块**')
        print('  ⇒ ★★ **不可能有 HDR 静态元数据块** ⇒ 这块屏在 EDID 层面【没有申报 HDR】')
        print('  ⇒ 若系统仍报 HDR10/HLG，那是【平台的默认回落值】，不是屏的能力')
        codes = cta_luminance_codes()
        for probe in (500.0, 250.0):
            ok = any(abs(probe - c) < 0.6 for c in codes)
            print(f'     旁证：CTAy 编码 50·2^(c/32) 里{"能" if ok else "【不能】"}表示 {probe} nit'
                  f' ⇒ {"可能来自 EDID" if ok else "必是平台硬编码默认值"}')
    else:
        print(f'  ⇒ 还有 {ext} 个扩展块（本脚本未解，需另取 128·{ext} 字节）')
    print('=' * 68)


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == '--hex':
        data = bytes.fromhex(re.sub(r'[^0-9a-fA-F]', '', sys.argv[2]))
    elif len(sys.argv) >= 2:
        with open(sys.argv[1], 'r', encoding='utf-8', errors='replace') as f:
            data = parse_hex(f.read())
    else:
        data = parse_hex(sys.stdin.read())
    decode(data)
    return 0


if __name__ == '__main__':
    sys.exit(main())
