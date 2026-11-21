  // ---------------------------------------------------------------- routing (mirrors UrlRouter.kt)

  function expand(template, m) {
    var filled = template.replace(/\$(\d)/g, function (_, n) {
      return m[+n] || '';
    });
    return '/' + filled.replace(/^\/+/, ''); // never "//host"
  }

  /**
   * Where an Instagram URL should go instead, or null if it may be shown.
   * {to: absolute URL, kind: why it was blocked (null for plain normalisation), rewrite: show 'to' instead}
   */
  function route(u) {
    var c = state.c;
    var path = u.pathname;
    if (anyMatch(c.alwaysAllowed, path)) return null;
    if (state.mode === MODE_MESSAGES) {
      return anyMatch(c.messagesOnly, path) ? null : { to: c.messagesUrl, kind: KIND_MESSAGES, rewrite: false };
    }
    for (var i = 0; i < c.blocked.length; i++) {
      var b = c.blocked[i];
      var m = b.re.exec(path);
      if (!m) continue;
      if (b.redirect) return { to: (c.home ? c.home.origin : u.origin) + expand(b.redirect, m), kind: b.kind, rewrite: true };
      return { to: c.homeUrl, kind: b.kind, rewrite: false };
    }
    if (c.home && path === c.home.pathname && c.homeVariants.indexOf(u.searchParams.get('variant')) < 0) {
      return { to: c.homeUrl, kind: null, rewrite: true };
    }
    return null;
  }

  function isInstagram(u) {
    return (u.protocol === 'https:' || u.protocol === 'http:') && hostMatches(state.c.instagramHosts, u.hostname);
  }

  /** Allows a redirect unless the same target was redirected to 3 times in 15 s (a loop). */
  function loopGuard(to) {
    var now = Date.now();
    var log = [];
    try {
      log = JSON.parse(sessionStorage.getItem(LOOP_KEY) || '[]');
    } catch (e) {
      log = [];
    }
    log = log.filter(function (e) {
      return e && now - e.t < 15000;
    });
    var repeats = log.filter(function (e) {
      return e.u === to;
    }).length;
    if (repeats >= 3) return false;
    log.push({ u: to, t: now });
    try {
      sessionStorage.setItem(LOOP_KEY, JSON.stringify(log.slice(-20)));
    } catch (e) {
      // Storage unavailable: the app's own loop guard still applies to full page loads.
    }
    return true;
  }

  /** Leaves a page that must not be shown. The page is blanked first so it can't flash. */
  function redirect(r) {
    state.leaving = true;
    applyClasses();
    if (r.kind) send({ t: 'blocked', kind: r.kind });
    if (loopGuard(r.to)) {
      location.replace(r.to);
      return;
    }
    // A loop: nothing leaves this document, so its in-page navigation (Back) must still be handled.
    // Blocked content stays blank until the address changes; a plain normalisation that Instagram
    // keeps undoing is given up and the page shows as it is.
    state.leaving = false;
    state.stuck = !!r.kind;
    applyClasses();
  }

  /** In-page navigation through Instagram's own link, when one exists (no reload). */
  function softNavigate(to) {
    var u = parseUrl(to);
    if (!u) return false;
    var link = document.querySelector('a[href="' + u.pathname + '"]');
    if (!link) return false;
    clickElement(link);
    return true;
  }

  /** @param source 'push', 'replace', 'pop' or 'start' */
  function onLocation(source) {
    var href = location.href;
    if (href === state.href) return;
    var previousPath = state.path;
    state.href = href;
    state.path = location.pathname;
    if (state.leaving) return;
    state.stuck = false; // a new address: routed afresh below
    if (clipPlayer && !state.clipNav && state.path !== previousPath) closeClipPlayer(false);
    if (!state.clipNav && (source === 'push' || source === 'pop')) {
      state.clipLeave = null; // the user went on: don't step back for a player closed earlier
      if (source === 'push') state.clipResume = null; // Back to Explore later doesn't reopen it
    }
    var u = parseUrl(href);
    var r = u && route(u);
    // Instagram tidying the address of the page already on screen (e.g. dropping ?variant=following)
    // is not a navigation: redirecting would reload the feed in a loop.
    if (r && r.kind === null && source === 'replace' && previousPath === state.path) r = null;
    if (r) {
      redirect(r);
      return;
    }
    applyClasses();
    updateExploreCard(); // remove "Enough for today" at once when leaving Explore
    send({ t: 'loc', url: href });
    perfStart(state.path);
    if (!pageActive('stories')) resetAhead(stories);
    else noteStoryLocation();
    if (!pageActive('home')) resetAhead(feedAhead);
    state.bgDirty = true;
    if (state.textScanSkipped && textScanActive()) {
      // Back from DMs or stories: catch up on what changed while text scanning was off, right after
      // Instagram's own navigation code (this runs inside it).
      state.textScanSkipped = false;
      setTimeout(function () {
        if (state.c) scanAll();
      }, 0);
    } else if (!textScanActive()) {
      state.textScanSkipped = true;
    }
    schedulePass(true);
  }

  function goHome() {
    var u = parseUrl(location.href);
    if (u && !route(u) && state.c.home && u.pathname === state.c.home.pathname) {
      window.scrollTo({ top: 0, behavior: 'smooth' }); // already on the feed: back to the top
      return;
    }
    location.assign(state.c.homeUrl);
  }

  /** Link taps, before Instagram's router sees them. */
  function onClick(e) {
    if (!state.c || e.defaultPrevented || e.button !== 0) return;
    if (createInAppTap(e)) return;
    if (openClipFromTap(e)) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var u = parseUrl(a.getAttribute('href'));
    if (!u || !isInstagram(u)) return; // other sites: the app opens them outside
    var r = route(u);
    if (!r) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (r.kind === null) goHome(); // the Home tab: the (filtered) home feed
    else if (r.rewrite) {
      if (loopGuard(r.to)) location.assign(r.to); // a single reel opens as a post
    } else send({ t: 'blocked', kind: r.kind });
  }

  function hookHistory() {
    [['pushState', 'push'], ['replaceState', 'replace']].forEach(function (pair) {
      var original = history[pair[0]];
      if (typeof original !== 'function') return;
      history[pair[0]] = function () {
        var result = original.apply(this, arguments);
        try {
          onLocation(pair[1]);
        } catch (e) {
          // Never break Instagram's navigation.
        }
        return result;
      };
    });
    var onPop = function () {
      onLocation('pop');
    };
    window.addEventListener('popstate', onPop, true);
    window.addEventListener('hashchange', onPop, true);
    // Also catches history calls made through History.prototype. It fires inside pushState /
    // replaceState, before the wrappers above, so it reports the navigation type itself.
    if (window.navigation && typeof window.navigation.addEventListener === 'function') {
      window.navigation.addEventListener('currententrychange', function (e) {
        var type = e && e.navigationType;
        onLocation(type === 'replace' ? 'replace' : type === 'push' ? 'push' : 'pop');
      });
    }
  }

