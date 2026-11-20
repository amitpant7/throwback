  // ---------------------------------------------------------------- setup

  /** App features passed in the config (see PageFeatures.kt). */
  function applyFeatures(cfg) {
    if (cfg && typeof cfg.createInApp === 'boolean') state.createInApp = cfg.createInApp;
    if (cfg && typeof cfg.storyInApp === 'boolean') state.storyInApp = cfg.storyInApp;
    if (cfg && 'supportAsk' in cfg) state.supportAsk = cfg.supportAsk === 'first' || cfg.supportAsk === 'again' ? cfg.supportAsk : null;
  }

  /** The app's trusted clock (see today()). */
  function applyClock(c) {
    if (!c || typeof c.offset !== 'number' || typeof c.zoneMin !== 'number') return;
    clock.offset = c.offset;
    clock.zoneMin = c.zoneMin;
    if (typeof c.day === 'number' && c.day > 0 && typeof c.nextAt === 'number') {
      clock.day = c.day;
      clock.nextAt = c.nextAt;
    }
  }

  function configure(cfg) {
    if (!cfg) return false;
    applyClock(cfg.clock); // first: anything below may already ask which day it is
    applyFeatures(cfg);
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

  function start() {
    hookHistory();
    installHomeLongPress();
    startFeedClock();
    watchStoryImages();
    startExploreClock();
    startDayClock();
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
    version: 1,
    perf: function () {
      return perf.log.slice();
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
      return { offset: clock.offset, zoneMin: clock.zoneMin, day: clock.day, nextAt: clock.nextAt, now: trustedNow() };
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
        fewestAheadSeen: feedTweak.minAhead, photosAhead: s.images, videosAhead: s.prefetched,
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
    installRequestTweak(); // before Instagram's scripts send any feed request
    start();
  }
})(__THROWBACK_CONFIG__);
