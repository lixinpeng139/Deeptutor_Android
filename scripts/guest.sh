#!/system/bin/sh
# Debug helper: run a command inside the app's guest the same way RuntimeService does.
# usage (from adb): run-as com.opendeeptutor.android sh /data/local/tmp/guest.sh <libdir> <cmd...>
APP=/data/user/0/com.opendeeptutor.android
# <libdir>: legacyNativeLibraryDir from `pm dump com.opendeeptutor.android`, plus /<abi>
LIB=$1
shift
export PROOT_LOADER=$LIB/libproot-loader.so LD_LIBRARY_PATH=$LIB PROOT_TMP_DIR=$APP/cache/proot-tmp
mkdir -p $PROOT_TMP_DIR
exec $LIB/libproot.so --kill-on-exit --link2symlink -0 -r $APP/files/rootfs \
  -b /dev -b /proc -b /sys -b $APP/cache/shm:/dev/shm -b $APP/files/home:/home/deeptutor \
  -w /home/deeptutor /usr/bin/env -i HOME=/home/deeptutor LANG=C.UTF-8 \
  PATH=/opt/python/bin:/opt/node/bin:/usr/bin:/bin PYTHONPATH=/opt/deeptutor/core \
  SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt DEEPTUTOR_HOME=/home/deeptutor/workspace "$@"
