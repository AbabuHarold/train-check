#!/bin/sh
# shared/ の core.js と timetable.json を Webアプリ・Alexa・Androidウィジェットにコピーする
cd "$(dirname "$0")"
cp shared/core.js shared/timetable.json docs/
cp shared/core.js shared/timetable.json alexa/lambda/
cp shared/timetable.json android/app/src/main/assets/
echo "synced"
