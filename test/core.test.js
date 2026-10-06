// node test/core.test.js
const assert = require('assert');
const core = require('../shared/core');

const jst = (s) => new Date(s + '+09:00');
const route = {
  from: '家', to: '大学', speechName: '大学', walkMinutes: 15, rideMinutes: 20, marks: { '快': '快速' },
  timetable: {
    weekday: { '7': [0, '12快', 20, 45], '8': [5], '24': [10] },
    holiday: { '5': [30], '9': [0] }
  }
};

// 平日 7:00 → 7:15以降 → 7:20, 7:45, 8:05
let r = core.nextDepartures(route, jst('2026-10-06T07:00:00'));
assert.deepStrictEqual(r.departures.map((d) => d.time), ['7:20', '7:45', '8:05']);
assert.strictEqual(r.departures[0].leaveIn, 5);
assert.strictEqual(r.departures[0].arrive, '7:40');
assert.strictEqual(r.dayType, 'weekday');

// ちょうど徒歩分後に発車 → 含む、快速の印
r = core.nextDepartures(route, jst('2026-10-06T06:57:00'));
assert.strictEqual(r.departures[0].time, '7:12');
assert.strictEqual(r.departures[0].kind, '快速');
assert.strictEqual(r.departures[0].leaveIn, 0);

// 深夜 23:50 → 0:10 の終電（24時表記）
r = core.nextDepartures(route, jst('2026-10-06T23:50:00'));
assert.strictEqual(r.departures[0].time, '0:10');
assert.strictEqual(r.departures[0].nextServiceDay, false);

// 深夜 0:30（火曜の運行日扱い）→ 翌日(水)の始発 7:00
r = core.nextDepartures(route, jst('2026-10-07T00:30:00'));
assert.strictEqual(r.dayType, 'weekday');
assert.strictEqual(r.departures[0].time, '7:00');
assert.strictEqual(r.departures[0].nextServiceDay, true);

// 祝日（2026-10-12 スポーツの日）→ 土休日ダイヤ
r = core.nextDepartures(route, jst('2026-10-12T05:00:00'));
assert.strictEqual(r.dayType, 'holiday');
assert.strictEqual(r.departures[0].time, '5:30');

// 土曜は saturday が無ければ holiday
r = core.nextDepartures(route, jst('2026-10-10T08:00:00'));
assert.strictEqual(r.dayType, 'saturday');
assert.strictEqual(r.departures[0].time, '9:00');

// 年末年始（extraHolidays）
const config = { extraHolidays: ['12-30'] };
r = core.nextDepartures(route, jst('2026-12-30T05:00:00'), { config });
assert.strictEqual(r.dayType, 'holiday');

// 金曜の最終後 → 土曜(holiday表)の 5:30
r = core.nextDepartures(route, jst('2026-10-09T23:58:00'), { count: 2 });
assert.deepStrictEqual(r.departures.map((d) => d.time), ['5:30', '9:00']);

// 読み上げ
r = core.nextDepartures(route, jst('2026-10-06T07:00:00'));
assert.strictEqual(core.speech(r), '大学方面、次は7時20分発です。家を出るまであと5分です。その次は7時45分発です。');
assert.strictEqual(core.speakTime('9:00'), '9時ちょうど');

// 実データの時刻表が読み込めるか
const tt = require('../shared/timetable.json');
for (const rt of tt.routes) {
  const res = core.nextDepartures(rt, jst('2026-10-06T08:00:00'), { config: tt });
  assert.ok(res.departures.length === 3, rt.id);
}
// 到着が早い順：各停20分・快速12分。7:20各停(→7:40) は 7:24快速(→7:36) に抜かれるので消える
const r2 = {
  from: '家', to: '大学', walkMinutes: 0, rideMinutes: 20, rideMinutesByMark: { '快': 12 }, marks: { '快': '快速' },
  timetable: { weekday: { '7': [10, 20, '24快', 30, 40, '45快'] } }
};
r = core.nextDepartures(r2, jst('2026-10-06T07:15:00'), { count: 4 });
assert.deepStrictEqual(r.departures.map((d) => d.time + '>' + d.arrive), ['7:24>7:36', '7:30>7:50', '7:45>7:57', '7:10>7:30']);
// 7:40各停(→8:00) は 7:45快速(→7:57) に抜かれるので出ない（4本目は翌日の始発）
assert.ok(!r.departures.some((d) => d.time === '7:40' || d.time === '7:20'));
// 発車順にもできる
r = core.nextDepartures(r2, jst('2026-10-06T07:15:00'), { count: 3, sortBy: 'departure' });
assert.deepStrictEqual(r.departures.map((d) => d.time), ['7:20', '7:24', '7:30']);

// 位置情報
const cfg2 = { places: { '家': { lat: 35.0, lng: 139.0 }, '大学': { lat: 35.05, lng: 139.0 } }, placeRadiusMeters: 800 };
assert.strictEqual(core.nearestPlace(cfg2, { lat: 35.003, lng: 139.0 }).name, '家');
assert.strictEqual(core.nearestPlace(cfg2, { lat: 35.025, lng: 139.0 }), null);
assert.strictEqual(core.nearestPlace({ places: { '家': null } }, { lat: 35, lng: 139 }), null);

// 乗り換えあり（実データ）：2026-10-07(水) 7:30 に家 → 7:51 南与野 → 赤羽 → 上野東京ライン → 上野 → 常磐線 → 北千住 8:46
{
  const rt = tt.routes.find((x) => x.id === 'home_univ');
  const res = core.nextDepartures(rt, jst('2026-10-07T07:30:00'), { config: tt });
  const a = res.departures[0];
  assert.strictEqual(a.time + '>' + a.arrive, '7:51>8:46');
  assert.deepStrictEqual(a.legs.map((l) => l.from + l.dep), ['南与野7:51', '赤羽8:18', '上野8:35']);
  // 乗り換え時間（赤羽4分）を守っている
  for (const d of res.departures) for (let i = 1; i < d.legs.length; i++) {
    const wait = d.legs[i].depAbs - d.legs[i - 1].arrAbs;
    assert.ok(wait >= tt.transferMinutes[d.legs[i].from], d.time + ' ' + d.legs[i].from);
  }
  // 終電後は翌朝の始発（深夜に駅で何時間も待つ行き方は出さない）
  const late = core.nextDepartures(tt.routes.find((x) => x.id === 'univ_home'), jst('2026-10-10T23:40:00'), { config: tt });
  assert.ok(late.departures[0].nextServiceDay);
  for (const d of late.departures) for (let i = 1; i < d.legs.length; i++) assert.ok(d.legs[i].depAbs - d.legs[i - 1].arrAbs <= 120);
}

console.log('all tests passed');
