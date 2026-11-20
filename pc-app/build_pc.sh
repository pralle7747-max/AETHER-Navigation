#!/bin/bash
# usage: build_pc.sh <version> <public dir>
set -e
D=/tmp/claude-0/-home-user-Samsung-cam-2-0/380022c1-4d37-542c-be2b-6ca3df2ba473/scratchpad/pc
V=$1; PUB=$2; OUT=$D/out/AETHER
rm -rf $D/out && mkdir -p $OUT && cd $OUT && unzip -q $D/electron-win.zip
mv electron.exe AETHER.exe && rm -f resources/default_app.asar LICENSES.chromium.html
mkdir -p resources/app/www && cp -r $PUB/. resources/app/www/ && cp $D/app/main.js $D/app/preload.js $D/app/icon.png resources/app/
printf '{"name":"aether","productName":"AETHER","version":"%s.0.0","main":"main.js"}\n' $V > resources/app/package.json
cd $D && node seticon.js $OUT/AETHER.exe $D/icon.ico $V
cd $D/out && rm -f $D/AETHER-PC-v$V.zip && zip -qr -9 $D/AETHER-PC-v$V.zip AETHER && ls -la $D/AETHER-PC-v$V.zip
cd $D && makensis -V2 -DVER=$V -DOUTFILE=$D/AETHER-Setup-v$V.exe setup.nsi && ls -la $D/AETHER-Setup-v$V.exe
cd $D && makensis -V2 -DVER=$V -DOUTFILE=$D/AETHER-v$V.exe portable.nsi && ls -la $D/AETHER-v$V.exe
