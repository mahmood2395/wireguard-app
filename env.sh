# Build environment for the MyVPN fork of wireguard-android.
# Usage:  source env.sh   (from /Users/m.mansouri/dev/wireguard-app)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# toolshim supplies GNU sha256sum (macOS /sbin/sha256sum lacks GNU -c);
# flock comes from `brew install flock`. Both are needed by
# tunnel/tools/libwg-go/Makefile, which bootstraps its own pinned Go toolchain.
export PATH="$JAVA_HOME/bin:/Users/m.mansouri/dev/wireguard-app/toolshim:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
