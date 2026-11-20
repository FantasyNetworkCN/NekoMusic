#!/usr/bin/env bash
set -euo pipefail

neko_native_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
neko_crate="$neko_native_dir/recognition"
if [[ -n "${NEKO_RECOGNITION_PREBUILT:-}" ]]; then
    # Docker 在独立 Rust 构建阶段生成的制品；仅用于构建，不是后端运行时配置。
    neko_library="$NEKO_RECOGNITION_PREBUILT"
else
    cargo build --manifest-path "$neko_crate/Cargo.toml" --release --locked --offline
    neko_library="$neko_crate/target/release/libneko_recognition.so"
fi
test -s "$neko_library"
neko_resources="$neko_native_dir/../src/main/resources/native/linux-x86_64"
mkdir -p "$neko_resources"
cp -- "$neko_library" "$neko_resources/libneko_recognition.so"
