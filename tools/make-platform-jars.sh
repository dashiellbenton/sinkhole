#!/bin/sh
# Builds smaller per-platform jars from target/SinkholeMC.jar by removing the WebRTC natives of the other platforms.
# usage: tools/make-platform-jars.sh   (run `mvn package` first) -> dist/SinkholeMC-{windows,linux,macos}.jar
set -e
SRC=target/SinkholeMC.jar
mkdir -p dist
WIN=webrtc-java-windows-x86_64.dll
LIN=libwebrtc-java-linux-x86_64.so
MACX=libwebrtc-java-macos-x86_64.dylib
MACA=libwebrtc-java-macos-aarch64.dylib
make() { cp "$SRC" "dist/SinkholeMC-$1.jar"; shift; zip -q -d "dist/SinkholeMC-$1.jar" "$@" >/dev/null 2>&1 || true; }
cp "$SRC" dist/SinkholeMC-windows.jar && zip -q -d dist/SinkholeMC-windows.jar $LIN $MACX $MACA >/dev/null
cp "$SRC" dist/SinkholeMC-linux.jar   && zip -q -d dist/SinkholeMC-linux.jar   $WIN $MACX $MACA >/dev/null
cp "$SRC" dist/SinkholeMC-macos.jar   && zip -q -d dist/SinkholeMC-macos.jar   $WIN $LIN >/dev/null
ls -la dist
