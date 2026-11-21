  // ---------------------------------------------------------------- trusted clock

  /*
   * Trusted time (owner, 2026-11-20), so changing the phone's clock or time zone neither starts a new
   * day for the daily counters (Explore minutes, Discover, Your day) nor ends the chat-reel cooldown.
   *
   * The app passes its tamper-resistant clock: how far it is from the phone's clock (offset), the UTC
   * offset of the zone days are counted in (a new zone only counts from the next midnight), today's
   * day number and when the next day may start, and when it read all that (at, phone ms). The page
   * anchors that time on a monotonic clock, so changing the phone's clock afterwards changes nothing
   * here. A clock older than CLOCK_FRESH_MS, or from the future, is not used: a new page starts with
   * the clock its script was registered with, which can be old; the app sends a fresh one when the
   * page is ready, whenever it comes to the front, and when the phone's time or zone changes.
   *
   * Without a fresh clock the day doesn't move on: today() keeps the last day seen (only the very
   * first start uses the phone's date). The day never goes backwards either.
   */
  var CLOCK_FRESH_MS = 10 * 60 * 1000;
  var clock = { fresh: false, base: 0, mono: 0, zoneMin: null, day: null, nextAt: 0 };
  var LASTDAY_KEY = '__tb_lastday';
  var lastDay = null;

  /** Applies a clock from the app: {offset, zoneMin, day, nextAt, at}. A stale one is ignored. */
  function applyClock(c) {
    if (!c || typeof c.offset !== 'number' || typeof c.zoneMin !== 'number' || typeof c.at !== 'number') return;
    var age = Date.now() - c.at; // by the phone's clock: tiny, unless the clock is old or was just changed
    if (!(age > -5000 && age < CLOCK_FRESH_MS)) return;
    clock.fresh = true;
    clock.base = c.at + c.offset + Math.max(0, age);
    clock.mono = performance.now();
    clock.zoneMin = c.zoneMin;
    if (typeof c.day === 'number' && c.day > 0 && typeof c.nextAt === 'number') {
      clock.day = c.day;
      clock.nextAt = c.nextAt;
    }
  }

  /**
   * The app's trusted time now, in whole ms like Date.now() (the phone's clock until a fresh clock has
   * arrived). Whole: these times are stored and sent to the app, which reads them as integers.
   */
  function trustedNow() {
    return clock.fresh ? Math.round(clock.base + (performance.now() - clock.mono)) : Date.now();
  }

  /** Today's date ("Fri Nov 20 2026") by the trusted clock; never goes backwards. */
  function today() {
    if (!lastDay) {
      lastDay = loadJson(LASTDAY_KEY);
      if (!lastDay || typeof lastDay.n !== 'number' || typeof lastDay.key !== 'string') lastDay = null;
    }
    if (!clock.fresh && lastDay) return lastDay.key; // no trusted time yet: the day doesn't move on
    var ms = trustedNow();
    var zoneMin = clock.zoneMin === null ? -new Date(ms).getTimezoneOffset() : clock.zoneMin;
    // The app's day while it lasts (it knows when the next one may start); after that, count on.
    var n = clock.day !== null && ms < clock.nextAt ? clock.day : Math.floor((ms + zoneMin * 60000) / 86400000);
    if (lastDay && n <= lastDay.n) return lastDay.key; // never backwards
    var local = new Date(n * 86400000);
    lastDay = { n: n, key: new Date(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()).toDateString() };
    saveJson(LASTDAY_KEY, lastDay);
    return lastDay.key;
  }

  /**
   * A record kept per day in localStorage (Explore minutes, Your day, Discover): get() loads it once
   * and starts a fresh one (from fresh(), with `date` set) when the stored one is from another day or
   * fails `valid`; save() stores it. The page saves these on a timer and when it is hidden.
   */
  function dailyRecord(key, fresh, valid) {
    var rec = null;
    return {
      get: function () {
        var day = today();
        if (!rec) rec = loadJson(key);
        if (!rec || rec.date !== day || (valid && !valid(rec))) {
          rec = fresh();
          rec.date = day;
        }
        return rec;
      },
      save: function () {
        if (rec) saveJson(key, rec);
      }
    };
  }

  /*
   * Instagram's server time for the app's trusted clock, on phones without network time of their own
   * (below Android 13). From the Date header of Instagram's own POST responses on this site (never
   * served from a cache; readable because same-origin), at most every SERVER_TIME_EVERY_MS. No request
   * is made for this.
   */
  var SERVER_TIME_EVERY_MS = 5 * 60 * 1000;
  var serverTimeSentAt = null;
  var serverTimeInstalled = false;

  function installServerTime() {
    var X = window.XMLHttpRequest;
    if (serverTimeInstalled || !X || !X.prototype) return;
    serverTimeInstalled = true;
    var open = X.prototype.open;
    X.prototype.open = function (method) {
      try {
        if (String(method).toUpperCase() === 'POST') this.addEventListener('load', onServerResponse);
      } catch (e) {
        // Never break Instagram's requests.
      }
      return open.apply(this, arguments);
    };
  }

  function onServerResponse() {
    var now = performance.now();
    if (serverTimeSentAt !== null && now - serverTimeSentAt < SERVER_TIME_EVERY_MS) return;
    var ms = NaN;
    try {
      ms = Date.parse(this.getResponseHeader('date') || ''); // null for other sites' responses
    } catch (e) {
      return;
    }
    if (!(ms > 0)) return;
    serverTimeSentAt = now;
    send({ t: 'serverTime', ms: ms });
  }

