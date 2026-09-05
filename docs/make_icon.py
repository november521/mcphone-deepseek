#!/usr/bin/env python3
"""把 docs/icon-source.png 缩成 App 图标 assets/.../textures/app/deepseek.png。

    python3 docs/make_icon.py

换图标的流程：把新图丢成 docs/icon-source.png，跑一遍这个，然后 ./gradlew build。

这个脚本到底解决什么
--------------------
只解决一件事：宽高比。

图标【不】需要正好是 20×20。MCphone 的 renderIcon 把整张贴图的 UV 0..1 铺满
格子，多大的图都显示得出来。但那个格子是正方的（接口签名里只有一个 size），
所以源图不是正方时，横竖两个方向的缩放倍数不一样，图会被压扁——这只鲸鱼
380×322，直接丢进去会被压掉 15% 的宽度。

所以这里做的是：按原比例缩进 20×20，短的那一边补透明边居中。

为什么是最近邻，不是面积平均
----------------------------
App 图标是纯色块拼出来的 logo，不是照片。这种图的形状全靠边界撑着，而面积平均
恰好把边界抹成中间色——实测缩完白肚子和眼睛都化开了，整只鲸鱼成了一个蓝团。
最近邻取点保住硬边，出来的东西反而更像原图。

这一条不是通则：源图要是带渐变或照片质感，结论会反过来。别为了"更高级"默认用
平滑缩放。

顺带一提，最近邻和游戏自己的缩放是同一回事（GUI 贴图默认 blur=false），所以这
一步在观感上等价于"把补成正方形的原图直接丢进 assets"。之所以还是离线缩一次：
20×20 的 PNG 是 500 字节，380×380 的是 90 KB，后者还要一直占着一块大得多的
显存——为一个 20 像素的图标不值当。

不用 PIL：这套环境里装不上，而要做的事就是解一个 8 位 RGBA 的 PNG——zlib 加
反滤波，几十行的量，不值得为它引一个依赖。
"""
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "icon-source.png"
TARGET = HERE.parent / "src/main/resources/assets/mcphone_deepseek/textures/app/deepseek.png"

SIZE = 20


#  ——— PNG 读写。只认 8 位 RGBA、非隔行 ———

def read_png(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path} 不是 PNG")

    pos, idat = 8, b""
    width = height = depth = ctype = interlace = None

    while pos < len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]

        if tag == b"IHDR":
            width, height, depth, ctype, _comp, _filter, interlace = \
                struct.unpack(">IIBBBBB", body)
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
        pos += 12 + length

    if (depth, ctype, interlace) != (8, 6, 0):
        raise SystemExit(
            f"只处理 8 位 RGBA、非隔行的 PNG；这张是 depth={depth} "
            f"colorType={ctype} interlace={interlace}。"
            f"用图像软件另存为 32 位 PNG 即可")

    raw = zlib.decompress(idat)
    bpp, stride = 4, width * 4
    out = bytearray()
    prev = bytearray(stride)
    p = 0

    for _ in range(height):
        kind = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride

        if kind == 1:                                   # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif kind == 2:                                 # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif kind == 3:                                 # Average
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 255
        elif kind == 4:                                 # Paeth
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 255
        elif kind != 0:
            raise SystemExit(f"没见过的 PNG filter：{kind}")

        out += line
        prev = line

    return width, height, bytes(out)


def write_png(path, width, height, pixels):
    raw = b"".join(b"\x00" + pixels[y * width * 4:(y + 1) * width * 4]
                   for y in range(height))

    def chunk(tag, body):
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xffffffff))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b""))


#  ——— 缩放 ———

def content_box(width, height, pixels):
    """不透明内容的包围盒。四周的空白不该占掉 20 像素里的地方"""
    x0, y0, x1, y1 = width, height, -1, -1
    for y in range(height):
        row = y * width
        for x in range(width):
            if pixels[(row + x) * 4 + 3]:
                x0 = min(x0, x)
                y0 = min(y0, y)
                x1 = max(x1, x)
                y1 = max(y1, y)
    if x1 < 0:
        raise SystemExit("整张图都是透明的")
    return x0, y0, x1 + 1, y1 + 1


def nearest_resize(src_w, src_h, pixels, box, out_w, out_h):
    """把 box 那块缩到 out_w×out_h，每个目标像素取源图上对应位置的那一个点。

    取的是格子中心而不是左上角：取左上角会让整张图朝左上偏半格，在 20 像素的
    图标上那是肉眼可见的一格。
    """
    bx0, by0, bx1, by1 = box
    bw, bh = bx1 - bx0, by1 - by0
    out = bytearray(out_w * out_h * 4)

    for oy in range(out_h):
        sy = by0 + min(bh - 1, int((oy + 0.5) * bh / out_h))
        for ox in range(out_w):
            sx = bx0 + min(bw - 1, int((ox + 0.5) * bw / out_w))

            i = (sy * src_w + sx) * 4
            o = (oy * out_w + ox) * 4
            out[o:o + 4] = pixels[i:i + 4]
    return bytes(out)


def center(canvas, width, height, pixels, at_y):
    """摆进一块 canvas×canvas 的透明画布"""
    out = bytearray(canvas * canvas * 4)
    for y in range(height):
        ty = at_y + y
        if not 0 <= ty < canvas:
            continue
        src = y * width * 4
        dst = ty * canvas * 4
        out[dst:dst + width * 4] = pixels[src:src + width * 4]
    return bytes(out)


def main():
    if not SOURCE.exists():
        raise SystemExit(f"没找到 {SOURCE}")

    width, height, pixels = read_png(SOURCE)
    box = content_box(width, height, pixels)
    bw, bh = box[2] - box[0], box[3] - box[1]

    # 宽占满 20，高按原比例；比 1 的话反过来
    if bw >= bh:
        out_w, out_h = SIZE, max(1, round(SIZE * bh / bw))
    else:
        out_w, out_h = max(1, round(SIZE * bw / bh)), SIZE

    small = nearest_resize(width, height, pixels, box, out_w, out_h)

    final = center(SIZE, out_w, out_h, small, (SIZE - out_h) // 2)
    # 横向也要居中（源图比 1 时用得上）
    if out_w < SIZE:
        shifted = bytearray(SIZE * SIZE * 4)
        pad = (SIZE - out_w) // 2
        for y in range(SIZE):
            src = y * SIZE * 4
            shifted[src + pad * 4: src + (pad + out_w) * 4] = \
                final[src: src + out_w * 4]
        final = bytes(shifted)

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    write_png(TARGET, SIZE, SIZE, final)

    print(f"{width}×{height}（内容 {bw}×{bh}）→ {out_w}×{out_h}，"
          f"居中放进 {SIZE}×{SIZE}")
    print(f"写入 {TARGET.relative_to(HERE.parent)}（{TARGET.stat().st_size} 字节）")


if __name__ == "__main__":
    main()
