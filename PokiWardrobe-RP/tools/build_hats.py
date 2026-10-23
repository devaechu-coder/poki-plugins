"""포키 치장 모자 5개 — 마크 자바 아이템 모델(json) + 팔레트 텍스처(png) 생성.

좌표: 아이템 모델 단위(0~16 = 블록 1칸). 머리 칸에 씌우면 마크가 0.625 배로 줄여서 머리 중심에 맞춤 →
플레이어 머리(8px)는 모델 좌표로 1.6~14.4, 머리 꼭대기 y=14.4. 앞(얼굴) = north = z 작은 쪽.
"""
import json, os, sys
from PIL import Image

OUT = sys.argv[1]
TEX_NAME = "poki:item/hats/hat_palette"

PALETTE = {
    "G": "#F2B705", "Gd": "#C98A0C", "Gl": "#FFE27A", "Rg": "#E3343C", "Bg": "#3FA9F5",
    "R": "#D9333A", "Rd": "#A8222A", "K": "#25272E", "W": "#F4F4F4",
    "P": "#FF8FB8", "Pl": "#FFC8DD", "Pd": "#E8628F",
    "C": "#FFF3E0", "Ci": "#FF9EC2",
    "Vg": "#3E8E2A", "Lg": "#6CC24A", "Y": "#FFD24A", "Fp": "#FF8FC7", "Fw": "#FFFFFF",
}
NAMES = list(PALETTE)
CELL = {n: (i % 16, i // 16) for i, n in enumerate(NAMES)}


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def box(x1, y1, z1, x2, y2, z2, col):
    return {"from": [x1, y1, z1], "to": [x2, y2, z2], "col": col}


# ---------- 모자 디자인 ----------

def crown():
    e = []
    G, Gd = "G", "Gd"
    e += [box(1, 13, 1, 15, 16.5, 2, G), box(1, 13, 14, 15, 16.5, 15, G),
          box(1, 13, 2, 2, 16.5, 14, G), box(14, 13, 2, 15, 16.5, 14, G)]
    e += [box(0.7, 12.6, 0.7, 15.3, 13.4, 1.3, Gd), box(0.7, 12.6, 14.7, 15.3, 13.4, 15.3, Gd),
          box(0.7, 12.6, 1.3, 1.3, 13.4, 14.7, Gd), box(14.7, 12.6, 1.3, 15.3, 13.4, 14.7, Gd)]
    for x, z in [(1, 1), (13.5, 1), (1, 13.5), (13.5, 13.5)]:
        e.append(box(x, 16.5, z, x + 1.5, 18.5, z + 1.5, G))
        e.append(box(x + 0.4, 18.5, z + 0.4, x + 1.1, 19.2, z + 1.1, "Gl"))
    for (x1, z1, x2, z2) in [(7, 1, 9, 2), (7, 14, 9, 15), (1, 7, 2, 9), (14, 7, 15, 9)]:
        e.append(box(x1, 16.5, z1, x2, 19, z2, G))
        cx, cz = (x1 + x2) / 2, (z1 + z2) / 2
        e.append(box(cx - 0.4, 19, cz - 0.4, cx + 0.4, 20, cz + 0.4, "Gl"))
    for (x1, z1, x2, z2) in [(4, 1, 5, 2), (11, 1, 12, 2), (4, 14, 5, 15), (11, 14, 12, 15),
                             (1, 4, 2, 5), (1, 11, 2, 12), (14, 4, 15, 5), (14, 11, 15, 12)]:
        e.append(box(x1, 16.5, z1, x2, 17.5, z2, G))
    e += [box(7, 14, 0.6, 9, 16, 1, "Rg"), box(7, 14, 15, 9, 16, 15.4, "Rg"),
          box(0.6, 14, 7, 1, 16, 9, "Rg"), box(15, 14, 7, 15.4, 16, 9, "Rg")]
    for x in (3.6, 11.4):
        e += [box(x, 14.4, 0.6, x + 1, 15.4, 1, "Bg"), box(x, 14.4, 15, x + 1, 15.4, 15.4, "Bg")]
    for z in (3.6, 11.4):
        e += [box(0.6, 14.4, z, 1, 15.4, z + 1, "Bg"), box(15, 14.4, z, 15.4, 15.4, z + 1, "Bg")]
    return e


def pokeball_cap():
    return [
        box(0.8, 13.4, 0.8, 15.2, 14.8, 15.2, "K"),
        box(1.0, 14.8, 1.0, 15.0, 16.4, 15.0, "R"),
        box(1.6, 16.4, 1.6, 14.4, 17.6, 14.4, "R"),
        box(2.6, 17.6, 2.6, 13.4, 18.6, 13.4, "R"),
        box(4.0, 18.6, 4.0, 12.0, 19.3, 12.0, "R"),
        box(6.0, 19.3, 6.0, 10.0, 19.7, 10.0, "Rd"),
        box(2.0, 13.4, -3.6, 14.0, 14.2, 0.8, "W"),
        box(3.2, 14.2, -2.2, 12.8, 14.7, 0.8, "W"),
        box(5.5, 13.0, 0.4, 10.5, 16.8, 0.8, "K"),
        box(6.4, 13.8, 0.1, 9.6, 16.0, 0.4, "W"),
        box(7.5, 14.4, -0.05, 8.5, 15.4, 0.1, "K"),
        box(6.0, 13.4, 15.2, 10.0, 14.6, 15.5, "Rd"),
    ]

def band(col, z1=6.5, z2=8.5):
    return [box(1.2, 14.4, z1, 14.8, 15.4, z2, col),
            box(0.6, 9.5, z1, 1.6, 15.4, z2, col),
            box(14.4, 9.5, z1, 15.4, 15.4, z2, col)]


def ribbon():
    e = band("P", 6.0, 9.0)
    e += [
        box(5.6, 15.3, 5.6, 8.0, 18.0, 9.4, "Pl"),
        box(1.4, 15.7, 6.1, 5.6, 19.0, 8.9, "P"),
        box(0.6, 16.3, 6.3, 1.4, 18.4, 8.7, "P"),
        box(8.0, 15.7, 6.1, 12.2, 19.0, 8.9, "P"),
        box(12.2, 16.3, 6.3, 13.0, 18.4, 8.7, "P"),
        box(2.2, 16.4, 5.9, 5.0, 18.3, 6.1, "Pd"),
        box(8.6, 16.4, 5.9, 11.4, 18.3, 6.1, "Pd"),
        box(4.9, 12.4, 5.4, 6.0, 15.3, 6.0, "P"),
        box(7.6, 12.4, 5.4, 8.7, 15.3, 6.0, "P"),
        box(4.7, 12.0, 5.3, 6.2, 12.6, 6.0, "Pd"),
        box(7.4, 12.0, 5.3, 8.9, 12.6, 6.0, "Pd"),
    ]
    return e

def cat_ears():
    e = band("C", 6.6, 8.8)
    for x0 in (1.6, 9.6):
        e += [
            box(x0, 15.2, 6.6, x0 + 4.8, 17.2, 8.6, "C"),
            box(x0 + 0.6, 17.2, 6.6, x0 + 4.2, 19.0, 8.6, "C"),
            box(x0 + 1.2, 19.0, 6.6, x0 + 3.6, 20.4, 8.6, "C"),
            box(x0 + 1.8, 20.4, 6.6, x0 + 3.0, 21.4, 8.6, "C"),
            box(x0 + 1.0, 15.6, 6.4, x0 + 3.8, 18.2, 6.6, "Ci"),
            box(x0 + 1.6, 18.2, 6.4, x0 + 3.2, 19.8, 6.6, "Ci"),
        ]
    e += [box(-0.2, 11.2, 7.0, 0.6, 12.8, 8.4, "Pd"),
          box(-0.1, 10.6, 5.0, 0.6, 13.4, 7.0, "P"),
          box(-0.1, 10.6, 8.4, 0.6, 13.4, 10.4, "P")]
    return e

def flower_crown():
    e = [box(1, 13.6, 1, 15, 15.0, 1.8, "Vg"), box(1, 13.6, 14.2, 15, 15.0, 15, "Vg"),
         box(1, 13.6, 1.8, 1.8, 15.0, 14.2, "Vg"), box(14.2, 13.6, 1.8, 15, 15.0, 14.2, "Vg")]

    def flower(side, a, petal):
        # side: 앞 n / 뒤 s / 왼 w / 오 e,  a: 벽을 따라간 위치, 꽃 중심 높이 14.6
        cy = 14.8
        k = 1.6
        parts = []
        for (du, dv, t, col) in [(0, 0, 0.8, "Y"), (0, 1, 0.55, petal), (0, -1, 0.55, petal), (1, 0, 0.55, petal), (-1, 0, 0.55, petal)]:
            u1, u2 = a + du * k - 0.85, a + du * k + 0.85
            v1, v2 = cy + dv * k - 0.85, cy + dv * k + 0.85
            if side == "n": parts.append(box(u1, v1, 1 - t, u2, v2, 1, col))
            if side == "s": parts.append(box(u1, v1, 15, u2, v2, 15 + t, col))
            if side == "w": parts.append(box(1 - t, v1, u1, 1, v2, u2, col))
            if side == "e": parts.append(box(15, v1, u1, 15 + t, v2, u2, col))
        return parts

    cols = ["Fp", "Fw"]
    i = 0
    for side in "nswe":
        for a in (4.5, 11.5):
            e += flower(side, a, cols[i % 2]); i += 1
    # 모서리 위에 눕힌 꽃
    for k_, (cx, cz) in enumerate([(1.4, 1.4), (14.6, 1.4), (1.4, 14.6), (14.6, 14.6)]):
        pc = "Fw" if k_ % 2 else "Fp"
        e.append(box(cx - 0.8, 15.0, cz - 0.8, cx + 0.8, 15.9, cz + 0.8, "Y"))
        for ddx, ddz in [(1.5, 0), (-1.5, 0), (0, 1.5), (0, -1.5)]:
            e.append(box(cx + ddx - 0.75, 15.0, cz + ddz - 0.75, cx + ddx + 0.75, 15.6, cz + ddz + 0.75, pc))
    for (x1, z1, x2, z2) in [(7.4, 0.6, 8.6, 1.4), (7.4, 14.6, 8.6, 15.4), (0.6, 7.4, 1.4, 8.6), (14.6, 7.4, 15.4, 8.6)]:
        e.append(box(x1 - 0.6, 15.0, z1, x2 + 0.6, 16.4, z2, "Lg") if z2 - z1 < 1 else box(x1, 15.0, z1 - 0.6, x2, 16.4, z2 + 0.6, "Lg"))
    return e


HATS = [
    ("poki_crown", "왕관", crown),
    ("poki_pokeball_cap", "몬스터볼 캡모자", pokeball_cap),
    ("poki_ribbon", "리본 머리띠", ribbon),
    ("poki_cat_ears", "고양이 귀 머리띠", cat_ears),
    ("poki_flower_crown", "꽃 화관", flower_crown),
]


def uv(col):
    cx, cy = CELL[col]
    return [cx + 0.3, cy + 0.3, cx + 0.7, cy + 0.7]


def model_json(elems):
    ys = [v for e in elems for v in (e["from"][1], e["to"][1])]
    cy = (min(ys) + max(ys)) / 2
    out = {
        "credit": "Poki server — PokiWardrobe",
        "texture_size": [16, 16],
        "textures": {"0": TEX_NAME, "particle": TEX_NAME},
        "elements": [],
        "display": {
            "head": {"translation": [0, 0, 0], "scale": [1, 1, 1]},
            "gui": {"rotation": [25, -145, 0], "translation": [0, round(-(cy - 8) * 0.62, 2), 0], "scale": [0.62, 0.62, 0.62]},
            "ground": {"translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
            "fixed": {"translation": [0, round(-(cy - 8) * 0.5, 2), 0], "scale": [0.5, 0.5, 0.5]},
        },
    }
    for e in elems:
        u = uv(e["col"])
        out["elements"].append({
            "from": [round(v, 3) for v in e["from"]],
            "to": [round(v, 3) for v in e["to"]],
            "faces": {f: {"uv": u, "texture": "#0"} for f in ("north", "east", "south", "west", "up", "down")},
        })
    return out


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for n, h in PALETTE.items():
        img.putpixel(CELL[n], hexc(h) + (255,))
    img.save(os.path.join(OUT, "hat_palette.png"))
    for fid, name, fn in HATS:
        el = fn()
        for e in el:
            for i in range(3):
                assert -16 <= e["from"][i] <= 32 and -16 <= e["to"][i] <= 32, (fid, e)
                assert e["from"][i] < e["to"][i], (fid, e)
        with open(os.path.join(OUT, fid + ".json"), "w") as f:
            json.dump(model_json(el), f, indent=1)
        print(fid, name, len(el), "elements")
