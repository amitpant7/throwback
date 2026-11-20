  // ---------------------------------------------------------------- your day (stats)

  /*
   * "Your day" in Throwback's settings (owner, 2026-11-20): time on each part of Instagram today and
   * what Throwback took out of the feed. Counted on the device only, per day; the app reads it with
   * __throwback.dayStats() when the screen opens. Time counts while the page is visible and not
   * covered by a Throwback screen.
   */
  var DAY_KEY = '__tb_day';
  var dayRec = null;

  function dayLog() {
    if (!dayRec) {
      try {
        dayRec = JSON.parse(localStorage.getItem(DAY_KEY) || 'null');
      } catch (e) {
        dayRec = null;
      }
    }
    if (!dayRec || dayRec.date !== today() || typeof dayRec.sec !== 'object' || !dayRec.sec) {
      dayRec = { date: today(), sec: {}, ads: 0, sugg: 0 };
    }
    return dayRec;
  }

  function saveDay() {
    if (totals && totals.dirty) saveTotals();
    try {
      localStorage.setItem(DAY_KEY, JSON.stringify(dayRec));
    } catch (e) {
      // Storage unavailable: today's counts last for this page only.
    }
  }

  function dayArea() {
    if (clipPlayer) return 'explore';
    var p = location.pathname;
    if (state.mode === MODE_MESSAGES || /^\/direct\//.test(p)) return 'messages';
    if (/^\/stories\//.test(p)) return 'stories';
    if (/^\/explore\//.test(p)) return 'explore';
    if (p === '/') return 'feed';
    return 'other'; // profiles, single posts, settings
  }

  function startDayClock() {
    setInterval(function () {
      if (document.visibilityState !== 'visible' || state.covered) return;
      var d = dayLog();
      var a = dayArea();
      d.sec[a] = (d.sec[a] || 0) + 1;
      d.dirty = (d.dirty || 0) + 1;
      if (d.dirty >= 10) {
        d.dirty = 0;
        saveDay();
      }
    }, 1000);
  }

  function countRemoved(field) {
    var d = dayLog();
    d[field] = (d[field] || 0) + 1;
    var t = totalsLog();
    t[field] = (t[field] || 0) + 1;
    t.dirty = true;
  }

  /*
   * Removed ads and suggested posts since the last support ask (or first use), for the line in
   * "You're all caught up" (owner, 2026-11-20: show what Throwback did, then "Say thanks").
   */
  var TOTALS_KEY = '__tb_totals';
  var totals = null;

  function totalsLog() {
    if (!totals) {
      try {
        totals = JSON.parse(localStorage.getItem(TOTALS_KEY) || 'null');
      } catch (e) {
        totals = null;
      }
      if (!totals || typeof totals.since !== 'number') totals = { since: trustedNow(), ads: 0, sugg: 0 };
    }
    return totals;
  }

  function saveTotals() {
    if (!totals) return;
    totals.dirty = false;
    try {
      localStorage.setItem(TOTALS_KEY, JSON.stringify({ since: totals.since, ads: totals.ads || 0, sugg: totals.sugg || 0 }));
    } catch (e) {
      // Storage unavailable: counts last for this page only.
    }
  }

  function dayStats() {
    var d = dayLog();
    var ex = state.c && state.c.explore;
    var rv = state.c && state.c.reelViewer;
    var log = reelLog();
    var cool = rv && log.poster ? Math.max(0, log.poster + rv.cooldownMs - trustedNow()) : 0;
    var ds = discState();
    return JSON.stringify({
      areas: d.sec,
      adsRemoved: d.ads || 0,
      suggestionsRemoved: d.sugg || 0,
      exploreUsedSec: exploreLog().used,
      exploreAllowanceSec: ex && ex.dailySeconds ? ex.dailySeconds : 0,
      discoverShown: ds.count || 0,
      discoverMax: DISCOVER.perDay,
      reelCooldownMs: cool
    });
  }

