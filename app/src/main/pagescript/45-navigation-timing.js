  // ---------------------------------------------------------------- navigation timing

  /*
   * How long screens take to open (stories, messages): tap → address change → page settled (no DOM
   * changes for PERF_SETTLE_MS). Page kinds and times only, kept in memory (last 30), read through
   * DevTools with __throwback.perf(). Nothing is sent anywhere.
   */
  var PERF_SETTLE_MS = 300;
  var PERF_LOG_MAX = 30;
  var perf = { lastTap: 0, rec: null, lastChange: 0, timer: 0, log: [] };

  /** Adds a record to the in-memory log read by __throwback.perf() (the last PERF_LOG_MAX). */
  function perfNote(rec) {
    perf.log.push(rec);
    if (perf.log.length > PERF_LOG_MAX) perf.log.shift();
  }

  function pageKind(path) {
    var c = state.c;
    for (var i = 0; c && i < c.pages.length; i++) if (c.pages[i].re.test(path)) return c.pages[i].name;
    return '/' + (path.split('/')[1] || '');
  }

  function perfStart(path) {
    var now = performance.now();
    var tap = now - perf.lastTap < 2000 ? perf.lastTap : now;
    perf.rec = { page: pageKind(path), tapToUrl: Math.round(now - tap), tap: tap };
    if (!state.readySent) perf.rec.tap = 0; // the page's own first load: count everything since it began
    perf.lastChange = now;
    perfCheck();
  }

  function perfChanged() {
    if (perf.rec) perf.lastChange = performance.now();
  }

  function perfCheck() {
    clearTimeout(perf.timer);
    perf.timer = setTimeout(function () {
      var rec = perf.rec;
      if (!rec) return;
      var idle = performance.now() - perf.lastChange;
      if (idle < PERF_SETTLE_MS) return perfCheck();
      rec.tapToSettled = Math.round(perf.lastChange - rec.tap);
      if (WARM_PAGES.indexOf(rec.page) !== -1) learnCodeAssets(rec.tap - 50, perf.lastChange, rec);
      delete rec.tap;
      perfNote(rec);
      perf.rec = null;
    }, PERF_SETTLE_MS);
  }

