  // ---------------------------------------------------------------- readiness and reports

  function checkReady() {
    if (state.readySent || state.leaving || !document.body) return;
    // Message pages (opened from a message alert, or messages-only mode) have nothing to filter
    // before showing, and matched none of the ready landmarks early: the cover stayed up for the
    // 6-second fallback. There, anything drawn is enough.
    if (pageActive('direct') && document.body.querySelector('svg, img, [role], [aria-label]')) {
      sendReady();
      return;
    }
    try {
      if (!document.querySelector(state.c.readySelector)) return;
    } catch (e) {
      return;
    }
    // The home feed is ready when its first post is there, not when only the top bar and Instagram's
    // spinner are (on a slow connection that looked like an Instagram logo screen after the pause).
    // The login form counts too; after HOME_READY_MAX_MS the page shows anyway.
    if (pageActive('home') && !document.querySelector('main article, input[type="password"], #' + END_CARD_ID)) {
      if (performance.now() < HOME_READY_MAX_MS) {
        if (!state.homeReadyTimer) {
          state.homeReadyTimer = setTimeout(checkReady, Math.max(0, HOME_READY_MAX_MS - performance.now()) + 50);
        }
        return;
      }
    }
    sendReady();
  }

  var HOME_READY_MAX_MS = 8000;

  function sendReady() {
    if (state.readySent || state.leaving) return;
    state.readySent = true;
    perf.log.push({ page: pageKind(location.pathname), fullLoadToReady: Math.round(performance.now()) });
    scheduleWarmups();
    // Two frames: the filtered DOM is styled and laid out before the app reveals the WebView.
    raf(function () {
      raf(function () {
        send({ t: 'ready' });
        reportBackground();
      });
    });
  }

  function opaque(color) {
    return color && color !== 'transparent' && !/rgba\([^)]*,\s*0\s*\)/.test(color);
  }

  /** The page colour, so the app can paint the status and navigation bar areas to match. */
  function reportBackground() {
    if (!document.body) return;
    var color = getComputedStyle(document.body).backgroundColor;
    if (!opaque(color)) color = getComputedStyle(document.documentElement).backgroundColor;
    if (!opaque(color)) {
      var dark = window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
      color = dark ? 'rgb(0, 0, 0)' : 'rgb(255, 255, 255)';
    }
    if (color !== state.lastBg) {
      state.lastBg = color;
      send({ t: 'bg', c: color });
    }
  }

  /** For pull-to-refresh: is this touch on a page (and inner scroller) that is at its top? */
  function onTouchStart(e) {
    if (!state.c || !anyMatch(state.c.pull, location.pathname)) return;
    if (e.touches && e.touches.length > 1) return;
    send({ t: 'touch', top: atTop(e.target) });
  }

  function atTop(target) {
    var se = document.scrollingElement || document.documentElement;
    if (se && se.scrollTop > 0) return false;
    var n = target && target.nodeType === 1 ? target : target && target.parentElement;
    for (; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      if (n.getAttribute('role') === 'dialog' || n.getAttribute('aria-modal') === 'true') return false;
      if (n.scrollTop > 0) return false;
    }
    return true;
  }

