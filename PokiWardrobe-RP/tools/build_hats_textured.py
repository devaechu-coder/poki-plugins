"""모자 5개 — 면마다 도트 텍스처(하이라이트·그림자·무늬)를 그려서 모자별 아틀라스 png + 모델 json.

모양은 build.py 의 상자 그대로, 텍스처만 새로. 텍셀 밀도: 모델 1단위 = 2텍셀.
UV 방향은 마크 기본 규칙 (옆면 v=0 이 위쪽).
"""
import json, os, sys, random, math
from PIL import Image
import build

OUT = sys.argv[1]
DENS = 2  # 모델 1단위당 텍셀

BASE = {k: build.hexc(v) for k, v in build.PALETTE.items()}
STYLE = {
    "G": "gold", "Gd": "gold", "Gl": "gold", "Rg": "gem", "Bg": "gem",
    "R": "cloth", "Rd": "cloth", "K": "dark", "W": "cloth",
    "P": "gingham", "Pl": "cloth", "Pd": "cloth",
    "C": "fur", "Ci": "fur",
    "Vg": "leaf", "Lg": "leaf", "Y": "pollen", "Fp": "petal", "Fw": "petal",
}


def mul(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def face_dims(e, face):
    (x1, y1, z1), (x2, y2, z2) = e["from"], e["to"]
    dx, dy, dz = x2 - x1, y2 - y1, z2 - z1
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[face]


def paint(col, face, w, h, rnd, el_index):
    """한 면 텍스처 (w x h 텍셀) 그리기 → [[rgb]]"""
    base = BASE[col]
    st = STYLE[col]
    px = [[base for _ in range(w)] for _ in range(h)]
    side = face not in ("up", "down")
    for y in range(h):
        for x in range(w):
            c = base
            n = rnd.uniform(-1, 1)
            if st == "gold":
                c = mul(c, 1 + n * 0.05)
                if side:
                    t = y / max(1, h - 1)
                    c = mul(c, 1.18 - 0.38 * t)
                    if h >= 4 and y == 1: c = mix(c, (255, 248, 200), 0.45)
                if (x + y + el_index) % 7 == 0 and w * h >= 12: c = mix(c, (255, 255, 230), 0.55)
            elif st == "gem":
                c = mul(c, 1.0 + n * 0.04)
            elif st == "cloth":
                c = mul(c, 1 + n * 0.06)
                if side: c = mul(c, 1.08 - 0.22 * (y / max(1, h - 1)))
            elif st == "dark":
                c = mul(c, 1 + n * 0.10)
                if side and y == 0: c = mix(c, (110, 116, 130), 0.5)
            elif st == "gingham":
                c = mul(c, 1 + n * 0.05)
                if (x // 2) % 2 == 0: c = mix(c, (255, 255, 255), 0.22)
                if (y // 2) % 2 == 0: c = mix(c, (255, 255, 255), 0.22)
                if side: c = mul(c, 1.05 - 0.15 * (y / max(1, h - 1)))
            elif st == "fur":
                c = mul(c, 1 + n * 0.07)
                if side: c = mul(c, 1.06 - 0.16 * (y / max(1, h - 1)))
                if rnd.random() < 0.12: c = mul(c, 0.92)
            elif st == "leaf":
                c = mul(c, 1 + n * 0.12)
                r = rnd.random()
                if r < 0.18: c = mix(c, (150, 220, 90), 0.5)
                elif r < 0.30: c = mul(c, 0.72)
            elif st == "pollen":
                c = mul(c, 1 + n * 0.08)
            elif st == "petal":
                c = mul(c, 1 + n * 0.04)
            px[y][x] = c
    # 테두리: 위·왼쪽 밝게, 아래·오른쪽 어둡게 (작은 면은 생략)
    if w >= 3 and h >= 3 and st not in ("leaf",):
        for x in range(w):
            px[0][x] = mix(px[0][x], (255, 255, 255), 0.18 if st != "dark" else 0.10)
            px[h - 1][x] = mul(px[h - 1][x], 0.72)
        for y in range(h):
            px[y][0] = mix(px[y][0], (255, 255, 255), 0.10)
            px[y][w - 1] = mul(px[y][w - 1], 0.80)
    if st == "gem":
        for y in range(h):
            for x in range(w):
                edge = x == 0 or y == 0 or x == w - 1 or y == h - 1
                if edge and w >= 2 and h >= 2: px[y][x] = mul(px[y][x], 0.65)
        px[min(1, h - 1)][min(1, w - 1)] = (255, 255, 255)
    if st == "petal" and w >= 2 and h >= 2:
        for y in range(h):
            for x in range(w):
                if x in (0, w - 1) or y in (0, h - 1): px[y][x] = mul(px[y][x], 0.86)
    if st == "pollen" and w >= 2 and h >= 2:
        px[h // 2][w // 2] = (240, 140, 40)
    return px


def pack(rects, size):
    """선반 채우기: rects=[(id,w,h)] → {id:(x,y)} 또는 None"""
    pos = {}
    x = y = shelf = 0
    for rid, w, h in sorted(rects, key=lambda r: -r[2]):
        if x + w > size:
            x, y, shelf = 0, y + shelf, 0
        if y + h > size or w > size:
            return None
        pos[rid] = (x, y)
        x += w
        shelf = max(shelf, h)
    return pos


def build_hat(fid, elems):
    rnd = random.Random(fid)
    faces = []
    for i, e in enumerate(elems):
        for f in ("north", "east", "south", "west", "up", "down"):
            du, dv = face_dims(e, f)
            w, h = max(1, round(du * DENS)), max(1, round(dv * DENS))
            faces.append(((i, f), w, h))
    size = 32
    while True:
        pos = pack([(k, w, h) for k, w, h in faces], size)
        if pos: break
        size *= 2
    assert size <= 512, (fid, size)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ppu = size / 16
    model_el = []
    for i, e in enumerate(elems):
        fj = {}
        for f in ("north", "east", "south", "west", "up", "down"):
            w, h = next((w, h) for k, w, h in faces if k == (i, f))
            x0, y0 = pos[(i, f)]
            tex = paint(e["col"], f, w, h, rnd, i)
            for yy in range(h):
                for xx in range(w):
                    img.putpixel((x0 + xx, y0 + yy), tex[yy][xx] + (255,))
            fj[f] = {"uv": [round(x0 / ppu, 4), round(y0 / ppu, 4), round((x0 + w) / ppu, 4), round((y0 + h) / ppu, 4)],
                     "texture": "#0"}
        model_el.append({"from": [round(v, 3) for v in e["from"]], "to": [round(v, 3) for v in e["to"]], "faces": fj})
    tex_name = "poki:item/hats/" + fid
    m = build.model_json(elems)
    m["texture_size"] = [size, size]
    m["textures"] = {"0": tex_name, "particle": tex_name}
    m["elements"] = model_el
    img.save(os.path.join(OUT, fid + ".png"))
    with open(os.path.join(OUT, fid + ".json"), "w") as fp:
        json.dump(m, fp, indent=1)
    return size


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for fid, name, fn in build.HATS:
        print(fid, name, "texture", build_hat(fid, fn()))
