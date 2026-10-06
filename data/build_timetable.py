"""data/enc*.txt（JR東日本 駅の時刻表から取得した区間データ）から shared/timetable.json を作る"""
import json, re, glob, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
KIND = {'k': '快速', 't': '通勤快速', 's': '特別快速'}
raw = {}
for f in sorted(glob.glob('enc*.txt')):
    for line in open(f, encoding='utf-8'):
        if '=' not in line: continue
        k, v = line.strip().split('=', 1)
        seg, day = k.split('.')
        trips = []
        for tok in v.split():
            m = re.fullmatch(r'(\d\d)(\d\d)(\d\d)([kts]?)', tok)
            dep = int(m[1]) * 60 + int(m[2]); arr = dep + int(m[3])
            s = f'{dep // 60:02d}:{dep % 60:02d}-{arr // 60:02d}:{arr % 60:02d}'
            if m[4]: s += ' ' + KIND[m[4]]
            trips.append(s)
        raw.setdefault(seg, {})[day] = trips

SEG = {
  'saikyo_up':          ('南与野', '赤羽', '埼京線', ''),
  'keihin_s_nippori':   ('赤羽', '日暮里', '京浜東北線', '京浜→日暮里'),
  'keihin_s_ueno':      ('赤羽', '上野', '京浜東北線', '京浜→上野'),
  'utl_up':             ('赤羽', '上野', '上野東京ライン', '上野東京ライン'),
  'joban_down_ueno':    ('上野', '北千住', '常磐線', ''),
  'joban_down_nippori': ('日暮里', '北千住', '常磐線', ''),
  'joban_up_nippori':   ('北千住', '日暮里', '常磐線', ''),
  'joban_up_ueno':      ('北千住', '上野', '常磐線', ''),
  'keihin_n_nippori':   ('日暮里', '赤羽', '京浜東北線', '日暮里→京浜'),
  'keihin_n_ueno':      ('上野', '赤羽', '京浜東北線', '京浜'),
  'utl_down':           ('上野', '赤羽', '上野東京ライン', '上野東京ライン'),
  'saikyo_down':        ('赤羽', '南与野', '埼京線', ''),
}
segments = {}
for sid, (fr, to, line, tag) in SEG.items():
    segments[sid] = {'from': fr, 'to': to, 'line': line, 'tag': tag,
                     'trips': {'weekday': raw[sid]['weekday'], 'holiday': raw[sid]['holiday']}}

cfg = {
  '_説明': ('時刻は JR東日本「駅の時刻表」2026年10月版（各列車の停車駅一覧）から作成。特急は除外。'
           'segments は区間ごとの時刻表で "発-着 種別"、0時台は 24:xx。transferMinutes は乗り換えにかける分数。'
           'walkMinutes は出発地から駅までの徒歩分数。places は各地点の緯度経度（今は駅の位置。家の位置にすると精度が上がる）。'
           'ダイヤ改正があったら作り直してください。'),
  'source': 'JR東日本 駅の時刻表 https://timetables.jreast.co.jp/ (2026年10月版)',
  'sortBy': 'arrival',
  'places': {
    '家': {'lat': 35.8674624, 'lng': 139.6311718},
    '大学': {'lat': 35.7491954, 'lng': 139.8047328},
    'バイト先': {'lat': 35.7138, 'lng': 139.7773},
  },
  'placeRadiusMeters': 1500,
  'transferMinutes': {'赤羽': 4, '上野': 6, '日暮里': 4},
  'webAppUrl': '',
  'widgetRoutes': ['home_univ', 'home_job', 'univ_home', 'job_home'],
  'extraHolidays': ['12-30', '12-31', '01-02', '01-03'],
  'routes': [
    {'id': 'home_univ', 'label': '家 → 大学', 'from': '家', 'to': '大学', 'alexa': 'univ', 'speechName': '大学',
     'station': '南与野駅', 'line': '埼京線 → 赤羽 → 上野/日暮里 → 常磐線', 'walkMinutes': 15,
     'origin': '南与野', 'destination': '北千住',
     'segments': ['saikyo_up', 'utl_up', 'keihin_s_ueno', 'keihin_s_nippori', 'joban_down_ueno', 'joban_down_nippori']},
    {'id': 'univ_home', 'label': '大学 → 家', 'from': '大学', 'to': '家',
     'station': '北千住駅', 'line': '常磐線 → 上野/日暮里 → 赤羽 → 埼京線', 'walkMinutes': 10,
     'origin': '北千住', 'destination': '南与野',
     'segments': ['joban_up_ueno', 'joban_up_nippori', 'utl_down', 'keihin_n_ueno', 'keihin_n_nippori', 'saikyo_down']},
    {'id': 'home_job', 'label': '家 → バイト先', 'from': '家', 'to': 'バイト先', 'alexa': 'job', 'speechName': 'バイト先',
     'station': '南与野駅', 'line': '埼京線 → 赤羽 → 上野', 'walkMinutes': 15,
     'origin': '南与野', 'destination': '上野',
     'segments': ['saikyo_up', 'utl_up', 'keihin_s_ueno']},
    {'id': 'job_home', 'label': 'バイト先 → 家', 'from': 'バイト先', 'to': '家',
     'station': '上野駅', 'line': '上野 → 赤羽 → 埼京線', 'walkMinutes': 5,
     'origin': '上野', 'destination': '南与野',
     'segments': ['utl_down', 'keihin_n_ueno', 'saikyo_down']},
  ],
  'segments': segments,
}
s = json.dumps(cfg, ensure_ascii=False, indent=1)
s = re.sub(r'\[\s*([^\[\]{}]*?)\s*\]', lambda m: '[' + re.sub(r'\s*\n\s*', ' ', m.group(1)) + ']', s)
open('../shared/timetable.json', 'w', encoding='utf-8').write(s + '\n')
print('trips', sum(len(v) for seg in segments.values() for v in seg['trips'].values()), 'bytes', len(s.encode()))
