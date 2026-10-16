#!/usr/bin/env python3
"""Generates the AstroWatchFace resources: background art, moon phases, weather icons,
watchface.xml and the preview. All positions are given in the reference design's pixels
(ref.png, 1254 px) and mapped onto the 450 x 450 watch face."""
import math, os, random, sys
from xml.sax.saxutils import escape, quoteattr
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = sys.argv[1]  # .../watchface/src/main
FONTS = sys.argv[2]  # dir with Barlow ttfs
RES = os.path.join(OUT, 'res')
for d in ('drawable-nodpi', 'raw', 'font', 'xml', 'values'):
    os.makedirs(os.path.join(RES, d), exist_ok=True)

W = 450
SS = 2  # supersampling for the art
CX, CY, R = 627, 617, 485  # display circle in the reference image
K = (W / 2) / R


def m(x, y):
    return ((x - CX) * K + W / 2, (y - CY) * K + W / 2)


def mx(x):
    return (x - CX) * K + W / 2


def my(y):
    return (y - CY) * K + W / 2


def ms(v):  # size in ref px -> face px
    return v * K


FONT_FILES = {
    'barlow_medium': 'Barlow-Medium.ttf',
    'barlow_semibold': 'Barlow-SemiBold.ttf',
    'barlow_sc_regular': 'BarlowSemiCondensed-Regular.ttf',
    'barlow_sc_medium': 'BarlowSemiCondensed-Medium.ttf',
}
for name, f in FONT_FILES.items():
    with open(os.path.join(FONTS, f), 'rb') as src, open(os.path.join(RES, 'font', name + '.ttf'), 'wb') as dst:
        dst.write(src.read())


def pil_font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, FONT_FILES[name]), int(round(size)))


# ---------------------------------------------------------------- colours
BLUE = '#4F8DF5'
TIDE = '#5BA8F2'
GREEN = '#7CC24A'
PURPLE = '#9A86F2'
ORANGE = '#F2B33D'
WHITE = '#E9ECF1'
GREY = '#AAB1BC'
DIM = '#6B7280'
PANEL = (15, 18, 23)
EDGE = (40, 45, 53)
GUTTER = (4, 5, 7)


def hexrgb(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# ---------------------------------------------------------------- background art
S = W * SS
bg = Image.new('RGBA', (S, S), (0, 0, 0, 255))
d = ImageDraw.Draw(bg)


def P(x, y):  # ref -> supersampled canvas
    a, b = m(x, y)
    return (a * SS, b * SS)


def poly(points, fill=None, outline=None, width=1):
    d.polygon([P(*p) for p in points], fill=fill)
    if outline:
        pts = [P(*p) for p in points] + [P(*points[0])]
        d.line(pts, fill=outline, width=width * SS, joint='curve')


def line(points, fill, width=1):
    d.line([P(*p) for p in points], fill=fill, width=int(width * SS), joint='curve')


# disc
d.ellipse((0, 0, S - 1, S - 1), fill=(11, 13, 17, 255))
# subtle radial vignette
vig = Image.new('L', (S, S), 0)
vd = ImageDraw.Draw(vig)
for i in range(60):
    r = S / 2 * (1 - i / 60)
    vd.ellipse((S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r), fill=int(i * 0.5))
bg = Image.composite(Image.new('RGBA', (S, S), (22, 26, 32, 255)), bg, vig)
d = ImageDraw.Draw(bg)

# moon window: starfield, clipped to its polygon + circle
moon_poly = [(300, 120), (954, 120), (892, 251), (842, 329), (412, 329), (362, 251)]
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).polygon([P(*p) for p in moon_poly], fill=255)
circle = Image.new('L', (S, S), 0)
ImageDraw.Draw(circle).ellipse((0, 0, S - 1, S - 1), fill=255)
mask = ImageChops.multiply(mask, circle)
sky = Image.new('RGBA', (S, S), (0, 0, 0, 0))
sd = ImageDraw.Draw(sky)
top, bot = my(130) * SS, my(330) * SS
for yy in range(int(top), int(bot) + 1):
    t = (yy - top) / max(1, bot - top)
    c = (int(14 + 6 * t), int(22 + 4 * t), int(40 - 14 * t), 255)
    sd.line((0, yy, S, yy), fill=c)
random.seed(7)
for _ in range(70):
    x = random.uniform(mx(370), mx(885)) * SS
    y = random.uniform(my(150), my(325)) * SS
    r = random.choice([0.5, 0.6, 0.8, 1.0, 1.4]) * SS * 0.6
    a = random.randint(90, 230)
    sd.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, a))
for (sx, sy) in [(530, 195), (803, 186), (585, 278), (700, 300), (502, 232)]:  # sparkles
    x, y = P(sx, sy)
    for ang in (0, 90):
        dx, dy = math.cos(math.radians(ang)) * 3.2 * SS, math.sin(math.radians(ang)) * 3.2 * SS
        sd.line((x - dx, y - dy, x + dx, y + dy), fill=(255, 255, 255, 200), width=1)
bg.paste(sky, (0, 0), ImageChops.multiply(mask, sky.getchannel('A')))
d = ImageDraw.Draw(bg)

# center panels
time_poly = [(410, 343), (845, 343), (868, 366), (868, 552), (845, 575), (410, 575), (387, 552), (387, 366)]
tide_poly = [(400, 585), (855, 585), (880, 612), (880, 840), (855, 866), (400, 866), (375, 840), (375, 612)]
sun_poly = [(410, 875), (845, 875), (905, 960), (905, 1110), (350, 1110), (350, 960)]
poly(time_poly, fill=(14, 17, 22, 255))
poly(tide_poly, fill=(13, 18, 26, 255))
sunmask = Image.new('L', (S, S), 0)
ImageDraw.Draw(sunmask).polygon([P(*p) for p in sun_poly], fill=255)
sunmask = ImageChops.multiply(sunmask, circle)
bg.paste(Image.new('RGBA', (S, S), (13, 15, 19, 255)), (0, 0), sunmask)
d = ImageDraw.Draw(bg)

# gutters between panels (dark line with a faint lighter edge)
def gutter(points, w=4.5):
    line(points, GUTTER + (255,), w)
    line(points, EDGE + (255,), 1.0)


gutter(moon_poly[2:] + [])
gutter([(362, 251), (300, 120)])
gutter([(892, 251), (954, 120)])
for pl in (time_poly, tide_poly):
    gutter(pl + [pl[0]])
gutter([(150, 960), (350, 960), (410, 875), (845, 875), (905, 960), (1110, 960)], 4.5)
# side separators
gutter([(150, 532), (387, 532)])
gutter([(150, 692), (375, 692)])
gutter([(868, 518), (1110, 518)])
gutter([(880, 692), (1110, 692)])
# thin inner separators
line([(1065, 599), (890, 599)], EDGE + (255,), 1)
line([(200, 532), (200, 532)], EDGE + (255,), 1)
# date pill
pill = [(492, 349), (762, 349), (748, 392), (506, 392)]
poly(pill, fill=(20, 26, 34, 255), outline=EDGE + (255,), width=1)
line([(627, 358), (627, 384)], (60, 66, 76, 255), 1)
# sunrise / sunset divider
line([(627, 534), (627, 558)], (60, 66, 76, 255), 1)
# left panel divider under weather
line([(200, 520), (370, 520)], (35, 40, 48, 255), 1)
# right: under elevation
line([(890, 518), (1060, 518)], (35, 40, 48, 255), 1)
# sun panel dividers
line([(557, 912), (557, 998)], (60, 66, 76, 255), 1)
line([(685, 912), (685, 998)], (60, 66, 76, 255), 1)

# outer edge ring
d.ellipse((1, 1, S - 2, S - 2), outline=(30, 34, 40, 255), width=3 * SS)

# ---- tide graph grid
gx0, gx1, gy0, gy1 = 432, 836, 673, 752
for yv in (673, 709, 745):
    line([(gx0, yv), (gx1, yv)], (32, 44, 60, 255), 0.8)
for xv in (436, 535, 634, 734, 832):
    line([(xv, gy0), (xv, 752)], (26, 36, 50, 255), 0.6)

# ---- compass arc ticks along the bottom
ccx, ccy, crr = 627, 617, 440
for deg in range(-60, 61, 4):
    if -14 <= deg <= 14:
        continue
    a = math.radians(90 + deg)
    long = deg % 20 == 0
    r1 = crr - (14 if long else 8)
    x0, y0 = ccx + crr * math.cos(a), ccy + crr * math.sin(a)
    x1, y1 = ccx + r1 * math.cos(a), ccy + r1 * math.sin(a)
    line([(x0, y0), (x1, y1)], (150, 156, 166, 255) if long else (95, 100, 110, 255), 1.0 if long else 0.7)
# hub for heading
hub = [(560, 1032), (580, 1012), (674, 1012), (694, 1032)]
line(hub, (80, 86, 96, 255), 1)
# red pointer
poly([(616, 1013), (638, 1013), (627, 1001)], fill=(222, 52, 40, 255))
poly([(608, 862), (626, 862), (617, 852)], fill=(222, 52, 40, 255))

bg_draw = d


# ---- static text labels (drawn into the art)
def label(text, x, y, size, color, font='barlow_sc_medium', anchor='mm', spacing=0):
    f = pil_font(font, size * SS)
    X, Y = P(x, y)
    if spacing:
        # letter-spaced caps
        total = sum(f.getlength(c) for c in text) + spacing * SS * (len(text) - 1)
        if anchor[0] == 'm':
            X -= total / 2
        elif anchor[0] == 'r':
            X -= total
        for c in text:
            bg_draw.text((X, Y), c, font=f, fill=hexrgb(color) if isinstance(color, str) else color, anchor='l' + anchor[1])
            X += f.getlength(c) + spacing * SS
    else:
        bg_draw.text((X, Y), text, font=f, fill=hexrgb(color) if isinstance(color, str) else color, anchor=anchor)


label('MOON PHASE', 627, 162, 10.5, '#C9CED6', spacing=0.6)
label('WEATHER', 322, 337, 10.5, BLUE, spacing=0.4)
label('ELEVATION', 927, 337, 10.5, GREEN, spacing=0.4)
label('ASTRONOMY', 269, 724, 10.5, PURPLE, spacing=0.4)
label('DEEP SKY', 975, 724, 10.5, PURPLE, spacing=0.4)
label('BARO', 900, 623, 9.5, GREY, anchor='lm')
label('MAX', 897, 543, 9.5, GREEN, anchor='lm')
label('MIN', 897, 571, 9.5, GREEN, anchor='lm')
label('TIDE', 590, 600, 11, TIDE, anchor='rm', spacing=0.5)
bg_draw.ellipse([*P(624, 597), *P(630, 603)], fill=hexrgb('#9AA3AE'))
label('SUN', 610, 892, 10.5, ORANGE, anchor='lm', spacing=0.5)
label('Azimuth', 474, 928, 9.5, GREY)
label('Day Length', 761, 928, 9.5, GREY)
label('UV', 621, 922, 9, ORANGE)
label('HIGH', 504, 804, 10, TIDE)
label('LOW', 502, 836, 10, TIDE)
label('ISS', 228, 766, 11.5, WHITE, anchor='lm')
label('W', 490, 1013, 9, GREY)
label('E', 754, 1013, 9, '#7FA6D8')
for txt, yv in (('2.0', 673), ('1.0', 709), ('0', 745)):
    label(txt, 416, yv, 8, DIM, anchor='rm')
for txt, xv in (('00', 436), ('06', 535), ('12', 634), ('18', 734), ('24', 832)):
    label(txt, xv, 770, 8, DIM)


# ---- small icons
def icon_canvas(size):
    return Image.new('RGBA', (size, size), (0, 0, 0, 0))


def paste_icon(img, x, y, size_ref):
    sz = int(ms(size_ref) * SS)
    im = img.resize((sz, sz), Image.LANCZOS)
    X, Y = P(x, y)
    bg.alpha_composite(im, (int(X - sz / 2), int(Y - sz / 2)))


def sun_img(color=ORANGE, half=False, rays=8):
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb(color)
    cx, cy = n / 2, n / 2 + (25 if half else 0)
    r = 34
    for i in range(rays):
        a = math.pi * 2 * i / rays
        if half and math.sin(a) > 0.05:
            continue
        dd.line((cx + math.cos(a) * (r + 14), cy + math.sin(a) * (r + 14), cx + math.cos(a) * (r + 40),
                 cy + math.sin(a) * (r + 40)), fill=c, width=12)
    if half:
        dd.pieslice((cx - r, cy - r, cx + r, cy + r), 180, 360, fill=c)
        dd.line((20, cy + 4, n - 20, cy + 4), fill=c, width=12)
    else:
        dd.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c)
    return im


def crescent_img(color='#D8DCE3'):
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    dd.ellipse((30, 30, 170, 170), fill=hexrgb(color))
    dd.ellipse((70, 10, 200, 140), fill=(0, 0, 0, 0))
    return im


def moonset_img():
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#D8DCE3')
    dd.pieslice((50, 60, 150, 160), 180, 360, fill=c)
    dd.pieslice((80, 40, 170, 140), 180, 360, fill=(0, 0, 0, 0))
    for yy, x0, x1 in ((125, 20, 180), (150, 45, 155)):
        dd.line((x0, yy, x1, yy), fill=c, width=12)
    return im


def drop_img():
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#4F9BF5')
    dd.ellipse((50, 80, 150, 180), fill=c)
    dd.polygon([(100, 15), (55, 115), (145, 115)], fill=c)
    return im


def wind_img():
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#C9CED6')
    dd.line((20, 70, 130, 70), fill=c, width=12)
    dd.arc((110, 30, 170, 90), 270, 90 + 360 - 180, fill=c, width=12)
    dd.line((20, 110, 170, 110), fill=c, width=12)
    dd.line((20, 150, 110, 150), fill=c, width=12)
    dd.arc((90, 130, 150, 190), 180 + 90, 90, fill=c, width=12)
    return im


def arrow_img(color='#6FA0F0'):
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    dd.polygon([(170, 30), (40, 100), (95, 110), (110, 165)], fill=hexrgb(color))
    return im


def mountain_img():
    n = 240
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb(GREEN)
    dd.line([(10, 200), (90, 70), (130, 130), (160, 95), (230, 200), (10, 200)], fill=c, width=12, joint='curve')
    dd.line([(70, 102), (90, 120), (110, 102)], fill=c, width=10)
    return im


def satellite_img():
    n = 240
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#8FA7D8')
    body = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.rectangle((100, 95, 140, 145), fill=hexrgb('#C9D3E6'))
    for x0 in (20, 150):
        bd.rectangle((x0, 100, x0 + 70, 140), fill=c)
        for k in range(1, 4):
            bd.line((x0 + k * 17, 100, x0 + k * 17, 140), fill=(30, 40, 60, 255), width=3)
    bd.line((90, 120, 150, 120), fill=hexrgb('#C9D3E6'), width=6)
    return body.rotate(-35, resample=Image.BICUBIC)


def galaxy_img():
    n = 400
    im = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    px = im.load()
    random.seed(3)
    for y in range(n):
        for x in range(n):
            u, v = (x - n / 2) / (n / 2), (y - n / 2) / (n / 2)
            # tilted ellipse
            a = math.radians(-28)
            xr, yr = u * math.cos(a) - v * math.sin(a), u * math.sin(a) + v * math.cos(a)
            rr = math.sqrt(xr * xr + (yr * 3.2) ** 2)
            core = math.exp(-(rr / 0.10) ** 2)
            disk = math.exp(-(rr / 0.55) ** 1.6)
            ang = math.atan2(yr * 3.2, xr)
            arms = 0.5 + 0.5 * math.cos(2 * ang - rr * 9)
            dust = 1 - 0.55 * math.exp(-((yr * 3.2 + 0.12) / 0.06) ** 2) * (rr < 0.7)
            i = (core * 1.6 + disk * (0.45 + 0.55 * arms)) * dust
            if i < 0.02:
                continue
            r_ = min(255, int(255 * min(1, i * (1.0 if core > 0.3 else 0.75))))
            g_ = min(255, int(235 * min(1, i * (0.95 if core > 0.3 else 0.62))))
            b_ = min(255, int(255 * min(1, i * 1.05)))
            px[x, y] = (r_, g_, b_, min(255, int(255 * min(1, i * 1.3))))
    sd_ = ImageDraw.Draw(im)
    for _ in range(40):
        x, y = random.uniform(0, n), random.uniform(0, n)
        sd_.ellipse((x - 1.5, y - 1.5, x + 1.5, y + 1.5), fill=(255, 255, 255, random.randint(80, 200)))
    return im.filter(ImageFilter.GaussianBlur(1.2))


paste_icon(crescent_img(), 401, 241, 30)
paste_icon(moonset_img(), 401, 284, 32)
paste_icon(sun_img(ORANGE, half=True), 497, 546, 38)
paste_icon(sun_img('#F28B3D', half=True), 672, 546, 38)
paste_icon(drop_img(), 209, 563, 26)
paste_icon(wind_img(), 206, 613, 30)
paste_icon(arrow_img(), 206, 653, 26)
paste_icon(mountain_img(), 931, 379, 62)
paste_icon(satellite_img(), 318, 774, 62)
paste_icon(galaxy_img(), 910, 853, 150)
paste_icon(sun_img(ORANGE), 590, 892, 26)

# moon-path dotted arc (static for now; the moon marker comes with the companion app)
for i in range(9):
    t = i / 8
    x = 535 + (720 - 535) * t
    y = 313 + 14 * math.sin(math.pi * t)
    X, Y = P(x, y)
    r = 1.6 * SS * 0.6
    d.ellipse((X - r, Y - r, X + r, Y + r), fill=(170, 180, 200, 255))
line([(535 + (720 - 535) * i / 40, 313 + 14 * math.sin(math.pi * i / 40)) for i in range(41)], (90, 100, 120, 255), 0.6)

bg_final = bg.resize((W, W), Image.LANCZOS)
# keep only the disc
cm = Image.new('L', (W, W), 0)
ImageDraw.Draw(cm).ellipse((0, 0, W - 1, W - 1), fill=255)
out_bg = Image.new('RGBA', (W, W), (0, 0, 0, 255))
out_bg.paste(bg_final, (0, 0), cm)
out_bg.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'bg.png'), optimize=True)


# ---------------------------------------------------------------- moon phases
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
parts = []  # dynamic elements inside the "details" group


def q(s):
    return quoteattr(s)


def text_el(x, y, w, h, template, params, font, size, color, align='CENTER', name=None):
    ps = ''.join('<Parameter expression=%s/>' % q(p) for p in params)
    return ('<PartText x="%.1f" y="%.1f" width="%.1f" height="%.1f"%s>'
            '<Text align="%s"><Font family="%s" size="%.1f" color="%s">'
            '<Template><![CDATA[%s]]>%s</Template></Font></Text></PartText>') % (
        x, y, w, h, (' name=%s' % q(name)) if name else '', align, font, size, color, template, ps)


def T(rx, ry, template, params=(), size=12, color=WHITE, font='barlow_sc_medium', align='CENTER', width=None):
    """Text centred vertically on ref (rx, ry); for START/END, rx is the left/right edge."""
    if not params:
        params, template = ['"%s"' % template], '%s'
    fx, fy = mx(rx), my(ry)
    h = size * 1.45
    w = width if width else size * 6
    if align == 'CENTER':
        x = fx - w / 2
    elif align == 'START':
        x = fx
    else:
        x = fx - w
    return text_el(x, fy - h / 2, w, h, template, params, font, size, color, align)


PH = '--'
def nest(src, values, start):
    # fully parenthesised: (src == start ? v0 : (src == start+1 ? v1 : ... last))
    if len(values) == 1:
        return '"%s"' % values[0]
    return '([%s] == %d ? "%s" : %s)' % (src, start, values[0], nest(src, values[1:], start + 1))


DOW = nest('DAY_OF_WEEK', ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'], 1)
MON = nest('MONTH', ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'], 1)
PH1 = ['New', 'Waxing', 'First', 'Waxing', 'Full', 'Waning', 'Last', 'Waning']
PH2 = ['Moon', 'Crescent', 'Quarter', 'Gibbous', 'Moon', 'Gibbous', 'Quarter', 'Crescent']


def by_type(names):
    return nest('MOON_PHASE_TYPE', names, 0)


ILLUM = 'round((1 - cos([MOON_PHASE_POSITION] * 0.2243995)) * 50)'
UVL = '([WEATHER.UV_INDEX] < 3 ? "LOW" : ([WEATHER.UV_INDEX] < 6 ? "MODERATE" : ([WEATHER.UV_INDEX] < 8 ? "HIGH" : ([WEATHER.UV_INDEX] < 11 ? "VERY HIGH" : "EXTREME"))))'

# moon image via bitmap font: one "character" per phase position 0..27
mx_, my_, msz = mx(627), my(244), ms(132)
parts.append('<PartText x="%.1f" y="%.1f" width="%.1f" height="%.1f"><Text align="CENTER">'
             '<BitmapFont family="moon" size="%.1f"><Template><![CDATA[%%s]]>'
             '<Parameter expression="[MOON_PHASE_POSITION]"/></Template></BitmapFont></Text></PartText>'
             % (mx_ - msz / 2, my_ - msz / 2, msz, msz, msz))
parts.append(T(425, 241, '%s', ['"--:--"'], size=14, color=WHITE, align='START', width=60))
parts.append(T(425, 284, '%s', ['"--:--"'], size=14, color=WHITE, align='START', width=60))
parts.append(T(800, 231, '%s', [ILLUM], size=15, color='#6FA8F5', align='END', width=50))
parts.append(T(802, 231, '%s', ['"%"'], size=15, color='#6FA8F5', align='START', width=20))
parts.append(T(784, 266, '%s', [by_type(PH1)], size=11.5, color='#C9CED6'))
parts.append(T(784, 295, '%s', [by_type(PH2)], size=11.5, color='#C9CED6'))
# date
parts.append(T(536, 371, '%s', [DOW], size=17, color='#5FA2F5', font='barlow_semibold'))
parts.append(T(690, 371, '%s %s', ['[DAY]', MON], size=17, color='#E9ECF1', font='barlow_medium'))
# sunrise / sunset (companion app)
parts.append(T(527, 546, '%s', ['"--:--"'], size=13.5, color=WHITE, align='START', width=60))
parts.append(T(702, 546, '%s', ['"--:--"'], size=13.5, color=WHITE, align='START', width=60))
# weather
WXAVAIL = '[WEATHER.IS_AVAILABLE]'
wx_icon = ('<Group name="wx_icon" x="%.1f" y="%.1f" width="%.1f" height="%.1f"><Condition><Expressions>'
           '<Expression name="day"><![CDATA[[WEATHER.IS_DAY] == 1]]></Expression></Expressions>'
           '<Compare expression="day">%s</Compare><Default>%s</Default></Condition></Group>')
isz = ms(80)


def wx_text(fam):
    return ('<PartText x="0" y="0" width="%.1f" height="%.1f"><Text align="CENTER"><BitmapFont family="%s" size="%.1f">'
            '<Template><![CDATA[%%s]]><Parameter expression="[WEATHER.CONDITION] &lt;= 15 ? [WEATHER.CONDITION] : 0"/>'
            '</Template></BitmapFont></Text></PartText>') % (isz, isz, fam, isz)


parts.append(wx_icon % (mx(286) - isz / 2, my(394) - isz / 2, isz, isz, wx_text('wx_day'), wx_text('wx_night')))
parts.append(T(293, 460, '%s°', ['%s ? round([WEATHER.TEMPERATURE]) : "--"' % WXAVAIL], size=25, color=WHITE,
               font='barlow_medium'))
parts.append(T(284, 504, 'Feels %s', ['"--°"'], size=12.5, color=GREY))
parts.append(T(240, 563, '%s', ['"--%"'], size=17, color=WHITE, align='START', width=70))
parts.append(T(237, 613, '%s', ['"-- km/h"'], size=15, color=WHITE, align='START', width=80))
parts.append(T(237, 653, '%s', ['"--"'], size=15, color=WHITE, align='START', width=60))
# astronomy (ISS) — build 3
parts.append(T(269, 807, '%s', ['"--"'], size=12.5, color='#6FA8F5'))
parts.append(T(293, 836, '%s', ['"--"'], size=12.5, color=WHITE))
parts.append(T(325, 867, '%s', ['"--°"'], size=12.5, color=WHITE))
parts.append(T(329, 907, '%s', ['"--:--"'], size=12.5, color=WHITE))
# elevation / baro — companion app
parts.append(T(934, 446, '%s m', ['"----"'], size=21, color=WHITE, font='barlow_medium'))
parts.append(T(934, 495, '+%s m', ['"--"'], size=13, color=GREEN))
parts.append(T(1040, 543, '%s m', ['"--"'], size=11, color=GREEN, align='END', width=60))
parts.append(T(1040, 571, '%s m', ['"--"'], size=11, color=GREEN, align='END', width=60))
parts.append(T(900, 655, '%s hPa', ['"----"'], size=14, color=WHITE, align='START', width=90))
# deep sky — build 3
parts.append(T(961, 761, 'M31', [], size=12.5, color=WHITE))
parts.append(T(961, 789, 'Andromeda', [], size=12, color='#C9CED6'))
parts.append(T(961, 815, 'Galaxy', [], size=12, color='#C9CED6'))
parts.append(T(903, 907, '%s', ['"--:--"'], size=12.5, color=WHITE))
# tide — companion app
parts.append(T(600, 600, 'MUMBAI', [], size=11, color='#C9CED6', align='START', width=90))
parts.append(T(522, 645, '%s m', ['"--"'], size=19, color=WHITE, font='barlow_medium'))
parts.append(T(710, 644, '%s', ['"-- "'], size=13, color=TIDE))
parts.append(T(599, 804, '%s', ['"--:--"'], size=11.5, color=TIDE))
parts.append(T(676, 804, '%s m', ['"--"'], size=11.5, color=TIDE))
parts.append(T(599, 836, '%s', ['"--:--"'], size=11.5, color=TIDE))
parts.append(T(676, 836, '%s m', ['"--"'], size=11.5, color=TIDE))
# sun
parts.append(T(488, 963, '%s', ['"--°"'], size=14, color=WHITE))
parts.append(T(621, 956, '%s', ['%s ? round([WEATHER.UV_INDEX]) : "-"' % WXAVAIL], size=21, color=ORANGE,
               font='barlow_medium'))
parts.append(T(621, 988, '%s', ['%s ? %s : ""' % (WXAVAIL, UVL)], size=9.5, color='#D9DDE3'))
parts.append(T(763, 963, '%s', ['"--h --m"'], size=14, color=WHITE))
# compass — companion app
parts.append(T(627, 1042, '%s', ['"---°"'], size=14.5, color=WHITE, font='barlow_medium'))
parts.append(T(627, 1067, '%s', ['"--"'], size=9.5, color=WHITE))

# clock
tx, ty, tw, th = mx(440), my(460) - ms(150) / 2, mx(815) - mx(440), ms(150)
TSIZE = ms(148)
clock = ('<DigitalClock x="%.1f" y="%.1f" width="%.1f" height="%.1f">'
         '<TimeText format="h:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="%.1f" height="%.1f">'
         '<Font family="barlow_medium" size="%.1f" color="#ECEFF3"/></TimeText></DigitalClock>') % (
    tx, ty, tw, th, tw, th, TSIZE)
ampm = T(414, 502, '%s', ['[AMPM_STATE] == 0 ? "AM" : "PM"'], size=14, color='#5FA2F5', font='barlow_medium')
secs = T(823, 497, '%s', ['[SECOND_Z]'], size=16, color='#5FA2F5', font='barlow_medium')
ampm_hide = ('<Group name="ampm" x="0" y="0" width="450" height="450"><Condition><Expressions>'
             '<Expression name="h12"><![CDATA[[IS_24_HOUR_MODE] == 0]]></Expression></Expressions>'
             '<Compare expression="h12">%s</Compare></Condition></Group>') % ampm


def bitmap_font(name, prefix, count, size):
    out = ['<BitmapFont name="%s">' % name]
    for i in range(count):
        tag = 'Character' if i < 10 else 'Word'
        out.append('<%s name="%d" resource="%s%02d" width="%d" height="%d"/>' % (tag, i, prefix, i, size, size))
    out.append('</BitmapFont>')
    return ''.join(out)


xml = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<WatchFace width="450" height="450">',
       '<Metadata key="CLOCK_TYPE" value="DIGITAL"/>',
       '<Metadata key="PREVIEW_TIME" value="10:42:38"/>',
       '<BitmapFonts>',
       bitmap_font('moon', 'moon_', 28, 120),
       bitmap_font('wx_day', 'wx_d_', 16, 96),
       bitmap_font('wx_night', 'wx_n_', 16, 96),
       '</BitmapFonts>',
       '<Scene backgroundColor="#FF000000">',
       '<Group name="active" x="0" y="0" width="450" height="450">',
       '<Variant mode="AMBIENT" target="alpha" value="0"/>',
       '<PartImage x="0" y="0" width="450" height="450"><Image resource="bg"/></PartImage>',
       ''.join(parts),
       secs,
       ampm_hide,
       '</Group>',
       # ambient: only date + time, dimmed
       '<Group name="ambient" x="0" y="0" width="450" height="450" alpha="0">',
       '<Variant mode="AMBIENT" target="alpha" value="255"/>',
       T(627, 371, '%s  %s %s', [DOW, '[DAY]', MON], size=17, color='#8A9099', font='barlow_medium', width=200),
       '</Group>',
       clock,
       '</Scene>',
       '</WatchFace>']
import re
XML = '\n'.join(xml)
XML = re.sub(r'\b(x|y|width|height)="(-?\d+(?:\.\d+)?)"', lambda mm: '%s="%d"' % (mm.group(1), round(float(mm.group(2)))), XML)
with open(os.path.join(RES, 'raw', 'watchface.xml'), 'w') as f:
    f.write(XML)

# ---------------------------------------------------------------- preview (sample values)
prev = out_bg.copy()
pd = ImageDraw.Draw(prev)


def ptext(rx, ry, s, size, color, font='barlow_sc_medium', anchor='mm'):
    pd.text(m(rx, ry), s, font=pil_font(font, size), fill=hexrgb(color), anchor=anchor)


moon_img = Image.open(os.path.join(RES, 'drawable-nodpi', 'moon_10.png')).resize((int(msz), int(msz)), Image.LANCZOS)
prev.alpha_composite(moon_img, (int(mx_ - msz / 2), int(my_ - msz / 2)))
wxi = Image.open(os.path.join(RES, 'drawable-nodpi', 'wx_d_14.png')).resize((int(isz), int(isz)), Image.LANCZOS)
prev.alpha_composite(wxi, (int(mx(286) - isz / 2), int(my(394) - isz / 2)))
ptext(627, 460, '10:42', TSIZE, '#ECEFF3', 'barlow_medium')
ptext(414, 502, 'AM', 14, '#5FA2F5', 'barlow_medium')
ptext(823, 497, '38', 16, '#5FA2F5', 'barlow_medium')
ptext(536, 371, 'SAT', 17, '#5FA2F5', 'barlow_semibold')
ptext(690, 371, '5 SEP', 17, WHITE, 'barlow_medium')
ptext(787, 231, '78%', 15, '#6FA8F5')
ptext(784, 266, 'Waxing', 11.5, '#C9CED6')
ptext(784, 295, 'Gibbous', 11.5, '#C9CED6')
ptext(293, 460, '28°', 25, WHITE, 'barlow_medium')
ptext(621, 956, '3', 21, ORANGE, 'barlow_medium')
ptext(621, 988, 'MODERATE', 9.5, '#D9DDE3')
ptext(961, 761, 'M31', 12.5, WHITE)
ptext(961, 789, 'Andromeda', 12, '#C9CED6')
ptext(961, 815, 'Galaxy', 12, '#C9CED6')
ptext(600, 600, 'MUMBAI', 11, '#C9CED6', anchor='lm')
prev.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'preview.png'), optimize=True)
print('ok')
