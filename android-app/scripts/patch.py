#!/usr/bin/env python3
"""Runs after `npx cap add android`. Applies icons, splash, permissions, the sign-in return link and version."""
import os, re, shutil, sys, glob

root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
app = os.path.join(root, 'android', 'app')
res = os.path.join(app, 'src', 'main', 'res')
ov = os.path.join(root, 'res-overrides')

# 1) icons + colours
for d in glob.glob(os.path.join(ov, 'mipmap-*')) + [os.path.join(ov, 'values')]:
    dst = os.path.join(res, os.path.basename(d)); os.makedirs(dst, exist_ok=True)
    for f in os.listdir(d): shutil.copy(os.path.join(d, f), os.path.join(dst, f))

# 2) splash: replace every splash.png of the template
n = 0
for f in glob.glob(os.path.join(res, '**', 'splash.png'), recursive=True):
    src = 'splash_land.png' if 'land' in f else 'splash_port.png'
    shutil.copy(os.path.join(ov, src), f); n += 1
print('splash files replaced:', n)

# 3) manifest: permissions + link that brings the user back after Google sign-in
mp = os.path.join(app, 'src', 'main', 'AndroidManifest.xml')
m = open(mp, encoding='utf-8').read()
perms = ''.join('    <uses-permission android:name="android.permission.%s" />\n' % p for p in ['VIBRATE', 'CAMERA', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'])
perms += '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n'
if 'ACCESS_FINE_LOCATION' not in m:
    m = m.replace('</manifest>', perms + '</manifest>')
flt = ('            <intent-filter>\n'
       '                <action android:name="android.intent.action.VIEW" />\n'
       '                <category android:name="android.intent.category.DEFAULT" />\n'
       '                <category android:name="android.intent.category.BROWSABLE" />\n'
       '                <data android:scheme="com.atharva.dailylog" android:host="auth" />\n'
       '            </intent-filter>\n')
if 'com.atharva.dailylog' not in m:
    i = m.index('</activity>'); m = m[:i] + flt + '        ' + m[i:]
open(mp, 'w', encoding='utf-8').write(m)


# 3b) widgets, screenshot lock and the phone-side plugin (Java + layouts), receivers, exact alarms
nat = os.path.join(root, 'native')
jdst = os.path.join(app, 'src', 'main', 'java', 'com', 'atharva', 'dailylog')
os.makedirs(jdst, exist_ok=True)
for f in os.listdir(os.path.join(nat, 'java')):
    shutil.copy(os.path.join(nat, 'java', f), os.path.join(jdst, f))
for sub in ['layout', 'xml', 'drawable', 'values']:
    d = os.path.join(res, sub); os.makedirs(d, exist_ok=True)
    for f in os.listdir(os.path.join(nat, 'res', sub)):
        shutil.copy(os.path.join(nat, 'res', sub, f), os.path.join(d, f))
m = open(mp, encoding='utf-8').read()
if 'WaterWidget' not in m:
    recv = ''
    for cls, info in [('WaterWidget', 'widget_water_info'), ('TodoWidget', 'widget_todo_info'), ('SnippetsWidget', 'widget_snippets_info')]:
        recv += ('        <receiver android:name=".%s" android:exported="false">\n'
                 '            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>\n'
                 '            <meta-data android:name="android.appwidget.provider" android:resource="@xml/%s" />\n'
                 '        </receiver>\n') % (cls, info)
    m = m.replace('</application>', recv + '    </application>')
    m = m.replace('</manifest>', '    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />\n    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />\n</manifest>')
    open(mp, 'w', encoding='utf-8').write(m)

# 4) version: every build gets a higher number so it installs over the old one
gp = os.path.join(app, 'build.gradle')
g = open(gp, encoding='utf-8').read()
run = os.environ.get('GITHUB_RUN_NUMBER', '1')
g = re.sub(r'versionCode\s+\d+', 'versionCode ' + run, g)
g = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0.' + run + '"', g)
open(gp, 'w', encoding='utf-8').write(g)
print('patched OK, build', run)
