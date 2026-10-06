/*
 * Alexaスキル「電車チェック」
 *   「アレクサ、電車チェックで大学は何分？」 → 家→大学の次の電車
 *   「アレクサ、電車チェックを開いて」       → 家から出るルートを全部
 */
const Alexa = require('ask-sdk-core');
const https = require('https');
const core = require('./core');
const bundled = require('./timetable.json');

// Webアプリと同じ時刻表を使う場合はURLを入れる（GitHub Pagesの timetable.json）
// 空のままなら、このフォルダの timetable.json を使う
const TIMETABLE_URL = '';

let cache = { at: 0, data: null };
function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: 2500 }, (res) => {
      if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (body += c));
      res.on('end', () => { try { resolve(JSON.parse(body)); } catch (e) { reject(e); } });
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}
async function loadTimetable() {
  if (!TIMETABLE_URL) return bundled;
  if (cache.data && Date.now() - cache.at < 10 * 60 * 1000) return cache.data;
  try {
    cache = { at: Date.now(), data: await fetchJson(TIMETABLE_URL + '?t=' + Date.now()) };
    return cache.data;
  } catch (e) {
    console.log('timetable fetch failed, using bundled copy:', e.message);
    return cache.data || bundled;
  }
}

function slotId(handlerInput) {
  const slot = Alexa.getSlot(handlerInput.requestEnvelope, 'destination');
  const res = slot && slot.resolutions && slot.resolutions.resolutionsPerAuthority;
  const match = res && res.find((r) => r.status.code === 'ER_SUCCESS_MATCH');
  return match ? match.values[0].value.id : null;
}

async function answer(handlerInput, id) {
  const tt = await loadTimetable();
  const routes = tt.routes.filter((r) => r.alexa && (!id || r.alexa === id));
  if (!routes.length) return handlerInput.responseBuilder.speak('そのルートは登録されていません。').getResponse();
  const now = new Date();
  const text = routes.map((r) => core.speech(core.nextDepartures(r, now, { config: tt }))).join(' ');
  const card = routes.map((r) => {
    const res = core.nextDepartures(r, now, { config: tt, count: 3 });
    return `${r.label}: ` + res.departures.map((d) => d.time + (d.kind ? `(${d.kind})` : '')).join(' / ');
  }).join('\n');
  return handlerInput.responseBuilder.speak(text).withSimpleCard('次の電車', card).withShouldEndSession(true).getResponse();
}

const LaunchRequestHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'LaunchRequest',
  handle: (h) => answer(h, null)
};

const NextTrainIntentHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'NextTrainIntent',
  handle: (h) => answer(h, slotId(h))
};

const HelpIntentHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'AMAZON.HelpIntent',
  handle: (h) => h.responseBuilder
    .speak('大学は何分、やバイト先は何分、のように聞いてください。どちらにしますか？')
    .reprompt('大学か、バイト先か、言ってください。').getResponse()
};

const StopHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && ['AMAZON.CancelIntent', 'AMAZON.StopIntent', 'AMAZON.NavigateHomeIntent'].includes(Alexa.getIntentName(h.requestEnvelope)),
  handle: (h) => h.responseBuilder.speak('いってらっしゃい。').withShouldEndSession(true).getResponse()
};

const FallbackHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'AMAZON.FallbackIntent',
  handle: (h) => answer(h, null)
};

const SessionEndedHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'SessionEndedRequest',
  handle: (h) => h.responseBuilder.getResponse()
};

const ErrorHandler = {
  canHandle: () => true,
  handle: (h, error) => {
    console.log('Error:', error && error.stack);
    return h.responseBuilder.speak('ごめんなさい、時刻を調べられませんでした。').getResponse();
  }
};

exports.handler = Alexa.SkillBuilders.custom()
  .addRequestHandlers(LaunchRequestHandler, NextTrainIntentHandler, HelpIntentHandler, StopHandler, FallbackHandler, SessionEndedHandler)
  .addErrorHandlers(ErrorHandler)
  .lambda();
