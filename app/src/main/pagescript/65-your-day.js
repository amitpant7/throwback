  // ---------------------------------------------------------------- your day (stats)

  /*
   * "Your day" in Throwback's settings (owner, 2026-11-20): time on each part of Instagram today and
   * what Throwback took out of the feed. Counted on the device only, per day; the app reads it with
   * __throwback.dayStats() when the screen opens. Time counts while the page is visible and not
   * covered by a Throwback screen.
   */
  var dayRecord = dailyRecord('__tb_day', function () {
    return { sec: {}, ads: 0, sugg: 0 };
  }, function (d) {
    return typeof d.sec === 'object' && !!d.sec;
  });
  var dayUnsaved = 0; // seconds counted since the last save

  function dayLog() {
    return dayRecord.get();
  }

  function saveDay() {
    if (totalsUnsaved) saveTotals();
    dayRecord.save();
    dayUnsaved = 0;
  }

  function dayArea() {
    if (clipPlayer) return 'explore';
    if (state.mode === MODE_MESSAGES || pageActive('direct')) return 'messages';
    if (pageActive('stories')) return 'stories';
    if (pageActive('explore')) return 'explore';
    if (pageActive('home')) return 'feed';
    return 'other'; // profiles, single posts, settings
  }

  /** Each second (startTick): one more second on the part of Instagram that is seen. */
  function tickDay(seen) {
    if (!seen) return;
    var d = dayLog();
    var a = dayArea();
    d.sec[a] = (d.sec[a] || 0) + 1;
    if (++dayUnsaved >= 10) saveDay();
  }

  function countRemoved(field) {
    var d = dayLog();
    d[field] = (d[field] || 0) + 1;
    var t = totalsLog();
    t[field] = (t[field] || 0) + 1;
    totalsUnsaved = true;
  }

  /*
   * Removed ads and suggested posts since the last support ask (or first use), for the line in
   * "You're all caught up" (owner, 2026-11-20: show what Throwback did, then "Say thanks").
   */
  var TOTALS_KEY = '__tb_totals';
  var totals = null;
  var totalsUnsaved = false;

  function totalsLog() {
    if (!totals) {
      totals = loadJson(TOTALS_KEY);
      if (!totals || typeof totals.since !== 'number') totals = { since: trustedNow(), ads: 0, sugg: 0 };
    }
    return totals;
  }

  function saveTotals() {
    if (!totals) return;
    totalsUnsaved = false;
    saveJson(TOTALS_KEY, { since: totals.since, ads: totals.ads || 0, sugg: totals.sugg || 0 });
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
      reelCooldownMs: Math.round(cool)
    });
  }

