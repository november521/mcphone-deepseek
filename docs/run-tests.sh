#!/usr/bin/env bash
# 跑 docs/ 下那两个断言测试。
#
# 为什么不是 ./gradlew test：这两个要 Minecraft 的类路径（Turn 的错误信息是
# Component，Md 产出的是带 Style 的 Component），而给这个工程接一套测试框架，
# 要引的东西比被测的代码还多。本体 docs/ 下那几个测试是同一个思路——能用
# javac 编就用 javac，这里只是多一步把类路径拼出来。
#
# 前置：先跑过一次 ./gradlew build（要有 build/classes 和 moddev 的构件）
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=$ROOT/build/tmp/docs-tests
MANIFEST=$ROOT/build/tmp/createMinecraftArtifacts/nfrt_artifact_manifest.properties
MERGED=$ROOT/build/moddev/artifacts/neoforge-21.1.248-merged.jar

if [ ! -f "$MANIFEST" ] || [ ! -f "$MERGED" ]; then
    echo "先跑一次 ./gradlew build" >&2
    exit 1
fi

# 类路径 = manifest 里的全部依赖 + moddev 产出的 minecraft+neoforge
#        + MCphone 本体 + 我们自己的 class
#
# 本体那个 jar 要单独加：它在 build.gradle 里是 compileOnly，不进 manifest。
# 少了它，凡是签名里带 PhoneCanvas / IPhonePage 的类一加载就 NoClassDefFoundError
CP=$(grep -v '^#' "$MANIFEST" | cut -d= -f2- | tr '\n' ':')
MCPHONE=$(ls "$ROOT"/libs/mcphone-*.jar 2>/dev/null | head -1)
if [ -z "$MCPHONE" ]; then
    echo "libs/ 下没有 MCphone 的 jar" >&2
    exit 1
fi
CP="$CP$MERGED:$MCPHONE:$ROOT/build/classes/java/main"

rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 -cp "$CP" -d "$OUT" \
    docs/DeepSeekClientTest.java docs/MdTest.java docs/LayoutTest.java

echo "── 对齐 ──"
java -cp "$CP:$OUT" LayoutTest 2>&1 | grep -v '^SLF4J'

echo
echo "── Markdown ──"
java -cp "$CP:$OUT" MdTest 2>&1 | grep -v '^SLF4J'

echo
echo "── 网络层 ──"

# 假服务端和测试都在一个临时目录里跑，工程目录一个字节都不落。
#
# 两边都会写文件：服务端记下收到的请求体（好让人核对请求形状），测试则会
# 生成 config/mcphone_deepseek/ 和对话记录。让它们写在工程根目录的话，跑一次
# 测试就多出几个不该提交的文件——而那种文件最容易被顺手 git add 进去。
WORK=$(mktemp -d)
SERVER=""
trap '[ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT

(cd "$WORK" && exec python3 "$ROOT/docs/fake_deepseek.py") &
SERVER=$!
sleep 1

(cd "$WORK" && java -cp "$CP:$OUT" DeepSeekClientTest 2>&1 | grep -vE '^SLF4J|WARN ')
