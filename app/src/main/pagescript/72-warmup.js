  // ---------------------------------------------------------------- messages and stories warm-up

  /*
   * Messages and the story viewer are separate parts of Instagram's code, loaded the first time they
   * open (messages measured at 16 of 22 code files downloaded). When they load, Throwback notes which
   * static code files (Instagram's public CDN, the same for every user) were used. On later starts,
   * once the feed is ready and the phone is idle, it prefetches just those files into the browser
   * cache. No logged-in requests, no inbox data, no change to "active" status; skipped with Data Saver.
   */
  var WARM_KEY = '__tb_code_assets'; // {sections: {<page kind>: {t, urls}}}
  var WARM_MAX = 120;
  // Sections whose code is loaded on first use: messages and the story viewer.
  var WARM_PAGES = ['inbox', 'direct', 'stories'];
  var WARM_MAX_AGE_MS = 7 * 24 * 3600 * 1000;
  var CODE_ENTRIES_MAX = 200;
  var warmDone = false;
  // Recent code-file loads, from the page's resource observer (watchStoryImages). The browser's own
  // resource-timing buffer is not used: feed images fill it within a minute and it then records nothing.
  var codeEntries = [];

  function staticAsset(url) {
    var u = parseUrl(url);
    if (!u || u.protocol !== 'https:' || !isCdnHost(u.hostname)) return false;
    return /\.(js|css)$/.test(u.pathname) || u.pathname.indexOf('/rsrc.php/') !== -1;
  }

  /** A resource the page loaded: kept if it is one of Instagram's static code files. */
  function noteCodeEntry(e) {
    if ((e.initiatorType !== 'script' && e.initiatorType !== 'link' && e.initiatorType !== 'css') || !staticAsset(e.name)) return;
    codeEntries.push({ name: e.name, startTime: e.startTime, transferSize: e.transferSize });
    if (codeEntries.length > CODE_ENTRIES_MAX) codeEntries.shift();
  }

  /**
   * After a section opened (rec.page: a full load, or Instagram's in-page switch): remember the code
   * files loaded between `since` and `until` (performance time) for that section, replacing what was
   * remembered before (Instagram's files change with each of its releases), and record in `rec` how
   * many came from the network.
   */
  function learnCodeAssets(since, until, rec) {
    var res = codeEntries.filter(function (r) {
      return r.startTime >= since && r.startTime <= until;
    });
    rec.codeFiles = res.length;
    if (!res.length) return;
    var fromNetwork = 0, bytes = 0;
    res.forEach(function (r) {
      if (r.transferSize > 0) {
        fromNetwork++;
        bytes += r.transferSize;
      }
    });
    rec.codeFromNetwork = fromNetwork;
    rec.codeNetworkKB = Math.round(bytes / 1024);
    var saved = loadJson(WARM_KEY);
    if (!saved || !saved.sections || typeof saved.sections !== 'object') saved = { sections: {} };
    saved.sections[rec.page] = { t: Date.now(), urls: res.map(function (r) {
      return r.name;
    }).slice(-WARM_MAX) };
    saveJson(WARM_KEY, saved);
  }

  /** On the feed, when idle: prefetch the remembered code files of recent sections (once per page). */
  function warmCodeAssets() {
    if (warmDone || !pageActive('home') || state.mode === MODE_MESSAGES) return;
    warmDone = true;
    var conn = navigator.connection;
    if (conn && conn.saveData) return;
    var saved = loadJson(WARM_KEY);
    var sections = saved && saved.sections;
    if (!sections || typeof sections !== 'object') return;
    var urls = [];
    Object.keys(sections).forEach(function (k) {
      var sec = sections[k];
      if (!sec || !Array.isArray(sec.urls) || !(Date.now() - sec.t < WARM_MAX_AGE_MS)) return;
      sec.urls.forEach(function (url) {
        if (urls.indexOf(url) === -1) urls.push(url);
      });
    });
    var have = {};
    if (performance.getEntriesByType) {
      performance.getEntriesByType('resource').forEach(function (r) {
        have[r.name] = 1;
      });
    }
    var n = 0;
    urls.forEach(function (url) {
      if (have[url] || !staticAsset(url)) return;
      var link = document.createElement('link');
      link.rel = 'prefetch';
      link.href = url;
      link.onload = link.onerror = function () {
        if (link.parentNode) link.parentNode.removeChild(link); // it is in the browser cache now
      };
      (document.head || document.documentElement).appendChild(link);
      n++;
    });
    perfNote({ page: 'code-warmup', prefetched: n });
  }

  function scheduleWarmups() {
    var idle = window.requestIdleCallback || function (fn) {
      return setTimeout(fn, 1);
    };
    // Late enough not to compete with the feed's own loading.
    setTimeout(function () {
      idle(warmCodeAssets, { timeout: 5000 });
    }, 5000);
  }
