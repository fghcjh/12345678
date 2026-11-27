#!/bin/bash
# 同步 assets 到浏览器预览目录，并生成各视图的快捷页（注入 mock 桥）
set -e
SRC=/root/12345678/memochat/assets
DST=/root/projects/memochat-preview
cp $SRC/app.css $SRC/app.js $SRC/avatar.js $SRC/viewer3d.js $DST/
mkdir -p $DST/lib && cp $SRC/lib/*.js $DST/lib/
python3 - <<'PY'
import os
src = '/root/12345678/memochat/assets/index.html'
dst = '/root/projects/memochat-preview'
base = open(src).read()
assert '<script src="app.js"></script>' in base
for view in ['', 'memory', 'settings', 'sessions', 'empty', 'stream', 'nokey', 'hold', 'character', 'charbook', 'savechar', 'media', 'mediasend', 'visionset', 'stringset', 'stringsend', 'attachmenu', 'camera', 'callmock', 'voice', 'callvoice', 'voiceset', 'avatar', 'avatarset', 'avatarupload', 'avatar3d', 'modelstage', 'callmodel', 'grow', 'market']:
    inject = '<script>window.PREVIEW_VIEW="%s";</script>\n<script src="mock.js"></script>\n<script src="app.js"></script>' % view
    page = base.replace('<script src="app.js"></script>', inject)
    name = (view or 'index') + '.html'
    open(os.path.join(dst, name), 'w').write(page)
print('pages written')
PY
echo "preview synced"
