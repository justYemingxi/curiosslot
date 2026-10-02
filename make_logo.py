import zlib, struct, math

W = H = 512

def make_px():
    bg   = (35, 42, 54, 255)      # 深板岩
    dark = (20, 26, 35, 255)      # 深内盘
    teal = (79, 209, 197, 255)    # 青色环
    gold = (251, 191, 36, 255)    # 金色槽位点
    soft = (58, 70, 88, 255)      # 中间色调描边

    r_corner = 100
    cx = cy = 256
    ro, ri = 158, 102           # 环外/内半径
    mrad = (ro + ri) / 2        # 环中线半径

    def inside_roundrect(x, y):
        r = r_corner
        if x < r and y < r: dx = x-r; dy = y-r; return dx*dx+dy*dy <= r*r
        if x >= W-r and y < r: dx = x-(W-r); dy = y-r; return dx*dx+dy*dy <= r*r
        if x < r and y >= H-r: dx = x-r; dy = y-(H-r); return dx*dx+dy*dy <= r*r
        if x >= W-r and y >= H-r: dx = x-(W-r); dy = y-(H-r); return dx*dx+dy*dy <= r*r
        return True

    def dist(a, b, x, y):
        return math.hypot(x-a, y-b)

    # 三个金色“槽位点”在环上的角度
    dot_ang = [-90, 30, 150]
    dots = [(cx + mrad*math.cos(math.radians(a)), cy + mrad*math.sin(math.radians(a))) for a in dot_ang]

    px = bytearray()
    for y in range(H):
        for x in range(W):
            if not inside_roundrect(x, y):
                px += bytes((0, 0, 0, 0))
                continue
            d = dist(cx, cy, x, y)
            c = bg
            if ri <= d <= ro:
                c = teal
            elif d < ri:
                c = dark
            # 环上三条细“缺口”线，模拟饰品槽缝隙
            for a in (-90, 30, 150):
                ra = math.radians(a)
                lx = cx + mrad*math.cos(ra)
                ly = cy + mrad*math.sin(ra)
                if dist(lx, ly, x, y) <= 8 and (ri+6) <= d <= (ro-6):
                    c = soft
            # 金色槽位点
            for (dx2, dy2) in dots:
                if dist(dx2, dy2, x, y) <= 24:
                    c = gold
            px += bytes(c)  # c 已是 RGBA 4 元组
    return bytes(px)

def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    crc = zlib.crc32(tag + data) & 0xffffffff
    return c + struct.pack('>I', crc)

raw = make_px()

def png_bytes():
    sig = b'\x89PNG\r\n\x1a\n'
    ihdr = struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)  # 8bit, RGBA
    # 每行前置 filter byte 0
    stride = W*4
    filtered = bytearray()
    for y in range(H):
        filtered.append(0)
        filtered += raw[y*stride:(y+1)*stride]
    idat = zlib.compress(bytes(filtered), 9)
    return sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b'')

out = r"C:\Users\secret\Doubao\chats\2026-09-28\new-chat-1\maidslot-mod\curiosslot_logo.png"
open(out, 'wb').write(png_bytes())
print("written", out)
