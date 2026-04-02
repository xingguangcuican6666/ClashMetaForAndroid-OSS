# Compiled Zygisk shared libraries (*.so) are placed here by the CI build.
# They are NOT committed to the repository — they are produced by the NDK
# build step in the GitHub Actions workflow.
#
# Expected files after a CI build:
#   arm64-v8a.so
#   armeabi-v7a.so
#   x86.so
#   x86_64.so
#
# To build locally:
#   NDK=$HOME/Android/Sdk/ndk/<version>
#   for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
#     cmake -DANDROID_ABI=$ABI \
#           -DANDROID_PLATFORM=android-21 \
#           -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#           -DCMAKE_BUILD_TYPE=Release \
#           -B /tmp/build_$ABI \
#           $(dirname $0)
#     cmake --build /tmp/build_$ABI
#     cp /tmp/build_$ABI/libmihomo_zygisk.so $ABI.so
#   done
