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
  var warmTimer = 0;
  // Sections learned in this document. Only a section's first open loads all its code; a later
  // open in the same document loads little or nothing and must not replace the full list.
  var warmLearned = {};
  // Recent code-file loads, from the page's resource observer (watchResources). The browser's own
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
   * After a section opened (rec.page: a full load, or Instagram's in-page switch): record in `rec`
   * how many code files loaded between `since` and `until` (performance time) and how many came from
   * the network. On the section's first open in this document, remember those files for it,
   * replacing what was remembered before (Instagram's files change with each of its releases).
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
    if (warmLearned[rec.page]) return;
    warmLearned[rec.page] = true;
    var saved = loadJson(WARM_KEY);
    if (!saved || !saved.sections || typeof saved.sections !== 'object') saved = { sections: {} };
    saved.sections[rec.page] = { t: Date.now(), urls: res.map(function (r) {
      return r.name;
    }).slice(-WARM_MAX) };
    saveJson(WARM_KEY, saved);
  }

  /**
   * On the feed, when idle: prefetch the remembered code files of recent sections (once per page).
   * Not on the feed then: it is tried again when the user gets there (onLocation).
   */
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
    if (warmDone || warmTimer) return;
    var idle = window.requestIdleCallback || function (fn) {
      return setTimeout(fn, 1);
    };
    // Late enough not to compete with the feed's own loading.
    warmTimer = setTimeout(function () {
      idle(function () {
        warmTimer = 0;
        warmCodeAssets();
      }, { timeout: 5000 });
    }, 5000);
  }
