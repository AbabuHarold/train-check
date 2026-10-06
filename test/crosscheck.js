// JS版の出力（leaveAtMillis は JS 側で同じ式で計算）
const fs = require('fs');
const core = require('../shared/core');
const cfg = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
for (const line of fs.readFileSync(process.argv[3], 'utf8').trim().split('\n')) {
  const t = Number(line);
  for (const r of cfg.routes) {
    const res = core.nextDepartures(r, new Date(t), { config: cfg, count: 4 });
    const sc = core.serviceClock(new Date(t));
    const start = Date.UTC(sc.y, sc.mo - 1, sc.d) - 9 * 3600e3;
    const walk = res.walkMinutes;
    console.log(`${t} ${r.id} ${res.dayType} ${res.now} |` + res.departures.map((d) => ` ${d.time}>${d.arrive} ${d.kind} ${d.leaveIn} ${d.nextServiceDay} ${start + (d.depAbs - walk) * 60000}` + (d.legs ? d.legs.map((l) => ` {${l.from}${l.dep}>${l.to}${l.arr}${l.kind}}`).join('') : '')).join(''));
  }
}
