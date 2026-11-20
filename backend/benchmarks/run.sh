#!/usr/bin/env bash
set -euo pipefail

neko_backend="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
neko_bench_dir="$(mktemp -d)"
trap 'rm -rf -- "$neko_bench_dir"' EXIT

# 可指定优化前的编译类目录；依赖与基准程序保持一致，便于分别启动 JVM 比较。
neko_classes="${NEKO_BENCH_CLASSES:-$neko_backend/target/classes}"
if [[ -n "${NEKO_BENCH_CLASSPATH_FILE:-}" ]]; then
    neko_dependencies="$(cat -- "$NEKO_BENCH_CLASSPATH_FILE")"
else
    "${MVN:-mvn}" -B -q -f "$neko_backend/pom.xml" dependency:build-classpath \
        -DincludeScope=test "-Dmdep.outputFile=$neko_bench_dir/classpath.txt"
    neko_dependencies="$(cat -- "$neko_bench_dir/classpath.txt")"
fi
neko_classpath="$neko_classes:$neko_dependencies"
javac -cp "$neko_classpath" -d "$neko_bench_dir/classes" "$neko_backend"/benchmarks/*.java
printf 'Premain-Class: com.neko.music.benchmark.BenchmarkMeter\n' > "$neko_bench_dir/MANIFEST.MF"
jar cfm "$neko_bench_dir/agent.jar" "$neko_bench_dir/MANIFEST.MF" -C "$neko_bench_dir/classes" .

neko_jvm=(-Xms256m -Xmx768m -XX:+UseG1GC --enable-native-access=ALL-UNNAMED
    --add-opens=java.base/java.util=ALL-UNNAMED
    --add-opens=java.base/java.lang=ALL-UNNAMED
    "-javaagent:$neko_bench_dir/agent.jar" -cp "$neko_bench_dir/classes:$neko_classpath")
java "${neko_jvm[@]}" com.neko.music.service.RecognitionBenchmark "${1:-300000}"
java "${neko_jvm[@]}" com.neko.music.search.FuzzyBenchmark
if [[ "${NEKO_BENCH_PROCESS_MEMORY:-0}" == 1 ]]; then
    java -cp "$neko_bench_dir/classes:$neko_classpath" \
        com.neko.music.service.IndexMemoryBenchmark --fixture "${1:-300000}" "$neko_bench_dir/library.nfi"
    java -Xms32m -Xmx768m -XX:+UseG1GC --enable-native-access=ALL-UNNAMED \
        -cp "$neko_bench_dir/classes:$neko_classpath" \
        com.neko.music.service.IndexMemoryBenchmark "${1:-300000}" "$neko_bench_dir/library.nfi"
fi
