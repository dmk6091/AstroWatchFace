#!/usr/bin/env python3
"""Terra watch face generator: deep-space astronomy / earth-science face with a live Earth globe.
Writes background art, moon phases, weather icons, watchface.xml, the preview, and the
companion's FieldSpec.java (the fixed-width field layout both sides share).
usage: terra.py <watchface/src/main> <dir with Barlow ttf files>"""
import math, os, random, re, sys
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
GLOBE = (225, 262, 172)            # centre x, centre y, slot size (square); the globe image is 300 px
GRAPH = (118, 372, 214, 32)        # tide graph slot: x, y, w, h  (image 428 x 64)


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


# faint orbit rings around the globe
gx, gy, gs = GLOBE
for rr, a in ((gs * 0.56, 60),):
    d.ellipse((P(gx - rr, gy - rr) + P(gx + rr, gy + rr)), outline=(80, 120, 190, a), width=SS)
# thin dividers
d.line(P(60, 160) + P(390, 160), fill=(60, 80, 115, 255), width=SS)
d.line(P(110, 352) + P(340, 352), fill=(60, 80, 115, 160), width=SS)


def label(text, x, y, size, color, font='barlow_sc_semibold', anchor='mm'):
    d.text(P(x, y), text, font=pil_font(font, size * SS), fill=hexrgb(color), anchor=anchor)


def icon(img, x, y, size):
    sz = int(size * SS)
    im = img.resize((sz, sz), Image.LANCZOS)
    bg.alpha_composite(im, (int(x * SS - sz / 2), int(y * SS - sz / 2)))


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


icon(sun_half(GOLD), 128, 82, 22)
icon(sun_half('#F28B3D'), 280, 82, 22)
icon(umbrella(), 52, 275, 22)
icon(drop(), 52, 305, 20)
icon(leaf(), 52, 335, 20)
icon(mountain(), 318, 300, 24)
icon(hourglass(), 318, 331, 20)
icon(wave(), 92, 365, 20)
label('AQI', 72, 335, 12, GREEN, anchor='lm')

# fallback globe (shown until the companion sends the live one): daylit Earth over India
_day = Image.open(os.path.join(HERE, 'textures', 'earth_day.jpg')).convert('RGB')
_night = Image.open(os.path.join(HERE, 'textures', 'earth_night.jpg')).convert('L')
_g = globe.render(18.62, 73.8, 1792213200000, size=300, sunfrac=None, day=_day, night=_night, lit_all=True)
_g = _g.resize((int(gs * SS), int(gs * SS)), Image.LANCZOS)
bg.alpha_composite(_g, (int((gx - gs / 2) * SS), int((gy - gs / 2) * SS)))

bg = bg.resize((W, W), Image.LANCZOS)
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
parts.append(T(338, 146, '%s', ['[SECOND_Z]'], size=20, color='#6FA8F5', font='barlow_medium', width=40))

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
FIELDS, COMP, EMPTY = [], [], []


def D(x, y, name, width, template, size, color, align='CENTER', w=None, font='barlow_sc_semibold', sample='',
      empty='--'):
    start = sum(f[1] for f in FIELDS)
    FIELDS.append((name, width, {'START': 'L', 'END': 'R', 'CENTER': 'C'}[align]))
    expr = 'subText([COMPLICATION.TEXT], %d, %d)' % (start, start + width)
    SAMPLES[expr] = sample
    COMP.append(T(x, y, template, [expr], size=size, color=color, font=font, align=align, width=w))
    EMPTY.append(T(x, y, template, ['"%s"' % empty], size=size, color=color, font=font, align=align, width=w,
                   preview=False))


D(142, 82, 'srise', 5, '%s', 19, WHITE, 'START', 70, sample='06:18', empty='--:--')
D(294, 82, 'sset', 5, '%s', 19, WHITE, 'START', 70, sample='18:24', empty='--:--')
D(70, 305, 'hum', 4, '%s', 21, WHITE, 'START', 80, sample='72%')
D(98, 335, 'aqi', 4, '%s', 21, WHITE, 'START', 60, sample='54')
D(358, 260, 'mage', 6, '%s', 16, SOFT, 'CENTER', 90, sample='6.2 d')
D(334, 300, 'alt', 5, '%s m', 21, WHITE, 'START', 90, sample='560')
D(334, 331, 'dlen', 7, '%s', 19, WHITE, 'START', 90, sample='11h 41m', empty='--h --m')
D(108, 365, 'tplace', 10, '%s', 16, ICE, 'START', 110, sample='MUMBAI', empty='TIDE')
D(250, 365, 'tnow', 5, '%s m', 17, WHITE, 'CENTER', 70, sample='1.8')
D(330, 365, 'tstate', 7, '%s', 14, '#5BA8F2', 'END', 70, sample='RISING', empty='')
D(172, 418, 'hit', 5, 'HIGH %s', 15, SOFT, 'CENTER', 100, sample='14:32', empty='--:--')
D(282, 418, 'lot', 5, 'LOW %s', 15, SOFT, 'CENTER', 100, sample='21:48', empty='--:--')

data_slot = ('<ComplicationSlot x="0" y="0" width="450" height="450" slotId="0" displayName="Astro data" '
             'supportedTypes="LONG_TEXT EMPTY" isCustomizable="TRUE">'
             '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
             'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.DataService" '
             'primaryProviderType="LONG_TEXT"/>'
             '<BoundingOval x="0" y="0" width="450" height="450"/>'
             '<Complication type="LONG_TEXT">%s</Complication>'
             '<Complication type="EMPTY">%s</Complication>'
             '<Variant mode="AMBIENT" target="alpha" value="0"/>'
             '</ComplicationSlot>') % (''.join(COMP), ''.join(EMPTY))


def image_slot(slot_id, name, provider, x, y, w, h):
    img = ('<PartImage x="0" y="0" width="%d" height="%d"><Image resource="[COMPLICATION.%%s]"/></PartImage>'
           % (w, h))
    return ('<ComplicationSlot x="%d" y="%d" width="%d" height="%d" slotId="%d" displayName="%s" '
            'supportedTypes="SMALL_IMAGE PHOTO_IMAGE EMPTY" isCustomizable="TRUE">'
            '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" '
            'primaryProvider="com.dmk.astrocompanion/com.dmk.astrocompanion.%s" primaryProviderType="SMALL_IMAGE"/>'
            '<BoundingBox x="0" y="0" width="%d" height="%d"/>'
            '<Complication type="SMALL_IMAGE">%s</Complication>'
            '<Complication type="PHOTO_IMAGE">%s</Complication>'
            '<Variant mode="AMBIENT" target="alpha" value="0"/>'
            '</ComplicationSlot>') % (x, y, w, h, slot_id, name, provider, w, h, img % 'SMALL_IMAGE',
                                      img % 'PHOTO_IMAGE')


globe_slot = image_slot(1, 'Astro globe', 'GlobeService', gx - gs / 2, gy - gs / 2, gs, gs)
graph_slot = image_slot(2, 'Tide graph', 'TideGraphService', *GRAPH)


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

clock = ('<DigitalClock x="60" y="92" width="330" height="72">'
         '<TimeText format="h:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="330" height="72">'
         '<Font family="barlow_semibold" size="70" color="#F2F5FA"/></TimeText></DigitalClock>')
amb_clock = clock.replace('#F2F5FA', '#C8CED8')
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
       '<Metadata key="CLOCK_TYPE" value="DIGITAL"/>',
       '<Metadata key="PREVIEW_TIME" value="8:16:31"/>',
       '<BitmapFonts>', bitmap_font('wx_day', 'wx_d_', 16, 96), bitmap_font('wx_night', 'wx_n_', 16, 96),
       '</BitmapFonts>',
       '<Scene backgroundColor="#FF000000">',
       '<Group name="active" x="0" y="0" width="450" height="450">',
       '<Variant mode="AMBIENT" target="alpha" value="0"/>',
       '<PartImage x="0" y="0" width="450" height="450"><Image resource="bg"/></PartImage>',
       ''.join(parts), battery, clock,
       '</Group>',
       globe_slot, graph_slot, data_slot,
       '<Group name="ambient" x="0" y="0" width="450" height="450" alpha="0">',
       '<Variant mode="AMBIENT" target="alpha" value="255"/>',
       AMB, amb_clock.replace('y="92"', 'y="120"'),
       '<Group name="moon_amb" x="0" y="0" width="450" height="450" alpha="150">'
       + MOON.replace('<Group name="moon" x="%d" y="%d"' % (358 - msz / 2, 198 - msz / 2),
                      '<Group name="moon2" x="%d" y="%d"' % (225 - msz / 2, 255)) + '</Group>',
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
live = globe.render(18.62, 73.8, 1792210000000, size=300, sunfrac=0.18, day=_day, night=_night)
live = live.resize((gs, gs), Image.LANCZOS)
prev.alpha_composite(live, (int(gx - gs / 2), int(gy - gs / 2)))
pd = ImageDraw.Draw(prev)
gxx, gyy, gw, gh = GRAPH
pts = [(gxx + 4 + i * (gw - 8) / 60, gyy + gh / 2 - (gh / 2 - 4) * math.cos(2 * math.pi * i / 60 * 1.9 + 0.6))
       for i in range(61)]
pd.line(pts, fill=hexrgb('#5BA8F2'), width=2)
pd.ellipse((pts[20][0] - 3, pts[20][1] - 3, pts[20][0] + 3, pts[20][1] + 3), fill=(255, 255, 255, 255))
pd.text((225, 128), '8:16', font=pil_font('barlow_semibold', 70), fill=hexrgb('#F2F5FA'), anchor='mm')
mi = Image.open(os.path.join(RES, 'drawable-nodpi', 'moon_09.png')).resize((msz, msz), Image.LANCZOS)
prev.alpha_composite(mi, (358 - msz // 2, 198 - msz // 2))
wi = Image.open(os.path.join(RES, 'drawable-nodpi', 'wx_d_14.png')).resize((isz, isz), Image.LANCZOS)
prev.alpha_composite(wi, (92 - isz // 2, 196 - isz // 2))
for (x, y, txt, size, color, font, align) in PREVIEW:
    anchor = 'mm' if align == 'CENTER' else ('lm' if align == 'START' else 'rm')
    pd.text((x, y), txt, font=pil_font(font, size), fill=hexrgb(color), anchor=anchor)
pd.arc((2, 2, 448, 448), -90, -90 + 280, fill=hexrgb('#5FA2F5'), width=2)
prev.convert('RGB').save(os.path.join(RES, 'drawable-nodpi', 'preview.png'), optimize=True)
print('ok')
