#!/usr/bin/env python3
# Copyright (c) 2026 Pingolin LLC
#
# This file is part of Pingolin Track.
#
# Pingolin Track is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# Pingolin Track is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.

"""Which Track strings each language still lacks, and the list LocaleCoverageTest holds them to.

    tools/i18n-missing.py                 per language, the English names it has no translation for
    tools/i18n-missing.py --snapshot      write app/src/test/resources/i18n/translated-strings.txt: every
                                          name all seven languages translate, in the English file's order;
                                          a listed name with no record yet has its English recorded
    tools/i18n-missing.py --stale         the translated names whose English changed since it was recorded:
                                          their seven translations may say what the English no longer says.
                                          Exits 1 when there is one, 0 when there is none
    tools/i18n-missing.py --accept NAME...  record NAME's English as it is now, once its seven translations
                                          say the same again: only in the same commit that replaces the stale
                                          translations, never on its own, so an accept can't hide a stale line
    tools/i18n-missing.py --drop NAME...  delete NAME from the seven language files and the list, for an
                                          English string that was deleted or renamed (lint's ExtraTranslation
                                          fails a translation left behind)

A new English string is owed, not an error: it joins the list with --snapshot once all seven have it.
--snapshot never re-records the English of a name already recorded, so a changed English stays stale until
--accept says its translations were checked. A name that leaves the list (one language's translation dropped
to be redone) keeps its record, so when it comes back the others are still held to the English they were
made from.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
SNAPSHOT = os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'i18n', 'translated-strings.txt')
ENGLISH = os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'i18n', 'translated-english.json')
FOLDERS = {'de': 'values-de', 'es': 'values-es', 'fr': 'values-fr', 'ja': 'values-ja', 'ko': 'values-ko',
           'pt': 'values-pt', 'zh': 'values-b+zh+Hans'}


def entries(folder):
    path = os.path.join(RES, folder, 'strings.xml')
    if not os.path.exists(path):
        return []
    root = ET.parse(path).getroot()
    return [el for el in root if el.tag in ('string', 'plurals') and el.get('translatable') != 'false']


def names(folder):
    return [el.get('name') for el in entries(folder)]


def source(el):
    """The entry's English as strings.xml has it, markup and escapes included; a plural's items in order."""
    el.tail = None
    xml = ET.tostring(el, encoding='unicode')
    inner = '' if xml.endswith('/>') and '</' not in xml else xml[xml.index('>') + 1:xml.rindex('</')]
    return re.sub(r'>\s+<', '><', inner.strip()) if el.tag == 'plurals' else inner


def english_now():
    return {el.get('name'): source(el) for el in entries('values')}


def listed():
    if not os.path.exists(SNAPSHOT):
        return []
    return [line.strip() for line in open(SNAPSHOT, encoding='utf-8') if line.strip() and not line.startswith('#')]


def recorded():
    return json.load(open(ENGLISH, encoding='utf-8')) if os.path.exists(ENGLISH) else {}


def write_recorded(english, order):
    """The records of the names still in the English file, in its order; a deleted string's record goes."""
    kept = {name: english[name] for name in order if name in english}
    os.makedirs(os.path.dirname(ENGLISH), exist_ok=True)
    with open(ENGLISH, 'w', encoding='utf-8') as out:
        json.dump(kept, out, ensure_ascii=False, indent=1)
        out.write('\n')


def stale(now):
    was = recorded()
    return [(name, was.get(name), now.get(name)) for name in listed() if name in now and was.get(name) != now[name]]


def main(argv):
    english = names('values')
    langs = {lang: set(names(folder)) for lang, folder in FOLDERS.items()}
    if argv[:1] == ['--drop']:
        for name in argv[1:]:
            block = re.compile(r'\n[ \t]*<(string|plurals) name="' + re.escape(name) + r'"[^>]*>.*?</\1>', re.S)
            for folder in FOLDERS.values():
                path = os.path.join(RES, folder, 'strings.xml')
                text = open(path, encoding='utf-8').read()
                open(path, 'w', encoding='utf-8').write(block.sub('', text))
            if os.path.exists(SNAPSHOT):
                kept = [line for line in open(SNAPSHOT, encoding='utf-8').read().splitlines() if line.strip() != name]
                open(SNAPSHOT, 'w', encoding='utf-8').write('\n'.join(kept) + '\n')
            was = recorded()
            if name in was:
                del was[name]
                write_recorded(was, english)
            print(f'dropped {name}')
        return 0
    if argv[:1] == ['--snapshot']:
        done = [n for n in english if all(n in have for have in langs.values())]
        os.makedirs(os.path.dirname(SNAPSHOT), exist_ok=True)
        header = ['# Every name here is translated in all seven languages and must stay so (LocaleCoverageTest).',
                  '# Regenerate with tools/i18n-missing.py --snapshot; a new English string joins once all seven have it.']
        open(SNAPSHOT, 'w', encoding='utf-8').write('\n'.join(header + done) + '\n')
        now, was = english_now(), recorded()
        write_recorded({**was, **{name: was.get(name, now[name]) for name in done}}, english)
        print(f'wrote {len(done)} names to {os.path.relpath(SNAPSHOT, ROOT)}, '
              f'{len([n for n in done if n not in was])} English recorded for the first time')
        return 0
    if argv[:1] == ['--accept']:
        now, was = english_now(), recorded()
        for name in argv[1:]:
            if name not in listed():
                print(f'{name} is not in the list; translate it in all seven and run --snapshot')
                return 2
            was[name] = now[name]
            print(f'recorded the English of {name}')
        write_recorded(was, english)
        return 0
    if argv[:1] == ['--stale']:
        found = stale(english_now())
        for name, was, now in found:
            if was is None:
                print(f'{name}: its English was never recorded (--snapshot records it)\n  now: {now}')
            else:
                print(f'{name}: the English changed since its translations were made\n  was: {was}\n  now: {now}')
        print(f'{len(found)} stale' if found else 'no stale translations')
        return 1 if found else 0
    for lang, have in langs.items():
        owed = [n for n in english if n not in have]
        extra = sorted(have - set(english))
        print(f'{lang}: {len(english) - len(owed)} of {len(english)} English strings translated')
        if owed:
            print('  owed: ' + ', '.join(owed))
        if extra:
            print('  no longer in English (--drop): ' + ', '.join(extra))
    found = stale(english_now())
    if found:
        print(f'stale (--stale): {", ".join(name for name, _, _ in found)}')
    return 0


sys.exit(main(sys.argv[1:]))
