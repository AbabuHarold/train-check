/*
 * 電車チェック 共通ロジック（Alexa / Webアプリ共通）
 * - 「今 + 徒歩分数」以降に出る電車を時刻表から探す
 * - 日本時間で計算（Alexa側のサーバーはUTCなので必須）
 * - 平日 / 土曜 / 土休日、祝日、年末年始、深夜0時台を考慮
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.TrainCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // 内閣府の祝日（振替休日・国民の休日を含む）2026〜2029年
  const HOLIDAYS = new Set([
    '2026-01-01','2026-01-12','2026-02-11','2026-02-23','2026-03-20','2026-04-29','2026-05-03','2026-05-04','2026-05-05','2026-05-06','2026-07-20','2026-08-11','2026-09-21','2026-09-22','2026-09-23','2026-10-12','2026-11-03','2026-11-23',
    '2027-01-01','2027-01-11','2027-02-11','2027-02-23','2027-03-21','2027-03-22','2027-04-29','2027-05-03','2027-05-04','2027-05-05','2027-07-19','2027-08-11','2027-09-20','2027-09-23','2027-10-11','2027-11-03','2027-11-23',
    '2028-01-01','2028-01-10','2028-02-11','2028-02-23','2028-03-20','2028-04-29','2028-05-03','2028-05-04','2028-05-05','2028-07-17','2028-08-11','2028-09-18','2028-09-22','2028-10-09','2028-11-03','2028-11-23',
    '2029-01-01','2029-01-08','2029-02-11','2029-02-12','2029-02-23','2029-03-20','2029-04-29','2029-04-30','2029-05-03','2029-05-04','2029-05-05','2029-07-16','2029-08-11','2029-09-17','2029-09-23','2029-09-24','2029-10-08','2029-11-03','2029-11-23'
  ]);

  // 深夜何時までを「前日のダイヤ」とみなすか（0時台・1時台の終電対策）
  const SERVICE_DAY_START_HOUR = 3;
  const DAY = 24 * 60;

  const pad = (n) => String(n).padStart(2, '0');

  /** Date → 日本時間の { y, mo, d, dow, h, mi } */
  function jstParts(date) {
    const t = new Date(date.getTime() + 9 * 3600 * 1000);
    return {
      y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate(),
      dow: t.getUTCDay(), h: t.getUTCHours(), mi: t.getUTCMinutes()
    };
  }

  /** 運行日（3時より前は前日扱い）と、その運行日の0時からの経過分 */
  function serviceClock(date) {
    const p = jstParts(date);
    let minutes = p.h * 60 + p.mi;
    let base = date;
    if (p.h < SERVICE_DAY_START_HOUR) {
      minutes += DAY;
      base = new Date(date.getTime() - DAY * 60 * 1000);
    }
    const s = jstParts(base);
    return { y: s.y, mo: s.mo, d: s.d, dow: s.dow, minutes };
  }

  /** 運行日 + n日 の {y,mo,d,dow} */
  function addDays(sd, n) {
    const t = new Date(Date.UTC(sd.y, sd.mo - 1, sd.d + n));
    return { y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate(), dow: t.getUTCDay() };
  }

  /** 'weekday' | 'saturday' | 'holiday' */
  function dayType(sd, config) {
    const iso = `${sd.y}-${pad(sd.mo)}-${pad(sd.d)}`;
    const extra = (config && config.extraHolidays) || [];
    if (sd.dow === 0 || HOLIDAYS.has(iso) || extra.includes(`${pad(sd.mo)}-${pad(sd.d)}`)) return 'holiday';
    if (sd.dow === 6) return 'saturday';
    return 'weekday';
  }

  /** 時刻表 {"6":[5,"15快"], "24":[10]} → [{min, mark}] を時刻順で */
  function parseTable(table) {
    const out = [];
    if (!table) return out;
    for (const [hour, list] of Object.entries(table)) {
      const h = parseInt(hour, 10);
      if (Number.isNaN(h)) continue;
      for (const item of list) {
        const m = String(item).match(/^(\d{1,2})(.*)$/);
        if (!m) continue;
        out.push({ min: h * 60 + parseInt(m[1], 10), mark: m[2].trim() });
      }
    }
    return out.sort((a, b) => a.min - b.min);
  }

  /** ルートと曜日区分から時刻表を選ぶ（土曜ダイヤが無ければ土休日を使う） */
  function tableFor(route, type) {
    const tt = route.timetable || {};
    if (type === 'saturday') return tt.saturday || tt.holiday || tt.weekday;
    if (type === 'holiday') return tt.holiday || tt.weekday;
    return tt.weekday;
  }

  function fmt(min) {
    const m = ((min % DAY) + DAY) % DAY;
    return `${Math.floor(m / 60)}:${pad(m % 60)}`;
  }

  /** その電車の乗車時間（種別ごとの指定があればそれを使う） */
  function rideFor(route, mark) {
    const by = route.rideMinutesByMark || {};
    if (mark && by[mark] != null) return by[mark];
    return route.rideMinutes != null ? route.rideMinutes : null;
  }

  /** 1本の時刻表（"時: [分]" 形式）から候補を作る */
  function simpleCandidates(route, config, clock, target, want) {
    const marks = route.marks || {};
    const list = [];
    // 当日 → 翌運行日 → 翌々運行日（連休の始発などに備えて）
    for (let k = 0; k < 3 && list.length < want; k++) {
      const sd = addDays(clock, k);
      const type = dayType(sd, config);
      for (const dep of parseTable(tableFor(route, type))) {
        const abs = dep.min + k * DAY;
        if (abs < target) continue;
        const ride = rideFor(route, dep.mark);
        list.push({
          time: fmt(abs),
          mark: dep.mark,
          kind: marks[dep.mark] || dep.mark || '',
          arrive: ride != null ? fmt(abs + ride) : null,
          depAbs: abs,
          arriveAbs: ride != null ? abs + ride : null,
          nextServiceDay: k > 0,
          dayType: type
        });
        if (list.length >= want) break;
      }
    }
    return list;
  }

  // ---- 乗り換えありのルート（区間ごとの時刻表をつなぐ） ----

  /** "05:06" → 運行日の0時からの分（3時前は翌日扱いで +24h） */
  function hm(h, m) { return (h < SERVICE_DAY_START_HOUR ? h + 24 : h) * 60 + m; }

  const TRIP = /^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})(?:\s+(.*))?$/;
  const tripCache = new WeakMap();

  /** 区間の時刻表 ["05:06-05:26 快速", ...] → [{dep, arr, kind}]（発車順） */
  function segTrips(seg, type) {
    let byType = tripCache.get(seg);
    if (!byType) { byType = {}; tripCache.set(seg, byType); }
    if (byType[type]) return byType[type];
    const tt = seg.trips || {};
    const src = type === 'saturday' ? (tt.saturday || tt.holiday || tt.weekday)
      : type === 'holiday' ? (tt.holiday || tt.weekday) : tt.weekday;
    const out = [];
    for (const s of src || []) {
      const m = String(s).match(TRIP);
      if (!m) continue;
      const dep = hm(+m[1], +m[2]);
      let arr = hm(+m[3], +m[4]);
      if (arr < dep) arr += DAY;
      out.push({ dep, arr, kind: (m[5] || '').trim() });
    }
    out.sort((a, b) => a.dep - b.dep);
    return (byType[type] = out);
  }

  /** 運行日 k の中で、時刻 t 以降に出る最初の便（乗り継ぎは同じ運行日・2時間以内に限る） */
  const MAX_WAIT = 120;
  function findTrip(seg, clock, k, t, config) {
    const trips = segTrips(seg, dayType(addDays(clock, k), config));
    for (const tr of trips) {
      const dep = tr.dep + k * DAY;
      if (dep < t) continue;
      if (dep - t > MAX_WAIT) return null;
      return { dep, arr: tr.arr + k * DAY, kind: tr.kind };
    }
    return null;
  }

  function makeLeg(seg, tr) {
    return { from: seg.from, to: seg.to, line: seg.line || '', tag: seg.tag || '', kind: tr.kind, depAbs: tr.dep, arrAbs: tr.arr, dep: fmt(tr.dep), arr: fmt(tr.arr) };
  }

  /** 出発駅を depAbs ちょうどに出る便から、目的駅に一番早く着く行き方 */
  function bestJourney(route, segs, config, clock, k, depAbs) {
    const transfers = config.transferMinutes || {};
    const best = {};
    for (const seg of segs) {
      if (seg.from !== route.origin) continue;
      const sd = dayType(addDays(clock, k), config);
      for (const tr of segTrips(seg, sd)) {
        if (tr.dep + k * DAY !== depAbs) continue;
        const leg = makeLeg(seg, { dep: tr.dep + k * DAY, arr: tr.arr + k * DAY, kind: tr.kind });
        const cur = best[seg.to];
        if (!cur || leg.arrAbs < cur.time) best[seg.to] = { time: leg.arrAbs, legs: [leg] };
      }
    }
    for (let round = 0; round < 6; round++) {
      let changed = false;
      for (const st of Object.keys(best)) {
        if (st === route.destination) continue;
        const here = best[st];
        const wait = transfers[st] != null ? transfers[st] : 3;
        for (const seg of segs) {
          if (seg.from !== st || seg.to === route.origin) continue;
          const tr = findTrip(seg, clock, k, here.time + wait, config);
          if (!tr) continue;
          const cur = best[seg.to];
          if (!cur || tr.arr < cur.time) {
            best[seg.to] = { time: tr.arr, legs: here.legs.concat([makeLeg(seg, tr)]) };
            changed = true;
          }
        }
      }
      if (!changed) break;
    }
    return best[route.destination] || null;
  }

  function journeyCandidates(route, config, clock, target, want) {
    const lib = config.segments || {};
    const segs = route.segments.map((id) => lib[id]).filter(Boolean);
    const list = [];
    for (let k = 0; k < 3 && list.length < want; k++) {
      const type = dayType(addDays(clock, k), config);
      const deps = new Set();
      for (const seg of segs) {
        if (seg.from !== route.origin) continue;
        for (const tr of segTrips(seg, type)) if (tr.dep + k * DAY >= target) deps.add(tr.dep + k * DAY);
      }
      for (const depAbs of [...deps].sort((a, b) => a - b)) {
        const j = bestJourney(route, segs, config, clock, k, depAbs);
        if (!j) continue;
        const tags = j.legs.map((l) => l.tag).filter(Boolean);
        list.push({
          time: fmt(depAbs),
          mark: '',
          kind: tags.join('・'),
          arrive: fmt(j.time),
          depAbs,
          arriveAbs: j.time,
          legs: j.legs,
          nextServiceDay: k > 0,
          dayType: type
        });
        if (list.length >= want) break;
      }
    }
    return list;
  }

  /**
   * 次の電車を探す
   * @param route  timetable.json の routes[i]
   * @param now    Date（省略時は現在）
   * @param opts   { count=3, walkMinutes=route.walkMinutes, config, sortBy }
   *   sortBy: 'arrival'（既定）… 到着が早い順。後から出て先に着く電車があれば、追い抜かれる方は出さない
   *           'departure'     … 発車順
   */
  function nextDepartures(route, now, opts) {
    opts = opts || {};
    now = now || new Date();
    const count = opts.count || 3;
    const sortBy = opts.sortBy || (opts.config && opts.config.sortBy) || 'arrival';
    const walk = opts.walkMinutes != null ? opts.walkMinutes : (route.walkMinutes || 0);
    const clock = serviceClock(now);
    const target = clock.minutes + walk;
    const want = sortBy === 'arrival' ? count + 10 : count;
    let list = route.segments
      ? journeyCandidates(route, opts.config || {}, clock, target, want)
      : simpleCandidates(route, opts.config, clock, target, want);
    for (const d of list) {
      d.departsIn = d.depAbs - clock.minutes;          // 発車まで何分
      d.leaveIn = d.depAbs - clock.minutes - walk;      // 出発地を出るまで何分
    }

    if (sortBy === 'arrival' && list.length && list.every((d) => d.arriveAbs != null)) {
      // 後から出て同時かそれより早く着く電車がある → 乗る意味がないので外す
      let best = Infinity;
      for (let i = list.length - 1; i >= 0; i--) {
        if (list[i].arriveAbs >= best) list[i].overtaken = true;
        else best = list[i].arriveAbs;
      }
      list = list.filter((d) => !d.overtaken).sort((a, b) => a.arriveAbs - b.arriveAbs || a.depAbs - b.depAbs);
    }
    list = list.slice(0, count);
    return { route, walkMinutes: walk, now: fmt(clock.minutes), dayType: dayType(clock, opts.config), departures: list };
  }

  /** 2点間の距離（m） */
  function distanceMeters(a, b) {
    const R = 6371000, rad = Math.PI / 180;
    const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }

  /** 現在地から一番近い登録地点（半径内のみ）。無ければ null */
  function nearestPlace(config, pos) {
    const places = (config && config.places) || {};
    const radius = (config && config.placeRadiusMeters) || 800;
    let best = null;
    for (const [name, p] of Object.entries(places)) {
      if (!p || typeof p.lat !== 'number' || typeof p.lng !== 'number') continue;
      const d = distanceMeters(pos, p);
      if (d <= radius && (!best || d < best.distance)) best = { name, distance: Math.round(d) };
    }
    return best;
  }

  /** 音声用の時刻「8時12分」「8時ちょうど」 */
  function speakTime(hhmm) {
    const [h, m] = hhmm.split(':').map(Number);
    return m === 0 ? `${h}時ちょうど` : `${h}時${m}分`;
  }

  /** Alexaが読み上げる文 */
  function speech(result) {
    const r = result.route;
    const deps = result.departures;
    const name = r.speechName || r.to || r.label;
    if (!deps.length) return `${name}方面の電車が見つかりませんでした。時刻表を確認してください。`;
    const [a, b] = deps;
    if (a.legs) return journeySpeech(result, name);
    const kind = a.kind ? `の${a.kind}` : '';
    let s = `${name}方面、次は${a.nextServiceDay ? '始発の' : ''}${speakTime(a.time)}発${kind}です。`;
    const from = r.from ? `${r.from}を出るまで` : '出発まで';
    if (a.leaveIn <= 0) s += '今すぐ出れば間に合います。';
    else s += `${from}あと${a.leaveIn}分です。`;
    if (b) s += `その次は${speakTime(b.time)}発${b.kind ? 'の' + b.kind : ''}です。`;
    return s;
  }

  function journeySpeech(result, name) {
    const r = result.route;
    const [a, b] = result.departures;
    const first = a.legs[0];
    let s = `${name}方面、次は${a.nextServiceDay ? '始発の' : ''}${first.from}${speakTime(a.time)}発${first.kind ? 'の' + first.kind : ''}です。`;
    const changes = a.legs.slice(1).map((l) => `${l.from}で${l.line}`);
    if (changes.length) s += `${changes.join('、')}に乗り換えて、`;
    s += `${r.destination || r.to}に${speakTime(a.arrive)}に着きます。`;
    const from = r.from ? `${r.from}を出るまで` : '出発まで';
    if (a.leaveIn <= 0) s += '今すぐ出れば間に合います。';
    else s += `${from}あと${a.leaveIn}分です。`;
    if (b && b.nextServiceDay && !a.nextServiceDay) s += `これが終電です。その次は始発の${speakTime(b.time)}発です。`;
    else if (b) s += `その次は${speakTime(b.time)}発、${speakTime(b.arrive)}着です。`;
    return s;
  }

  const DAY_LABEL = { weekday: '平日', saturday: '土曜', holiday: '土休日' };

  return { nextDepartures, nearestPlace, distanceMeters, speech, speakTime, dayType, serviceClock, parseTable, jstParts, DAY_LABEL, HOLIDAYS };
});
