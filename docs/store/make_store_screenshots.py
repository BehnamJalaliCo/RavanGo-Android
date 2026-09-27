import os, subprocess, html
# Renders the Cafe Bazaar store screenshots (1080x1920) with headless Chromium.
# Needs: app captures in <S>/src (from docs/screenshots and the Roborazzi outputs) and the licensed Ravagh font in private/.
S=os.path.dirname(os.path.abspath(__file__)); R=os.path.abspath(os.path.join(S, '..', '..'))
F=R+'/private/fonts/ravagh/'
mark=open(R+'/docs/brand/ravango-mark.svg').read()
slides=[
 ('01-home','home-fa-light.png','استودیوی ساخت ویدیو،<br><em>در جیب تو</em>','تله‌پرامپتر، دوربین حرفه‌ای، آرایش زنده و ادیتور در یک برنامه',('#1A7FFF','#4B3BE0'),-3),
 ('02-looks','camera-studio-looks-fa-dark.png','<em>۲۰ آرایش آماده</em><br>فقط با یک لمس','گلم، عروس ایرانی، عربی، کره‌ای، لاته و… زنده روی صورتت',('#6A4DF4','#C04BD8'),3),
 ('03-prompter','prompter-controls-fa-dark.png','روان جلوی دوربین<br><em>حرف بزن</em>','تله‌پرامپتر هم‌گام با ضبط؛ سرعت، فونت و اندازه‌ی دلخواه',('#0E6BF0','#1FB5C9'),-3),
 ('04-filters','camera-effects-filters-fa-dark.png','<em>فیلتر زنده</em><br>با کشیدن انگشت','۱۲ فیلتر رنگ، لنزهای سرگرمی و پس‌زمینه‌ی مجازی',('#3B34D6','#1A7FFF'),3),
 ('05-editor','editor-main-fa-dark.png','<em>تدوین حرفه‌ای</em><br>همین‌جا، روی گوشی','تایم‌لاین چندلایه، متن، موسیقی، زیرنویس و حذف سکوت',('#4B3BE0','#8A4BF0'),-3),
 ('06-beauty','beauty-panel-makeup-fa-dark.png','زیبایی طبیعی،<br><em>زنده و روان</em>','صاف‌کردن پوست، فرم صورت، خط چشم، مژه، رژ و لنز رنگی',('#C04BD8','#1A7FFF'),3),
]
css='''
@font-face{font-family:Rv;src:url(file://%sbrand_black.ttf);font-weight:900}
@font-face{font-family:Rv;src:url(file://%sbrand_extrabold.ttf);font-weight:800}
@font-face{font-family:Rv;src:url(file://%sbrand_medium.ttf);font-weight:500}
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:1080px;height:1920px;overflow:hidden}
body{font-family:Rv;direction:rtl;position:relative;background:linear-gradient(160deg,var(--a),var(--b))}
.blob{position:absolute;border-radius:50%%;filter:blur(90px);opacity:.55}
.b1{width:760px;height:760px;background:#fff;opacity:.18;top:-260px;left:-220px}
.b2{width:680px;height:680px;background:var(--a);top:900px;right:-260px;opacity:.7}
.b3{width:520px;height:520px;background:#FFC9E8;top:1350px;left:-180px;opacity:.28}
.grain{position:absolute;inset:0;background-image:radial-gradient(rgba(255,255,255,.10) 1.2px,transparent 1.3px);background-size:26px 26px;opacity:.5}
.card{position:absolute;top:72px;left:72px;right:72px;background:#fff;border-radius:64px;padding:52px 52px 50px;text-align:center;box-shadow:0 30px 80px rgba(10,12,60,.28)}
.logo{height:72px;margin:0 auto 20px;display:block}
.logo svg{height:72px;width:auto}
h1{font-weight:900;font-size:82px;line-height:1.28;color:#0B0F1B;letter-spacing:-.5px}
h1 em{font-style:normal;background:linear-gradient(90deg,var(--b),var(--a));-webkit-background-clip:text;background-clip:text;color:transparent}
p{text-wrap:balance;margin-top:22px;font-weight:500;font-size:40px;line-height:1.6;color:#4B5471}
.phone{position:absolute;left:50%%;top:%dpx;width:566px;height:1190px;margin-left:-283px;border-radius:78px;background:#0B0F1B;padding:20px;box-shadow:0 60px 120px rgba(5,8,40,.45),0 0 0 3px rgba(255,255,255,.18) inset;transform:rotate(%ddeg)}
.screen{width:100%%;height:100%%;border-radius:60px;overflow:hidden;background:#000}
.screen img{width:100%%;display:block}
.notch{position:absolute;top:38px;left:50%%;width:26px;height:26px;margin-left:-13px;border-radius:50%%;background:#1b2030}
'''
chrome='/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
os.makedirs(S+'/out',exist_ok=True)
for name,img,h1,p,(a,b),rot in slides:
    doc=f'''<!doctype html><html lang="fa"><head><meta charset="utf-8"><style>{css % (F,F,F,676,rot)}</style></head>
<body style="--a:{a};--b:{b}"><div class="blob b1"></div><div class="blob b2"></div><div class="blob b3"></div><div class="grain"></div>
<div class="phone"><div class="screen"><img src="file://{S}/src/{img}"></div><div class="notch"></div></div>
<div class="card"><div class="logo">{mark}</div><h1>{h1}</h1><p>{p}</p></div></body></html>'''
    hp=f'{S}/{name}.html'; open(hp,'w').write(doc)
    subprocess.run([chrome,'--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars','--force-device-scale-factor=1','--window-size=1080,2200','--allow-file-access-from-files',f'--screenshot={S}/out/{name}.png','file://'+hp],capture_output=True,timeout=120)
    from PIL import Image
    im=Image.open(f'{S}/out/{name}.png'); im.crop((0,0,1080,1920)).convert('RGB').save(f'{S}/out/{name}.png')
    print(name, im.size)
