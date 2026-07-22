#!/usr/bin/env bash
set -euo pipefail

ndk=${1:?Android NDK path is required}
jni_out=${2:?JNI output directory is required}
assets_out=${3:?runtime assets output directory is required}
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
build_jobs=${CODEX_MOBILE_NATIVE_JOBS:-4}
backend_libraries=(
  libcodex_mutool.so libcodex_tesseract.so
  libcodex_officecli.so libcodex_officecli_musl.so libcodex_officecli_gcc.so libcodex_officecli_cxx.so
  libcodex_tgcli.so libcodex_node.so libcodex_z.so libcodex_cares.so libcodex_sqlite3.so
  libcodex_crypto.so libcodex_ssl.so libcodex_icudata.so libcodex_icui18n.so libcodex_icuuc.so libcodex_cxx.so
)

case $(uname -s) in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo "Unsupported build host" >&2; exit 1 ;;
esac

toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
cc="$toolchain/aarch64-linux-android26-clang"
cxx="$toolchain/aarch64-linux-android26-clang++"
strip="$toolchain/llvm-strip"
for command in bsdtar curl cmake ninja make npm patch patchelf zip; do
  command -v "$command" >/dev/null || {
    echo "prepare-private-backends: missing build command: $command" >&2
    exit 1
  }
done
command -v sha256sum >/dev/null || command -v shasum >/dev/null || {
  echo "prepare-private-backends: missing SHA-256 command" >&2
  exit 1
}
test -x "$cc" || { echo "prepare-private-backends: invalid NDK: $ndk" >&2; exit 1; }

verify_sha256() {
  local expected=$1 file=$2 actual
  if command -v sha256sum >/dev/null; then
    actual=$(sha256sum "$file" | cut -d' ' -f1)
  else
    actual=$(shasum -a 256 "$file" | cut -d' ' -f1)
  fi
  test "$actual" = "$expected" || {
    echo "prepare-private-backends: checksum mismatch: $file" >&2
    return 1
  }
}

download() {
  local url=$1 sha256=$2 output=$3
  curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error "$url" -o "$output"
  verify_sha256 "$sha256" "$output"
}

extract_deb() {
  local archive=$1 destination=$2 unpack="$work/deb-unpack"
  rm -rf "$unpack"
  mkdir -p "$unpack" "$destination"
  bsdtar -xf "$archive" -C "$unpack"
  tar -xf "$unpack/data.tar.xz" -C "$destination"
}

mkdir -p "$jni_out" "$assets_out"
for library in "${backend_libraries[@]}"; do
  rm -f "$jni_out/$library"
done
rm -rf "$assets_out"
mkdir -p "$assets_out/licenses" "$assets_out/officecli" "$assets_out/tessdata"

# MuPDF and Tesseract share one official source bundle. Tofu builds omit large
# fallback fonts; text extraction and page rasterization remain available.
mupdf_archive="$work/mupdf.tar.gz"
download \
  "https://mupdf.com/downloads/archive/mupdf-1.28.0-source.tar.gz" \
  "21c7f064903154f1c3a7458bee81f130fc36f9b5147ea13328f9980e02d2dea2" \
  "$mupdf_archive"
mupdf="$work/mupdf"
mkdir -p "$mupdf"
tar -xzf "$mupdf_archive" -C "$mupdf" --strip-components=1
install -m 644 "$mupdf/COPYING" "$assets_out/licenses/mupdf-COPYING.txt"
install -m 644 "$mupdf/docs/license.md" "$assets_out/licenses/mupdf-license.md"
install -m 644 "$mupdf/thirdparty/tesseract/LICENSE" "$assets_out/licenses/tesseract-LICENSE.txt"
install -m 644 "$mupdf/thirdparty/leptonica/leptonica-license.txt" \
  "$assets_out/licenses/leptonica-LICENSE.txt"
while IFS= read -r license; do
  component=$(basename "$(dirname "$license")")
  filename=$(basename "$license")
  install -m 644 "$license" "$assets_out/licenses/mupdf-${component}-${filename}.txt"
done < <(find "$mupdf/thirdparty" -mindepth 2 -maxdepth 2 -type f \
  \( -name COPYING -o -name LICENSE -o -name LICENSE.txt -o -name LICENSE.TXT \) | sort)
make -C "$mupdf" generate
make -C "$mupdf" -j"$build_jobs" \
  OS=Linux build=small build_prefix=android/ \
  CC="$cc" CXX="$cxx" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
  HAVE_OBJCOPY=no HAVE_LIBCRYPTO=no HAVE_GLUT=no HAVE_X11=no HAVE_PTHREAD=no \
  tofu=yes tofu_cjk=yes LIBS='-lm -llog' tools
install -m 755 "$mupdf/build/android/small-tofu-tofu_cjk/mutool" "$jni_out/libcodex_mutool.so"

patch -d "$mupdf" -p1 < "$root/scripts/patches/tesseract-android.patch"
tess_build="$work/tesseract"
tess_prefix="$tess_build/prefix"
cmake -S "$mupdf/thirdparty/leptonica" -B "$tess_build/leptonica" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_INSTALL_PREFIX="$tess_prefix" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_PROG=OFF -DSW_BUILD=OFF \
  -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF -DENABLE_JPEG=OFF \
  -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF
cmake --build "$tess_build/leptonica" --target install -j "$build_jobs"
cmake -S "$mupdf/thirdparty/tesseract" -B "$tess_build/tesseract" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DLeptonica_DIR="$tess_prefix/lib/cmake/leptonica" \
  -DLEPT_TIFF_RESULT=1 -DLEPT_TIFF_COMPILE_SUCCESS=TRUE \
  -DCMAKE_INSTALL_PREFIX="$tess_prefix" -DBUILD_SHARED_LIBS=OFF -DSW_BUILD=OFF \
  -DOPENMP_BUILD=OFF -DGRAPHICS_DISABLED=ON -DBUILD_TRAINING_TOOLS=OFF \
  -DBUILD_TESTS=OFF -DDISABLE_TIFF=ON -DDISABLE_ARCHIVE=ON -DDISABLE_CURL=ON \
  -DINSTALL_CONFIGS=OFF -DENABLE_LTO=OFF -DENABLE_NATIVE=OFF \
  -DENABLE_PRECOMPILED_HEADERS=OFF -DENABLE_CCACHE=OFF
cmake --build "$tess_build/tesseract" --target tesseract -j "$build_jobs"
install -m 755 "$tess_build/tesseract/bin/tesseract" "$jni_out/libcodex_tesseract.so"
download \
  "https://github.com/tesseract-ocr/tessdata_fast/raw/refs/tags/4.1.0/eng.traineddata" \
  "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2" \
  "$assets_out/tessdata/eng.traineddata"

# OfficeCLI publishes an Alpine arm64 binary. A tiny Bionic launcher invokes
# it through a pinned musl loader, with only its two C++ runtime libraries.
officecli="$assets_out/officecli/officecli"
download \
  "https://github.com/iOfficeAI/OfficeCLI/releases/download/v1.0.139/officecli-linux-alpine-arm64" \
  "c59a6989cd8bb342a421d43a8ac0d01d56eee59631be3238c426d082b4c8c07c" \
  "$officecli"
download \
  "https://raw.githubusercontent.com/iOfficeAI/OfficeCLI/v1.0.139/LICENSE" \
  "7e282402a5a6db33995fe638bb3fe79013f9884d8f7d15a42e481c1e86aadda1" \
  "$assets_out/licenses/officecli-LICENSE.txt"
alpine="$work/alpine"
mkdir -p "$alpine"
for package in \
  "musl-1.2.5-r23.apk 6a3edd924ead1fad88a69e28c5775809af3026b322f58428001cd02fedc5299e" \
  "libgcc-15.2.0-r2.apk eaaafda78fde1c904e1741680ddea91649f051e29a343152c8a4327605704b0f" \
  "libstdc++-15.2.0-r2.apk 10d72e25f6fcc0f3d9fdd801c9bdaed81d6e836aa2b65b63f25d2d97f860a7d1"; do
  set -- $package
  download "https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/$1" "$2" "$work/$1"
  tar -xzf "$work/$1" -C "$alpine"
done
install -m 755 "$alpine/lib/ld-musl-aarch64.so.1" "$jni_out/libcodex_officecli_musl.so"
install -m 755 "$alpine/usr/lib/libgcc_s.so.1" "$jni_out/libcodex_officecli_gcc.so"
install -m 755 "$alpine/usr/lib/libstdc++.so.6" "$jni_out/libcodex_officecli_cxx.so"
patchelf --replace-needed libgcc_s.so.1 libcodex_officecli_gcc.so "$officecli"
patchelf --replace-needed libstdc++.so.6 libcodex_officecli_cxx.so "$officecli"
patchelf --replace-needed libgcc_s.so.1 libcodex_officecli_gcc.so "$jni_out/libcodex_officecli_cxx.so"
for library in "$officecli" "$jni_out/libcodex_officecli_gcc.so" "$jni_out/libcodex_officecli_cxx.so"; do
  patchelf --replace-needed libc.musl-aarch64.so.1 libcodex_officecli_musl.so "$library"
done
patchelf --set-soname libcodex_officecli_musl.so "$jni_out/libcodex_officecli_musl.so"
patchelf --set-soname libcodex_officecli_gcc.so "$jni_out/libcodex_officecli_gcc.so"
patchelf --set-soname libcodex_officecli_cxx.so "$jni_out/libcodex_officecli_cxx.so"
"$cc" -Os -fPIE -pie "$root/scripts/native/officecli-launcher.c" -o "$jni_out/libcodex_officecli.so"

# kfastov/tgcli 2.1.0 runs on a checksum-pinned Termux Node 24 runtime. The
# Android patch uses Node's built-in SQLite and adds a JSON login prompt seam;
# Kotlin invokes this private backend by absolute path; Codex never sees it on PATH.
termux="$work/termux"
mkdir -p "$termux"
while read -r path sha256; do
  archive="$work/${path##*/}"
  download "https://packages.termux.dev/apt/termux-main/$path" "$sha256" "$archive"
  extract_deb "$archive" "$termux"
done <<'PACKAGES'
pool/main/n/nodejs-lts/nodejs-lts_24.17.0_aarch64.deb 391428ee751dd1e960c8d3fbe02f7c2c18bb2b20a226d55ac920364e0bb51604
pool/main/libc/libc++/libc++_29_aarch64.deb bb9f12113c137aa0e8513bb51cc49fe77a5ce3ca39ab9e92c57d228ecdf00222
pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb 86760e9ce736f463236f2c15b1eb3a3fdcfc5778d0fd7077a917448dcc90f3aa
pool/main/c/c-ares/c-ares_1.34.8_aarch64.deb 7681fc23e822d7988ba8b2adf3468f93ae68f724dda365cff1385096a9fa87e6
pool/main/libi/libicu/libicu_78.3_aarch64.deb f536403f65a08fe0df6e7304184e902d54def77d5c3bd5edfd9109d57601d276
pool/main/libs/libsqlite/libsqlite_3.53.3_aarch64.deb 147365c5633b571bea063ab6c27022577fca89d73e99a7607030602b0166eded
pool/main/z/zlib/zlib_1.3.2_aarch64.deb 75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9
PACKAGES
termux_prefix="$termux/data/data/com.termux/files/usr"
for document in \
  "share/doc/nodejs-lts/copyright nodejs-copyright.txt" \
  "share/doc/c-ares/copyright c-ares-copyright.txt" \
  "share/doc/libicu/LICENSE icu-LICENSE.txt" \
  "share/doc/zlib/copyright zlib-copyright.txt"; do
  set -- $document
  install -m 644 "$termux_prefix/$1" "$assets_out/licenses/$2"
done
install -m 755 "$termux_prefix/bin/node" "$jni_out/libcodex_node.so"
install -m 755 "$termux_prefix/lib/libz.so" "$jni_out/libcodex_z.so"
install -m 755 "$termux_prefix/lib/libcares.so" "$jni_out/libcodex_cares.so"
install -m 755 "$termux_prefix/lib/libsqlite3.so" "$jni_out/libcodex_sqlite3.so"
install -m 755 "$termux_prefix/lib/libcrypto.so" "$jni_out/libcodex_crypto.so"
install -m 755 "$termux_prefix/lib/libssl.so" "$jni_out/libcodex_ssl.so"
install -m 755 "$termux_prefix/lib/libicudata.so" "$jni_out/libcodex_icudata.so"
install -m 755 "$termux_prefix/lib/libicui18n.so" "$jni_out/libcodex_icui18n.so"
install -m 755 "$termux_prefix/lib/libicuuc.so" "$jni_out/libcodex_icuuc.so"
install -m 755 "$termux_prefix/lib/libc++_shared.so" "$jni_out/libcodex_cxx.so"
for library in "$jni_out"/libcodex_{node,z,cares,sqlite3,crypto,ssl,icudata,icui18n,icuuc,cxx}.so; do
  patchelf --set-rpath '$ORIGIN' "$library"
done
while read -r old new; do
  for library in "$jni_out"/libcodex_{node,z,cares,sqlite3,crypto,ssl,icudata,icui18n,icuuc,cxx}.so; do
    if patchelf --print-needed "$library" | grep -Fxq "$old"; then
      patchelf --replace-needed "$old" "$new" "$library"
    fi
  done
done <<'NAMES'
libz.so.1 libcodex_z.so
libcares.so libcodex_cares.so
libsqlite3.so libcodex_sqlite3.so
libcrypto.so.3 libcodex_crypto.so
libssl.so.3 libcodex_ssl.so
libicudata.so.78 libcodex_icudata.so
libicui18n.so.78 libcodex_icui18n.so
libicuuc.so.78 libcodex_icuuc.so
libc++_shared.so libcodex_cxx.so
NAMES
for library in "$jni_out"/libcodex_{node,z,cares,sqlite3,crypto,ssl,icudata,icui18n,icuuc,cxx}.so; do
  patchelf --set-soname "${library##*/}" "$library"
  "$strip" "$library"
done
"$cc" -Os -fPIE -pie "$root/scripts/native/tgcli-launcher.c" -o "$jni_out/libcodex_tgcli.so"

tgcli_archive="$work/tgcli.tar.gz"
download \
  "https://github.com/kfastov/tgcli/archive/649d93701fa6cf36f52031c8dfc67a2e86a2b7f7.tar.gz" \
  "f1be9cd6b4b9170da4fc64be6b95377be6bee054bc49731d8bcb39dfdbcd94ed" \
  "$tgcli_archive"
tgcli="$work/tgcli"
mkdir -p "$tgcli"
tar -xzf "$tgcli_archive" -C "$tgcli" --strip-components=1
install -m 644 "$tgcli/LICENSE" "$assets_out/licenses/tgcli-LICENSE.txt"
install -m 644 "$root/scripts/native/tgcli-package-lock.json" "$tgcli/package-lock.json"
(cd "$tgcli" && npm ci --ignore-scripts --omit=dev)
(cd "$tgcli" && npm audit --omit=dev --audit-level=high)

# Sending is available only while the pinned sources prove one SDK submission
# for retries=0 and reuse the generated random_id when resolving the update.
grep -Fq 'const totalAttempts = (details.retries ?? 0) + 1' "$tgcli/cli.js"
for method in send-text.js send-media.js; do
  source_path=$(find "$tgcli/node_modules/@mtcute/core" -type f -name "$method" -print -quit)
  test -n "$source_path"
  test "$(grep -Fc 'const randomId = randomLong();' "$source_path")" -eq 1
  test "$(grep -Fc 'const res = await _maybeInvokeWithBusinessConnection(' "$source_path")" -eq 1
  grep -Fq '_findMessageInUpdate(client, res, false, !params.shouldDispatch, false, randomId);' "$source_path"
done
patch -d "$tgcli" -p1 < "$root/scripts/patches/tgcli-android.patch"
rm -rf "$tgcli/node_modules/better-sqlite3"
(cd "$tgcli" && zip -q -r "$assets_out/tgcli.zip" \
  cli.js client.js mcp-client.js mcp-server.js message-sync-service.js store-lock.js \
  telegram-client.js core node_modules package.json)

verify_android_dependencies() {
  local binary=$1 needed
  while IFS= read -r needed; do
    case "$needed" in
      libcodex_*.so|libc.so|libm.so|libdl.so|liblog.so|libandroid.so) ;;
      *) echo "prepare-private-backends: unexpected dependency $needed in $binary" >&2; return 1 ;;
    esac
  done < <(patchelf --print-needed "$binary")
}
for library in "${backend_libraries[@]}"; do
  verify_android_dependencies "$jni_out/$library"
done
verify_android_dependencies "$officecli"

chmod 755 "$jni_out"/libcodex_*.so
printf 'Prepared private Android arm64 document and Telegram backends.\n'
