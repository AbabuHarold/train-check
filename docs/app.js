(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* 無視 */ } }
  };
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  let config = null;
  let current = null;
  let place = null;          // 現在地に一番近い登録地点の名前
  let geoState = 'off';      // off | waiting | ok | denied | far
  let showAll = false;

  const hasPlaces = () => Object.values((config && config.places) || {}).some((p) => p && typeof p.lat === 'number');
  function visibleRoutes() {
    if (showAll || !place) return config.routes;
    const list = config.routes.filter((r) => r.from === place);
    return list.length ? list : config.routes;
  }

  function locate() {
    if (!hasPlaces() || !('geolocation' in navigator)) return;
    geoState = 'waiting';
    navigator.geolocation.getCurrentPosition((pos) => {
      const near = TrainCore.nearestPlace(config, { lat: pos.coords.latitude, lng: pos.coords.longitude });
      place = near ? near.name : null;
      geoState = near ? 'ok' : 'far';
      renderTabs();
      render();
    }, () => { geoState = 'denied'; place = null; renderTabs(); }, { enableHighAccuracy: false, maximumAge: 5 * 60 * 1000, timeout: 10000 });
  }

  async function load() {
    try {
      const res = await fetch('timetable.json', { cache: 'no-cache' });
      config = await res.json();
    } catch (e) {
      $('main').innerHTML = '<p class="error">時刻表を読み込めませんでした。</p>';
      return;
    }
    const param = new URLSearchParams(location.search).get('r');
    const ids = config.routes.map((r) => r.id);
    current = ids.includes(param) ? param : (ids.includes(store.get('route')) ? store.get('route') : ids[0]);
    if (param) showAll = true;   // ショートカットから開いたときは指定ルートを優先
    renderTabs();
    render();
    locate();
  }

  function renderTabs() {
    const routes = visibleRoutes();
    if (!routes.some((r) => r.id === current)) current = routes[0].id;
    let status = '';
    if (hasPlaces()) {
      const msg = { waiting: '現在地を確認中…', denied: '位置情報がオフのため全ルート表示', far: '登録地点の近くではないため全ルート表示', off: '' }[geoState]
        || (showAll ? `現在地: ${esc(place)}付近（全ルート表示中）` : `現在地: ${esc(place)}付近`);
      const btn = place ? `<button class="link" id="toggleAll">${showAll ? '近くだけ' : 'すべて表示'}</button>` : '';
      status = `<div class="geo"><span>${msg}</span>${btn}</div>`;
    }
    $('geo').innerHTML = status;
    const t = $('toggleAll');
    if (t) t.onclick = () => { showAll = !showAll; renderTabs(); render(); };
    $('tabs').style.gridTemplateColumns = routes.length === 1 ? '1fr' : '1fr 1fr';
    $('tabs').innerHTML = routes.map((r) =>
      `<button class="tab" role="tab" data-id="${esc(r.id)}" aria-selected="${r.id === current}">${esc(r.label)}</button>`).join('');
    $('tabs').querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
      current = b.dataset.id;
      store.set('route', current);
      history.replaceState(null, '', '?r=' + encodeURIComponent(current));
      renderTabs();
      render();
    }));
  }

  function walkFor(route) {
    const custom = store.get('walk', {})[route.id];
    return custom != null ? custom : (route.walkMinutes || 0);
  }

  function render() {
    if (!config) return;
    const route = config.routes.find((r) => r.id === current);
    const walk = walkFor(route);
    const res = TrainCore.nextDepartures(route, new Date(), { count: 6, walkMinutes: walk, config });
    const [first, ...rest] = res.departures;
    const from = route.from || '出発地';

    if (!first) {
      $('main').innerHTML = '<p class="empty">この先の電車が時刻表にありません。</p>';
    } else {
      const cls = first.leaveIn <= 0 ? 'now' : first.leaveIn <= 3 ? 'soon' : '';
      const leaveText = first.leaveIn <= 0 ? '今すぐ出れば間に合う' : `${esc(from)}を出るまで あと <b>${first.leaveIn}</b> 分`;
      $('main').innerHTML = `
        <section class="card">
          <div class="meta"><span>${esc(route.station || '')}</span><span>${esc(route.line || '')}</span></div>
          <div class="next">
            <span class="time">${first.time}</span><span class="suffix">発</span>
            ${first.kind ? `<span class="badge">${esc(first.kind)}</span>` : ''}
            ${first.nextServiceDay ? '<span class="badge">始発</span>' : ''}
          </div>
          <div class="leave ${cls}">${leaveText}</div>
          ${first.arrive ? `<div class="arrive">${esc(route.destination || route.to + 'の駅')} 着 ${first.arrive}（発車まで ${first.departsIn} 分）</div>` : ''}
          ${first.legs ? `<ol class="legs">${first.legs.map((l) => `
            <li><span class="lt">${l.dep}</span><span>${esc(l.from)} → ${esc(l.to)} <small>${l.arr}着</small></span><span class="ll">${esc(l.line)}${l.kind ? ' ' + esc(l.kind) : ''}</span></li>`).join('')}
          </ol>` : ''}
          <div class="walk">
            <span>駅まで徒歩</span>
            <span class="stepper">
              <button id="minus" aria-label="1分減らす">−</button><span>${walk}分</span><button id="plus" aria-label="1分増やす">＋</button>
            </span>
          </div>
        </section>
        <h2>その次（到着が早い順）</h2>
        <section class="card" style="padding:4px 14px">
          <ul>${rest.map((d) => `
            <li><span class="t">${d.time}</span>
                <span>${d.kind ? `<span class="badge">${esc(d.kind)}</span>` : ''}</span>
                <span class="r">${d.arrive ? `<b>${d.arrive}着</b><br>` : ''}あと${d.leaveIn}分で出発</span></li>`).join('')}
          </ul>
        </section>`;
      $('minus').onclick = () => setWalk(route, walk - 1);
      $('plus').onclick = () => setWalk(route, walk + 1);
    }
    $('foot').textContent = `${TrainCore.DAY_LABEL[res.dayType]}ダイヤ ・ ${res.now} 時点`;
  }

  function setWalk(route, v) {
    const all = store.get('walk', {});
    all[route.id] = Math.max(0, Math.min(90, v));
    store.set('walk', all);
    render();
  }

  load();
  setInterval(render, 15000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden && config) { locate(); render(); } });
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
})();
