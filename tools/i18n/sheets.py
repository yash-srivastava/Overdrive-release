#!/usr/bin/env python3
"""Translation spreadsheets: one .xlsx per language out, checked translations back in.

  python3 tools/i18n/sheets.py export [--lang de ...] [--out DIR]
  python3 tools/i18n/sheets.py import SHEET.xlsx|.csv [--lang de] [--dry-run] [--allow-english]

--allow-english applies rows whose translation equals the English (loanwords, numbers, URLs)
over an existing translation; use it only after reviewing those rows.
"""
import argparse, csv, io, json, os, re, sys
from collections import OrderedDict

ROOT = os.environ.get('OD_I18N_ROOT') or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main')
LANGS = ['zh-CN', 'zh-TW', 'pt-BR', 'es', 'de', 'fr', 'it', 'nb', 'nl', 'ja', 'ko', 'th', 'vi', 'hi', 'tr', 'ru', 'ar', 'cs', 'he']
NAMES = {'zh-CN': '简体中文', 'zh-TW': '繁體中文', 'pt-BR': 'Português (Brasil)', 'es': 'Español', 'de': 'Deutsch', 'fr': 'Français',
         'it': 'Italiano', 'nb': 'Norsk bokmål', 'nl': 'Nederlands', 'ja': '日本語', 'ko': '한국어', 'th': 'ไทย', 'vi': 'Tiếng Việt',
         'hi': 'हिन्दी', 'tr': 'Türkçe', 'ru': 'Русский', 'ar': 'العربية', 'cs': 'Čeština', 'he': 'עברית'}
ANDROID_DIR = {'zh-CN': 'zh-rCN', 'zh-TW': 'zh-rTW', 'pt-BR': 'pt-rBR'}
# Plural forms each runtime picks for a language (web: core.js pluralRule; Android: CLDR); a missing form falls back to "other".
WEB_FORMS = {'cs': ['one', 'few', 'many', 'other'], 'ru': ['one', 'few', 'many', 'other'], 'he': ['one', 'two', 'other'],
             'ar': ['zero', 'one', 'two', 'few', 'many', 'other'], 'zh-CN': ['other'], 'zh-TW': ['other'], 'ja': ['other'],
             'ko': ['other'], 'th': ['other'], 'vi': ['other'], 'tr': ['other']}
ANDROID_FORMS = {'cs': ['one', 'few', 'many', 'other'], 'ru': ['one', 'few', 'many', 'other'], 'he': ['one', 'two', 'other'],
                 'ar': ['zero', 'one', 'two', 'few', 'many', 'other'], 'zh-CN': ['other'], 'zh-TW': ['other'], 'ja': ['other'],
                 'ko': ['other'], 'th': ['other'], 'vi': ['other']}
PLURAL = {'zero', 'one', 'two', 'few', 'many', 'other'}
BRANDS = ['OverDrive', 'Overdrive', 'RoadSense', 'ABRP', 'MQTT', 'Telegram', 'Tailscale', 'Zrok', 'Cloudflared', 'Sing-box',
          'WireGuard', 'BYD', 'DiLink', 'Home Assistant', 'Google', 'Spotify']
HEAD = ['id', 'english', 'translation', 'status']

# ------------------------------------------------------------------ catalogs


def jpath(area, lang):
    return os.path.join(ROOT, 'assets', 'web/i18n' if area == 'web' else 'server-i18n', lang + '.json')


def load_json(path):
    if not os.path.exists(path):
        return OrderedDict()
    with open(path, encoding='utf-8') as f:
        return json.load(f, object_pairs_hook=OrderedDict)


def is_plural(v):
    return isinstance(v, dict) and 'other' in v and set(v) <= PLURAL


def flat(d, pre=''):
    """Leaf strings by dotted key; plural objects stay whole so forms can be added per language."""
    for k, v in d.items():
        key = pre + k
        if key == '_meta':
            continue
        if is_plural(v):
            yield key, v
        elif isinstance(v, dict):
            yield from flat(v, key + '.')
        elif isinstance(v, str):
            yield key, v


def android_files():
    d = os.path.join(ROOT, 'res', 'values')
    return sorted(f for f in os.listdir(d) if f.endswith('.xml') and (f.startswith('strings') or f.endswith('_strings.xml')))


def adir(lang):
    return os.path.join(ROOT, 'res', 'values-' + ANDROID_DIR.get(lang, lang))


STR_RE = re.compile(r'<string\s+name="([^"]+)"([^>]*?)(?:/>|>(.*?)</string>)', re.S)
PL_RE = re.compile(r'<plurals\s+name="([^"]+)"([^>]*)>(.*?)</plurals>', re.S)
ITEM_RE = re.compile(r'<item\s+quantity="([a-z]+)"\s*(?:/>|>(.*?)</item>)', re.S)


def blank_comments(text):
    return re.sub(r'<!--.*?-->', lambda m: ' ' * len(m.group(0)), text, flags=re.S)


def parse_android(path):
    """{name: (attrs, raw)} for strings and {name: {quantity: raw}} for plurals, raw = inner XML as written."""
    strings, plurals = OrderedDict(), OrderedDict()
    if not os.path.exists(path):
        return strings, plurals
    text = blank_comments(open(path, encoding='utf-8').read())
    for m in STR_RE.finditer(text):
        if 'translatable="false"' not in (m.group(2) or ''):
            strings[m.group(1)] = (m.group(2) or '', m.group(3) or '')
    for m in PL_RE.finditer(text):
        if 'translatable="false"' not in m.group(2):
            plurals[m.group(1)] = OrderedDict((q, raw or '') for q, raw in ITEM_RE.findall(m.group(3)))
    return strings, plurals

# ------------------------------------------------------------------ Android text <-> display text


def has_tags(raw):
    return re.search(r'<\s*/?\s*[A-Za-z]', raw) is not None


def a_display(raw):
    s = raw.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    s = s.replace("\\'", "'").replace('\\"', '"').replace('\\@', '@').replace('\\?', '?')
    if not has_tags(s):
        s = s.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"').replace('&apos;', "'").replace('&amp;', '&')
    return s


def a_raw(text, en_raw):
    """Display text back to resource XML in the English string's style (quoted, markup)."""
    t = text.replace('\r\n', '\n').replace('\r', '\n').replace('\n', '\\n')
    tagged = has_tags(en_raw)
    if tagged:
        t = re.sub(r'&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)', '&amp;', t)
    else:
        t = t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    # Quotes inside tags (attribute values) stay as written.
    parts = re.split(r'(<[^>]*>)', t) if tagged else [t]
    for k in range(0, len(parts), 2):
        parts[k] = re.sub(r'(?<!\\)"', '\\"', re.sub(r"(?<!\\)'", "\\'", parts[k]))
    t = ''.join(parts)
    if t[:1] in ('@', '?'):
        t = '\\' + t
    s = en_raw.strip()
    return '"' + t + '"' if len(s) >= 2 and s[0] == '"' and s[-1] == '"' else t

# ------------------------------------------------------------------ rows


def rows_for(lang):
    """(id, english, current) for every translatable string, plural forms as the language needs them."""
    out = []
    for area in ('android', 'web', 'server'):
        if area == 'android':
            for fn in android_files():
                en_s, en_p = parse_android(os.path.join(ROOT, 'res', 'values', fn))
                tr_s, tr_p = parse_android(os.path.join(adir(lang), fn))
                for name, (_, raw) in en_s.items():
                    cur = tr_s.get(name)
                    out.append(('android:%s:%s' % (fn, name), a_display(raw), a_display(cur[1]) if cur else None))
                for name, forms in en_p.items():
                    want = ANDROID_FORMS.get(lang, ['one', 'other'])
                    have = tr_p.get(name, {})
                    for q in [q for q in ['zero', 'one', 'two', 'few', 'many', 'other'] if q in want or q in have]:
                        en_raw = forms.get(q, forms.get('other', ''))
                        out.append(('android:%s:%s#%s' % (fn, name, q), a_display(en_raw), a_display(have[q]) if q in have else None))
        else:
            en = OrderedDict(flat(load_json(jpath(area, 'en'))))
            tr = OrderedDict(flat(load_json(jpath(area, lang))))
            for key, v in en.items():
                cur = tr.get(key)
                if isinstance(v, dict):
                    have = cur if isinstance(cur, dict) else {}
                    want = WEB_FORMS.get(lang, ['one', 'other'])
                    for q in [q for q in ['zero', 'one', 'two', 'few', 'many', 'other'] if q in want or q in have]:
                        out.append(('%s:%s#%s' % (area, key, q), v.get(q, v['other']), have.get(q)))
                else:
                    out.append(('%s:%s' % (area, key), v, cur if isinstance(cur, str) else None))
    return out


def status(en, cur):
    if cur is None or not cur.strip():
        return 'missing'
    if cur == en and re.search(r'[A-Za-z]{4,}', en):
        return 'check'
    return ''

# ------------------------------------------------------------------ export


def export(langs, out_dir):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    os.makedirs(out_dir, exist_ok=True)
    order = {'missing': 0, 'check': 1, '': 2}
    for lang in langs:
        rows = [(i, en, cur or '', status(en, cur)) for i, en, cur in rows_for(lang)]
        rows.sort(key=lambda r: order[r[3]])
        wb = Workbook()
        ws = wb.active
        ws.title = 'Translate'
        # The language rides in the header so a renamed file or a CSV copy still imports.
        ws.append(['id', 'english', 'translation (%s)' % lang, 'status'])
        for r in rows:
            ws.append(list(r))
        for col, w in zip('ABCD', (28, 60, 60, 10)):
            ws.column_dimensions[col].width = w
        wrap, grey = Alignment(wrap_text=True, vertical='top'), Font(color='888888', size=9)
        todo = PatternFill('solid', fgColor='FFF4CC')
        for c in ws[1]:
            c.font = Font(bold=True)
        for row in ws.iter_rows(min_row=2):
            for c in row[1:3]:
                if isinstance(c.value, str) and c.value.startswith('='):
                    c.data_type = 's'
            row[0].font = grey
            row[1].alignment = row[2].alignment = wrap
            if row[3].value:
                row[2].fill = todo
        ws.freeze_panes = 'C2'
        ws.auto_filter.ref = 'A1:D%d' % (len(rows) + 1)
        how = wb.create_sheet('How to')
        for line in HOWTO.format(name=NAMES[lang], lang=lang).strip().split('\n'):
            how.append([line])
        how.column_dimensions['A'].width = 110
        path = os.path.join(out_dir, 'OverDrive-%s.xlsx' % lang)
        wb.save(path)
        n = sum(1 for r in rows if r[3] == 'missing')
        print('%-6s %5d strings, %5d missing, %4d same as English -> %s' % (lang, len(rows), n, sum(1 for r in rows if r[3] == 'check'), path))


HOWTO = """
OverDrive translation sheet: {name} ({lang})
1. Fill in the "translation" column (yellow cells still need translating). Leave "id" and "english" as they are.
2. Keep these exactly as they are: {{name}}, {{0}}, %s, %d, %1$s, ${{var:NAME}}, \\n, <b>...</b> and other tags.
3. Don't translate brand names: OverDrive, BYD, DiLink, RoadSense, ABRP, MQTT, Telegram, Tailscale, Zrok, WireGuard...
4. You don't have to finish: translate what you can. Fix existing translations the same way.
5. Save (keep it as .xlsx, or CSV UTF-8) and attach the file to a "Translation update" issue on GitHub:
   https://github.com/yash-srivastava/Overdrive-release/issues/new/choose
Every line is checked before it ships, so a mistake can't break the app.
"""

# ------------------------------------------------------------------ import


def read_sheet(path):
    if path.lower().endswith(('.xlsx', '.xlsm')):
        from openpyxl import load_workbook
        wb = load_workbook(path, read_only=True, data_only=True)
        ws = wb['Translate'] if 'Translate' in wb.sheetnames else wb.worksheets[0]
        rows = [[('' if c is None else str(c)) for c in r] for r in ws.iter_rows(values_only=True)]
    else:
        data = open(path, 'rb').read()
        try:
            raw = data.decode('utf-8-sig')
        except UnicodeDecodeError:
            # Excel's plain "CSV" is the ANSI code page: fine for Latin text, already lossy for other scripts.
            raw = data.decode('cp1252', errors='replace')
            print('warning: %s is not UTF-8; save as .xlsx or "CSV UTF-8"' % os.path.basename(path))
        # Spreadsheets in many locales save with ";" or tabs; the header line tells which.
        first = raw.split('\n', 1)[0]
        rows = list(csv.reader(io.StringIO(raw), delimiter=max([',', ';', '\t'], key=first.count), quotechar='"', doublequote=True))
    head = [h.strip().lower() for h in rows[0]]
    idx, lang = {}, None
    for n, h in enumerate(head):
        k = h.split('(')[0].strip()
        if k in HEAD[:3] and k not in idx:
            idx[k] = n
            m = re.search(r'\(([a-z]{2}(?:-[a-z]{2})?)\)', h)
            lang = lang or (m and next((l for l in LANGS if l.lower() == m.group(1)), None))
    if len(idx) < 3:
        sys.exit('sheet needs id, english and translation columns')
    get = lambda r, k: r[idx[k]] if idx[k] < len(r) else ''
    return lang, [(get(r, 'id').strip(), get(r, 'english'), get(r, 'translation')) for r in rows[1:] if r and get(r, 'id').strip()]


FMT = r'%%|%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdfxXc]'
PH = re.compile(FMT + r'|\$\{[^}]*\}|\{\{?\s*[\w.]+\s*\}?\}|\\n')
TUNNELS = ['Tailscale', 'Zrok', 'Cloudflared', 'Sing-box', 'WireGuard']
TAG = re.compile(r'</?\s*([A-Za-z][\w:-]*)[^>]*?>')


def check(en, tr, android):
    """Why the translation can't ship, or None."""
    if '\ufffd' in tr or ('???' in tr and '???' not in en):
        return 'characters were lost when the file was saved; save as .xlsx or "CSV UTF-8"'
    if sorted(PH.findall(en)) != sorted(PH.findall(tr)):
        return 'placeholders differ (%s vs %s)' % (' '.join(sorted(PH.findall(en))) or 'none', ' '.join(sorted(PH.findall(tr))) or 'none')
    if sorted(t.lower() for t in TAG.findall(en)) != sorted(t.lower() for t in TAG.findall(tr)):
        return 'markup tags differ'
    lost = [b for b in BRANDS if b in en and not has_brand(tr, b)] + \
        [b for b in TUNNELS if b in tr and b.lower() not in en.lower()]
    if lost:
        return 'brand name changed or missing: ' + ', '.join(lost)
    if android and re.search(FMT, en) and '%' in re.sub(FMT, '', tr):
        return 'stray % sign (write %% for a percent sign)'
    if android:
        import xml.etree.ElementTree as ET
        try:
            ET.fromstring('<string>%s</string>' % a_raw(tr, en_raw_of(en)))
        except ET.ParseError:
            return 'broken markup'
    return None


# BYD and Google have official Chinese names.
BRAND_SPELLINGS = {'OverDrive': ('OverDrive', 'Overdrive'), 'Overdrive': ('Overdrive', 'OverDrive'),
                   'BYD': ('BYD', '比亚迪', '比亞迪'), 'Google': ('Google', '谷歌')}


def has_brand(text, brand):
    return any(b in text for b in BRAND_SPELLINGS.get(brand, (brand,)))


def en_raw_of(en):
    """The English display text as resource XML (only markup matters for the check)."""
    return en if has_tags(en) else ''



def import_sheet(path, lang, dry, allow_english=False):
    sheet_lang, rows = read_sheet(path)
    if not lang:
        m = re.search(r'OverDrive-([A-Za-z]{2}(?:-[A-Za-z]{2})?)', os.path.basename(path))
        lang = sheet_lang or (m.group(1) if m else None)
    if lang not in LANGS:
        sys.exit('language unknown: pass --lang (one of %s)' % ' '.join(LANGS))
    current = {i: (en, cur) for i, en, cur in rows_for(lang)}
    applied, changed, skipped = [], [], []
    for rid, en_sheet, tr in rows:
        tr = tr.replace('\ufeff', '')
        if not tr.strip() or rid not in current:
            if tr.strip() and rid not in current:
                skipped.append((rid, 'not in OverDrive any more'))
            continue
        en, cur = current[rid]
        # Whitespace-only differences are not edits; new text loses stray edge spaces English doesn't have.
        if cur is not None and tr.strip() == cur.strip():
            continue
        if en == en.strip():
            tr = tr.strip()
        if en_sheet.strip() and en_sheet.strip() != en.strip():
            skipped.append((rid, 'English changed since the sheet was made; translate the new text'))
            continue
        if tr == en and cur and cur != en and not allow_english:
            skipped.append((rid, 'English pasted over an existing translation'))
            continue
        why = check(en, tr, rid.startswith('android:'))
        if why:
            skipped.append((rid, why))
            continue
        (changed if cur and cur.strip() and cur != en else applied).append((rid, tr))
    # A plural the language doesn't have yet needs its "other" form: without it Android throws and the web shows the key.
    plural = {}
    for rid, _ in applied + changed:
        if '#' in rid:
            plural.setdefault(rid.split('#')[0], set()).add(rid.split('#')[1])
    for base, forms in plural.items():
        if 'other' not in forms and current.get(base + '#other', (None, None))[1] is None:
            drop = {base + '#' + f for f in forms}
            applied = [x for x in applied if x[0] not in drop]
            changed = [x for x in changed if x[0] not in drop]
            skipped += [(r, 'translate the "#other" row of this plural too') for r in sorted(drop)]
    if not dry:
        write(lang, applied + changed)
    print('%s: %d new, %d changed, %d skipped%s' % (lang, len(applied), len(changed), len(skipped), ' (dry run)' if dry else ''))
    for rid, tr in changed:
        print('  changed  %s: %s -> %s' % (rid, current[rid][1][:60], tr[:60]))
    for rid, why in skipped:
        print('  skipped  %s: %s' % (rid, why))

# ------------------------------------------------------------------ write


def write(lang, items):
    """Render and check every touched file first, then replace each atomically: a bad file writes nothing."""
    by_file = OrderedDict()
    for rid, tr in items:
        area, rest = rid.split(':', 1)
        by_file.setdefault((area, rest.split(':', 1)[0] if area == 'android' else None), []).append((rest, tr))
    staged = []
    for (area, fn), entries in by_file.items():
        if area == 'android':
            staged.append(render_android(lang, fn, [(r.split(':', 1)[1], tr) for r, tr in entries]))
        else:
            staged.append(render_json(area, lang, entries))
    for path, text in staged:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + '.sheets-tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(text)
        os.replace(tmp, path)


def render_json(area, lang, entries):
    path = jpath(area, lang)
    data = load_json(path)
    for key, tr in entries:
        key, form = (key.split('#', 1) + [None])[:2]
        parts, node = key.split('.'), data
        for p in parts[:-1]:
            if not isinstance(node.get(p), dict):
                node[p] = OrderedDict()
            node = node[p]
        leaf = parts[-1]
        if form:
            old = node.get(leaf)
            if not isinstance(old, dict):
                node[leaf] = OrderedDict([('other', old)]) if isinstance(old, str) else OrderedDict()
            node[leaf][form] = tr
        else:
            node[leaf] = tr
    text = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    json.loads(text)
    return path, text


def render_android(lang, fn, entries):
    import xml.etree.ElementTree as ET
    path = os.path.join(adir(lang), fn)
    en_path = os.path.join(ROOT, 'res', 'values', fn)
    en_s, en_p = parse_android(en_path)
    if os.path.exists(path):
        text = open(path, encoding='utf-8').read()
    else:
        text = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    for key, tr in entries:
        name, form = (key.split('#', 1) + [None])[:2]
        if form:
            en_raw = en_p.get(name, {}).get(form) or en_p.get(name, {}).get('other', '')
            raw = a_raw(tr, en_raw)
            text = put_plural(text, name, form, raw)
        else:
            attrs, en_raw = en_s[name]
            raw = a_raw(tr, en_raw)
            text = put_string(text, name, attrs, raw)
    text = declare_namespaces(text, open(en_path, encoding='utf-8').read())
    ET.fromstring(text.encode('utf-8'))
    return path, text


KNOWN_NS = {'tools': 'http://schemas.android.com/tools', 'xliff': 'urn:oasis:names:tc:xliff:document:1.2'}
ROOT_TAG = re.compile(r'<resources\b[^>]*>')


def declare_namespaces(text, en_text):
    """Declare on <resources> every prefix the file uses (tools:ignore copied from English, <xliff:g>), URIs as English has them."""
    m = ROOT_TAG.search(blank_comments(text))
    if not m:
        return text
    root = text[m.start():m.end()]
    body = blank_comments(text[m.end():])
    used = set(re.findall(r'\s([A-Za-z_][\w.-]*):[A-Za-z_][\w.-]*\s*=', body)) | set(re.findall(r'</?([A-Za-z_][\w.-]*):[A-Za-z_]', body))
    em = ROOT_TAG.search(blank_comments(en_text))
    en_root = en_text[em.start():em.end()] if em else ''
    add = ''
    for prefix in sorted(used - {'xmlns', 'xml'}):
        if re.search(r'\sxmlns:%s\s*=' % re.escape(prefix), root):
            continue
        um = re.search(r'\sxmlns:%s\s*=\s*"([^"]*)"' % re.escape(prefix), en_root)
        uri = um.group(1) if um else KNOWN_NS.get(prefix)
        if uri:
            add += ' xmlns:%s="%s"' % (prefix, uri)
    if not add:
        return text
    head, tail = (root[:-2], '/>') if root.endswith('/>') else (root[:-1], '>')
    return text[:m.start()] + head.rstrip() + add + tail + text[m.end():]


def _find(text, rx):
    for m in rx.finditer(blank_comments(text)):
        yield m


def put_string(text, name, attrs, raw):
    rx = re.compile(r'<string\s+name="%s"([^>]*?)(?:/>|>(.*?)</string>)' % re.escape(name), re.S)
    for m in _find(text, rx):
        return text[:m.start()] + '<string name="%s"%s>%s</string>' % (name, m.group(1).rstrip(), raw) + text[m.end():]
    extra = re.sub(r'\s*translatable="[^"]*"', '', attrs).rstrip()
    return insert_end(text, '    <string name="%s"%s>%s</string>\n' % (name, extra, raw))


def put_plural(text, name, form, raw):
    rx = re.compile(r'<plurals\s+name="%s"[^>]*>(.*?)</plurals>' % re.escape(name), re.S)
    for m in _find(text, rx):
        body = m.group(1)
        irx = re.compile(r'<item\s+quantity="%s"\s*(?:/>|>.*?</item>)' % form, re.S)
        item = '<item quantity="%s">%s</item>' % (form, raw)
        nb = irx.sub(lambda _: item, body, count=1) if irx.search(body) else body.rstrip() + '\n        ' + item + '\n    '
        return text[:m.start(1)] + nb + text[m.end(1):]
    return insert_end(text, '    <plurals name="%s">\n        <item quantity="%s">%s</item>\n    </plurals>\n' % (name, form, raw))


def insert_end(text, block):
    i = text.rfind('</resources>')
    return text[:i] + block + text[i:]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    e = sub.add_parser('export')
    e.add_argument('--lang', nargs='*', default=LANGS)
    e.add_argument('--out', default='translations')
    i = sub.add_parser('import')
    i.add_argument('sheet')
    i.add_argument('--lang')
    i.add_argument('--dry-run', action='store_true')
    i.add_argument('--allow-english', action='store_true')
    a = ap.parse_args()
    if a.cmd == 'export':
        export(a.lang, a.out)
    else:
        import_sheet(a.sheet, a.lang, a.dry_run, a.allow_english)


if __name__ == '__main__':
    main()
