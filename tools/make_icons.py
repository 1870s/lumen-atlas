"""Generates the icon atlas and the mod logo of Lumen Atlas.

All icons are original pixel art defined below as text grids, so the atlas can be rebuilt (and changed) at any
time:  python tools/make_icons.py

Atlas layout (common/src/main/resources/assets/lumenatlas/textures/gui/icons.png, 256 x 64, 16 x 16 cells):
  row 0-1: structure glyphs in the order of StructureKinds (icon index = row * 16 + column)
  row 2:   waypoint glyphs in the order of WaypointIcon (white, tinted with the waypoint color in game)
  row 3:   UI sprites: 0 player arrow, 1 disc, 2 ring, 3 dashed ring, 4 dot, 5 pointer arrow, 6 cross
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "common/src/main/resources/assets/lumenatlas/textures/gui/icons.png"
LOGO = ROOT / "common/src/main/resources/assets/lumenatlas/icon.png"

# Shared palette. Any glyph may also define its own colors.
P = {
    ".": None,
    "k": (24, 20, 30), "K": (60, 54, 66), "w": (245, 245, 245), "g": (150, 150, 150), "G": (100, 100, 105),
    "y": (250, 210, 70), "Y": (200, 150, 40), "o": (235, 140, 50), "O": (170, 90, 30), "r": (210, 60, 50),
    "R": (130, 30, 30), "b": (120, 80, 50), "B": (80, 50, 30), "s": (225, 180, 140), "S": (180, 130, 95),
    "e": (70, 200, 90), "E": (30, 120, 60), "c": (60, 220, 220), "C": (20, 110, 120), "p": (180, 110, 240),
    "P": (110, 50, 170), "l": (110, 180, 255), "L": (40, 90, 190), "n": (240, 160, 170), "N": (190, 100, 120),
    "t": (230, 200, 130), "T": (190, 150, 80), "m": (95, 140, 60), "M": (60, 95, 40), "d": (40, 40, 48),
}

STRUCTURES = [
    # village: villager head
    """
....bbbb....
...bBBBBb...
..bBssssBb..
..BsssssSB..
..BkksskkB..
..BeksskeB..
..BsSssSsB..
..BssSSssB..
...BsSSsB...
...BSsSSB...
....BBBB....
............
""",
    # stronghold: eye of ender
    """
....EEEE....
..EEeeeeEE..
.EeeeccceeE.
.EeeckkkceE.
EeeckkkkkceE
EeeckkwkkceE
EeeckkkkkceE
.EeeckkkceE.
.EeeeccceeE.
..EEeeeeEE..
....EEEE....
............
""",
    # ancient city: warden-like sculk face
    """
...C.CC.C...
..CcCCCCcC..
.CdddddddCC.
.CdcdddcdC..
.Cdccdccdd..
CCddddddddC.
.CdkkkkkkdC.
.CddkkkkddC.
..CddddddC..
...CCddCC...
....C..C....
............
""",
    # trial chambers: trial spawner
    """
OOOOOOOOOOOO
OoKoKoKoKoKO
OKkkkkkkkkKO
OokkkykkkkoO
OKkkyoykkkKO
OokkyoykkkoO
OKkkkyykkkKO
OokkkkkkkkoO
OKkkkkkkkkKO
OoKoKoKoKoKO
OOOOOOOOOOOO
............
""",
    # mansion
    """
.....BB.....
....BBBB....
..BBBBBBBB..
.BBBBBBBBBB.
.bbbbbbbbbb.
.bwbbwwbbwb.
.bbbbbbbbbb.
.bwbbBBbbwb.
.bbbbBBbbbb.
.gggggggggg.
............
............
""",
    # pillager outpost: pillager head
    """
...GGGGGG...
..GggggggG..
..GggggggG..
..GkkkkkkG..
..GgegggegG.
..GggGGggG..
..GggGGggG..
..GgkkkkgG..
...GggggG...
....GGGG....
............
............
""",
    # desert pyramid
    """
.....tt.....
....tTTt....
....tttt....
...tooootT..
...tttttt...
..tTTTTTTt..
..tttlltttT.
.ttttlltttt.
.tooooooooT.
tttttttttttt
TTTTTTTTTTTT
............
""",
    # jungle temple
    """
....gmmg....
....gggg....
...mggggm...
...gkggkg...
..mgggggmg..
..ggggggggg.
.mggmggkggm.
.ggggkkgggg.
mgggmkkggmgg
gggggkkggggg
............
............
""",
    # ocean monument: guardian
    """
..y......y..
...CCCCCC...
.yCccccccCy.
.CccwwwcccC.
.CcwkkwcccCy
yCcwkkwcccC.
.CccwwwcccC.
.yCccccccCy.
...CCCCCC.y.
..y.y..y....
............
............
""",
    # shipwreck
    """
......B.....
......Bww...
......Bwww..
......Bww...
......B.....
.BB...B..BB.
.BbbbbbbbbB.
..BbBbbbbB..
..lBbbbBBl..
.llllBBllll.
llllllllllll
............
""",
    # nether fortress: blaze head
    """
.y........y.
..yYYYYYYy..
..YyyyyyyY..
.yYkkyykkYy.
..YykyykyY..
..YyyyyyyY..
.yYyYkkYyYy.
..YyyyyyyY..
..yYYYYYYy..
.y...yy...y.
............
............
""",
    # bastion: piglin head
    """
..NnnnnnnN..
.NnnnnnnnnN.
.NnkknnkknN.
.NnwknnwknN.
.NnnnnnnnnN.
.NnNNNNNNnN.
.NnNkNNkNnN.
.NnNNNNNNnN.
.NyNnnnnNyN.
..NNNNNNNN..
............
............
""",
    # end city: shulker
    """
.PPPPPPPPPP.
PppppppppppP
PpPPPPPPPPpP
PpPkkkkkkPpP
PpPkwwwwkPpP
PpPkwkkwkPpP
PpPkkkkkkPpP
PpPPPPPPPPpP
PppppppppppP
.PPPPPPPPPP.
............
............
""",
    # mineshaft: minecart
    """
............
............
.GG......GG.
.GggggggggG.
.GgKKKKKKgG.
.GgKKKKKKgG.
.GggggggggG.
..GGGGGGGG..
..kK....kK..
bBbBbBbBbBbB
gggggggggggg
............
""",
    # ruined portal
    """
.kk.kkkkk...
.kpppppPkk..
kkpPpppppk..
.kppPpppPkk.
.kpppPpppk..
kkppppPppk..
.kPpppppPk..
.kpppPpppkk.
.kppppppPk..
.kkkk.kkkk..
..r.r..o.r..
............
""",
    # igloo
    """
............
....wwww....
..wwwwwwww..
.wwlwwwwlww.
.wwwwwwwwww.
wwwwwwwwwwww
wwwlwkkwlwww
wwwwwkkwwwww
wwwwwkkwwwww
gggggggggggg
............
............
""",
    # witch hut: witch hat
    """
......kk....
.....kPk....
.....kPPk...
....kPPPk...
....kPPPPk..
...kPPPPPk..
...kPeeePPk.
..kPPPPPPPPk
kkPPPPPPPPPPk
.kkkkkkkkkkk.
............
............
""",
    # ocean ruins: broken column under water
    """
............
...gg.......
...gGg..g...
...gGg.ggg..
...gGg.gGg..
..lgGgllgGl.
.llgGglllll.
llggGgglllll
lLgggggglLll
LLLLLLLLLLLL
............
............
""",
    # trail ruins: decorated pot
    """
....OOOO....
.....OO.....
....OOOO....
...OooooO...
..OoOooOoO..
..OoooooOO..
..OoOooOoO..
..OoooooOO..
...OooooO...
....OOOO....
............
............
""",
    # buried treasure: chest with X
    """
............
..BBBBBBBB..
.BbbbbbbbbB.
.BbbbbbbbbB.
.BBBByyBBBB.
.BbbbyybbbB.
.BbbbbbbbbB.
.BbbbbbbbbB.
.BBBBBBBBBB.
...r....r...
....r..r....
............
""",
    # nether fossil: bone
    """
..ww......ww
.wwww....www
..wwww..www.
....wwwwww..
.....wwww...
....wwwwww..
..wwww..wwww
.www.....www
.ww.......w.
............
............
............
""",
    # abandoned camp: tent
    """
.....b......
.....bT.....
....tTTt....
...ttTkTt...
...tTTkTTt..
..ttTkkkTtt.
..tTTkkkTTt.
.ttTTkkkTTtt
.tTTTkkkTTTt
bbbbbbbbbbbb
............
............
""",
    # amethyst geode
    """
......p.....
.....pPp....
..p..pPp..p.
..Pp.pPp.pP.
..pPppPppPp.
...pPpPpPp..
...pPPPPPp..
..ggpPPPpgg.
.gGGGGGGGGGg
gGGGGGGGGGGG
............
............
""",
    # monster dungeon: spawner
    """
GGGGGGGGGGGG
GkGkGkGkGkkG
GkkkkkkkkkkG
GkkkreekkkkG
GkkkeeeekkkG
GkkkkeekkkkG
GkkkkkkkkkkG
GkkkkkkkkkkG
GkGkGkGkGkkG
GGGGGGGGGGGG
............
............
""",
    # other: question mark
    """
...wwwwww...
..ww....ww..
..ww....ww..
........ww..
.......ww...
......ww....
.....ww.....
.....ww.....
............
.....ww.....
.....ww.....
............
""",
]

WAYPOINTS = [
    # pin
    """
....wwww....
...wwwwww...
..wwwkkwww..
..wwkkkkww..
..wwkkkkww..
..wwwkkwww..
...wwwwww...
....wwww....
.....ww.....
.....ww.....
......w.....
............
""",
    # house
    """
.....ww.....
....wwww....
...wwwwww...
..wwwwwwww..
.wwwwwwwwww.
..wwwwwwww..
..ww.wwwww..
..ww.ww.ww..
..ww.ww.ww..
..wwwwwwww..
............
............
""",
    # star
    """
.....ww.....
.....ww.....
....wwww....
wwwwwwwwwwww
.wwwwwwwwww.
..wwwwwwww..
...wwwwww...
..wwwwwwww..
..www..www..
.www....www.
.ww......ww.
............
""",
    # diamond
    """
............
...wwwwww...
..wwkwwwkww.
.wwkwwwwwkww
wwwwwwwwwwww
.wwwwwwwwww.
..wwwwwwww..
...wwwwww...
....wwww....
.....ww.....
............
............
""",
    # skull
    """
...wwwwww...
..wwwwwwww..
.wwwwwwwwww.
.wwwwwwwwww.
.wkkkwwkkkw.
.wkkkwwkkkw.
.wwwwkkwwww.
..wwwwwwww..
...wkwkwkw..
...wwwwww...
............
............
""",
    # flag
    """
.wwwwwwwwww.
.wwwwwwwwww.
.wwwwwwwwww.
.wwwwwwwwww.
.wwwwwwwwww.
.ww.........
.ww.........
.ww.........
.ww.........
.ww.........
wwww........
............
""",
    # cave
    """
............
...wwwwww...
..wwwwwwww..
.wwwkkkkwww.
.wwkkkkkkww.
wwwkkkkkkwww
wwkkkkkkkkww
wwkkkkkkkkww
wwkkkkkkkkww
wwwwwwwwwwww
............
............
""",
    # portal
    """
.wwwwwwwwww.
.wkkkkkkkkw.
.wkwkkkkwkw.
.wkkwkkwkkw.
.wkkkwwkkkw.
.wkkkwwkkkw.
.wkkwkkwkkw.
.wkwkkkkwkw.
.wkkkkkkkkw.
.wwwwwwwwww.
............
............
""",
    # farm: wheat
    """
.....w......
....www.....
...w.w.w....
....www.....
...w.w.w....
....www.....
...w.w.w....
....www.....
.....w......
.....w......
.....w......
............
""",
    # sword
    """
..........ww
.........www
........www.
.......www..
......www...
.....www....
.ww.www.....
..wwww......
...ww.......
..ww.w......
.ww.........
ww..........
""",
]


def glyph(art, palette=P):
    rows = [r for r in art.strip("\n").split("\n")]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows[:12]):
        for x, ch in enumerate(row[:12]):
            col = palette.get(ch)
            if col is not None:
                img.putpixel((x + 2, y + 2), col + (255,))
    return img


def ui_sprites():
    sprites = []
    # 0 player arrow: white with dark outline, pointing up
    arrow = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(arrow)
    d.polygon([(8, 1), (14, 14), (8, 11), (2, 14)], fill=(24, 20, 30, 255))
    d.polygon([(8, 3), (12, 12), (8, 10), (4, 12)], fill=(255, 255, 255, 255))
    sprites.append(arrow)
    # 1 disc, 2 ring, 3 dashed ring (drawn at 4x and scaled down for smooth edges)
    for kind in ("disc", "ring", "dashed"):
        big = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(big)
        if kind == "disc":
            d.ellipse((2, 2, 61, 61), fill=(255, 255, 255, 255))
        elif kind == "ring":
            d.ellipse((2, 2, 61, 61), outline=(255, 255, 255, 255), width=6)
        else:
            for a in range(0, 360, 30):
                d.arc((2, 2, 61, 61), a, a + 18, fill=(255, 255, 255, 255), width=6)
        sprites.append(big.resize((16, 16), Image.LANCZOS))
    # 4 dot
    dot = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    ImageDraw.Draw(dot).ellipse((16, 16, 47, 47), fill=(255, 255, 255, 255))
    sprites.append(dot.resize((16, 16), Image.LANCZOS))
    # 5 pointer arrow (edge indicator), pointing up
    pointer = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    ImageDraw.Draw(pointer).polygon([(8, 2), (13, 11), (3, 11)], fill=(255, 255, 255, 255))
    sprites.append(pointer)
    # 6 cross
    cross = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(cross)
    d.line((3, 3, 12, 12), fill=(255, 255, 255, 255), width=2)
    d.line((12, 3, 3, 12), fill=(255, 255, 255, 255), width=2)
    sprites.append(cross)
    return sprites


def build_atlas():
    atlas = Image.new("RGBA", (256, 64), (0, 0, 0, 0))
    for i, art in enumerate(STRUCTURES):
        atlas.paste(glyph(art), ((i % 16) * 16, (i // 16) * 16))
    for i, art in enumerate(WAYPOINTS):
        atlas.paste(glyph(art), (i * 16, 32))
    for i, sprite in enumerate(ui_sprites()):
        atlas.paste(sprite, (i * 16, 48))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT)
    print("wrote", OUT, len(STRUCTURES), "structure icons,", len(WAYPOINTS), "waypoint icons")


def build_logo():
    size = 512
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Rounded dark tile.
    d.rounded_rectangle((16, 16, size - 16, size - 16), radius=96, fill=(20, 17, 30, 255))
    # Map "lens": violet gradient disc.
    lens = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lens)
    for r in range(180, 0, -2):
        t = r / 180
        col = (int(90 + 80 * (1 - t)), int(50 + 60 * (1 - t)), int(160 + 80 * (1 - t)), 255)
        ld.ellipse((256 - r, 256 - r, 256 + r, 256 + r), fill=col)
    # Map grid inside the lens.
    for k in range(-160, 161, 40):
        ld.line((256 + k, 80, 256 + k, 432), fill=(255, 255, 255, 40), width=3)
        ld.line((80, 256 + k, 432, 256 + k), fill=(255, 255, 255, 40), width=3)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((76, 76, 436, 436), fill=255)
    img.paste(lens, (0, 0), mask)
    # Glow ring.
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((70, 70, 442, 442), outline=(196, 160, 255, 255), width=14)
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(6)))
    d = ImageDraw.Draw(img)
    d.ellipse((76, 76, 436, 436), outline=(220, 200, 255, 255), width=8)
    # Compass needle.
    d.polygon([(256, 120), (292, 256), (256, 236), (220, 256)], fill=(255, 255, 255, 255))
    d.polygon([(256, 392), (292, 256), (256, 276), (220, 256)], fill=(60, 40, 90, 255))
    d.ellipse((240, 240, 272, 272), fill=(250, 210, 70, 255))
    img.resize((128, 128), Image.LANCZOS).save(LOGO)
    print("wrote", LOGO)


if __name__ == "__main__":
    build_atlas()
    build_logo()
