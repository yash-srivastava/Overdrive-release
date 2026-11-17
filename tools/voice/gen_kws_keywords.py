#!/usr/bin/env python3
"""Generate the pre-tokenized sherpa-onnx keyword files from CommandCatalog.java.

Usage (needs a JDK plus `pip install sherpa-onnx pypinyin sentencepiece`):

  python3 tools/voice/gen_kws_keywords.py --model-dir /path/to/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20

The model directory is the unpacked official release (it holds en.phone / bpe.model, which are
needed here but not shipped in the APK). Output goes to app/src/main/assets/voice/kws/ unless
--out is given. The script fails on any out-of-vocabulary token and on any phrase whose tokens
are a prefix of a phrase with a different meaning: matches fire as soon as a phrase ends, so the
shorter phrase would always win.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from collections import defaultdict

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CATALOG_SRC = os.path.join(
    REPO, "app/src/main/java/com/overdrive/app/voice/local/CommandCatalog.java")
DUMPER_SRC = os.path.join(REPO, "tools/voice/DumpCatalog.java")
DEFAULT_OUT = os.path.join(REPO, "app/src/main/assets/voice/kws")

# Words missing from (or mispronounced by) the model's en.phone lexicon, in ARPAbet.
EN_LEXICON_EXTRA = {
    "CLOSE": "K L OW1 Z",  # the verb; en.phone has the adjective
    "DEFOG": "D IY0 F AA1 G",
    "DEFROSTER": "D IH0 F R AO1 S T ER0",
    "DEMIST": "D IY0 M IH1 S T",
    "RECIRCULATE": "R IY0 S ER1 K Y AH0 L EY2 T",
    "RECIRCULATION": "R IY0 S ER2 K Y AH0 L EY1 SH AH0 N",
    "SUNSHADE": "S AH1 N SH EY2 D",
}

# 调/調 means "adjust" (tiáo) in every catalog phrase; pypinyin often picks diào.
TIAO_CHARS = {"调", "調"}

# Per-keyword acoustic threshold by token count: short phrases need more evidence.
def threshold_for(num_tokens):
    if num_tokens <= 5:
        return 0.30
    if num_tokens <= 9:
        return 0.25
    return 0.20


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def dump_catalog():
    tmp = tempfile.mkdtemp(prefix="kws-catalog-")
    try:
        run(["javac", "-encoding", "UTF-8", "-d", tmp, CATALOG_SRC, DUMPER_SRC])
        out = run(["java", "-Dfile.encoding=UTF-8", "-cp", tmp,
                   "com.overdrive.app.voice.local.DumpCatalog"],
                  stdout=subprocess.PIPE).stdout.decode("utf-8")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    rows = []
    for line in out.splitlines():
        lang, tag, intent, slots, phrase = line.split("\t")
        rows.append({"lang": lang, "tag": tag, "intent": intent, "slots": slots,
                     "phrase": phrase})
    return rows


def load_tokens(path):
    table = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2:
                table[parts[0]] = int(parts[1])
    return table


def load_lexicon(path):
    lex = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2 and parts[0] not in lex:
                lex[parts[0]] = parts[1:]
    for word, phones in EN_LEXICON_EXTRA.items():
        lex[word] = phones.split()
    return lex


def tone_of(syllable):
    marks = {1: "āēīōūǖ", 2: "áéíóúǘ", 3: "ǎěǐǒǔǚ", 4: "àèìòùǜ"}
    for tone, chars in marks.items():
        if any(c in syllable for c in chars):
            return tone
    return 5


def pinyin_variants(phrase):
    """pypinyin's reading plus the tone-sandhi readings speakers actually use for 一 and 不."""
    from pypinyin import pinyin
    base = [p[0] for p in pinyin(phrase)]
    for i, ch in enumerate(phrase):
        if ch in TIAO_CHARS:
            base[i] = "tiáo"
    variants = {tuple(base)}
    sandhi = list(base)
    for i, ch in enumerate(phrase):
        nxt = tone_of(base[i + 1]) if i + 1 < len(base) else None
        if ch == "一" and nxt is not None:
            sandhi[i] = "yí" if nxt == 4 else "yì"
        elif ch == "不" and nxt == 4:
            sandhi[i] = "bú"
    variants.add(tuple(sandhi))
    plain = list(base)
    for i, ch in enumerate(phrase):
        if ch == "一":
            plain[i] = "yī"
    variants.add(tuple(plain))
    return [list(v) for v in sorted(variants)]


def split_pinyin(syllables):
    from pypinyin.contrib.tone_convert import to_finals_tone, to_initials
    out = []
    for s in syllables:
        initial = to_initials(s, strict=False)
        final = to_finals_tone(s, strict=False)
        if not initial and not final:
            out.append(s)
            continue
        if initial:
            out.append(initial)
        if final:
            out.append(final)
    return out


def tokenize(row, tokens_type, lexicon, bpe):
    phrase = row["phrase"]
    if tokens_type == "bpe":
        return [bpe.encode(phrase.upper(), out_type=str)]
    if row["lang"] == "en":
        phones = []
        for word in phrase.upper().split():
            if word not in lexicon:
                raise SystemExit("No pronunciation for '%s' in '%s'; add it to EN_LEXICON_EXTRA"
                                 % (word, phrase))
            phones += lexicon[word]
        return [phones]
    return [split_pinyin(v) for v in pinyin_variants(phrase)]


def check_conflicts(rows):
    errors, warnings = [], []
    by_lang = defaultdict(list)
    for r in rows:
        by_lang[r["lang"]].append(r)
    for lang, items in by_lang.items():
        for a in items:
            for b in items:
                if a is b or (a["intent"] == b["intent"] and a["slots"] == b["slots"]):
                    continue
                for ta in a["tokens"]:
                    for tb in b["tokens"]:
                        if len(ta) <= len(tb) and tb[:len(ta)] == ta:
                            errors.append("%s: '%s' (%s %s) is a prefix of '%s' (%s %s)" % (
                                lang, a["phrase"], a["intent"], a["slots"],
                                b["phrase"], b["intent"], b["slots"]))
                        elif len(ta) < len(tb) and tb[-len(ta):] == ta:
                            warnings.append("%s: '%s' is a suffix of '%s'" % (
                                lang, a["phrase"], b["phrase"]))
    return sorted(set(errors)), sorted(set(warnings))


def keyword_line(tokens, tag):
    return "%s #%.2f @%s" % (" ".join(tokens), threshold_for(len(tokens)), tag)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--tokens-type", default="phone+ppinyin", choices=["phone+ppinyin", "bpe"])
    ap.add_argument("--languages", default="en,zh-CN,zh-TW")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--check-only", action="store_true")
    ap.add_argument("--show-warnings", action="store_true")
    args = ap.parse_args()

    languages = args.languages.split(",")
    tokens = load_tokens(os.path.join(args.model_dir, "tokens.txt"))
    lexicon, bpe = None, None
    if args.tokens_type == "bpe":
        import sentencepiece
        bpe = sentencepiece.SentencePieceProcessor()
        bpe.load(os.path.join(args.model_dir, "bpe.model"))
    else:
        lexicon = load_lexicon(os.path.join(args.model_dir, "en.phone"))

    rows = [r for r in dump_catalog() if r["lang"] in languages]
    for r in rows:
        r["tokens"] = tokenize(r, args.tokens_type, lexicon, bpe)
        for t in r["tokens"]:
            oov = [x for x in t if x not in tokens]
            if oov or not t:
                raise SystemExit("OOV tokens %s in '%s' (%s)" % (oov, r["phrase"], r["lang"]))

    errors, warnings = check_conflicts(rows)
    if args.show_warnings:
        for w in warnings:
            print("warning: " + w)
    if errors:
        for e in errors:
            print("error: " + e, file=sys.stderr)
        raise SystemExit("%d prefix conflicts" % len(errors))

    counts = defaultdict(int)
    lines = defaultdict(list)
    for r in rows:
        for t in r["tokens"]:
            lines[r["lang"]].append(keyword_line(t, r["tag"]))
        counts[r["lang"]] += 1
    for lang in languages:
        print("%s: %d phrases, %d keyword lines" % (lang, counts[lang], len(lines[lang])))
    if args.check_only:
        return

    os.makedirs(args.out, exist_ok=True)
    for lang in languages:
        with open(os.path.join(args.out, "keywords-%s.txt" % lang), "w", encoding="utf-8") as f:
            f.write("\n".join(lines[lang]) + "\n")
    # The engine appends its default file to every stream's keywords; it must parse, so it
    # carries the English cancel phrase, which is valid in every window.
    cancel = [r for r in rows if r["lang"] == "en" and r["tag"] == "cancel"]
    if not cancel:
        raise SystemExit("English 'cancel' phrase missing from the catalog")
    with open(os.path.join(args.out, "keywords-default.txt"), "w", encoding="utf-8") as f:
        f.write(keyword_line(cancel[0]["tokens"][0], "cancel") + "\n")
    print("wrote " + args.out)


if __name__ == "__main__":
    main()
