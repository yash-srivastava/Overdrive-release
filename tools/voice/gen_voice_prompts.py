#!/usr/bin/env python3
"""Generates the voice assistant's spoken confirmations (assets/voice/prompts/<lang>/*.m4a).

Offline Kokoro TTS (Apache-2.0) via sherpa-onnx; each clip is checked by transcribing it back
with SenseVoice. File names come from VoicePrompts.keyFor() so the app finds them by intent.

  python gen_voice_prompts.py --kokoro kokoro-multi-lang-v1_0 \
      --asr sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import wave

import numpy as np
import sherpa_onnx

ZH_NUM = {16: "十六", 17: "十七", 18: "十八", 19: "十九", 20: "二十", 21: "二十一",
          22: "二十二", 23: "二十三", 24: "二十四", 25: "二十五", 26: "二十六",
          27: "二十七", 28: "二十八", 29: "二十九", 30: "三十"}
EN_NUM = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven",
          16: "sixteen", 17: "seventeen", 18: "eighteen", 19: "nineteen", 20: "twenty",
          21: "twenty-one", 22: "twenty-two", 23: "twenty-three", 24: "twenty-four",
          25: "twenty-five", 26: "twenty-six", 27: "twenty-seven", 28: "twenty-eight",
          29: "twenty-nine", 30: "thirty"}
ZH_TO_EN = {ZH_NUM[n]: EN_NUM[n] for n in sorted(ZH_NUM, reverse=True)}
ZH_DIGIT = {1: "一", 2: "二", 3: "三", 4: "四", 5: "五", 6: "六", 7: "七"}


def prompts():
    p = {}

    def on_off(key, en_on, en_off, zh_on, zh_off):
        p[key + "_on"] = (en_on, zh_on)
        p[key + "_off"] = (en_off, zh_off)

    on_off("climate_power", "AC on.", "AC off.", "空调已打开。", "空调已关闭。")
    for c in range(16, 31):
        p["climate_set_temp_%d" % c] = ("Temperature set to %s degrees." % EN_NUM[c],
                                        "温度已设为%s度。" % ZH_NUM[c])
    p["climate_step_temp_up"] = ("Temperature up.", "温度调高了。")
    p["climate_step_temp_down"] = ("Temperature down.", "温度调低了。")
    p["climate_step_temp_up2"] = ("Temperature up two degrees.", "温度调高两度。")
    p["climate_step_temp_down2"] = ("Temperature down two degrees.", "温度调低两度。")
    for level in range(1, 8):
        p["climate_fan_%d" % level] = ("Fan speed %s." % EN_NUM[level],
                                       "风量调到%s档。" % ZH_DIGIT[level])
    p["climate_fan_up"] = ("Fan up.", "风量调大了。")
    p["climate_fan_down"] = ("Fan down.", "风量调小了。")
    on_off("climate_recirculation", "Recirculation on.", "Recirculation off.",
           "已开启内循环。", "已切换到外循环。")
    on_off("climate_max_cool", "Max cooling on.", "Max cooling off.",
           "已开启最大制冷。", "已关闭最大制冷。")
    on_off("climate_auto", "Auto climate on.", "Auto climate off.",
           "已开启自动空调。", "已关闭自动空调。")
    on_off("climate_defrost_front", "Front defrost on.", "Front defrost off.",
           "前除霜已开启。", "前除霜已关闭。")
    on_off("climate_defrost_rear", "Rear defrost on.", "Rear defrost off.",
           "后除霜已开启。", "后除霜已关闭。")
    p["climate_goal_cool"] = ("Cooling the cabin.", "正在为车内降温。")
    p["climate_goal_warm"] = ("Warming the cabin.", "正在为车内加热。")
    p["climate_goal_defog"] = ("Clearing the windshield.", "正在给挡风玻璃除雾。")
    p["climate_goal_fresh"] = ("Bringing in fresh air.", "正在引入新鲜空气。")
    for seat, en_seat, zh_seat in (("driver", "Driver", "主驾"), ("passenger", "Passenger", "副驾")):
        for kind, en_kind, zh_kind in (("heat", "heating", "加热"), ("vent", "ventilation", "通风")):
            base = "seat_%s_%s" % (kind, seat)
            p[base + "_0"] = ("%s seat %s off." % (en_seat, en_kind),
                              "%s座椅%s已关闭。" % (zh_seat, zh_kind))
            for level in (1, 2):
                p[base + "_%d" % level] = ("%s seat %s, level %s." % (en_seat, en_kind, EN_NUM[level]),
                                           "%s座椅%s%s档。" % (zh_seat, zh_kind, ZH_DIGIT[level]))
    on_off("steering_heat", "Steering wheel heating on.", "Steering wheel heating off.",
           "方向盘加热已开启。", "方向盘加热已关闭。")
    p["sunshade_open"] = ("Opening the sunshade.", "正在打开遮阳帘。")
    p["sunshade_close"] = ("Closing the sunshade.", "正在关闭遮阳帘。")
    p["windows_close_all"] = ("Closing the windows.", "正在关闭车窗。")
    p["windows_vent_all"] = ("Venting the windows.", "正在开窗透气。")
    on_off("ambient_power", "Ambient lights on.", "Ambient lights off.",
           "氛围灯已打开。", "氛围灯已关闭。")
    p["recording_save_clip"] = ("Saving the clip.", "正在保存视频片段。")
    p["status_done"] = ("Done.", "好的。")
    p["status_cancelled"] = ("Cancelled.", "已取消。")
    p["status_blocked_moving"] = ("Not available while driving.", "行驶中无法操作。")
    p["status_refused"] = ("Sorry, I can't do that by voice.", "抱歉，这个不能用语音操作。")
    p["status_failed"] = ("Sorry, that didn't work.", "抱歉，没有成功。")
    p["status_not_understood"] = ("Sorry, I didn't catch that.", "抱歉，我没听清。")
    p["status_offline"] = ("The AI assistant is unavailable right now.", "智能助手暂时不可用。")
    p["status_busy"] = ("The assistant is busy.", "助手正忙。")
    p["brief_cluster_offer"] = ("Show it on the cluster too?", "也在仪表盘上显示吗？")
    return p


def make_tts(kokoro, threads):
    d = kokoro
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=os.path.join(d, "model.onnx"),
                voices=os.path.join(d, "voices.bin"),
                tokens=os.path.join(d, "tokens.txt"),
                data_dir=os.path.join(d, "espeak-ng-data"),
                dict_dir=os.path.join(d, "dict"),
                lexicon=os.path.join(d, "lexicon-us-en.txt") + "," + os.path.join(d, "lexicon-zh.txt"),
            ),
            provider="cpu",
            num_threads=threads,
        ),
        rule_fsts=",".join(os.path.join(d, f) for f in ("phone-zh.fst", "date-zh.fst", "number-zh.fst")),
        max_num_sentences=1,
    )
    return sherpa_onnx.OfflineTts(cfg)


def speaker_ids(kokoro):
    import onnx
    m = onnx.load(os.path.join(kokoro, "model.onnx"), load_external_data=False)
    names = {p.key: p.value for p in m.metadata_props}["speaker_names"].split(",")
    return {n: i for i, n in enumerate(names)}


def finish(samples, rate):
    x = np.asarray(samples, dtype=np.float32)
    level = np.abs(x)
    above = np.where(level > 10 ** (-45 / 20))[0]
    if above.size:
        pad = int(0.06 * rate)
        x = x[max(0, above[0] - pad): min(len(x), above[-1] + pad)]
    peak = float(np.max(np.abs(x))) or 1.0
    x = x * (10 ** (-2 / 20) / peak)
    fade = int(0.01 * rate)
    if len(x) > 2 * fade:
        ramp = np.linspace(0, 1, fade, dtype=np.float32)
        x[:fade] *= ramp
        x[-fade:] *= ramp[::-1]
    return x


def write_wav(path, x, rate):
    pcm = np.clip(x * 32767, -32768, 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def encode(wav, out):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-ac", "1", "-ar", "24000",
                    "-c:a", "aac", "-b:a", "40k", "-movflags", "+faststart", out], check=True)


def normalize(text, lang):
    """Chinese compares as toned pinyin so ASR homophone spellings don't count as errors."""
    t = text.lower()
    if lang == "zh":
        from pypinyin import Style, lazy_pinyin
        hanzi = re.sub(r"[^一-鿿]", "", t)
        return lazy_pinyin(hanzi, style=Style.TONE3, neutral_tone_with_five=True)
    for num, word in ZH_TO_EN.items():
        t = t.replace(num, " " + word + " ")
    t = t.replace("-", " ")
    return re.sub(r"[^a-z0-9 ]", "", re.sub(r"\s+", " ", t)).strip()


def cer(ref, hyp):
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1] / max(1, len(ref))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kokoro", required=True)
    ap.add_argument("--asr")
    ap.add_argument("--moonshine-en")
    ap.add_argument("--moonshine-zh")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "..",
                                                  "app", "src", "main", "assets", "voice", "prompts"))
    ap.add_argument("--voice-en", default="af_heart")
    ap.add_argument("--voice-zh", default="zf_xiaoxiao")
    ap.add_argument("--speed", type=float, default=1.05)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--max-cer", type=float, default=0.2)
    ap.add_argument("--takes", type=int, default=4)
    ap.add_argument("--only", help="comma-separated key prefixes to regenerate")
    args = ap.parse_args()

    ids = speaker_ids(args.kokoro)
    voices = {"en": ids[args.voice_en], "zh": ids[args.voice_zh]}
    tts = make_tts(args.kokoro, args.threads)
    asr = {"en": [], "zh": []}
    if args.asr:
        for lang in ("en", "zh"):
            asr[lang].append(sherpa_onnx.OfflineRecognizer.from_sense_voice(
                model=os.path.join(args.asr, "model.int8.onnx"),
                tokens=os.path.join(args.asr, "tokens.txt"),
                num_threads=args.threads, use_itn=False, language=lang))
    for lang, d in (("en", args.moonshine_en), ("zh", args.moonshine_zh)):
        if d:
            asr[lang].append(sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
                encoder=os.path.join(d, "encoder_model.ort"),
                decoder=os.path.join(d, "decoder_model_merged.ort"),
                tokens=os.path.join(d, "tokens.txt"), num_threads=args.threads))
    checking = bool(asr["en"] or asr["zh"])

    failures = []
    total = 0
    with tempfile.TemporaryDirectory() as tmp:
        only = tuple(args.only.split(",")) if args.only else None
        for key, texts in prompts().items():
            if only and not key.startswith(only):
                continue
            for lang, text in (("en", texts[0]), ("zh", texts[1])):
                best = None
                for _ in range(args.takes if checking else 1):
                    audio = tts.generate(text, sid=voices[lang], speed=args.speed)
                    x = finish(audio.samples, audio.sample_rate)
                    heard, err = "", 0.0
                    if checking:
                        err = 9.0
                        # A take passes when any recognizer hears it; each has its own quirks.
                        for rec in asr[lang]:
                            stream = rec.create_stream()
                            stream.accept_waveform(audio.sample_rate, x)
                            rec.decode_stream(stream)
                            e = cer(normalize(text, lang), normalize(stream.result.text, lang))
                            if e < err:
                                err, heard = e, stream.result.text
                    if best is None or err < best[0]:
                        best = (err, x, audio.sample_rate, heard)
                    if err == 0.0:
                        break
                err, x, rate, heard = best
                wav = os.path.join(tmp, "%s_%s.wav" % (lang, key))
                write_wav(wav, x, rate)
                out_dir = os.path.join(args.out, lang)
                os.makedirs(out_dir, exist_ok=True)
                encode(wav, os.path.join(out_dir, key + ".m4a"))
                total += 1
                if err > args.max_cer:
                    failures.append((lang, key, text, heard, err))
    print("generated %d clips" % total)
    for f in failures:
        print("CHECK %s %-28s expected=%r heard=%r cer=%.2f" % f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
