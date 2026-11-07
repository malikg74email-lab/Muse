#!/usr/bin/env bash
# =============================================================================
# Muse Runtime Pack 构建脚本（在 WSL / Linux 环境运行）
# -----------------------------------------------------------------------------
# 作用：从 Termux 官方仓库下载 Node.js 运行时（node 主程序 + 依赖共享库 + npm），
#       经 patchelf 规范化（改名 / SONAME / DT_NEEDED / RPATH=$ORIGIN）后输出：
#
#   app/src/main/jniLibs/<abi>/          ← 可执行部分（node + 9 个依赖库）
#   app/src/main/assets/muse-runtime-assets.zip ← 数据部分（npm，纯 JS）
#
# 用法：
#   bash tools/runtime-pack/build-runtime-pack.sh both     # arm64 + x86_64（默认）
#   bash tools/runtime-pack/build-runtime-pack.sh arm64
#
# 环境依赖（WSL Ubuntu 示例）：
#   sudo apt-get install -y curl ca-certificates patchelf
#
# 版本策略（2026-11-07 起）：不再写死版本号。写死已三次被 Termux 仓库淘汰
#   （2026-11-03 nodejs/libsqlite/npm、2026-11-05 npm、2026-11-07 zlib）。
#   现在每次构建从官方 Packages 索引按包名解析当前版本与 Filename（见下方
#   「Termux 索引解析」一节），索引里找不到就大声报错终止，绝不用旧版顶替。
#
# 升级流程（逐项同步）：
#   1. 版本号无需维护（自动解析）；包集合变化时改下方 PKG_NAME 与 COLLECT。
#   2. npm 版本变化无需再手工同步 MuseRuntime：重解压标记已由
#      MuseRuntime.bundledDataVersion() 从本包 VERSION.txt（下方从真实
#      package.json 写出）派生，npm 浮动后升级设备自动重解压。
#      MuseRuntime.RUNTIME_DATA_VERSION 只是 VERSION.txt 读不到时的兜底。
#   3. 若新增依赖库，补充 app/build.gradle.kts 的 jniLibs.keepDebugSymbols 列表
#   4. 重新构建后，跑 RuntimeSelfCheckTest（connected 测试）验证
#
# ⚠️ 重要陷阱（血泪教训，勿改）：
#   AGP 的 stripDebugDebugSymbols 任务（llvm-strip）对 patchelf 处理过的 ELF 会
#   重排段布局、破坏程序头与内容映射，导致运行时 SIGSEGV。因此所有运行时库都
#   必须列入 build.gradle.kts 的 keepDebugSymbols 以跳过 strip。
#   验证方法：构建后对比 app/build/intermediates/stripped_native_libs/.../lib*.so
#   与 app/src/main/jniLibs/.../lib*.so 的 md5，必须一致。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORK="${MUSE_RUNTIME_WORK:-/tmp/muse-runtime-pack}"

# ─── 包集合：内部 key -> Termux 包名（版本由下方索引解析动态决定） ──────────
declare -A PKG_NAME=(
  [nodejs]="nodejs" [libcxx]="libc++" [openssl]="openssl" [cares]="c-ares"
  [libicu]="libicu" [libsqlite]="libsqlite" [zlib]="zlib" [libffi]="libffi"
  [npm]="npm"
)

# ─── 文件收集表：源文件 glob -> 输出名（jniLibs 只接受 lib*.so 命名） ──────
# 带版本后缀的实体名（libz.so.1.4、libicui18n.so.78.3、libsqlite3.54.1.so）
# 随上游版本浮动，故用 glob 匹配；收集时据实际命中文件名动态构建 FILEMAP。
declare -a COLLECT=(
  "libc++_shared.so|libc++_shared.so"
  "libssl.so.3|libssl.so"
  "libcrypto.so.3|libcrypto.so"
  "libcares.so|libcares.so"
  "libicui18n.so.*|libicui18n.so"
  "libicuuc.so.*|libicuuc.so"
  "libicudata.so.*|libicudata.so"
  "libffi.so|libffi.so"
  "libsqlite3*.so|libsqlite3.so"
  "libz.so.*|libz.so"
)
declare -A FILEMAP=()

# ─── DT_NEEDED 映射：原字符串 -> 新名（含链接名与实体名两种形态） ───────────
declare -A NEEDMAP=(
  ["libz.so.1"]="libz.so"
  ["libz.so.1.3.2"]="libz.so"
  ["libcrypto.so.3"]="libcrypto.so"
  ["libssl.so.3"]="libssl.so"
  ["libicui18n.so.78"]="libicui18n.so"
  ["libicui18n.so.78.3"]="libicui18n.so"
  ["libicuuc.so.78"]="libicuuc.so"
  ["libicuuc.so.78.3"]="libicuuc.so"
  ["libicudata.so.78"]="libicudata.so"
  ["libicudata.so.78.3"]="libicudata.so"
  ["libsqlite3.so.3.53.4"]="libsqlite3.so"
)

# === RESOLUTION SECTION BEGIN (test-extractable) ===
# ─── Termux 索引解析：构建时从官方 Packages 索引解析版本，绝不写死 ────────
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
TERMUX_DIST="stable"
INDEX_DIR="$WORK/index"
declare -A RESOLVED_FILE=()   # "key|abi" -> pool 相对 Filename
declare -A RESOLVED_VER=()    # "key|abi" -> Version

termux_arch() { # $1=arm64|x86_64|all；本脚本叫 arm64，仓库叫 aarch64；arch:all 的包（如 npm）在各 arch 索引中都有 stanza，all 用 aarch64 索引
  if [ "$1" = "x86_64" ]; then echo "x86_64"; else echo "aarch64"; fi
}

fetch_index() { # $1=termux arch；每次运行重新下载，避免陈旧索引指向已被淘汰的文件
  local arch="$1" out="$INDEX_DIR/Packages-$arch"
  mkdir -p "$INDEX_DIR"
  echo "  解析 Termux 索引 ($arch) ..." >&2
  curl -sSL --fail --retry 3 --connect-timeout 20 -o "$out" \
    "$TERMUX_REPO/dists/$TERMUX_DIST/main/binary-$arch/Packages"
}

resolve_pkg() { # $1=key $2=abi(arm64|x86_64|all)；填充 RESOLVED_FILE / RESOLVED_VER
  local key="$1" abi="$2" slot="$1|$2"
  [ -n "${RESOLVED_FILE[$slot]:-}" ] && return 0
  local name="${PKG_NAME[$key]:-}"
  [ -n "$name" ] || { echo "!! 未知包 key: $key" >&2; exit 1; }
  local arch; arch=$(termux_arch "$abi")
  fetch_index "$arch"
  local parsed
  parsed=$(awk -v want="$name" '
    BEGIN { RS=""; FS="\n" }
    {
      pkg=""; ver=""; fn=""
      for (i=1; i<=NF; i++) {
        if ($i ~ /^Package: /) pkg=substr($i, 10)
        else if ($i ~ /^Version: /) ver=substr($i, 10)
        else if ($i ~ /^Filename: /) fn=substr($i, 11)
      }
      if (pkg == want) { print ver "\t" fn; exit }
    }' "$INDEX_DIR/Packages-$arch")
  local ver="${parsed%%$'\t'*}" fn="${parsed##*$'\t'}"
  if [ -z "$parsed" ] || [ -z "$ver" ] || [ -z "$fn" ]; then
    echo "!! 包 $name 在 Termux 索引中未找到 (abi=$abi, arch=$arch) — 终止，不做任何替代" >&2
    exit 1
  fi
  RESOLVED_FILE[$slot]="$fn"
  RESOLVED_VER[$slot]="$ver"
  echo "  [解析] $name ($abi) -> $ver" >&2
}

verify_deb() { # $1=文件：必须是真正的 ar 归档，否则大声报错终止（杜绝 404 页面冒充 .deb）
  ar t "$1" >/dev/null 2>&1 || { echo "!! $1 不是有效的 .deb（ar 校验失败）" >&2; exit 1; }
}

FETCHED_DEB=""
fetch_deb() { # $1=包 key $2=abi $3=目标目录；结果路径写入全局 FETCHED_DEB
  # 注意：必须直接调用、不能用 $(fetch_deb ...) 命令替换——子 shell 会把
  # resolve_pkg 填好的 RESOLVED_* 状态吞掉，后续步骤将拿到空版本。
  local key="$1" abi="$2" destdir="$3"
  resolve_pkg "$key" "$abi"
  local dest="$destdir/$(basename "${RESOLVED_FILE[$key|$abi]}")"
  mkdir -p "$destdir"
  if [ -f "$dest" ] && ! ar t "$dest" >/dev/null 2>&1; then
    echo "  缓存损坏，重新下载 $key ($abi) ..." >&2
    rm -f "$dest"
  fi
  if [ ! -f "$dest" ]; then
    echo "  下载 $key ($abi) ${RESOLVED_VER[$key|$abi]} ..." >&2
    curl -sSL --fail --retry 3 --connect-timeout 20 -o "$dest" "$TERMUX_REPO/${RESOLVED_FILE[$key|$abi]}"
  fi
  verify_deb "$dest"
  FETCHED_DEB="$dest"
}
# === RESOLUTION SECTION END ===

unpack_deb() { # $1=deb 文件 $2=输出目录
  local deb="$1" out="$2"
  rm -rf "$out"; mkdir -p "$out"
  (cd "$out" && ar x "$deb")
  local dt
  dt=$(cd "$out" && ls data.tar.* 2>/dev/null | head -1)
  [ -n "$dt" ] || { echo "!! $deb 无 data.tar"; exit 1; }
  (cd "$out" && mkdir -p data && tar xf "$dt" -C data)
}

# ─── 单 ABI 组装 ────────────────────────────────────────────────────────────
build_abi() { # $1=arm64|x86_64
  local abi="$1"
  local base="$WORK/$abi"
  local debs="$base/debs"
  local work="$base/pack"
  # Android 的 jniLibs 目录名与 Termux 的 ABI 名不一致
  local outabi="$abi"
  [ "$abi" = "arm64" ] && outabi="arm64-v8a"
  local outdir="$PROJ_ROOT/app/src/main/jniLibs/$outabi"

  echo "== [$abi] 下载 =="
  mkdir -p "$debs"
  declare -A DEB_PATH=() UNPACK_DIR=()
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    fetch_deb "$key" "$abi" "$debs"
    DEB_PATH[$key]="$FETCHED_DEB"
  done

  echo "== [$abi] 解包 =="
  for key in nodejs libcxx openssl cares libicu libsqlite zlib libffi; do
    UNPACK_DIR[$key]="$base/unpacked/$(basename "${DEB_PATH[$key]}" .deb)"
    [ -d "${UNPACK_DIR[$key]}" ] || unpack_deb "${DEB_PATH[$key]}" "${UNPACK_DIR[$key]}"
  done

  echo "== [$abi] 收集源文件 =="
  rm -rf "$work"; mkdir -p "$work"
  local missing=0
  FILEMAP=()
  local pair pat out src
  for pair in "${COLLECT[@]}"; do
    pat="${pair%%|*}"; out="${pair##*|}"
    # 同一 glob 命中多个实体文件时取路径最长者（即版本后缀最全的实体文件）
    src=$(find "$base/unpacked" -name "$pat" -type f | awk '{ print length($0) "\t" $0 }' | sort -rn | head -1 | cut -f2- || true)
    if [ -z "$src" ]; then echo "  !! 未找到 $pat"; missing=1; continue; fi
    FILEMAP[$(basename "$src")]="$out"
    cp "$src" "$work/$(basename "$src")"
  done
  [ "$missing" = 1 ] && { echo "!! [$abi] 依赖缺失，终止"; exit 1; }

  local node_src
  node_src=$(find "${UNPACK_DIR[nodejs]}" -type f -name node | head -1)
  cp "$node_src" "$work/libmuse_node.so"

  # 依据本次解析到的实际版本补充 DT_NEEDED 映射（静态表中的旧版本条目命中不到时自动无害失效）
  local icu_ver="${RESOLVED_VER[libicu|$abi]}" z_ver="${RESOLVED_VER[zlib|$abi]}"
  local icu_major="${icu_ver%%.*}"
  NEEDMAP["libicui18n.so.${icu_ver}"]="libicui18n.so"
  NEEDMAP["libicuuc.so.${icu_ver}"]="libicuuc.so"
  NEEDMAP["libicudata.so.${icu_ver}"]="libicudata.so"
  NEEDMAP["libicui18n.so.${icu_major}"]="libicui18n.so"
  NEEDMAP["libicuuc.so.${icu_major}"]="libicuuc.so"
  NEEDMAP["libicudata.so.${icu_major}"]="libicudata.so"
  NEEDMAP["libz.so.${z_ver}"]="libz.so"

  echo "== [$abi] patchelf 处理 =="
  (cd "$work"
    for f in "${!FILEMAP[@]}"; do
      patchelf --set-soname "${FILEMAP[$f]}" "$f"
    done
    for f in *; do
      [ -f "$f" ] || continue
      for old in "${!NEEDMAP[@]}"; do
        new="${NEEDMAP[$old]}"
        [ "$old" = "$new" ] && continue
        patchelf --replace-needed "$old" "$new" "$f" 2>/dev/null || true
      done
      patchelf --set-rpath '$ORIGIN' "$f"
    done
    for f in "${!FILEMAP[@]}"; do
      new="${FILEMAP[$f]}"
      [ "$f" != "$new" ] && mv "$f" "$new"
    done
  )

  echo "== [$abi] 16KB 页对齐校验 =="
  local fail=0
  for f in "$work"/*; do
    local al
    al=$(readelf -lW "$f" | awk '/LOAD/{print $NF; exit}')
    [ "$al" = "0x4000" ] || { echo "  !! $(basename "$f") align=$al"; fail=1; }
  done
  [ "$fail" = 1 ] && { echo "!! [$abi] 存在非 16KB 对齐文件"; exit 1; }
  echo "  全部 16KB 对齐 ✓"

  echo "== [$abi] 输出 -> $outdir =="
  mkdir -p "$outdir"
  cp "$work"/* "$outdir/"
  ls -lh "$outdir"
}

# ─── npm 数据包（assets，一次性） ────────────────────────────────────────────
build_npm_assets() {
  local base="$WORK/npm"
  local stage="$base/stage"
  local outzip="$PROJ_ROOT/app/src/main/assets/muse-runtime-assets.zip"

  echo "== [npm] 下载并解包 =="
  mkdir -p "$base/debs"
  fetch_deb npm all "$base/debs"
  local npm_deb="$FETCHED_DEB"
  local npm_unpacked="$base/unpacked/$(basename "$npm_deb" .deb)"
  [ -d "$npm_unpacked" ] || unpack_deb "$npm_deb" "$npm_unpacked"

  rm -rf "$stage"; mkdir -p "$stage"
  local npm_dir
  npm_dir=$(find "$npm_unpacked" -type d -path '*usr/lib/node_modules/npm' | head -1)
  cp -r "$npm_dir" "$stage/npm"
  echo "npm $(grep -m1 '"version"' "$stage/npm/package.json" | sed 's/.*: *"\([^"]*\)".*/\1/')" > "$stage/VERSION.txt"

  echo "== [npm] 打包 -> $outzip =="
  [ -f "$outzip" ] && rm -f "$outzip"
  python3 - "$stage" "$outzip" <<'PYEOF'
import sys, zipfile, os
root, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dirpath, dirnames, filenames in os.walk(root):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            z.write(full, os.path.relpath(full, root))
            count += 1
print(f"  zip 完成: {count} 个文件, {os.path.getsize(out)/1e6:.2f}MB")
PYEOF
}

# ─── 主流程 ─────────────────────────────────────────────────────────────────
TARGETS="${1:-both}"
case "$TARGETS" in
  both)  TARGET_LIST=(arm64 x86_64) ;;
  arm64) TARGET_LIST=(arm64) ;;
  x86_64) TARGET_LIST=(x86_64) ;;
  *) echo "用法: $0 [both|arm64|x86_64]"; exit 1 ;;
esac

mkdir -p "$WORK"
for t in "${TARGET_LIST[@]}"; do build_abi "$t"; done
build_npm_assets

echo
echo "============================================================"
echo "构建完成。产物："
echo "  app/src/main/jniLibs/{arm64-v8a,x86_64}/  （可执行部分）"
echo "  app/src/main/assets/muse-runtime-assets.zip （npm 数据）"
echo "提醒：跑 :app:connectedDebugAndroidTest -P...class=...RuntimeSelfCheckTest 做验证。"
