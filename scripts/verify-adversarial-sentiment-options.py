"""Independently reconcile the T81 plots to retained native public reports.

Run after generation; reads data/artifacts only, then writes a verification receipt.
"""
import argparse
import collections
import csv
import hashlib
import json
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image
from pypdf import PdfReader

parser = argparse.ArgumentParser(description=__doc__)
for name in ('study', 'output', 'qa', 'baseline-options'):
    parser.add_argument('--' + name, type=Path, required=True)
args = parser.parse_args()
root = Path.cwd()
methods = ('vader-sentiment', 'cardiff-sentiment')
models = ('gpt-6-luna', 'qwen3:8b')
levels = (0, 10, 100)
classes = ('negative', 'neutral', 'positive', 'uncertain')
scope_topics = {'retirement-topic-derailment': ('retirement', 'NZ_FIRST=TOPIC_DERAILMENT'),
                'workweek-straw-man': ('four_day_week', 'ACT=STRAW_MAN'),
                'nuclear-procedural-manipulation': ('nuclear_power', 'GREEN=PROCEDURAL_MANIPULATION')}


def rows(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


def close(a, b, tolerance=1e-12):
    assert abs(float(a) - float(b)) < tolerance, (a, b)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


manifest = json.loads((args.output / 'manifest.json').read_text(encoding='utf-8'))
source_runs = rows(args.study / 'runs.csv')
run_by_id = {r['Run ID']: r for r in source_runs}
assert len(source_runs) == len(run_by_id) == 72
assert manifest['counts'] == {'debates': 72, 'baseline_debates': 54, 'assigned_debates': 18,
                              'matched_control_debates': 18, 'speeches': 1800,
                              'chunks_per_method': 5876, 'cardiff_uncertain_chunks': 1540}
for name, expected in manifest['input_sha256'].items():
    assert digest(args.study / name) == expected
for scope, (topic, condition) in scope_topics.items():
    assert (manifest['scopes'][scope]['topic'], manifest['scopes'][scope]['condition']) == (topic, condition)
source_chunks = {(r['Run ID'], r['Turn ID'], r['Method'], int(r['Sentence index'])): r
                 for r in rows(args.study / 'sentences.csv') if r['Method'] in methods}
native = collections.defaultdict(list)
native_ids = set()
for run in source_runs:
    report = json.loads((args.study / 'evidence' / run['Batch'] / 'reports' / (run['Cell ID'] + '-local.json')).read_text(encoding='utf-8'))
    assert report['runId'] == run['Run ID'] and report['transcriptSha256'] == run['Transcript SHA256']
    for method in report['methods']:
        if method['methodId'] not in methods:
            continue
        assert method['status'] == 'ok'
        for batch in method['batches']:
            assert batch['status'] == 'ok'
            for item in batch['items']:
                assert item['status'] == 'ok'
                for chunk in item['chunks']:
                    key = (run['Run ID'], item['turnId'], method['methodId'], chunk['sentenceIndex'])
                    assert key not in native_ids
                    native_ids.add(key)
                    source = source_chunks[key]
                    assert chunk['label'] == source['Label'] and chunk['text'] == source['Sentence text']
                    assert chunk['start'] == int(source['Start offset']) and chunk['end'] == int(source['End offset'])
                    for c in classes[:3]:
                        close(chunk['scores'][c], source[c.title()])
                    if method['methodId'] == methods[0]:
                        close(chunk['compound'], source['VADER compound'])
                    else:
                        assert str(chunk['uncertainty']['abstained']).lower() == source['Abstained']
                    native[key[:3]].append(chunk)
assert native_ids == set(source_chunks) and len(native_ids) == 11752 and len(native) == 3600
speech_groups = collections.defaultdict(list)
for (rid, tid, method), chunks in native.items():
    speech_groups[(rid, method)].append({
        'score': statistics.mean(c['compound'] if method == methods[0] else c['scores']['positive'] for c in chunks),
        'chunks': len(chunks), 'uncertain': sum(c['label'] == 'uncertain' for c in chunks),
        **{f'share_{c}': sum(x['label'] == c for x in chunks) / len(chunks) for c in classes}})
independent = {}
for key, speeches in speech_groups.items():
    assert len(speeches) == 25
    independent[key] = {'score': statistics.mean(s['score'] for s in speeches), 'n_speeches': 25,
                        'n_chunks': sum(s['chunks'] for s in speeches), 'n_uncertain_chunks': sum(s['uncertain'] for s in speeches),
                        **{f'share_{c}': statistics.mean(s[f'share_{c}'] for s in speeches) for c in classes}}
debates = rows(args.output / 'sentiment-debate-means.csv')
assert len(debates) == 144
assert {(d['run_id'], d['method']) for d in debates} == set(independent)
for d in debates:
    for key, value in independent[(d['run_id'], d['method'])].items():
        close(d[key], value)
    source = run_by_id[d['run_id']]
    assert (d['model'], d['topic'], d['condition']) == (source['Model'], source['Topic key'], source['Strategy condition'])
    assert (int(d['grounding']), int(d['repetition'])) == (int(source['Hansard excerpts']), int(source['Repetition']))

pair_ids = {(r['NONE run ID'], r['Assigned run ID']) for r in rows(args.study / 'matched-local.csv')}
assert len(pair_ids) == 18 and len({c for c, _ in pair_ids}) == 18
for control, assigned in pair_ids:
    c, a = run_by_id[control], run_by_id[assigned]
    assert c['Strategy condition'] == 'NONE' and a['Strategy condition'] != 'NONE'
    for key in ('Model', 'Topic key', 'Hansard excerpts', 'Repetition', 'Speaking order', 'Temperature', 'Reasoning',
                'Completion cap', 'Judge model', 'Judge cap', 'Judge reasoning', 'Judge temperature', 'Corpus SHA256'):
        assert c[key] == a[key]
    assert c['Repetition'] == '1'
paired = rows(args.output / 'sentiment-matched-debates.csv')
assert len(paired) == 36
assert {(p['control_run_id'], p['assigned_run_id'], p['method']) for p in paired} == {(c, a, m) for c, a in pair_ids for m in methods}
for p in paired:
    c, a = independent[(p['control_run_id'], p['method'])], independent[(p['assigned_run_id'], p['method'])]
    close(p['control_score'], c['score'])
    close(p['assigned_score'], a['score'])
    close(p['assigned_minus_control'], a['score'] - c['score'])
    close(p['control_uncertain_share'], c['share_uncertain'])
    close(p['assigned_uncertain_share'], a['share_uncertain'])

summaries = rows(args.output / 'sentiment-summary.csv')
summary = {(s['scope'], s['method'], s['model'], s['condition'], int(s['grounding'])): s for s in summaries}
assert len(summary) == len(summaries) == 84
matched_run_ids = {rid for pair in pair_ids for rid in pair}
for (scope, method, model, condition, h), actual in summary.items():
    chosen = [r for r in source_runs if r['Model'] == model and r['Strategy condition'] == condition and int(r['Hansard excerpts']) == h
              and (condition == 'NONE' if scope == 'baseline' else r['Run ID'] in matched_run_ids and r['Topic key'] == scope_topics[scope][0])]
    assert len(chosen) == int(actual['n_debates']) == (9 if scope == 'baseline' else 1)
    assert set(actual['run_ids'].split(';')) == {r['Run ID'] for r in chosen}
    values = [independent[(r['Run ID'], method)] for r in chosen]
    close(actual['mean_score'], statistics.mean(v['score'] for v in values))
    close(actual['min_debate_score'], min(v['score'] for v in values))
    close(actual['max_debate_score'], max(v['score'] for v in values))
    for count in ('n_speeches', 'n_chunks', 'n_uncertain_chunks'):
        assert int(actual[count]) == sum(v[count] for v in values)
    for c in classes:
        close(actual[f'share_{c}'], statistics.mean(v[f'share_{c}'] for v in values))
for old in rows(args.baseline_options / 'sentiment-summary.csv'):
    actual = summary[('baseline', old['method'], old['model'], 'NONE', int(old['grounding']))]
    for key in ('mean_score', 'min_debate_score', 'max_debate_score', *[f'share_{c}' for c in classes]):
        close(actual[key], old[key])
dist_rows = rows(args.output / 'sentiment-distributions.csv')
distributions = {(d['scope'], d['method'], d['model'], d['condition'], d['grounding']): d for d in dist_rows}
assert len(distributions) == len(dist_rows) == 36
for key, d in distributions.items():
    scope, method, model, condition, grounding = key
    selected = [summary[(scope, method, model, condition, h)] for h in ([int(grounding)] if scope == 'baseline' else levels)]
    assert int(d['n_debates']) == (9 if scope == 'baseline' else 3)
    for c in classes:
        close(d[f'share_{c}'], statistics.mean(float(s[f'share_{c}']) for s in selected))

# Decode actual SVG coordinates, using fixed required scales and panel geometry.
ns = {'s': 'http://www.w3.org/2000/svg'}
number = r'[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?'
height_pt = 81 * 72 / 25.4
wide_width = (557.74 - 6.26) / 2
authoring = json.loads((args.qa / 'authoring-checks.json').read_text(encoding='utf-8'))
assert len(authoring['text_bounds']) == 24 and all(b['text_bounds'] == b['text_overlap'] == 'PASS' for b in authoring['text_bounds'])
marks = {(m['scope'], m['kind'], m['gid']): m for m in authoring['marks']}
checked = 0
for asset in manifest['assets']:
    scope, name = asset['scope'], asset['name']
    path = args.output / scope / name
    svg = ET.parse(path.with_suffix('.svg')).getroot()
    close(float(svg.attrib['width'][:-2]) * 25.4 / 72, asset['width_mm'], 1e-6)
    close(float(svg.attrib['height'][:-2]) * 25.4 / 72, 81, 1e-6)
    assert not svg.findall('.//s:image', ns)
    with Image.open(path.with_suffix('.png')) as im:
        assert list(im.size) == asset['png_pixels'] and im.mode == 'RGBA' and im.getpixel((0, 0))[3] == 0
        close(im.info['dpi'][0], 600, .01)
        assert abs(im.width - asset['width_mm'] * 600 / 25.4) < 1 and abs(im.height - 81 * 600 / 25.4) < 1
    for group in svg.findall('.//s:g', ns):
        gid = group.attrib.get('id', '')
        kind = 'trend' if gid.startswith('trend-') else 'bar' if gid.startswith('label-') else 'dots' if gid.startswith(('debates-', 'mean-')) else None
        if kind is None:
            continue
        mark = marks[(scope, kind, gid)]
        if kind == 'bar':
            h = str(mark['grounding']) if scope == 'baseline' else '0;10;100'
            expected = float(distributions[(scope, mark['method'], mark['model'], mark['condition'], h)][f"share_{mark['label']}"]) * 100
            close(mark['value'], expected)
            coords = [float(x) for x in re.findall(number, group.find('s:path', ns).attrib['d'])]
            close((coords[2] - coords[0]) / (wide_width * 72 / 25.4 * .73) * 100, expected, 2e-6)
            checked += 1
            continue
        method, model, condition = mark['method'], mark['model'], mark['condition']
        lo, hi = (0, 100) if mark.get('metric') == 'uncertain' else (-1, 1) if method == methods[0] else (0, 1)
        assert tuple(mark['limits']) == (lo, hi)
        if kind == 'trend':
            expected = [float(summary[(scope, method, model, condition, h)]['share_uncertain' if mark['metric'] == 'uncertain' else 'mean_score'])
                        * (100 if mark['metric'] == 'uncertain' else 1) for h in levels]
            coords = [float(x) for x in re.findall(number, group.find('s:path', ns).attrib['d'])]
            assert len(coords) == 6
            ys = coords[1::2]
        else:
            actual = summary[(scope, method, model, condition, mark['grounding'])]
            if gid.startswith('mean-'):
                expected = [float(actual['mean_score'])]
            else:
                chosen = sorted([r for r in source_runs if r['Run ID'] in actual['run_ids'].split(';')], key=lambda r: (r['Topic key'], int(r['Repetition'])))
                expected = [independent[(r['Run ID'], method)]['score'] for r in chosen]
                assert mark['run_ids'] == [r['Run ID'] for r in chosen]
            ys = [float(u.attrib['y']) for u in group.findall('.//s:use', ns)]
            if not ys:
                # Matplotlib serializes a single hollow marker as a positioned
                # path instead of a reusable marker plus <use> coordinates.
                paths = group.findall('s:path', ns)
                assert condition != 'NONE' and len(expected) == len(paths) == 1
                assert 'fill: none' in paths[0].attrib['style']
                coords = [float(x) for x in re.findall(number, paths[0].attrib['d'])]
                marker_ys = coords[1::2]
                close(max(marker_ys) - min(marker_ys), 85 ** .5, 2e-6)
                ys = [(max(marker_ys) + min(marker_ys)) / 2]
        assert len(ys) == len(expected) == len(mark['values']), (scope, kind, gid)
        for y, value, recorded in zip(ys, expected, mark['values']):
            close(recorded, value)
            close(lo + (height_pt * .65 - y) / (height_pt * .35) * (hi - lo), value, 2e-6)
            checked += 1
assert len(manifest['assets']) == 24 and checked == 570
pdfs = [args.output / 'all-sentiment-options.pdf', *[args.output / s / 'sentiment-options.pdf' for s in manifest['scopes']]]
for path in pdfs:
    pdf = PdfReader(path)
    assert len(pdf.pages) == (12 if path.parent == args.output else 3)
    for page in pdf.pages:
        close(float(page.mediabox.width) * 25.4 / 72, 557.74, 1e-6)
        close(float(page.mediabox.height) * 25.4 / 72, 81, 1e-6)
        assert 'VADER' in page.extract_text() and 'Cardiff' in page.extract_text()
        objects = page['/Resources'].get('/XObject', {})
        assert not any(o.get_object().get('/Subtype') == '/Image' for o in objects.values())
before = json.loads((args.qa / 'before.json').read_text(encoding='utf-8'))
for relative, expected in before['protected_file_sha256'].items():
    # The snapshot ran while the new, explicitly task-owned renderer was authored.
    if relative == 'scripts/generate-adversarial-sentiment-options.py':
        continue
    assert digest(root / relative) == expected, relative
for ref, expected in before['reference_tips'].items():
    assert subprocess.check_output(['git', 'rev-parse', ref], text=True).strip() == expected
assert subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip() == 'codex/dual-interface-integration'
assert subprocess.run(['git', 'merge-base', '--is-ancestor', 'main', 'HEAD']).returncode == 0
for commit in before['excluded_ancestors']:
    assert subprocess.run(['git', 'merge-base', '--is-ancestor', commit, 'HEAD']).returncode == 1
previous = (args.qa / 'before-progress.md').read_text(encoding='utf-8')
current = (root / 'docs/implementation-progress.md').read_text(encoding='utf-8')
assert current[current.index('## T80 '):].rstrip() == previous[previous.index('## T80 '):].rstrip()
receipt = {'status': 'PASS', 'native_local_reports': 72, 'native_sentiment_chunks': 11752,
           'speech_method_means': 3600, 'debate_method_means': 144, 'scope_method_summaries': 84,
           'matched_debate_method_pairs': 36, 'baseline_values_unchanged': True,
           'svg_plotted_values_checked': checked, 'png_geometry_dpi_transparency': 'PASS',
           'pdfs': 5, 'combined_pdf_pages': 12, 'vector_geometry_no_raster_images': 'PASS',
           'protected_files_unchanged': len(before['protected_file_sha256']) - int('scripts/generate-adversarial-sentiment-options.py' in before['protected_file_sha256']),
           'reference_tips': before['reference_tips'], 'excluded_ancestors': before['excluded_ancestors'],
           'previous_progress_preserved': True, 'inference_performed': False,
           'builder_sha256': digest(root / 'scripts/generate-adversarial-sentiment-options.py')}
(args.qa / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
(args.output / 'verification.json').write_text(json.dumps({k: v for k, v in receipt.items() if k not in ('reference_tips', 'excluded_ancestors')}, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in receipt.items() if k not in ('reference_tips', 'excluded_ancestors')}))
