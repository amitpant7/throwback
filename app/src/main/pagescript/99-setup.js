  // ---------------------------------------------------------------- setup

  /** App features passed in the config (see PageFeatures.kt). */
  function applyFeatures(cfg) {
    if (cfg && typeof cfg.createInApp === 'boolean') state.createInApp = cfg.createInApp;
    if (cfg && typeof cfg.storyInApp === 'boolean') state.storyInApp = cfg.storyInApp;
    if (cfg && 'supportAsk' in cfg) state.supportAsk = cfg.supportAsk === 'first' || cfg.supportAsk === 'again' ? cfg.supportAsk : null;
  }

  function configure(cfg) {
    if (!cfg) return false;
    applyClock(cfg.clock); // first: anything below may already ask which day it is
    applyFeatures(cfg);
    // Only a clock or features (sent when the page is ready and whenever the app comes to the front):
    // nothing to rebuild or rescan. The day may have changed, so the page classes are refreshed.
    if (!cfg.rules && typeof cfg.css !== 'string' && !cfg.mode) {
      if (!state.c) return false;
      applyClasses();
      schedulePass(false);
      return true;
    }
    var modeChanged = false;
    if (cfg.rules) state.c = compile(cfg.rules);
    if (!state.c) return false;
    installDataFilter();
    installRequestTweak();
    if (typeof cfg.css === 'string') state.baseCss = cfg.css;
    if (cfg.mode && cfg.mode !== state.mode) {
      state.mode = cfg.mode === MODE_MESSAGES ? MODE_MESSAGES : MODE_FULL;
      modeChanged = true;
    }
    state.css = buildCss(state.c, state.baseCss);
    ensureStyle();

    // Re-check the current page under the new mode or rules.
    state.href = location.href;
    state.path = location.pathname;
    var u = parseUrl(location.href);
    var r = u && !state.leaving ? route(u) : null;
    if (r && modeChanged && r.kind === KIND_MESSAGES && softNavigate(r.to)) {
      // Instagram's own link took us to the inbox without a reload.
    } else if (r) {
      redirect(r);
      return true;
    }
    applyClasses();
    state.bgDirty = true;
    if (document.documentElement) scanAll();
    return true;
  }

  /**
   * The page's one clock, once a second: counts time where Instagram is seen (visible, not covered by
   * a Throwback screen) for Your day, the feed's discovery windows and Explore, and notices a location
   * change the history hooks missed (history calls through History.prototype on old WebViews).
   */
  function startTick() {
    setInterval(function () {
      if (!state.c) return;
      if (location.href !== state.href) locationChanged('replace');
      var seen = document.visibilityState === 'visible' && !state.covered;
      tickDay(seen);
      tickFeed(seen);
      tickExplore(seen);
    }, 1000);
    // Counted time is saved every few seconds and whenever the page is hidden or left.
    var flush = function () {
      saveDay();
      saveDisc();
      exploreRecord.save();
    };
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') flush();
    });
  }

  function start() {
    hookHistory();
    installHomeLongPress();
    installChatGestures();
    watchResources();
    startTick();
    // Scrolling into Instagram's empty placeholder space changes nothing in the page, so check the
    // end of the feed on scroll too (at most once a frame).
    var endCheckQueued = false;
    window.addEventListener('scroll', function () {
      if (endCheckQueued || !state.c) return;
      endCheckQueued = true;
      requestAnimationFrame(function () {
        endCheckQueued = false;
        updateEndCard();
      });
    }, { passive: true });
    window.addEventListener('click', onClick, true);
    window.addEventListener('popstate', onClipPop, true);
    document.addEventListener('play', onClipUnderPlay, true);
    document.addEventListener('visibilitychange', onClipVisibility);
    document.addEventListener('play', onMediaPlay, true); // media events don't bubble; capture sees them
    document.addEventListener('play', onCoveredPlay, true);
    window.addEventListener('pointerup', function () {
      perf.lastTap = performance.now();
    }, { capture: true, passive: true });
    document.addEventListener('touchstart', onTouchStart, { capture: true, passive: true });
    if (window.matchMedia) {
      var mq = matchMedia('(prefers-color-scheme: dark)');
      var onScheme = function () {
        state.bgDirty = true;
        schedulePass(false);
      };
      if (mq.addEventListener) mq.addEventListener('change', onScheme);
      else if (mq.addListener) mq.addListener(onScheme);
    }

    new MutationObserver(onMutations).observe(document, { childList: true, subtree: true, characterData: true });
    watchRootClass();

    var onDomReady = function () {
      scanAll();
      setTimeout(sendReady, READY_FALLBACK_MS);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onDomReady);
    else onDomReady();
    window.addEventListener('load', function () {
      schedulePass(true);
    });

    // First route check, before Instagram's own scripts run.
    onLocation('start');
  }

  var api = {
    configure: configure,
    perf: function () {
      return perf.log.slice();
    },
    /** Errors caught in passes and navigation (the last 20). */
    errors: function () {
      return errorLog.slice();
    },
    /** The app tells the page when one of its own screens (Settings, lock, pause) covers it. */
    setCovered: function (covered) {
      state.covered = !!covered;
      if (covered) pauseAllMedia();
      else onClipVisibility(); // the Explore reel player picks up where it was
      // Backup: when the poster goes away, a stopped viewer that is somehow still open gets closed.
      if (!covered) closeReelViewer();
    },
    /** Today's counts for the app's "Your day" screen (JSON string). */
    dayStats: dayStats,
    /** The trusted clock the app passed (for tests over DevTools). */
    clockInfo: function () {
      return { fresh: clock.fresh, zoneMin: clock.zoneMin, day: clock.day, nextAt: clock.nextAt, now: trustedNow(), today: today() };
    },
    storyStats: function () {
      var s = stories.stats;
      return { learned: s.learned, videosAhead: s.prefetched, videoPiecesFromMemory: s.hits, failed: s.failed,
        photosAhead: s.images, current: stories.current, videoHeight: stories.height, photoWidth: stories.imgWidth,
        inMemoryKB: memoryKB(stories) };
    },
    feedStats: function () {
      var s = feedAhead.stats;
      return { requestsRaised: feedTweak.raised, refused: feedTweak.refused, raiseOff: feedTweak.off,
        postsLearned: s.learned, current: feedAhead.current, postsLoadedAhead: feedAhead.items.length - 1 - feedAhead.current,
        photosAhead: s.images, videosAhead: s.prefetched,
        videoPiecesFromMemory: s.hits, failed: s.failed, inMemoryKB: memoryKB(feedAhead) };
    }
  };
  try {
    Object.defineProperty(window, '__throwback', { value: api, enumerable: false, configurable: false, writable: false });
  } catch (e) {
    window.__throwback = api;
  }

  state.mode = config && config.mode === MODE_MESSAGES ? MODE_MESSAGES : MODE_FULL;
  applyClock(config && config.clock); // before anything asks which day it is
  applyFeatures(config);
  state.baseCss = (config && config.css) || '';
  state.path = location.pathname;
  if (config && config.rules) {
    state.c = compile(config.rules);
    state.css = buildCss(state.c, state.baseCss);
    ensureStyle();
    applyClasses();
    installDataFilter(); // before Instagram's own scripts parse any feed data
    installServerTime();
    installRequestTweak(); // before Instagram's scripts send any feed request
    start();
  }
})(__THROWBACK_CONFIG__);
