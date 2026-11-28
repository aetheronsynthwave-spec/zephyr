"""Builds the black and white Zephyr wordmark and mod icon (needs Microsoft Edge)."""
import math, os, pathlib, subprocess

HERE = pathlib.Path(__file__).resolve().parent
# Same outline as MARK in gui/render/Ui.java
MARK = [(2,30),(26,10),(98,8),(42,72),(70,72),(98,66),(76,90),(2,92),(58,28),(34,28)]

def poly(pts, fill="#fff"):
    return f'<polygon fill="{fill}" points="' + " ".join(f"{x:.1f},{y:.1f}" for x, y in pts) + '"/>'

def cut(a, b, w0, w1):
    """One brush-cut blade: slanted heel at a, razor point past b."""
    dx, dy = b[0]-a[0], b[1]-a[1]; d = math.hypot(dx, dy); ux, uy = dx/d, dy/d; nx, ny = -uy, ux
    def at(p, along, side): return (p[0]+ux*along+nx*side, p[1]+uy*along+ny*side)
    return poly([at(a,-w0*.7,w0/2), at(b,-w1*.3,w1/2), at(b,w1*.9,-w1*.5), at(a,w0*.3,-w0/2)])

CUTS = [
    ((0,8),(70,6),17,13), ((70,6),(6,94),21,16), ((4,94),(70,93),16,10),            # Z
    ((106,2),(101,98),17,11), ((102,8),(148,5),14,8), ((102,50),(140,48),12,7), ((101,93),(150,90),14,8),  # E
    ((183,0),(180,104),18,5), ((180,7),(231,14),14,11), ((231,12),(222,48),15,10), ((224,46),(184,57),12,7),  # P
    ((262,0),(260,100),17,7), ((320,-4),(314,104),18,5), ((258,52),(316,47),11,8),  # H
    ((338,0),(369,48),18,11), ((410,-5),(368,50),15,10), ((369,45),(367,104),17,4),  # Y
    ((435,0),(432,104),19,5), ((431,7),(487,12),15,12), ((486,9),(478,46),17,11), ((481,44),(438,58),13,9),  # R
]

def tail():
    """The R's leg, swept far to the right and thinning to a point."""
    p = ((444,53),(500,92),(580,90),(720,120))
    top, bottom = [], []
    for k in range(25):
        t = k/24; u = 1-t
        x, y = (u**3*p[0][i]+3*u*u*t*p[1][i]+3*u*t*t*p[2][i]+t**3*p[3][i] for i in (0,1))
        w = 21*(1-t)**.8
        top.append((x, y-w/2)); bottom.append((x, y+w/2))
    return poly(top + bottom[::-1]) + poly([(520,97),(590,102),(668,119),(584,108)])  # dry-brush sliver

def tiny(x, y, text, size, gap):
    """Very small Chinese caption, centred on x."""
    return (f'<text x="{x + gap/2}" y="{y}" fill="#fff" font-family="Microsoft YaHei, SimHei, sans-serif" '
            f'font-size="{size}" letter-spacing="{gap}" text-anchor="middle">{text}</text>')

TOP, BOTTOM = "西风之神", "疾风如刃"   # "god of the west wind" / "swift wind like a blade"

def render(name, w, h, body, png, crop=None):
    svg = HERE / (name + ".svg")
    back = "" if crop else f'<rect width="{w}" height="{h}" fill="#000"/>'
    view = f' viewBox="{crop[0]} {crop[1]} {w} {h}"' if crop else ""
    svg.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}"{view}>'
                   f'{back}{body}</svg>\n', encoding="utf-8")
    edge = next(p for p in [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                            r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"] if os.path.exists(p))
    subprocess.run([edge, "--headless", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    f"--window-size={w},{h}", "--default-background-color=00000000", f"--screenshot={png}", svg.as_uri()],
                   check=True, capture_output=True, timeout=60)

word = '<g transform="translate(110 100) scale(1.6) translate(24 0) skewX(-14)">' + "".join(cut(*c) for c in CUTS) + tail() + '</g>'
word += tiny(526, 74, TOP, 11, 9) + tiny(526, 306, BOTTOM, 11, 9)
render("zephyr-wordmark", 1360, 360, word, HERE / "zephyr-wordmark.png")
# The same wordmark, white on transparent and cropped, for the in-game menu.
ASSETS = HERE.parent / "src/main/resources/assets/zephyr"
(ASSETS / "textures/gui").mkdir(parents=True, exist_ok=True)
render("zephyr-wordmark-ingame", 1180, 262, word, ASSETS / "textures/gui/wordmark.png", crop=(90, 55))
icon = '<g transform="translate(28 28) scale(2)">' + poly(MARK) + '</g>' + tiny(128, 30, TOP, 10, 5) + tiny(128, 237, BOTTOM, 10, 5)
render("zephyr-icon", 256, 256, icon, ASSETS / "icon.png")
