#!/usr/bin/env python3
"""常驻 Demucs 工作进程：模型只加载一次，按任务和分片输出 JSON 事件。"""

import argparse
import json
import math
import sys
from pathlib import Path

import soundfile as sf
import torch


def emit(**payload):
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), flush=True)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--daemon", action="store_true")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--model", default="htdemucs")
    parser.add_argument("--chunk-seconds", type=float, default=30.0)
    parser.add_argument("--context-seconds", type=float, default=1.0)
    parser.add_argument("--bitrate", type=int, default=320)
    parser.add_argument("--fp16", action="store_true")
    return parser.parse_args()


def load_separator(model, fp16):
    from demucs.api import Separator

    device = "cuda" if torch.cuda.is_available() else "cpu"
    if device == "cuda":
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True
    separator = Separator(
        model=model,
        device=device,
        shifts=1,
        overlap=0.25,
        split=True,
        progress=False,
    )
    return separator, device, bool(fp16 and device == "cuda")


def separate_job(separator, device, fp16, input_path, output_dir, chunk_seconds,
                 context_seconds, bitrate):
    from demucs.api import save_audio

    input_path = Path(input_path)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    info = sf.info(str(input_path))
    source_rate = info.samplerate
    total_frames = info.frames
    chunk_frames = max(1, int(round(chunk_seconds * source_rate)))
    context_frames = max(0, int(round(context_seconds * source_rate)))
    total_chunks = max(1, math.ceil(total_frames / chunk_frames))

    emit(
        event="start",
        totalChunks=total_chunks,
        sampleRate=source_rate,
        device=device,
        fp16=fp16,
        chunkSeconds=chunk_seconds,
    )

    for index in range(total_chunks):
        core_start = index * chunk_frames
        core_end = min(total_frames, core_start + chunk_frames)
        read_start = max(0, core_start - context_frames)
        read_end = min(total_frames, core_end + context_frames)

        data, actual_rate = sf.read(
            str(input_path),
            start=read_start,
            frames=read_end - read_start,
            dtype="float32",
            always_2d=True,
        )
        wav = torch.from_numpy(data.T.copy())
        if fp16:
            with torch.autocast(device_type="cuda", dtype=torch.float16):
                _, stems = separator.separate_tensor(wav, actual_rate)
        else:
            _, stems = separator.separate_tensor(wav, actual_rate)

        # 上下文只用于给模型边界信息，输出仍裁成互不重叠的固定片长。
        left_trim = int(round((core_start - read_start) * separator.samplerate / actual_rate))
        right_trim = int(round((read_end - core_end) * separator.samplerate / actual_rate))
        vocals = stems["vocals"]
        end = vocals.shape[1] - right_trim if right_trim > 0 else vocals.shape[1]
        vocals = vocals[:, left_trim:end]

        accompaniment = None
        for name, stem in stems.items():
            if name == "vocals":
                continue
            part = stem[:, left_trim:end]
            accompaniment = part if accompaniment is None else accompaniment + part
        if accompaniment is None:
            accompaniment = torch.zeros_like(vocals)

        vocals_name = f"vocals-{index:03d}.mp3"
        accompaniment_name = f"accompaniment-{index:03d}.mp3"
        save_audio(
            vocals.detach().float().cpu(),
            output_dir / vocals_name,
            samplerate=separator.samplerate,
            bitrate=bitrate,
            preset=2,
        )
        save_audio(
            accompaniment.detach().float().cpu(),
            output_dir / accompaniment_name,
            samplerate=separator.samplerate,
            bitrate=bitrate,
            preset=2,
        )

        emit(
            event="chunk",
            index=index,
            totalChunks=total_chunks,
            startSec=core_start / source_rate,
            endSec=core_end / source_rate,
            vocals=vocals_name,
            accompaniment=accompaniment_name,
        )

    emit(event="done", totalChunks=total_chunks)


def run_daemon(args):
    separator = None
    device = ""
    fp16 = False
    for line in sys.stdin:
        if not line.strip():
            continue
        command = json.loads(line)
        if command.get("action") == "shutdown":
            return
        if command.get("action") != "separate":
            emit(event="error", message="unsupported action")
            continue
        if separator is None:
            separator, device, fp16 = load_separator(
                command.get("model", args.model),
                command.get("fp16", args.fp16),
            )
        try:
            separate_job(
                separator,
                device,
                fp16,
                command["input"],
                command["outputDir"],
                float(command.get("chunkSeconds", args.chunk_seconds)),
                float(command.get("contextSeconds", args.context_seconds)),
                int(command.get("bitrate", args.bitrate)),
            )
        except Exception as exc:
            print(f"separation job failed: {exc}", file=sys.stderr, flush=True)
            emit(event="error", message="separation job failed")
            return


def main():
    args = parse_args()
    if args.daemon:
        run_daemon(args)
        return
    if args.input is None or args.output_dir is None:
        raise SystemExit("--input and --output-dir are required outside daemon mode")
    separator, device, fp16 = load_separator(args.model, args.fp16)
    separate_job(
        separator,
        device,
        fp16,
        args.input,
        args.output_dir,
        args.chunk_seconds,
        args.context_seconds,
        args.bitrate,
    )


if __name__ == "__main__":
    main()
