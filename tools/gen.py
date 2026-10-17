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
    'barlow_sc_semibold': 'BarlowSemiCondensed-SemiBold.ttf',
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
GREY = '#C3C9D2'
DIM = '#8A919C'
PANEL = (15, 18, 23)
EDGE = (64, 70, 80)
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
d.ellipse((0, 0, S - 1, S - 1), fill=(17, 20, 25, 255))
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
poly(time_poly, fill=(23, 27, 34, 255))
poly(tide_poly, fill=(19, 27, 39, 255))
sunmask = Image.new('L', (S, S), 0)
ImageDraw.Draw(sunmask).polygon([P(*p) for p in sun_poly], fill=255)
sunmask = ImageChops.multiply(sunmask, circle)
bg.paste(Image.new('RGBA', (S, S), (21, 24, 30, 255)), (0, 0), sunmask)
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
def label(text, x, y, size, color, font='barlow_sc_semibold', anchor='mm', spacing=0):
    size *= 1.0 if spacing else 1.15  # titles stay small, small labels readable
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
label('BARO', 910, 623, 9.5, GREY, anchor='lm')
label('MAX', 905, 543, 9.5, GREEN, anchor='lm')
label('MIN', 905, 571, 9.5, GREEN, anchor='lm')
label('TIDE', 612, 600, 11, TIDE, anchor='rm', spacing=0.5)
bg_draw.ellipse([*P(622, 596), *P(630, 604)], fill=hexrgb('#9AA3AE'))
label('SUN', 610, 892, 10.5, ORANGE, anchor='lm', spacing=0.5)
label('Azimuth', 474, 928, 9.5, GREY)
label('Day Length', 761, 928, 9.5, GREY)
label('UV', 621, 922, 9, ORANGE)
label('HIGH', 504, 804, 10, TIDE)
label('LOW', 502, 836, 10, TIDE)
label('ISS', 228, 766, 11.5, WHITE, anchor='lm')
label('W', 490, 1013, 9, GREY)
label('E', 754, 1013, 9, '#7FA6D8')
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


def steps_img():
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#C9CED6')
    for (ox, oy) in ((30, 20), (105, 70)):
        dd.ellipse((ox, oy, ox + 55, oy + 85), fill=c)
        dd.ellipse((ox + 8, oy + 92, ox + 47, oy + 125), fill=c)
    return im


def heart_img():
    n = 200
    im = icon_canvas(n)
    dd = ImageDraw.Draw(im)
    c = hexrgb('#E5484D')
    dd.ellipse((20, 30, 105, 115), fill=c)
    dd.ellipse((95, 30, 180, 115), fill=c)
    dd.polygon([(24, 92), (176, 92), (100, 180)], fill=c)
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
paste_icon(drop_img(), 194, 563, 28)
paste_icon(steps_img(), 192, 613, 32)
paste_icon(heart_img(), 192, 655, 28)
paste_icon(mountain_img(), 931, 379, 62)
paste_icon(satellite_img(), 318, 774, 62)
paste_icon(galaxy_img(), 905, 852, 120)
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


def text_el(x, y, w, h, template, params, font, size, color, align='CENTER', name=None, launch=None):
    ps = ''.join('<Parameter expression=%s/>' % q(p) for p in params)
    return ('<PartText x="%.1f" y="%.1f" width="%.1f" height="%.1f"%s>%s'
            '<Text align="%s"><Font family="%s" size="%.1f" color="%s">'
            '<Template><![CDATA[%s]]>%s</Template></Font></Text></PartText>') % (
        x, y, w, h, (' name=%s' % q(name)) if name else '',
        ('<Launch target="%s"/>' % launch) if launch else '', align, font, size, color, template, ps)


PREVIEW = []
SAMPLES = {}  # expression -> sample text for the preview image


def T(rx, ry, template, params=(), size=12, color=WHITE, font='barlow_sc_semibold', align='CENTER', width=None,
      launch=None, preview=True):
    """Text centred vertically on ref (rx, ry); for START/END, rx is the left/right edge."""
    if not params:
        params, template = ['"%s"' % template], '%s'
    if size < 20:
        size *= 1.2  # small text was too thin to read on the watch
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
        PREVIEW.append((rx, ry, template.replace('%s', '{}').format(*vals), size, color, font, align, width))
    fx, fy = mx(rx), my(ry)
    h = size * 1.45
    w = width if width else size * 6
    if align == 'CENTER':
        x = fx - w / 2
    elif align == 'START':
        x = fx
    else:
        x = fx - w
    return text_el(x, fy - h / 2, w, h, template, params, font, size, color, align, launch=launch)


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

SAMPLES.update({ILLUM: '78', by_type(PH1): 'Waxing', by_type(PH2): 'Gibbous', DOW: 'SAT', '[DAY]': '5', MON: 'SEP',
                '%s ? round([WEATHER.TEMPERATURE]) : "--"' % '[WEATHER.IS_AVAILABLE]': '28',
                '%s ? round([WEATHER.UV_INDEX]) : "-"' % '[WEATHER.IS_AVAILABLE]': '3',
                '%s ? %s : ""' % ('[WEATHER.IS_AVAILABLE]', UVL): 'MODERATE', '[SECOND_Z]': '38'})
# moon image via bitmap font: one "character" per phase position 0..27
mx_, my_, msz = mx(627), my(244), ms(132)
def moon_img_el(i):
    return '<PartImage x="0" y="0" width="%d" height="%d"><Image resource="moon_%02d"/></PartImage>' % (
        round(msz), round(msz), i)


cond = ['<Group name="moon" x="%.1f" y="%.1f" width="%.1f" height="%.1f"><Condition><Expressions>' % (
    mx_ - msz / 2, my_ - msz / 2, msz, msz)]
for i in range(27):
    cond.append('<Expression name="m%d"><![CDATA[floor([MOON_PHASE_POSITION]) == %d]]></Expression>' % (i, i))
cond.append('</Expressions>')
for i in range(27):
    cond.append('<Compare expression="m%d">%s</Compare>' % (i, moon_img_el(i)))
cond.append('<Default>%s</Default></Condition></Group>' % moon_img_el(27))
parts.append(''.join(cond))
AMB_MOON = ''.join(cond).replace('<Group name="moon"', '<Group name="moon2"')
parts.append(T(800, 231, '%s', [ILLUM], size=15, color='#6FA8F5', align='END', width=50))
parts.append(T(802, 231, '%s', ['"%"'], size=15, color='#6FA8F5', align='START', width=20))
parts.append(T(790, 262, '%s', [by_type(PH1)], size=12.5, color='#C9CED6'))
parts.append(T(790, 298, '%s', [by_type(PH2)], size=12.5, color='#C9CED6'))
# date
parts.append(T(536, 371, '%s', [DOW], size=17, color='#5FA2F5', font='barlow_semibold', launch='CALENDAR'))
parts.append(T(690, 371, '%s %s', ['[DAY]', MON], size=17, color='#E9ECF1', font='barlow_medium', launch='CALENDAR'))
# sunrise / sunset (companion app)
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
parts.append(T(293, 450, '%s°', ['%s ? round([WEATHER.TEMPERATURE]) : "--"' % WXAVAIL], size=29, color=WHITE,
               font='barlow_medium'))
HI = '[WEATHER.IS_AVAILABLE] ? round([WEATHER.DAY_TEMPERATURE_HIGH]) : "--"'
LO = '[WEATHER.IS_AVAILABLE] ? round([WEATHER.DAY_TEMPERATURE_LOW]) : "--"'
RAIN = '[WEATHER.IS_AVAILABLE] ? round([WEATHER.CHANCE_OF_PRECIPITATION]) : "--"'
HR = '[HEART_RATE] > 0 ? [HEART_RATE] : "--"'
SAMPLES.update({HI: '31', LO: '24', RAIN: '20', '[STEP_COUNT]': '6,240', HR: '72'})
parts.append(T(222, 563, '%s%s', [RAIN, '"%"'], size=20, color=WHITE, align='START', width=70))
parts.append(T(220, 613, '%s', ['[STEP_COUNT]'], size=18, color=WHITE, align='START', width=80))
parts.append(T(220, 655, '%s bpm', [HR], size=18, color=WHITE, align='START', width=80, launch='HEALTH_HEART_RATE'))
# astronomy (ISS) — build 3
parts.append(T(262, 807, '%s', ['"--"'], size=14.5, color='#6FA8F5'))
parts.append(T(286, 838, '%s', ['"--"'], size=14.5, color=WHITE))
parts.append(T(318, 869, '%s', ['"--°"'], size=14.5, color=WHITE))
parts.append(T(325, 907, '%s', ['"--:--"'], size=14.5, color=WHITE))
# elevation / baro — companion app
# deep sky — build 3
parts.append(T(972, 756, 'M31', [], size=14, color=WHITE))
parts.append(T(972, 789, 'Andromeda', [], size=13.5, color='#C9CED6'))
parts.append(T(912, 907, '%s', ['"--:--"'], size=14.5, color=WHITE))
# tide — companion app
# sun
parts.append(T(621, 954, '%s', ['%s ? round([WEATHER.UV_INDEX]) : "-"' % WXAVAIL], size=24, color=ORANGE,
               font='barlow_medium'))
parts.append(T(621, 989, '%s', ['%s ? %s : ""' % (WXAVAIL, UVL)], size=10.5, color='#D9DDE3'))
# compass — companion app

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


# ---------------------------------------------------------------- companion data
# One LONG_TEXT complication from the Astro companion app carries every value, in fixed-width
# fields (the face cuts them out with subText). FIELDS is the shared spec: the generator also
# writes it into the companion app's FieldSpec.java, so both sides always agree.
FIELDS = []  # (name, width, align)
COMP, EMPTY = [], []


def D(rx, ry, name, width, template, size, color, align='CENTER', w=None, font='barlow_sc_semibold',
      sample='', empty='--'):
    start = sum(f[1] for f in FIELDS)
    FIELDS.append((name, width, {'START': 'L', 'END': 'R', 'CENTER': 'C'}[align]))
    expr = 'subText([COMPLICATION.TEXT], %d, %d)' % (start, start + width)
    SAMPLES[expr] = sample
    COMP.append(T(rx, ry, template, [expr], size=size, color=color, font=font, align=align, width=w))
    EMPTY.append(T(rx, ry, template, ['"%s"' % empty], size=size, color=color, font=font, align=align, width=w,
                   preview=False))


D(425, 241, 'mrise', 5, '%s', 15.5, WHITE, 'START', 60, sample='12:45', empty='--:--')
D(425, 284, 'mset', 5, '%s', 15.5, WHITE, 'START', 60, sample='23:32', empty='--:--')
D(527, 546, 'srise', 5, '%s', 15, WHITE, 'START', 60, sample='06:18', empty='--:--')
D(702, 546, 'sset', 5, '%s', 15, WHITE, 'START', 60, sample='18:24', empty='--:--')
D(284, 510, 'feels', 4, 'Feels %s', 14.5, GREY, sample='31°')
D(946, 446, 'elev', 5, '%s m', 25, WHITE, font='barlow_medium', sample='560')
D(946, 495, 'gain', 5, '%s m', 15.5, GREEN, sample='+128')
D(1058, 543, 'emax', 5, '%s m', 13, GREEN, 'END', 60, sample='602')
D(1058, 571, 'emin', 5, '%s m', 13, GREEN, 'END', 60, sample='548')
D(908, 657, 'baro', 5, '%s hPa', 16.5, WHITE, 'START', 90, sample='1008', empty='----')
D(1062, 623, 'btrend', 4, '%s', 11, GREY, 'END', 40, sample='+1.2', empty='')
D(640, 600, 'tplace', 10, '%s', 11, '#C9CED6', 'START', 90, sample='MUMBAI', empty='')
D(522, 645, 'tnow', 5, '%s m', 23, WHITE, font='barlow_medium', sample='1.8')
D(710, 644, 'tstate', 7, '%s', 15, TIDE, sample='RISING', empty='')
D(599, 804, 'hit', 5, '%s', 13.5, TIDE, sample='14:32', empty='--:--')
D(683, 804, 'hih', 5, '%s m', 13.5, TIDE, sample='2.3')
D(599, 836, 'lot', 5, '%s', 13.5, TIDE, sample='21:48', empty='--:--')
D(683, 836, 'loh', 5, '%s m', 13.5, TIDE, sample='0.4')
D(482, 963, 'az', 7, '%s', 16.5, WHITE, sample='103° E', empty='--°')
D(768, 963, 'dlen', 8, '%s', 16.5, WHITE, sample='12h 06m', empty='--h --m')
D(627, 1042, 'head', 4, '%s', 16, WHITE, font='barlow_medium', sample='312°', empty='---°')
D(627, 1067, 'hdir', 3, '%s', 9.5, WHITE, sample='NW')
# without the companion, the feels-like row shows today's high / low from the watch
EMPTY.append(T(284, 510, 'H %s°  L %s°', [HI, LO], size=14.5, color=GREY, preview=False))

data_slot = ('<ComplicationSlot x="0" y="0" width="450" height="450" slotId="0" displayName="Astro data" '
             'supportedTypes="LONG_TEXT EMPTY" isCustomizable="TRUE">'
             '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
             'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.DataService" '
             'primaryProviderType="LONG_TEXT"/>'
             '<BoundingBox x="%d" y="%d" width="%d" height="%d"/>'
             '<Complication type="LONG_TEXT">%s</Complication>'
             '<Complication type="EMPTY">%s</Complication>'
             '<Variant mode="AMBIENT" target="alpha" value="0"/>'
             '</ComplicationSlot>') % (round(mx(410)), round(my(880)), round(mx(845) - mx(410)),
                                       round(my(1000) - my(880)), ''.join(COMP), ''.join(EMPTY))
gx, gy = mx(398), my(664)
gw, gh = mx(842) - gx, my(758) - gy
graph_slot = ('<ComplicationSlot x="%d" y="%d" width="%d" height="%d" slotId="1" displayName="Tide graph" '
              'supportedTypes="PHOTO_IMAGE EMPTY" isCustomizable="TRUE">'
              '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
              'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.TideGraphService" '
              'primaryProviderType="PHOTO_IMAGE"/>'
              '<BoundingBox x="0" y="0" width="%d" height="%d"/>'
              '<Complication type="PHOTO_IMAGE"><PartImage x="0" y="0" width="%d" height="%d">'
              '<Image resource="[COMPLICATION.PHOTO_IMAGE]"/></PartImage></Complication>'
              '<Variant mode="AMBIENT" target="alpha" value="0"/>'
              '</ComplicationSlot>') % (round(gx), round(gy), round(gw), round(gh), round(gw), round(gh),
                                        round(gw), round(gh))

# FieldSpec.java for the companion
spec_dir = os.path.join(os.path.dirname(OUT.rstrip('/')), '..', '..', 'companion', 'src', 'main', 'java', 'com',
                        'dmk', 'astrocompanion')
if os.path.isdir(os.path.dirname(os.path.dirname(spec_dir))):
    os.makedirs(spec_dir, exist_ok=True)
    with open(os.path.join(spec_dir, 'FieldSpec.java'), 'w') as f:
        f.write('package com.dmk.astrocompanion;\n\n'
                '/** Generated by tools/gen.py: the watch face cuts these fixed-width fields out of the text. */\n'
                'final class FieldSpec {\n'
                '    static final String[] NAMES = {%s};\n'
                '    static final int[] WIDTHS = {%s};\n'
                '    /** L = left aligned (pad right), R = right aligned, C = centred. */\n'
                '    static final String ALIGN = "%s";\n\n'
                '    private FieldSpec() {}\n}\n' % (
                    ', '.join('"%s"' % f[0] for f in FIELDS), ', '.join(str(f[1]) for f in FIELDS),
                    ''.join(f[2] for f in FIELDS)))

# battery: thin ring along the left edge, 225° (bottom-left) up to 315° (top-left) at 100 %
def arc_draw(start, end, color, thick, expr=None, launch=None, d=432):
    tr = '<Transform target="endAngle" value=%s/>' % q(expr) if expr else ''
    return ('<PartDraw x="0" y="0" width="450" height="450">%s<Arc centerX="225" centerY="225" width="%d" '
            'height="%d" startAngle="%d" endAngle="%d"><Stroke color="%s" thickness="%.1f" cap="ROUND"/>%s'
            '</Arc></PartDraw>') % (('<Launch target="%s"/>' % launch) if launch else '', d, d, start, end, color,
                                    thick, tr)


battery = ''

# and a very thin full ring around the edge, filling clockwise from 12 o'clock
RING_END = '360 * [BATTERY_PERCENT] / 100'
battery += (arc_draw(0, 360, '#1E232A', 1.6, d=446, launch='BATTERY_STATUS') +
            '<Group name="batt_ring" x="0" y="0" width="450" height="450"><Condition><Expressions>'
            '<Expression name="low2"><![CDATA[[BATTERY_PERCENT] <= 20]]></Expression></Expressions>'
            '<Compare expression="low2">%s</Compare><Default>%s</Default></Condition></Group>') % (
    arc_draw(0, 360, '#E5484D', 1.6, RING_END, d=446), arc_draw(0, 360, GREEN, 1.6, RING_END, d=446))

_n = len(PREVIEW)
AMB = T(627, 371, '%s  %s %s', [DOW, '[DAY]', MON], size=17, color='#8A9099', font='barlow_medium', width=200)
del PREVIEW[_n:]  # ambient-only text isn't part of the preview
xml = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<WatchFace width="450" height="450">',
       '<Metadata key="CLOCK_TYPE" value="DIGITAL"/>',
       '<Metadata key="PREVIEW_TIME" value="10:42:38"/>',
       '<BitmapFonts>',
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
       battery,
       '</Group>',
       data_slot,
       graph_slot,
       # ambient: only date + time, dimmed
       '<Group name="ambient" x="0" y="0" width="450" height="450" alpha="0">',
       '<Variant mode="AMBIENT" target="alpha" value="255"/>',
       AMB,
       '<Group name="moon_amb" x="0" y="0" width="450" height="450" alpha="140">' + AMB_MOON + '</Group>',
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
pd.arc((2, 2, 448, 448), 0, 360, fill=(30, 35, 42), width=2)
pd.arc((2, 2, 448, 448), -90, -90 + 288, fill=hexrgb(GREEN), width=2)
for (rx, ry, txt, size, color, font, align, width) in PREVIEW:
    if align == 'CENTER':
        ptext(rx, ry, txt, size, color, font)
    elif align == 'START':
        ptext(rx, ry, txt, size, color, font, anchor='lm')
    else:
        ptext(rx, ry, txt, size, color, font, anchor='rm')
prev.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'preview.png'), optimize=True)
print('ok')
