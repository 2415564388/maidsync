#!/usr/bin/env bash
# MaidSync 构建脚本（无 ForgeGradle，直接 javac + jar）
# 用法：在 Git Bash 里执行  bash build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ─────────────────────────────────────────────────────────────────────────────
# 换设备请改这三行，或用环境变量覆盖（推荐，不用改文件）：
#   MAIDSYNC_LIB   整合包的 .minecraft/libraries
#   MAIDSYNC_MODS  整合包的 .minecraft/versions/<实例名>/mods
#   MAIDSYNC_JDK   JDK 21 的 bin 目录（必须 21，更高版本编出的 class 1.21.1 跑不了）
#
#   export MAIDSYNC_LIB="D:/MC/.minecraft/libraries"
#   export MAIDSYNC_MODS="D:/MC/.minecraft/versions/pack/mods"
#   export MAIDSYNC_JDK="C:/Program Files/Java/jdk-21/bin"
# ─────────────────────────────────────────────────────────────────────────────
LIB="${MAIDSYNC_LIB:-E:/血族机械师/.minecraft/libraries}"
MODS="${MAIDSYNC_MODS:-E:/血族机械师/.minecraft/versions/血族机械师redux/mods}"
JDK="${MAIDSYNC_JDK:-C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin}"

# 提前报错，别等编译到一半才说找不到
for p in "$LIB" "$MODS" "$JDK"; do
    [ -d "$p" ] || { echo "[错误] 目录不存在：$p" >&2; echo "       请参考脚本顶部的说明设置 MAIDSYNC_LIB / MAIDSYNC_MODS / MAIDSYNC_JDK" >&2; exit 1; }
done
[ -x "$JDK/javac.exe" ] || { echo "[错误] $JDK/javac.exe 不存在——请指向 JDK 21 的 bin" >&2; exit 1; }
JAVAC="$JDK/javac.exe"
JAR="$JDK/jar.exe"

VERSION="2.1.0"
NAME="maidsync-$VERSION.jar"

# NeoForge 补丁类在前，其余兜底
CP="$LIB/net/neoforged/neoforge/21.1.228/neoforge-21.1.228-client.jar"
CP="$CP;$LIB/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar"
CP="$CP;$LIB/net/neoforged/neoforge/21.1.228/neoforge-21.1.228-universal.jar"
CP="$CP;$LIB/net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar"
CP="$CP;$LIB/net/neoforged/bus/8.0.5/bus-8.0.5.jar"
CP="$CP;$LIB/net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/sponge-mixin-0.15.2+mixin.0.8.7.jar"
CP="$CP;$LIB/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
# Component 需要 brigadier，BuiltInRegistries 需要 datafixerupper
CP="$CP;$LIB/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"
CP="$CP;$LIB/com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar"
# IntList（ClientboundRemoveEntitiesPacket.getEntityIds）
CP="$CP;$LIB/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar"
# Sable 的 Pose3d 签名用到 joml
CP="$CP;$LIB/org/joml/joml/1.10.5/joml-1.10.5.jar"
CP="$CP;$MODS/touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar"
# Sable。它把 companion 库以 jarjar 形式嵌在 jar 里，而 SubLevel.logicalPose() 等签名要用到它，
# 所以编译期必须先把那个嵌套 jar 抽出来（只在 build/libs 下，不进产物）。
SABLE_JAR="$MODS/sable-neoforge-1.21.1-2.0.5.jar"
CP="$CP;$SABLE_JAR"
COMPANION=$(unzip -l "$SABLE_JAR" 2>/dev/null | awk '/sable-companion.*\.jar$/ {print $NF; exit}')
if [ -n "$COMPANION" ]; then
    # 注意放在 build/ 之外：构建脚本后面会 rm -rf build
    mkdir -p "$ROOT/libs"
    unzip -o -q -j "$SABLE_JAR" "$COMPANION" -d "$ROOT/libs"
    # cygpath -w：classpath 是以 ';' 拼接的整串，MSYS 不会转换里面的 Unix 风格路径，
    # 而这个路径来自 $ROOT（/f/...），不转的话 javac 找不到它
    CP="$CP;$(cygpath -w "$ROOT/libs/$(basename "$COMPANION")")"
else
    echo "[WARN] 没在 Sable jar 里找到嵌套的 companion 库，编译可能失败" >&2
fi

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/src/main/java" -name '*.java' | sort)
echo "编译 ${#SOURCES[@]} 个源文件 ..."

# -proc:none 关掉注解处理：sponge-mixin 里的 MixinObfuscationProcessor 会被 javac 自动发现，
# 但它需要 ASM 才跑得起来，而且它唯一的产出是 refmap —— NeoForge 1.20.2+ 运行时就是官方映射名，
# 不需要 refmap（参考 promaid 的 jar，里面也没有任何 *.refmap.json）。
#
# 交给 MSYS 做路径转换：javac/jar 是原生 Windows 程序，认不了 /f/... 这种 Unix 路径。
# -cp 里是 ';' 分隔的 Windows 路径，MSYS 会逐段转换，正好是我们想要的。
"$JAVAC" -encoding UTF-8 --release 21 -nowarn -proc:none \
    -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}"

cp -r "$ROOT/src/main/resources/." "$OUT/classes/"

# jar 的 -C 需要 Windows 路径，这里自行转一次，避免依赖 MSYS 的启发式
"$JAR" --create --file "$(cygpath -w "$OUT/$NAME")" -C "$(cygpath -w "$OUT/classes")" .

cp "$OUT/$NAME" "$MODS/"
echo "完成 -> $MODS/$NAME"
