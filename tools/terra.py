#!/usr/bin/env python3
"""Terra watch face generator: deep-space astronomy / earth-science face with a live Earth globe.
Writes background art, moon phases, weather icons, watchface.xml, the preview, and the
companion's FieldSpec.java (the fixed-width field layout both sides share).
usage: terra.py <watchface/src/main> <dir with Barlow ttf files>"""
import math, os, random, re, shutil, sys
from xml.sax.saxutils import quoteattr
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import globe

OUT, FONTS = sys.argv[1], sys.argv[2]
RES = os.path.join(OUT, 'res')
W = 450
SS = 2
for d in ('drawable-nodpi', 'raw', 'font', 'xml', 'values'):
    os.makedirs(os.path.join(RES, d), exist_ok=True)
# start clean: old art from earlier designs must not linger
for f in os.listdir(os.path.join(RES, 'drawable-nodpi')):
    os.remove(os.path.join(RES, 'drawable-nodpi', f))
for f in os.listdir(os.path.join(RES, 'font')):
    os.remove(os.path.join(RES, 'font', f))

FONT_FILES = {
    'barlow_medium': 'Barlow-Medium.ttf',
    'barlow_semibold': 'Barlow-SemiBold.ttf',
    'barlow_sc_semibold': 'BarlowSemiCondensed-SemiBold.ttf',
}
for name, f in FONT_FILES.items():
    with open(os.path.join(FONTS, f), 'rb') as a, open(os.path.join(RES, 'font', name + '.ttf'), 'wb') as b:
        b.write(a.read())


def pil_font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, FONT_FILES[name]), int(round(size)))


def hexrgb(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# colours
WHITE = '#EEF2F7'
SOFT = '#B9C3D3'
DIM = '#7D8799'
ICE = '#8FB8FF'
GOLD = '#F2B33D'
TEAL = '#5BC8D6'
GREEN = '#7CC24A'

# ---------------------------------------------------------------- moon phases + weather icons
def moon_texture(n):
    random.seed(11)
    tex = Image.new('L', (n, n), 0)
    td = ImageDraw.Draw(tex)
    td.ellipse((0, 0, n - 1, n - 1), fill=180)
    # maria (dark seas), roughly where they are on the real near side
    for (x, y, rx, ry, s) in [(0.30, 0.32, 0.17, 0.13, 70), (0.45, 0.42, 0.12, 0.10, 65), (0.62, 0.30, 0.10, 0.09, 70),
                              (0.68, 0.48, 0.12, 0.10, 60), (0.38, 0.62, 0.14, 0.09, 60), (0.55, 0.60, 0.09, 0.07, 70),
                              (0.25, 0.50, 0.10, 0.14, 65), (0.75, 0.38, 0.06, 0.06, 75)]:
        td.ellipse(((x - rx) * n, (y - ry) * n, (x + rx) * n, (y + ry) * n), fill=180 - s)
    tex = tex.filter(ImageFilter.GaussianBlur(n * 0.025))
    td = ImageDraw.Draw(tex)
    for _ in range(int(n * 0.9)):
        x, y = random.uniform(0.08, 0.92) * n, random.uniform(0.08, 0.92) * n
        r = random.choice([0.006, 0.008, 0.012, 0.018, 0.025]) * n
        v = random.randint(-35, 30)
        td.ellipse((x - r, y - r, x + r, y + r), outline=max(0, min(255, 170 + v)))
    # Tycho with rays hint
    td.ellipse((0.42 * n, 0.82 * n, 0.47 * n, 0.87 * n), fill=230)
    noise = Image.effect_noise((n, n), 18).filter(ImageFilter.GaussianBlur(0.6))
    tex = ImageChops.add(tex, noise, 1.0, -128)
    return tex.filter(ImageFilter.GaussianBlur(0.5))


MN = 256
tex = moon_texture(MN)
tp = tex.load()
for pos in range(28):
    phi = 2 * math.pi * pos / 28
    sx, sz = math.sin(phi), -math.cos(phi)
    im = Image.new('RGBA', (MN, MN), (0, 0, 0, 0))
    px = im.load()
    for y in range(MN):
        for x in range(MN):
            u, v = (x + 0.5 - MN / 2) / (MN / 2), (y + 0.5 - MN / 2) / (MN / 2)
            rr = u * u + v * v
            if rr > 1:
                continue
            z = math.sqrt(1 - rr)
            lit = u * sx + z * sz
            k = max(0.0, min(1.0, (lit + 0.04) / 0.08))  # soft terminator
            limb = 0.75 + 0.25 * z
            b = tp[x, y] / 255
            lv = min(1.0, (0.07 + 0.93 * k) * limb * (0.25 + 1.05 * b))
            edge = min(1.0, (1 - math.sqrt(rr)) * MN / 2)  # anti-aliased edge
            c = int(255 * lv)
            px[x, y] = (c, c, min(255, int(c * 1.03) + 2), int(255 * edge))
    im.resize((120, 120), Image.LANCZOS).save(os.path.join(RES, 'drawable-nodpi', 'moon_%02d.png' % pos))


# ---------------------------------------------------------------- weather icons (96 px)
def cloud(dd, ox, oy, s, color):
    c = hexrgb(color)
    dd.ellipse((ox + 10 * s, oy + 40 * s, ox + 50 * s, oy + 80 * s), fill=c)
    dd.ellipse((ox + 30 * s, oy + 20 * s, ox + 80 * s, oy + 70 * s), fill=c)
    dd.ellipse((ox + 60 * s, oy + 40 * s, ox + 95 * s, oy + 80 * s), fill=c)
    dd.rectangle((ox + 30 * s, oy + 55 * s, ox + 78 * s, oy + 80 * s), fill=c)


def wx(kind, night=False):
    n = 384
    im = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    dd = ImageDraw.Draw(im)
    s = n / 100

    def sun_at(cx, cy, r):
        if night:
            dd.ellipse((cx - r, cy - r, cx + r, cy + r), fill=hexrgb('#E6E2C8'))
            dd.ellipse((cx - r + r * 0.6, cy - r - r * 0.3, cx + r + r * 0.6, cy + r - r * 0.3), fill=(0, 0, 0, 0))
            return
        c = hexrgb('#FFC93C')
        for i in range(8):
            a = math.pi * 2 * i / 8
            dd.line((cx + math.cos(a) * r * 1.35, cy + math.sin(a) * r * 1.35, cx + math.cos(a) * r * 1.8,
                     cy + math.sin(a) * r * 1.8), fill=c, width=int(r * 0.28))
        dd.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c)

    if kind in ('sunny', 'clear'):
        sun_at(50 * s, 50 * s, 22 * s)
    elif kind == 'partly':
        sun_at(62 * s, 35 * s, 15 * s)
        cloud(dd, 0, 15 * s, s * 0.85, '#E8ECF2')
    elif kind == 'cloudy':
        cloud(dd, 0, 5 * s, s, '#D5DAE2')
    elif kind in ('rain', 'heavy_rain', 'light_rain'):
        cloud(dd, 0, -5 * s, s, '#C9CFD8')
        cnt = {'light_rain': 2, 'rain': 3, 'heavy_rain': 4}[kind]
        for i in range(cnt):
            x = (25 + i * 50 / max(1, cnt - 1)) * s if cnt > 1 else 50 * s
            dd.line((x, 80 * s, x - 6 * s, 96 * s), fill=hexrgb('#4FA3FF'), width=int(5 * s))
    elif kind == 'thunder':
        cloud(dd, 0, -5 * s, s, '#AEB6C2')
        dd.polygon([(52 * s, 66 * s), (38 * s, 86 * s), (50 * s, 86 * s), (42 * s, 100 * s), (64 * s, 78 * s),
                    (52 * s, 78 * s), (60 * s, 66 * s)], fill=hexrgb('#FFD23C'))
    elif kind in ('snow', 'heavy_snow', 'light_snow', 'sleet'):
        cloud(dd, 0, -5 * s, s, '#D5DAE2')
        for i, x in enumerate((28, 50, 72)):
            if kind == 'light_snow' and i == 1:
                continue
            c = hexrgb('#4FA3FF') if (kind == 'sleet' and i == 1) else (255, 255, 255, 255)
            dd.ellipse((x * s - 4 * s, 86 * s - 4 * s, x * s + 4 * s, 86 * s + 4 * s), fill=c)
    elif kind in ('fog', 'mist'):
        for i, yy in enumerate((35, 52, 69)):
            dd.line((15 * s + (i % 2) * 8 * s, yy * s, 85 * s - (i % 2) * 8 * s, yy * s), fill=hexrgb('#C9CFD8'),
                    width=int(7 * s))
    elif kind == 'windy':
        c = hexrgb('#C9CFD8')
        dd.line((10 * s, 40 * s, 65 * s, 40 * s), fill=c, width=int(6 * s))
        dd.arc((55 * s, 22 * s, 85 * s, 52 * s), 180 + 90, 90 + 180 + 180, fill=c, width=int(6 * s))
        dd.line((10 * s, 60 * s, 80 * s, 60 * s), fill=c, width=int(6 * s))
        dd.line((10 * s, 78 * s, 55 * s, 78 * s), fill=c, width=int(6 * s))
    else:  # unknown
        dd.text((50 * s, 50 * s), '?', font=pil_font('barlow_semibold', 60 * s), fill=hexrgb('#AAB1BC'), anchor='mm')
    return im.resize((96, 96), Image.LANCZOS)


WX = ['unknown', 'clear', 'cloudy', 'fog', 'heavy_rain', 'heavy_snow', 'rain', 'snow', 'sunny', 'thunder',
      'sleet', 'light_snow', 'light_rain', 'mist', 'partly', 'windy']
for i, k in enumerate(WX):
    wx(k).save(os.path.join(RES, 'drawable-nodpi', 'wx_d_%02d.png' % i))
    wx(k, night=True).save(os.path.join(RES, 'drawable-nodpi', 'wx_n_%02d.png' % i))

# ---------------------------------------------------------------- watchface.xml


# ---------------------------------------------------------------- layout (450 x 450 face units)
GLOBE = (225, 225, 450)            # the globe fills the whole round face (image 450 px)
GLOBE_DIM = 0.55                   # day side dimmed so the text on top stays readable
GRAPH = (100, 347, 250, 30)        # bottom picture slot: x, y, w, h  (image 428 x 64)


# ---------------------------------------------------------------- background art
S = W * SS
bg = Image.new('RGBA', (S, S), (0, 0, 0, 255))
# deep space: navy centre fading to black
grad = Image.new('L', (S, S), 0)
gd = ImageDraw.Draw(grad)
for i in range(120):
    r = S / 2 * (1 - i / 120)
    gd.ellipse((S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r), fill=int(255 * (i / 120) ** 1.6))
navy = Image.new('RGBA', (S, S), (13, 24, 46, 255))
bg = Image.composite(navy, bg, grad)
d = ImageDraw.Draw(bg)
# stars, kept away from the text
random.seed(5)
quiet = [(30, 120, 420, 160), (30, 175, 150, 350), (300, 175, 420, 350), (100, 340, 350, 430), (90, 30, 360, 92)]
for _ in range(140):
    x, y = random.uniform(0, W), random.uniform(0, W)
    if (x - 225) ** 2 + (y - 225) ** 2 > 220 ** 2:
        continue
    if any(a <= x <= c and b <= y <= e for a, b, c, e in quiet):
        continue
    r = random.choice([0.5, 0.6, 0.8, 1.0]) * SS
    d.ellipse((x * SS - r, y * SS - r, x * SS + r, y * SS + r), fill=(255, 255, 255, random.randint(60, 200)))


def P(x, y):
    return (x * SS, y * SS)


gx, gy, gs = GLOBE
# Icons, labels and dividers go on a see-through layer drawn ABOVE the globe.
fg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(fg)
# thin dividers


def label(text, x, y, size, color, font='barlow_sc_semibold', anchor='mm'):
    d.text(P(x, y), text, font=pil_font(font, size * SS), fill=hexrgb(color), anchor=anchor)


def icon(img, x, y, size):
    sz = int(size * SS)
    im = img.resize((sz, sz), Image.LANCZOS)
    fg.alpha_composite(im, (int(x * SS - sz / 2), int(y * SS - sz / 2)))


def canvas(n=200):
    return Image.new('RGBA', (n, n), (0, 0, 0, 0))


def sun_half(color):
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb(color)
    cx, cy, r = 100, 125, 36
    for i in range(5):
        a = math.pi + math.pi * i / 4
        dd.line((cx + math.cos(a) * 50, cy + math.sin(a) * 50, cx + math.cos(a) * 78, cy + math.sin(a) * 78), fill=c,
                width=12)
    dd.pieslice((cx - r, cy - r, cx + r, cy + r), 180, 360, fill=c)
    dd.line((15, cy + 8, 185, cy + 8), fill=c, width=12)
    return im


def umbrella():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#6FA8F5')
    dd.pieslice((20, 30, 180, 170), 180, 360, fill=c)
    dd.line((100, 100, 100, 165), fill=c, width=12)
    dd.arc((100, 140, 140, 180), 0, 180, fill=c, width=12)
    return im


def drop():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb(TEAL)
    dd.ellipse((50, 80, 150, 180), fill=c)
    dd.polygon([(100, 15), (55, 115), (145, 115)], fill=c)
    return im


def leaf():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb(GREEN)
    dd.chord((30, 30, 230, 230), 180, 270, fill=c)
    dd.chord((-30, -30, 170, 170), 0, 90, fill=c)
    dd.line((40, 170, 160, 40), fill=(10, 20, 30, 255), width=8)
    return im


def mountain():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#C9A26B')
    dd.polygon([(10, 175), (80, 50), (120, 115), (145, 85), (195, 175)], fill=c)
    dd.polygon([(62, 82), (80, 50), (98, 82), (88, 76), (80, 86), (72, 76)], fill=(240, 240, 250, 255))
    return im


def hourglass():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb(GOLD)
    dd.line((45, 25, 155, 25), fill=c, width=14)
    dd.line((45, 175, 155, 175), fill=c, width=14)
    dd.polygon([(60, 35), (140, 35), (100, 100)], fill=c)
    dd.polygon([(100, 100), (60, 165), (140, 165)], outline=c, width=10)
    dd.polygon([(78, 165), (122, 165), (100, 135)], fill=c)
    return im


def wave():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#5BA8F2')
    for oy in (70, 120):
        pts = [(x, oy + 18 * math.sin(x / 200 * 2 * math.pi * 2)) for x in range(10, 191, 5)]
        dd.line(pts, fill=c, width=14, joint='curve')
    return im


d.polygon([P(326, 274), P(321, 283), P(331, 283)], fill=hexrgb('#C9B3FF'))      # moonrise: up
d.polygon([P(372, 283), P(367, 275), P(377, 275)], fill=hexrgb('#C9B3FF'))      # moonset: down
icon(sun_half(GOLD), 128, 82, 22)
icon(sun_half('#F28B3D'), 280, 82, 22)
icon(umbrella(), 52, 275, 22)
icon(drop(), 52, 305, 20)
icon(leaf(), 52, 335, 20)
icon(mountain(), 318, 301, 24)
icon(hourglass(), 318, 325, 20)
label('AQI', 72, 335, 12, GREEN, anchor='lm')


# bottom panel icons (one per screen) and its page dots
def seismo():
    im = canvas()
    dd = ImageDraw.Draw(im)
    pts = [(5, 100), (40, 100), (62, 30), (95, 170), (125, 55), (148, 130), (165, 100), (195, 100)]
    dd.line(pts, fill=hexrgb(QRED_ICON), width=14, joint='curve')
    return im


QRED_ICON = '#FF5440'


def windy():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#5EE0C8')
    for y, x1, r in ((60, 150, 22), (100, 170, 26), (140, 125, 20)):
        dd.line((15, y, x1, y), fill=c, width=14)
        dd.arc((x1 - r, y - 2 * r, x1 + r, y), -90, 160, fill=c, width=14)
    return im


def gauge():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#FFB86B')
    dd.arc((20, 30, 180, 190), 180, 360, fill=c, width=14)
    dd.line((100, 110, 145, 60), fill=c, width=14)
    dd.ellipse((88, 98, 112, 122), fill=c)
    dd.line((20, 110, 180, 110), fill=c, width=8)
    return im


def swell():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#59A8FF')
    pts = [(x, 110 - 55 * math.sin(x / 200 * 2 * math.pi * 1.5)) for x in range(10, 191, 4)]
    dd.line(pts, fill=c, width=16, joint='curve')
    dd.line([(x, y + 45) for x, y in pts], fill=c, width=10, joint='curve')
    return im


def haze():
    im = canvas()
    dd = ImageDraw.Draw(im)
    c = hexrgb('#FFD23C')
    for (x, y, r) in ((50, 60, 14), (110, 45, 10), (160, 75, 16), (80, 115, 18), (140, 135, 12), (40, 160, 10), (110, 175, 14)):
        dd.ellipse((x - r, y - r, x + r, y + r), fill=c)
    return im


for name, im in (('ic_wave', wave()), ('ic_quake', seismo()), ('ic_rain', umbrella()), ('ic_wind', windy()),
                 ('ic_press', gauge()), ('ic_sea', swell()), ('ic_air', haze())):
    im.resize((44, 44), Image.LANCZOS).save(os.path.join(RES, 'drawable-nodpi', name + '.png'))


NPAN = 7


def dots(active):
    """Page dots: 56 x 10 at the face's scale (drawn 4x, kept at 2x)."""
    im = Image.new('RGBA', (56 * 4, 10 * 4), (0, 0, 0, 0))
    dd = ImageDraw.Draw(im)
    for i in range(NPAN):
        x, y = (4 + 8 * i) * 4, 5 * 4
        if i == active:
            dd.ellipse((x - 12, y - 12, x + 12, y + 12), fill=(230, 238, 250, 255))
        else:
            dd.ellipse((x - 8, y - 8, x + 8, y + 8), outline=(150, 175, 215, 230), width=4)
    return im.resize((112, 20), Image.LANCZOS)


for i in range(NPAN):
    dots(i).save(os.path.join(RES, 'drawable-nodpi', 'dots_%d.png' % i))

def hand(w, length, tail, hub_from_top, fill):
    """A tapered hand with a dark outline: w x (length + tail) px, pivot `length` px from the top."""
    k = 4
    h = length + tail
    im = Image.new('RGBA', (w * k, h * k), (0, 0, 0, 0))
    dd = ImageDraw.Draw(im)
    def poly(grow, col):
        g = grow * k
        pts = [(w * k / 2 - 0.50 * w * k * 0.5 - g, length * k), (w * k / 2 - 0.20 * w * k * 0.5 - g, 6 * k - g),
               (w * k / 2 + 0.20 * w * k * 0.5 + g, 6 * k - g), (w * k / 2 + 0.50 * w * k * 0.5 + g, length * k),
               (w * k / 2 + 0.38 * w * k * 0.5 + g, h * k - 4 * k + g), (w * k / 2 - 0.38 * w * k * 0.5 - g, h * k - 4 * k + g)]
        dd.polygon(pts, fill=col)
        dd.ellipse((w * k / 2 - 0.2 * w * k * 0.5 - g, 6 * k - 0.2 * w * k * 0.5 - g, w * k / 2 + 0.2 * w * k * 0.5 + g,
                    6 * k + 0.2 * w * k * 0.5 + g), fill=col)
    poly(2.2, (0, 0, 0, 150))
    poly(0, hexrgb(fill))
    return im.resize((w, h), Image.LANCZOS)


HAND_H = (26, 104, 26)      # width, length above the centre, tail below it
HAND_M = (20, 150, 28)
hand(*HAND_H, 0, '#F4F7FB').save(os.path.join(RES, 'drawable-nodpi', 'hand_h.png'))
hand(*HAND_M, 0, '#FFFFFF').save(os.path.join(RES, 'drawable-nodpi', 'hand_m.png'))
_hub = Image.new('RGBA', (80, 80), (0, 0, 0, 0))
_hd = ImageDraw.Draw(_hub)
_hd.ellipse((4, 4, 76, 76), fill=(0, 0, 0, 170))
_hd.ellipse((12, 12, 68, 68), fill=hexrgb('#FF9A5C'))
_hub.resize((20, 20), Image.LANCZOS).save(os.path.join(RES, 'drawable-nodpi', 'hub.png'))

# fallback globe (shown until the companion sends the live one): daylit Earth over India
_day = Image.open(os.path.join(HERE, 'textures', 'earth_day.jpg')).convert('RGB')
_night = Image.open(os.path.join(HERE, 'textures', 'earth_night.jpg')).convert('L')
_g = globe.render(18.62, 73.8, 1792213200000, size=W, sunfrac=None, day=_day, night=_night, lit_all=True,
                  full=True, dim=GLOBE_DIM)
_g = _g.resize((S, S), Image.LANCZOS)
bg.alpha_composite(_g, (0, 0))

bg = bg.resize((W, W), Image.LANCZOS)
fg.resize((W, W), Image.LANCZOS).save(os.path.join(RES, 'drawable-nodpi', 'fg.png'), optimize=True)
cm = Image.new('L', (W, W), 0)
ImageDraw.Draw(cm).ellipse((0, 0, W - 1, W - 1), fill=255)
out_bg = Image.new('RGBA', (W, W), (0, 0, 0, 255))
out_bg.paste(bg, (0, 0), cm)
out_bg.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'bg.png'), optimize=True)

# ---------------------------------------------------------------- watchface.xml
PREVIEW = []
SAMPLES = {}


def q(s):
    return quoteattr(s)


def T(x, y, template, params=(), size=20, color=WHITE, font='barlow_sc_semibold', align='CENTER', width=None,
      preview=True, launch=None):
    """Text centred vertically on y; x is the centre, or the left / right edge for START / END."""
    if not params:
        params, template = ['"%s"' % template], '%s'
    vals = []
    for p_ in params:
        if p_ in SAMPLES:
            vals.append(SAMPLES[p_])
        elif p_.startswith('"') and p_.endswith('"') and p_.count('"') == 2:
            vals.append(p_[1:-1])
        else:
            vals = None
            break
    if vals is not None and preview:
        PREVIEW.append((x, y, template.replace('%s', '{}').format(*vals), size, color, font, align))
    h = size * 1.4
    w = width if width else size * 6
    bx = x - w / 2 if align == 'CENTER' else (x if align == 'START' else x - w)
    ps = ''.join('<Parameter expression=%s/>' % q(p) for p in params)
    return ('<PartText x="%.1f" y="%.1f" width="%.1f" height="%.1f">%s<Text align="%s">'
            '<Font family="%s" size="%.1f" color="%s"><Template><![CDATA[%s]]>%s</Template></Font></Text>'
            '</PartText>') % (bx, y - h / 2, w, h, ('<Launch target="%s"/>' % launch) if launch else '', align,
                             font, size, color, template, ps)


def nest(src, values, start):
    if len(values) == 1:
        return '"%s"' % values[0]
    return '([%s] == %d ? "%s" : %s)' % (src, start, values[0], nest(src, values[1:], start + 1))


DOW = nest('DAY_OF_WEEK', ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'], 1)
MON = nest('MONTH', ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'], 1)
ILLUM = 'round((1 - cos([MOON_PHASE_POSITION] * 0.2243995)) * 50)'
WXA = '[WEATHER.IS_AVAILABLE]'
TEMP = '%s ? round([WEATHER.TEMPERATURE]) : "--"' % WXA
RAIN = '%s ? round([WEATHER.CHANCE_OF_PRECIPITATION]) : "--"' % WXA
SAMPLES.update({DOW: 'SAT', '[DAY]': '17', MON: 'OCT', ILLUM: '35', TEMP: '24', RAIN: '20', '[SECOND_Z]': '31'})

parts = []
parts.append(T(225, 48, '%s  %s %s', [DOW, '[DAY]', MON], size=22, color=ICE, font='barlow_semibold', width=220,
               launch='CALENDAR'))

# weather icon (bitmap font by condition), temperature, rain chance
isz = 44
wx_icon = ('<Group name="wx" x="%d" y="%d" width="%d" height="%d"><Condition><Expressions>'
           '<Expression name="day"><![CDATA[[WEATHER.IS_DAY] == 1]]></Expression></Expressions>'
           '<Compare expression="day">%s</Compare><Default>%s</Default></Condition></Group>')


def wx_text(fam):
    return ('<PartText x="0" y="0" width="%d" height="%d"><Text align="CENTER"><BitmapFont family="%s" size="%d">'
            '<Template><![CDATA[%%s]]><Parameter expression="[WEATHER.CONDITION] &lt;= 15 ? [WEATHER.CONDITION] : 0"/>'
            '</Template></BitmapFont></Text></PartText>') % (isz, isz, fam, isz)


parts.append(wx_icon % (92 - isz / 2, 196 - isz / 2, isz, isz, wx_text('wx_day'), wx_text('wx_night')))
parts.append(T(95, 238, '%s°', [TEMP], size=36, color=WHITE, font='barlow_medium', width=100))
parts.append(T(70, 275, '%s%s', [RAIN, '"%"'], size=21, color=WHITE, align='START', width=80))

# moon: picture by phase position (decimal), % lit
msz = 50


def moon_img(i):
    return '<PartImage x="0" y="0" width="%d" height="%d"><Image resource="moon_%02d"/></PartImage>' % (msz, msz, i)


cond = ['<Group name="moon" x="%d" y="%d" width="%d" height="%d"><Condition><Expressions>' % (
    358 - msz / 2, 198 - msz / 2, msz, msz)]
for i in range(27):
    cond.append('<Expression name="m%d"><![CDATA[floor([MOON_PHASE_POSITION]) == %d]]></Expression>' % (i, i))
cond.append('</Expressions>')
for i in range(27):
    cond.append('<Compare expression="m%d">%s</Compare>' % (i, moon_img(i)))
cond.append('<Default>%s</Default></Condition></Group>' % moon_img(27))
MOON = ''.join(cond)
parts.append(MOON)
parts.append(T(358, 238, '%s%s', [ILLUM, '"%"'], size=21, color=WHITE, width=80))

# ---- companion values: one LONG_TEXT, fixed-width fields cut out with subText
FIELDS, COMP, EMPTY = [], [], []      # top / middle values (slot 3)
BOTTOM, EMPTY_B = [], []               # bottom panel + ISS line (slot 0: lowest id, so it gets the taps)


def D(x, y, name, width, template, size, color, align='CENTER', w=None, font='barlow_sc_semibold', sample='',
      empty='--', to=None):
    """A companion value. to: one of the bottom panel's screens (PANEL[...]) instead of always shown."""
    start = sum(f[1] for f in FIELDS)
    FIELDS.append((name, width, {'START': 'L', 'END': 'R', 'CENTER': 'C'}[align]))
    expr = 'subText([COMPLICATION.TEXT], %d, %d)' % (start, start + width)
    SAMPLES[expr] = sample
    (COMP if to is None else to).append(T(x, y, template, [expr], size=size, color=color, font=font, align=align,
                                          width=w, preview=(to is None or to is BOTTOM or to is PANEL['tide'])))
    if to is None or to is BOTTOM or to is PANEL['tide']:
        (EMPTY if to is None else EMPTY_B).append(T(x, y, template, ['"%s"' % empty], size=size, color=color,
                                                     font=font, align=align, width=w, preview=False))


# bottom panel: three screens (tide / quakes / rain), the companion picks one, a tap goes to the next
PANEL = {'tide': [], 'quake': [], 'rain': [], 'wind': [], 'press': [], 'sea': [], 'air': []}
QRED = '#FF5440'



D(142, 82, 'srise', 5, '%s', 19, WHITE, 'START', 70, sample='06:18', empty='--:--')
D(294, 82, 'sset', 5, '%s', 19, WHITE, 'START', 70, sample='18:24', empty='--:--')
D(70, 305, 'hum', 4, '%s', 21, WHITE, 'START', 80, sample='72%')
D(98, 335, 'aqi', 4, '%s', 21, WHITE, 'START', 60, sample='54')
D(358, 258, 'mage', 6, '%s', 16, SOFT, 'CENTER', 90, sample='6.2 d')
D(334, 279, 'mrise', 5, '%s', 15, '#C9B3FF', 'START', 44, sample='12:05', empty='--:--')
D(380, 279, 'mset', 5, '%s', 15, '#C9B3FF', 'START', 44, sample='23:48', empty='--:--')
D(334, 301, 'alt', 5, '%s m', 21, WHITE, 'START', 90, sample='560')
D(334, 325, 'dlen', 7, '%s', 19, WHITE, 'START', 90, sample='11h 41m', empty='--h --m')
TP, QP, RP = PANEL['tide'], PANEL['quake'], PANEL['rain']
D(108, 390, 'tplace', 10, '%s', 16, ICE, 'START', 110, sample='MUMBAI', empty='TIDE', to=TP)
D(250, 390, 'tnow', 5, '%s m', 17, WHITE, 'CENTER', 70, sample='1.8', to=TP)
D(338, 390, 'tstate', 7, '%s', 14, '#5BA8F2', 'END', 70, sample='RISING', empty='', to=TP)
D(172, 407, 'hit', 5, 'HIGH %s', 15, SOFT, 'CENTER', 100, sample='14:32', empty='--:--', to=TP)
D(282, 407, 'lot', 5, 'LOW %s', 15, SOFT, 'CENTER', 100, sample='21:48', empty='--:--', to=TP)
D(225, 298, 'iss', 10, 'ISS %s', 15, ICE, 'CENTER', 130, sample='19:42 58°', empty='--')
# quakes: strongest of the last 24 h, how long ago, how many, nearest to you
D(110, 390, 'qmag', 5, '%s', 17, QRED, 'START', 44, to=QP)
D(148, 390, 'qplace', 16, '%s', 15, WHITE, 'START', 140, to=QP)
D(340, 390, 'qago', 7, '%s', 14, SOFT, 'END', 60, to=QP)
D(172, 407, 'qcnt', 10, '%s', 15, SOFT, 'CENTER', 104, to=QP)
D(280, 407, 'qnear', 14, '%s', 15, SOFT, 'CENTER', 124, to=QP)
# rain: when it starts, how heavy, how much at most, when it stops
D(108, 390, 'rhead', 15, '%s', 17, TEAL, 'START', 176, to=RP)
D(340, 390, 'rlvl', 8, '%s', 14, SOFT, 'END', 80, to=RP)
D(172, 407, 'rmax', 15, '%s', 15, SOFT, 'CENTER', 124, to=RP)
D(282, 407, 'rend', 12, '%s', 15, SOFT, 'CENTER', 112, to=RP)
# wind / pressure / sea / air share these four fields (the companion fills them for the screen showing)
XP = PANEL['wind']
D(108, 390, 'xh', 17, '%s', 17, WHITE, 'START', 176, to=XP)
D(340, 390, 'xl', 11, '%s', 14, SOFT, 'END', 90, to=XP)
D(172, 407, 'xa', 16, '%s', 15, SOFT, 'CENTER', 112, to=XP)
D(282, 407, 'xb', 16, '%s', 15, SOFT, 'CENTER', 112, to=XP)
for k_ in ('press', 'sea', 'air'):
    PANEL[k_].extend(XP)
# text along the bezel in the empty strips at 9 and 3 o'clock (reads upward on the left, downward on
# the right, letters' tops toward the edge). Angles: 0 = 12 o'clock, clockwise.
ARCS = []
ARC_R = 204


def ARC(name, width, mid, sample, size=15, color=SOFT, span=52):
    start = sum(f[1] for f in FIELDS)
    FIELDS.append((name, width, 'C'))
    expr = 'subText([COMPLICATION.TEXT], %d, %d)' % (start, start + width)
    COMP.append(('<PartText x="0" y="0" width="450" height="450"><TextCircular centerX="225" centerY="225" '
                 'width="%d" height="%d" startAngle="%d" endAngle="%d" direction="CLOCKWISE" align="CENTER">'
                 '<Font family="barlow_sc_semibold" size="%d" color="%s"><Template><![CDATA[%%s]]>'
                 '<Parameter expression=%s/></Template></Font></TextCircular></PartText>')
                % (2 * ARC_R, 2 * ARC_R, mid - span / 2, mid + span / 2, size, color, q(expr)))
    ARCS.append((sample, mid, size, color))


D(225, 130, 'arcl', 40, '%s', 17, WHITE, 'CENTER', 330, sample='UV 6 · 1008 hPa (+1.2) · Kp 3', empty='')
ARC('arcp', 16, 276, 'GOLDEN HOUR', size=16, color='#F2B33D', span=56)
ARC('arcr', 26, 75, 'SUN 285°W · FACING 40°NE', size=13, span=44)
TOTAL = sum(f[1] for f in FIELDS)     # + 1, 2 or 3 marks at the end: which screen (see Engine.pack)

gx_, gy_, gw_, gh_ = GRAPH


def pimg(res, x, y, w, h):
    return '<PartImage x="%.1f" y="%.1f" width="%.1f" height="%.1f"><Image resource="%s"/></PartImage>' % (
        x, y, w, h, res)


def ptxt(x, y, s, size, color, align):
    return T(x, y, s, size=size, color=color, align=align, width=40, preview=False)


SCREENS = (('tide', 'ic_wave', None), ('quake', 'ic_quake', ('-24h', 'NOW')), ('rain', 'ic_rain', ('NOW', '+2h')),
           ('wind', 'ic_wind', ('-6h', '+18h')), ('press', 'ic_press', ('-6h', '+18h')),
           ('sea', 'ic_sea', ('-6h', '+18h')), ('air', 'ic_air', ('-6h', '+18h')))
for i, (name, ic, ends) in enumerate(SCREENS):
    PANEL[name].insert(0, pimg(ic, 92 - 11, 390 - 11, 22, 22) + pimg('dots_%d' % i, 197, 336, 56, 10))
    if ends:
        PANEL[name].append(ptxt(gx_ - 2, gy_ + gh_ - 3, ends[0], 10, DIM, 'END')
                           + ptxt(gx_ + gw_ + 2, gy_ + gh_ - 3, ends[1], 10, DIM, 'START'))
exprs_ = ''.join('<Expression name="p%d"><![CDATA[textLength([COMPLICATION.TEXT]) == %d]]></Expression>'
                 % (i, TOTAL + i + 1) for i in range(1, NPAN))
cmps_ = ''.join('<Compare expression="p%d">%s</Compare>' % (i, ''.join(PANEL[SCREENS[i][0]])) for i in range(1, NPAN))
panel_group = ('<Group name="panel" x="0" y="0" width="450" height="450"><Condition><Expressions>%s</Expressions>'
               '%s<Default>%s</Default></Condition></Group>') % (exprs_, cmps_, ''.join(PANEL['tide']))
BOTTOM.append(panel_group)
EMPTY_B.insert(0, pimg('ic_wave', 92 - 11, 390 - 11, 22, 22) + pimg('dots_0', 197, 336, 56, 10))

# The renderer draws a slot only inside its bounding area, and a tap goes to the slot with the lowest id
# among those under the finger. So the companion's text feeds two slots:
#   slot 3: everything above the bottom panel (no taps of its own: the globe, id 1, is lower)
#   slot 0: the bottom panel + ISS line; lowest id, so a tap there always reaches it (next screen)
SPLIT = 336


def data_slot_xml(slot_id, name, top, height, comp, empty):
    return ('<ComplicationSlot x="0" y="0" width="450" height="450" slotId="%d" displayName="%s" '
            'supportedTypes="LONG_TEXT EMPTY" isCustomizable="TRUE">'
            '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
            'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.DataService" '
            'primaryProviderType="LONG_TEXT"/>'
            '<BoundingBox x="0" y="%d" width="450" height="%d"/>'
            '<Complication type="LONG_TEXT">%s</Complication>'
            '<Complication type="EMPTY">%s</Complication>'
            '<Variant mode="AMBIENT" target="alpha" value="0"/>'
            '</ComplicationSlot>') % (slot_id, name, top, height, ''.join(comp), ''.join(empty))


data_slot = data_slot_xml(3, 'Astro data', 0, SPLIT, COMP, EMPTY)
panel_slot = data_slot_xml(0, 'Astro panel', SPLIT, 450 - SPLIT, BOTTOM, EMPTY_B)


def image_slot(slot_id, name, provider, x, y, w, h, bound=None, img_at=None):
    """bound: tap area (x, y, w, h) inside the slot; img_at: where the picture goes inside the slot."""
    ix, iy, iw, ih = img_at or (0, 0, w, h)
    img = ('<PartImage x="%d" y="%d" width="%d" height="%d"><Image resource="[COMPLICATION.%%s]"/></PartImage>'
           % (ix, iy, iw, ih))
    bx, by, bw, bh = bound or (0, 0, w, h)
    return ('<ComplicationSlot x="%d" y="%d" width="%d" height="%d" slotId="%d" displayName="%s" '
            'supportedTypes="SMALL_IMAGE PHOTO_IMAGE EMPTY" isCustomizable="TRUE">'
            '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
            'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.%s" primaryProviderType="SMALL_IMAGE"/>'
            '<BoundingBox x="%d" y="%d" width="%d" height="%d"/>'
            '<Complication type="SMALL_IMAGE">%s</Complication>'
            '<Complication type="PHOTO_IMAGE">%s</Complication>'
            '<Variant mode="AMBIENT" target="alpha" value="0"/>'
            '</ComplicationSlot>') % (x, y, w, h, slot_id, name, provider, bx, by, bw, bh, img % 'SMALL_IMAGE',
                                      img % 'PHOTO_IMAGE')


# globe: whole face (drawn only inside its bounding area)
globe_slot = image_slot(1, 'Astro globe', 'GlobeService', gx - gs / 2, gy - gs / 2, gs, gs)
graph_slot = image_slot(2, 'Astro panel picture', 'TideGraphService', *GRAPH)


# battery: very thin ring around the edge, clockwise from 12 o'clock, red at 20 % or less
def arc(start, end, color, thick, expr=None, launch=None):
    tr = '<Transform target="endAngle" value=%s/>' % q(expr) if expr else ''
    return ('<PartDraw x="0" y="0" width="450" height="450">%s<Arc centerX="225" centerY="225" width="446" '
            'height="446" startAngle="%d" endAngle="%d"><Stroke color="%s" thickness="%.1f" cap="ROUND"/>%s'
            '</Arc></PartDraw>') % (('<Launch target="%s"/>' % launch) if launch else '', start, end, color, thick, tr)


RING = '360 * [BATTERY_PERCENT] / 100'
battery = (arc(0, 360, '#1A2130', 1.8, launch='BATTERY_STATUS') +
           '<Group name="batt" x="0" y="0" width="450" height="450"><Condition><Expressions>'
           '<Expression name="low"><![CDATA[[BATTERY_PERCENT] <= 20]]></Expression></Expressions>'
           '<Compare expression="low">%s</Compare><Default>%s</Default></Condition></Group>') % (
    arc(0, 360, '#E5484D', 1.8, RING), arc(0, 360, '#5FA2F5', 1.8, RING))

def analog(tint=None):
    hh, mh = HAND_H, HAND_M
    t = (' tintColor="%s"' % tint) if tint else ''
    return ('<AnalogClock x="0" y="0" width="450" height="450">'
            '<HourHand resource="hand_h" x="%d" y="%d" width="%d" height="%d" pivotX="0.5" pivotY="%.4f"%s/>'
            '<MinuteHand resource="hand_m" x="%d" y="%d" width="%d" height="%d" pivotX="0.5" pivotY="%.4f"%s/>'
            '</AnalogClock>') % (225 - hh[0] / 2, 225 - hh[1], hh[0], hh[1] + hh[2], hh[1] / (hh[1] + hh[2]), t,
                                 225 - mh[0] / 2, 225 - mh[1], mh[0], mh[1] + mh[2], mh[1] / (mh[1] + mh[2]), t)


clock = analog() + '<PartImage x="215" y="215" width="20" height="20"><Image resource="hub"/></PartImage>'
amb_clock = analog('#C8CED8')
_n = len(PREVIEW)
AMB = T(225, 60, '%s  %s %s', [DOW, '[DAY]', MON], size=22, color='#7D8799', font='barlow_semibold', width=220)
del PREVIEW[_n:]


def bitmap_font(name, prefix, count, size):
    out = ['<BitmapFont name="%s">' % name]
    for i in range(count):
        tag = 'Character' if i < 10 else 'Word'
        out.append('<%s name="%d" resource="%s%02d" width="%d" height="%d"/>' % (tag, i, prefix, i, size, size))
    out.append('</BitmapFont>')
    return ''.join(out)


xml = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<WatchFace width="450" height="450">',
       '<Metadata key="CLOCK_TYPE" value="ANALOG"/>',
       '<Metadata key="PREVIEW_TIME" value="10:08:32"/>',
       '<BitmapFonts>', bitmap_font('wx_day', 'wx_d_', 16, 96), bitmap_font('wx_night', 'wx_n_', 16, 96),
       '</BitmapFonts>',
       '<Scene backgroundColor="#FF000000">',
       '<Group name="back" x="0" y="0" width="450" height="450">',
       '<Variant mode="AMBIENT" target="alpha" value="0"/>',
       '<PartImage x="0" y="0" width="450" height="450"><Image resource="bg"/></PartImage>',
       '</Group>',
       globe_slot,
       '<Group name="active" x="0" y="0" width="450" height="450">',
       '<Variant mode="AMBIENT" target="alpha" value="0"/>',
       '<PartImage x="0" y="0" width="450" height="450"><Image resource="fg"/></PartImage>',
       ''.join(parts), battery, clock,
       '</Group>',
       graph_slot, panel_slot, data_slot,
       '<Group name="ambient" x="0" y="0" width="450" height="450" alpha="0">',
       '<Variant mode="AMBIENT" target="alpha" value="255"/>',
       AMB, amb_clock,
       '<Group name="moon_amb" x="0" y="0" width="450" height="450" alpha="150">'
       + MOON.replace('<Group name="moon" x="%d" y="%d"' % (358 - msz / 2, 198 - msz / 2),
                      '<Group name="moon2" x="%d" y="%d"' % (225 - msz / 2, 330)) + '</Group>',
       '</Group>',
       '</Scene>', '</WatchFace>']
XML = '\n'.join(xml)
XML = re.sub(r'\b(x|y|width|height)="(-?\d+(?:\.\d+)?)"', lambda mm: '%s="%d"' % (mm.group(1), round(float(mm.group(2)))), XML)
with open(os.path.join(RES, 'raw', 'watchface.xml'), 'w') as f:
    f.write(XML)

# ---------------------------------------------------------------- FieldSpec.java for the companion
spec_dir = os.path.join(HERE, '..', 'companion', 'src', 'main', 'java', 'com', 'dmk', 'astrocompanion')
if os.path.isdir(spec_dir):
    with open(os.path.join(spec_dir, 'FieldSpec.java'), 'w') as f:
        f.write('package com.dmk.astrocompanion;\n\n'
                '/** Generated by tools/terra.py: the watch face cuts these fixed-width fields out of the text. */\n'
                'final class FieldSpec {\n'
                '    static final String[] NAMES = {%s};\n'
                '    static final int[] WIDTHS = {%s};\n'
                '    /** L = left aligned (pad right), R = right aligned, C = centred. */\n'
                '    static final String ALIGN = "%s";\n\n'
                '    private FieldSpec() {}\n}\n' % (
                    ', '.join('"%s"' % f[0] for f in FIELDS), ', '.join(str(f[1]) for f in FIELDS),
                    ''.join(f[2] for f in FIELDS)))

# ---------------------------------------------------------------- preview
prev = out_bg.copy()
live = globe.render(18.62, 73.8, 1792210000000, size=W, sunfrac=0.18, day=_day, night=_night, full=True,
                    dim=GLOBE_DIM)
prev.alpha_composite(live, (0, 0))
prev.alpha_composite(Image.open(os.path.join(RES, 'drawable-nodpi', 'fg.png')).convert('RGBA'), (0, 0))
pd = ImageDraw.Draw(prev)
gxx, gyy, gw, gh = GRAPH
pts = [(gxx + 4 + i * (gw - 8) / 60, gyy + gh / 2 - (gh / 2 - 4) * math.cos(2 * math.pi * i / 60 * 1.9 + 0.6))
       for i in range(61)]
pd.line(pts, fill=hexrgb('#5BA8F2'), width=2)
pd.ellipse((pts[20][0] - 3, pts[20][1] - 3, pts[20][0] + 3, pts[20][1] + 3), fill=(255, 255, 255, 255))
for img_, (w_, ln_, tl_), ang_ in ((Image.open(os.path.join(RES, 'drawable-nodpi', 'hand_h.png')), HAND_H, 10 * 30 + 4),
                                   (Image.open(os.path.join(RES, 'drawable-nodpi', 'hand_m.png')), HAND_M, 8 * 6)):
    big = Image.new('RGBA', (400, 400), (0, 0, 0, 0))
    big.alpha_composite(img_.convert('RGBA'), (200 - w_ // 2, 200 - ln_))
    big = big.rotate(-ang_, resample=Image.BICUBIC, center=(200, 200))
    prev.alpha_composite(big, (25, 25))
prev.alpha_composite(Image.open(os.path.join(RES, 'drawable-nodpi', 'hub.png')).convert('RGBA'), (215, 215))
mi = Image.open(os.path.join(RES, 'drawable-nodpi', 'moon_09.png')).resize((msz, msz), Image.LANCZOS)
prev.alpha_composite(mi, (358 - msz // 2, 198 - msz // 2))
wi = Image.open(os.path.join(RES, 'drawable-nodpi', 'wx_d_14.png')).resize((isz, isz), Image.LANCZOS)
prev.alpha_composite(wi, (92 - isz // 2, 196 - isz // 2))
for res_, (x_, y_, w_, h_) in (('ic_wave', (81, 379, 22, 22)), ('dots_0', (197, 336, 56, 10))):
    im_ = Image.open(os.path.join(RES, 'drawable-nodpi', res_ + '.png')).convert('RGBA').resize((w_, h_), Image.LANCZOS)
    prev.alpha_composite(im_, (x_, y_))
def arc_text(img, text, wff_mid, r_base, size, color):
    f = pil_font('barlow_sc_semibold', size)
    widths = [f.getlength(ch) for ch in text]
    R = r_base + size * 0.35
    ang = math.radians(wff_mid - 90) - sum(widths) / R / 2
    for ch, w in zip(text, widths):
        a_ = ang + w / R / 2
        tile = Image.new('RGBA', (size * 2, size * 2), (0, 0, 0, 0))
        ImageDraw.Draw(tile).text((size, size), ch, font=f, fill=hexrgb(color), anchor='mm')
        rot = tile.rotate(-(math.degrees(a_) + 90), resample=Image.BICUBIC)
        cx_, cy_ = 225 + R * math.cos(a_), 225 + R * math.sin(a_)
        img.alpha_composite(rot, (int(cx_ - rot.width / 2), int(cy_ - rot.height / 2)))
        ang += w / R


for (txt_, mid_, size_, color_) in ARCS:
    arc_text(prev, txt_, mid_, ARC_R, size_, color_)
for (x, y, txt, size, color, font, align) in PREVIEW:
    anchor = 'mm' if align == 'CENTER' else ('lm' if align == 'START' else 'rm')
    pd.text((x, y), txt, font=pil_font(font, size), fill=hexrgb(color), anchor=anchor)
pd.arc((2, 2, 448, 448), -90, -90 + 280, fill=hexrgb('#5FA2F5'), width=2)
prev.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'preview.png'), optimize=True)

# ---------------------------------------------------------------- the phone app reuses the art
PHONE = os.path.join(HERE, '..', 'phone', 'src', 'main', 'res')
if os.path.isdir(os.path.dirname(PHONE)):
    for d_ in ('drawable-nodpi', 'font'):
        os.makedirs(os.path.join(PHONE, d_), exist_ok=True)
    for f_ in sorted(os.listdir(os.path.join(RES, 'drawable-nodpi'))):
        if f_.startswith('wx_'):
            shutil.copy(os.path.join(RES, 'drawable-nodpi', f_), os.path.join(PHONE, 'drawable-nodpi', f_))
    shutil.copy(os.path.join(RES, 'drawable-nodpi', 'preview.png'), os.path.join(PHONE, 'drawable-nodpi', 'watch_preview.png'))
    for name in FONT_FILES:
        shutil.copy(os.path.join(RES, 'font', name + '.ttf'), os.path.join(PHONE, 'font', name + '.ttf'))
print('ok')
