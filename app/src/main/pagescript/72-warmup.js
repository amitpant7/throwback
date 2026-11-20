  // ---------------------------------------------------------------- messages and stories warm-up

  /*
   * Messages and the story viewer are separate parts of Instagram's code, loaded the first time they
   * open (messages measured at 16 of 22 code files downloaded). The first time they load, Throwback
   * notes which static code files (Instagram's public CDN, the same for every user) were used. On later starts, once the feed is ready and
   * the phone is idle, it prefetches just those files into the browser cache. No logged-in requests,
   * no inbox data, no change to "active" status; skipped with Data Saver on.
   */
  var WARM_KEY = '__tb_dm_assets';
  var WARM_MAX = 120;
  // Sections whose code is loaded on first use: messages and the story viewer.
  var WARM_PAGES = ['inbox', 'direct', 'stories'];
  var WARM_MAX_AGE_MS = 7 * 24 * 3600 * 1000;
  var warmDone = false;

  function staticAsset(url) {
    var u = parseUrl(url);
    if (!u || u.protocol !== 'https:') return false;
    if (!/(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname)) return false;
    return /\.(js|css)$/.test(u.pathname) || u.pathname.indexOf('/rsrc.php/') !== -1;
  }

  /**
   * After opening messages (a full load, or Instagram's in-page switch): remember the code files loaded
   * since `since` (performance time) and record how many came from the network into `rec`.
   */
  function learnDmAssets(since, until, rec) {
    if (!performance.getEntriesByType) return;
    var res = performance.getEntriesByType('resource').filter(function (r) {
      return r.startTime >= since && r.startTime <= until &&
        (r.initiatorType === 'script' || r.initiatorType === 'link' || r.initiatorType === 'css') && staticAsset(r.name);
    });
    if (!res.length) {
      rec.codeFiles = 0;
      return;
    }
    var fromNetwork = 0, bytes = 0;
    res.forEach(function (r) {
      if (r.transferSize > 0) {
        fromNetwork++;
        bytes += r.transferSize;
      }
    });
    rec.codeFiles = res.length;
    rec.codeFromNetwork = fromNetwork;
    rec.codeNetworkKB = Math.round(bytes / 1024);
    var saved = null;
    try {
      saved = JSON.parse(localStorage.getItem(WARM_KEY) || 'null');
    } catch (e) {
      saved = null;
    }
    var urls = (saved && saved.urls) || [];
    res.forEach(function (r) {
      if (urls.indexOf(r.name) === -1) urls.push(r.name);
    });
    try {
      localStorage.setItem(WARM_KEY, JSON.stringify({ t: Date.now(), urls: urls.slice(-WARM_MAX) }));
    } catch (e) {
      // No storage: nothing to warm next time.
    }
  }

  /** On the feed, when idle: prefetch the remembered messages code files (once per page). */
  function warmDmAssets() {
    if (warmDone || !pageActive('home') || state.mode === MODE_MESSAGES) return;
    warmDone = true;
    var conn = navigator.connection;
    if (conn && conn.saveData) return;
    var saved = null;
    try {
      saved = JSON.parse(localStorage.getItem(WARM_KEY) || 'null');
    } catch (e) {
      saved = null;
    }
    if (!saved || !saved.urls || Date.now() - saved.t > WARM_MAX_AGE_MS) return;
    var have = {};
    performance.getEntriesByType('resource').forEach(function (r) {
      have[r.name] = 1;
    });
    var n = 0;
    saved.urls.forEach(function (url) {
      if (have[url] || !staticAsset(url)) return;
      var link = document.createElement('link');
      link.rel = 'prefetch';
      link.href = url;
      (document.head || document.documentElement).appendChild(link);
      n++;
    });
    perfNote({ page: 'messages-warmup', prefetched: n });
  }

  function scheduleWarmups() {
    var idle = window.requestIdleCallback || function (fn) {
      return setTimeout(fn, 1);
    };
    // Late enough not to compete with the feed's own loading.
    setTimeout(function () {
      idle(function () {
        if (!pageActive('direct')) warmDmAssets();
      }, { timeout: 5000 });
    }, 5000);
  }

