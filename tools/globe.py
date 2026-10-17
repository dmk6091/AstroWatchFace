import math, time
from PIL import Image, ImageDraw, ImageFilter
RAD=math.pi/180
def days(ms): return ms/86400000 - 0.5 + 2440588 - 2451545
E=RAD*23.4397
def ra(l,b): return math.atan2(math.sin(l)*math.cos(E)-math.tan(b)*math.sin(E), math.cos(l))
def dec(l,b): return math.asin(math.sin(b)*math.cos(E)+math.cos(b)*math.sin(E)*math.sin(l))
def subsolar(ms):
    d=days(ms); M=RAD*(357.5291+0.98560028*d)
    C=RAD*(1.9148*math.sin(M)+0.02*math.sin(2*M)+0.0003*math.sin(3*M))
    L=M+C+RAD*102.9372+math.pi
    a=ra(L,0); de=dec(L,0); gmst=RAD*(280.16+360.9856235*d)
    lon=(a-gmst+math.pi)%(2*math.pi)-math.pi
    return de, lon
def render(lat0,lon0,ms,size=300,sunfrac=None,day=None,night=None,lit_all=False):
    W=size; im=Image.new('RGBA',(W,W),(0,0,0,0)); px=im.load()
    dw,dh=day.size; dp=day.load(); npx=night.load()
    cx,cy,R=W/2, W*0.55, W*0.39
    sd,sl=subsolar(ms)
    p0=lat0*RAD; l0=lon0*RAD
    for y in range(W):
        for x in range(W):
            xn=(x+0.5-cx)/R; yn=(cy-y-0.5)/R; rr=xn*xn+yn*yn
            if rr>1:
                # atmosphere glow
                r=math.sqrt(rr)
                if r<1.08:
                    a=int(120*(1-(r-1)/0.08)**2)
                    px[x,y]=(90,150,255,a)
                continue
            rho=math.sqrt(rr); c=math.asin(min(1,rho))
            if rho==0: lat=p0; lon=l0
            else:
                lat=math.asin(math.cos(c)*math.sin(p0)+yn*math.sin(c)*math.cos(p0)/rho)
                lon=l0+math.atan2(xn*math.sin(c), rho*math.cos(c)*math.cos(p0)-yn*math.sin(c)*math.sin(p0))
            u=int(((lon/RAD+180)%360)/360*dw)%dw; v=min(dh-1,int((90-lat/RAD)/180*dh))
            r_,g_,b_=dp[u,v]; li=npx[u,v]
            cz=math.sin(lat)*math.sin(sd)+math.cos(lat)*math.cos(sd)*math.cos(lon-sl)
            dayf=1.0 if lit_all else max(0,min(1,(cz+0.08)/0.16))
            shade=0.85 if lit_all else 0.35+0.65*max(0,cz)**0.6
            limb=0.7+0.3*math.sqrt(max(0,1-rr))
            nr=(r_*0.07+li*0.9*1.0, g_*0.07+li*0.75, b_*0.09+li*0.4)
            dr=(r_*shade,g_*shade,b_*shade)
            col=[ (dr[i]*dayf+nr[i]*(1-dayf))*limb for i in range(3)]
            edge=min(1,(1-math.sqrt(rr))*R)
            px[x,y]=(min(255,int(col[0])),min(255,int(col[1])),min(255,int(col[2])),int(255*edge))
    d=ImageDraw.Draw(im)
    if lit_all:
        return im
    # sky arc: sunrise (left/east) .. sunset (right/west) over the globe
    ar=R*1.22
    box=(cx-ar,cy-ar,cx+ar,cy+ar)
    d.arc(box,180,360,fill=(80,110,170,160),width=2)
    if sunfrac is not None and 0<=sunfrac<=1:
        d.arc(box,180,180+180*sunfrac,fill=(242,179,61,230),width=3)
        a=math.pi*(1-sunfrac); sx=cx+ar*math.cos(a); sy=cy-ar*math.sin(a)
        for k,al in ((11,40),(8,90),(5.5,255)):
            d.ellipse((sx-k,sy-k,sx+k,sy+k),fill=(255,200,80,al))
    # you are here
    d.ellipse((cx-4,cy-4,cx+4,cy+4),outline=(255,140,60,255),width=2)
    return im
if __name__=='__main__':
    day=Image.open('earth_day.jpg').convert('RGB'); night=Image.open('earth_night.jpg').convert('L')
    t0=time.mktime((2026,10,17,8,16,0,0,0,-1))*1000-5.5*3600*1000+time.timezone*-1000  # rough
    for name,ms,f in (('g_morning',1792210000000,0.18),('g_evening',1792250000000,0.95),('g_night',1792270000000,None)):
        render(18.62,73.8,ms,sunfrac=f,day=day,night=night).save(name+'.png')
    print('ok')
