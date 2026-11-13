# -*- coding: utf-8 -*-
"""বক্তব্য কার্ডের ১০টা ডিজাইন → assets/shtpl_quote_ready.json
কার্ডের মাপ ১০৮০×১২৬০: ওপরে ১০৮০×১০৮০ ডিজাইন, মাঝে ১০৮০×১২০ ব্যানার-জায়গা (adSlot, ব্যানার না দিলে
ডিজাইনের ব্যাকগ্রাউন্ড রঙে ফাঁকা — ফোটোকার্ডের মতো), নিচে ৬০px চিকন ফুটার (লোগো + ওয়েবসাইট)।"""
import json, hashlib

FOOT_H = 60
W, DESIGN_H, AD_H = 1080, 1080, 120
H = DESIGN_H + AD_H + FOOT_H   # ১২৬০
_n = [0]
def nid():
    _n[0] += 1
    return 'q' + hashlib.md5(str(_n[0]).encode()).hexdigest()[:11]

def photo(x, y, w, h, **k):
    e = dict(id=nid(), type='photo', x=x, y=y, w=w, h=h); e.update(k); return e
def rect(x, y, w, h, color, alpha=100, **k):
    e = dict(id=nid(), type='rect', x=x, y=y, w=w, h=h, color=color, alpha=alpha, shape='rect', grad='none'); e.update(k); return e
def circle(x, y, w, h, color, alpha=100, **k):
    return rect(x, y, w, h, color, alpha, shape='circle', **k)
def quote(x, y, w, h, color, size=52, align='l', valign='t', lh=1.42, lines=12, **k):
    e = dict(id=nid(), type='quote', x=x, y=y, w=w, h=h, color=color, size=size, align=align, valign=valign, lh=lh, lines=lines); e.update(k); return e
def qmark(x, y, w, h, color, alpha=100, align='l', g='open', **k):
    e = dict(id=nid(), type='qmark', x=x, y=y, w=w, h=h, color=color, alpha=alpha, align=align, g=g); e.update(k); return e
def text(src, x, y, w, h, color, size=30, align='l', **k):
    e = dict(id=nid(), type='text', src=src, x=x, y=y, w=w, h=h, color=color, size=size, align=align, box=False, bg='#000000', balpha=60, text=''); e.update(k); return e
def logo(x, y, w, h, align='l', color='#ffffff'):
    return dict(id=nid(), type='logo', x=x, y=y, w=w, h=h, align=align, color=color)

def strip(bg, fg, with_logo=True):
    """নিচের চিকন ফুটার (ব্যানার-জায়গার নিচে)। ব্যানার-জায়গায় (১০৮০–১২০০) কিছু আঁকা হয় না"""
    fy = DESIGN_H + AD_H
    els = [rect(0, fy, W, FOOT_H, bg)]
    if with_logo:
        els += [logo(30, fy + 7, 320, 46, 'l', fg), text('site', 520, fy, 530, FOOT_H, fg, 24, 'r')]
    else:
        els += [text('site', 40, fy, 1000, FOOT_H, fg, 26, 'c')]
    return els

def layout(bg, els):
    return dict(w=W, h=H, bg=bg, adSlot=True, adY=DESIGN_H, adH=AD_H, els=els)

def d1():  # স্ক্রিনশটের ডিজাইন: বাঁয়ে লাল প্যানেল, ডানে ছবি, ছবির নিচে সাদা নাম-বক্স
    red = '#d9232b'
    els = [
        photo(540, 0, 540, 1080),
        rect(540, 934, 540, 146, '#ffffff'),
        rect(0, 0, 540, 1080, red, r=64, cn='0100'),
        qmark(57, 64, 140, 84, '#ffffff', 90),
        rect(409, 60, 20, 20, '#ffffff', 60, nf=True, sw=2, sc='#ffffff', r=4),
        rect(440, 60, 20, 20, '#ffffff', 60, nf=True, sw=2, sc='#ffffff', r=4),
        rect(471, 60, 20, 20, '#ffffff', 60, nf=True, sw=2, sc='#ffffff', r=4),
        quote(57, 210, 440, 600, '#ffffff', 52, 'l', 't', 1.45),
        text('dateurl', 41, 972, 480, 56, '#ffffff', 22, 'l'),
        text('name', 553, 946, 510, 58, red, 42, 'l'),
        text('role', 553, 1004, 510, 46, '#111111', 26, 'l'),
    ] + strip('#f3f4f6', red)
    return layout('#f3f4f6', els)

def d2():  # নেভি + সোনালি, মাঝখানে গোল ছবি
    gold = '#d4af37'
    els = [
        circle(-200, -260, 700, 700, '#14307a', 60),
        circle(700, 700, 600, 600, '#14307a', 45),
        photo(360, 70, 360, 360, shape='circle', bw=10, bc=gold),
        qmark(440, 456, 200, 64, gold, 100, 'c'),
        quote(110, 545, 860, 290, '#ffffff', 50, 'c', 'm', 1.4, 7),
        rect(440, 856, 200, 4, gold),
        text('name', 90, 876, 900, 62, gold, 44, 'c'),
        text('role', 90, 938, 900, 48, '#dfe6f5', 28, 'c'),
        text('date', 90, 1010, 900, 40, '#9fb0d6', 24, 'c'),
    ] + strip('#07173a', gold)
    return layout('#0b1f4b', els)

def d3():  # ফুল-ফটো + নিচে কালো ছায়া, হলুদ দাগ
    yel = '#ffd400'
    els = [
        photo(0, 0, 1080, 1080),
        rect(0, 330, 1080, 750, '#000000', 100, grad='down'),
        logo(40, 36, 300, 100, 'l', '#ffffff'),
        rect(70, 618, 10, 250, yel),
        qmark(70, 512, 140, 64, yel),
        quote(110, 610, 860, 262, '#ffffff', 54, 'l', 't', 1.4, 6),
        text('name', 94, 884, 900, 60, yel, 42, 'l'),
        text('role', 94, 944, 900, 44, '#ffffff', 26, 'l'),
        text('date', 94, 1010, 900, 40, '#cfcfcf', 22, 'l'),
    ] + strip('#000000', '#ffffff', with_logo=False)
    return layout('#000000', els)

def d3_fix(l):
    return l

def d4():  # সাদা ম্যাগাজিন: বাঁয়ে ছবি, লাল দাগ
    red = '#c8102e'
    els = [
        photo(0, 0, 430, 1080),
        rect(430, 0, 14, 1080, red),
        qmark(500, 90, 160, 76, red),
        quote(500, 205, 520, 560, '#111111', 50, 'l', 't', 1.45),
        rect(500, 826, 80, 6, red),
        text('name', 484, 846, 570, 60, '#111111', 40, 'l'),
        text('role', 484, 906, 570, 44, red, 26, 'l'),
        text('date', 484, 1000, 570, 44, '#777777', 22, 'l'),
    ] + strip('#f4f4f4', red)
    return layout('#ffffff', els)

def d5():  # সবুজ: ওপরে ছবি, নিচে প্যানেল, সাদা নাম-চিপ
    g = '#0b6e3a'
    els = [
        rect(0, 520, 1080, 560, '#0b7d41', 100, grad='two', color2='#075c30'),
        photo(0, 0, 1080, 520),
        circle(485, 462, 110, 110, '#ffd400'),
        qmark(485, 495, 110, 34, g, 100, 'c'),
        quote(90, 590, 900, 300, '#ffffff', 50, 'c', 'm', 1.4, 6),
        rect(240, 920, 600, 124, '#ffffff', r=24),
        text('name', 240, 932, 600, 56, g, 38, 'c'),
        text('role', 240, 988, 600, 44, '#333333', 24, 'c'),
    ] + strip('#075c30', '#ffffff')
    return layout('#0b6e3a', els)

def d6():  # পার্পল গ্রেডিয়েন্ট, ডানে গোল-কোণা ছবি
    pur = '#4a148c'
    els = [
        rect(0, 0, 1080, 1080, '#4a148c', 100, grad='two', color2='#8e24aa'),
        circle(640, -120, 560, 560, '#ffffff', 8),
        circle(-160, 760, 520, 520, '#ffffff', 8),
        photo(620, 110, 370, 470, r=44, bw=8, bc='#ffffff'),
        rect(620, 556, 370, 190, '#ffffff', r=30),
        text('name', 620, 580, 370, 62, pur, 34, 'c'),
        text('role', 620, 640, 370, 84, '#444444', 24, 'c', lines=2),
        qmark(70, 110, 150, 70, '#ffffff', 85),
        quote(60, 215, 520, 640, '#ffffff', 46, 'l', 't', 1.45),
        text('date', 38, 970, 520, 50, '#e1bee7', 24, 'l'),
    ] + strip('#2a0650', '#e1bee7')
    return layout('#3b0a6b', els)

def d7():  # কালো + লাল, ডানে নিচে গোল ছবি
    red = '#e10600'
    els = [
        rect(0, 0, 26, 1080, red),
        circle(596, 536, 450, 450, red, 100),
        photo(610, 550, 422, 422, shape='circle'),
        qmark(90, 90, 160, 76, red),
        quote(74, 200, 900, 352, '#ffffff', 52, 'l', 't', 1.42, 8),
        rect(74, 748, 90, 6, red),
        text('name', 58, 770, 540, 64, '#ffffff', 46, 'l'),
        text('role', 58, 836, 540, 84, '#ff6a63', 28, 'l', lines=2),
        text('date', 58, 972, 520, 44, '#9a9a9a', 22, 'l'),
    ] + strip(red, '#ffffff')
    return layout('#0d0d0d', els)

def d8():  # ক্রিম ক্লাসিক, ফ্রেম, মাঝখানে ছোট গোল ছবি
    br = '#7a4b1e'; tan = '#c9a46c'
    els = [
        rect(34, 34, 1012, 1012, br, 100, nf=True, sw=5, sc=br),
        rect(52, 52, 976, 976, tan, 100, nf=True, sw=2, sc=tan),
        photo(420, 100, 240, 240, shape='circle', bw=6, bc=br),
        qmark(440, 366, 200, 58, tan, 100, 'c'),
        quote(110, 445, 860, 330, '#3b2412', 48, 'c', 'm', 1.42, 8),
        rect(300, 800, 200, 3, tan),
        rect(580, 800, 200, 3, tan),
        circle(526, 792, 28, 18, br),
        text('name', 90, 830, 900, 62, br, 44, 'c'),
        text('role', 90, 892, 900, 48, '#5c4a3a', 28, 'c'),
        text('date', 90, 975, 900, 40, '#9a8268', 22, 'c'),
    ] + strip(br, '#fbf3e4')
    return layout('#fbf3e4', els)

def d9():  # টিল: ওপরে বক্তব্য, নিচে ছবি, মাঝে সাদা ব্যাজ
    tl = '#00796b'
    els = [
        quote(80, 70, 920, 340, '#ffffff', 50, 'c', 'm', 1.4, 7),
        photo(0, 500, 1080, 580),
        rect(0, 800, 1080, 280, '#004d40', 100, grad='down'),
        circle(480, 440, 120, 120, '#ffffff'),
        qmark(480, 482, 120, 36, tl, 100, 'c'),
        text('name', 44, 936, 800, 64, '#ffffff', 44, 'l'),
        text('role', 44, 1000, 900, 44, '#b2dfdb', 26, 'l'),
    ] + strip('#004d40', '#b2dfdb')
    return layout(tl, els)

def d10():  # চারকোল + হলুদ ব্লক, বাঁয়ে ফ্রেমে ছবি
    y = '#ffcc00'
    els = [
        rect(0, 0, 1080, 14, y),
        rect(100, 140, 420, 560, y),
        photo(70, 110, 420, 560),
        text('name', 54, 722, 500, 60, y, 38, 'l'),
        text('role', 54, 780, 500, 90, '#ffffff', 26, 'l', lines=2),
        qmark(590, 110, 160, 76, y),
        quote(580, 225, 440, 600, '#ffffff', 46, 'l', 't', 1.45),
        text('date', 570, 980, 460, 44, '#9aa0a6', 22, 'l'),
    ] + strip(y, '#1f2328')
    return layout('#1f2328', els)

def d11():  # আগের বিল্ট-ইন বক্তব্য কার্ড (ডানে লাল গ্রেডিয়েন্ট, বাঁয়ে ছবি, ওপরে সাদা লোগো-পট্টি)
    els = [
        photo(0, 0, 500, 1080),
        rect(500, 0, 580, 1080, '#e30000', 100, grad='two', color2='#6e0000'),
        rect(499, 0, 3, 1080, '#ffffff', 55),
        rect(500, 56, 580, 78, '#ffffff'),
        logo(552, 62, 476, 66, 'c', '#b30012'),
        text('site', 862, 134, 210, 38, '#ffd0d0', 26, 'l'),
        qmark(534, 246, 150, 72, '#ffffff', 60),
        quote(570, 410, 450, 460, '#ffffff', 64, 'l', 'm', 1.42, 12, min=22),
        rect(1056, 900, 3, 156, '#ffffff'),
        text('name', 572, 926, 480, 52, '#ffffff', 40, 'r'),
        text('role', 572, 978, 480, 80, '#f1d6d6', 30, 'r', lines=2),
    ] + strip('#6e0000', '#ffffff')
    return layout('#6e0000', els)

NAMES = [
    ('১. রেড স্প্লিট', d1), ('২. নেভি ও সোনালি', d2), ('৩. ফুল ফটো', d3), ('৪. সাদা ম্যাগাজিন', d4),
    ('৫. সবুজ প্যানেল', d5), ('৬. পার্পল কার্ড', d6), ('৭. কালো ও লাল', d7), ('৮. ক্রিম ক্লাসিক', d8),
    ('৯. টিল ব্যাজ', d9), ('১০. হলুদ ব্লক', d10), ('১১. ক্লাসিক লাল (আগের ডিজাইন)', d11),
]

def build():
    return [dict(name=n, S=f()) for n, f in NAMES]

if __name__ == '__main__':
    out = build()
    with open('app/src/main/assets/shtpl_quote_ready.json', 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    print(len(out), 'designs written')
