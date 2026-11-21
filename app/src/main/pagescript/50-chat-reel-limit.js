  // ---------------------------------------------------------------- reel viewer limit

  /*
   * A reel opened from a chat plays in Instagram's full-screen swipe viewer: the address stays the
   * same and a vertical scroll-snap strip of videos opens (found on the owner's phone). Opening a
   * reel someone sent is fine; swiping on through more is the endless feed again. The opened reel is
   * free; swipes to other reels are counted (rules: reelViewer), across viewers, so closing and
   * reopening doesn't reset it. The poster shows on a random swipe (1st to 4th, so no number of swipes is safe): the videos stop and
   * the app shows its poster.
   * After the poster, no free swipes for the cooldown (an hour): opening other reels to get "3 more"
   * each time doesn't work. Swipes and the last poster time are stored, so a restart doesn't reset.
   */
  var ATTR_REEL_STOP = 'data-tb-reelstop';
  var REELS_KEY = '__tb_reels';
  var reels = { current: null, viewer: null };

  function reelLog() {
    var log = loadJson(REELS_KEY);
    if (!log || !Array.isArray(log.swipes)) log = { swipes: [], poster: 0, limit: 0 };
    return log;
  }

  function saveReelLog(log) {
    saveJson(REELS_KEY, log);
  }

  /**
   * The swipe viewer around a video (rules: reelViewer): the nearest ancestor that matches the rules'
   * selector, or else scroll-snaps along their axis (a vertical strip), holding enough videos.
   */
  function reelViewerOf(video, rv) {
    var n = video.parentElement;
    for (var depth = 0; n && depth < rv.maxDepth && n !== document.body; depth++, n = n.parentElement) {
      var isViewer = rv.selector ? n.matches(rv.selector) : (getComputedStyle(n).scrollSnapType || '').indexOf(rv.snapAxis) === 0;
      if (isViewer && n.querySelectorAll('video').length >= rv.minVideos) return n;
    }
    return null;
  }

  function onMediaPlay(e) {
    var v = e.target;
    var rv = state.c && state.c.reelViewer;
    // A viewer the user closed themselves: let go of it (and its videos).
    if (reels.viewer && !reels.viewer.isConnected) reels.viewer = reels.current = null;
    // Chats only (DECISIONS 13f): every other video play (the feed autoplays) skips the style lookups.
    if (!rv || !v || v.tagName !== 'VIDEO' || !pageActive('direct') || clipPlayer) return;
    var viewer = reelViewerOf(v, rv);
    if (!viewer) return;
    if (viewer.hasAttribute(ATTR_REEL_STOP)) {
      v.pause();
      return;
    }
    if (reels.current === v) return; // the same reel looping
    var opened = reels.viewer !== viewer;
    reels.current = v;
    reels.viewer = viewer;
    if (opened) return; // the reel that was opened is free
    var now = trustedNow();
    var log = reelLog();
    var cooling = log.poster && now - log.poster < rv.cooldownMs;
    log.swipes = log.swipes.filter(function (t) {
      return now - t < rv.windowMs;
    });
    // A new count: pick on which swipe the poster shows (random, so it can't be predicted).
    if (!log.swipes.length || !log.limit) {
      log.limit = rv.minSwipe + Math.floor(Math.random() * (rv.maxSwipe - rv.minSwipe + 1));
    }
    log.swipes.push(now);
    if (cooling || log.swipes.length >= log.limit) {
      log.poster = now;
      log.swipes = [];
      log.limit = 0;
      saveReelLog(log);
      stopReels(viewer);
      return;
    }
    saveReelLog(log);
  }

  /**
   * Stops the viewer (no more swiping, videos paused), closes it with its own Close button so the
   * poster appears over the chat, and asks the app for the poster. Closing is done here, by the page,
   * at once: an app-to-page request after the poster's button proved unreliable on the phone, while
   * clicking the viewer's Close closes it every time (Instagram adds no history entry for it).
   */
  function stopReels(viewer) {
    viewer.setAttribute(ATTR_REEL_STOP, '1');
    var vids = viewer.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) vids[i].pause();
    send({ t: 'reelLimit' });
    setTimeout(function () {
      if (!closeReelViewer()) setTimeout(closeReelViewer, 400); // one retry if the button wasn't there yet
    }, 50);
  }

  /**
   * Clicks the viewer's own Close: the visible, top-most element labelled as the rules' closeLabels
   * (the chat underneath has hidden ones too). True when a stopped viewer was there and the button was found.
   */
  function closeReelViewer() {
    var viewer = document.querySelector('[' + ATTR_REEL_STOP + ']');
    var rv = state.c && state.c.reelViewer;
    if (!viewer || !rv) return false;
    var best = null;
    var bestScore = Infinity;
    var labelled = document.querySelectorAll('[aria-label]');
    for (var i = 0; i < labelled.length; i++) {
      if (!rv.closeLabels.has(norm(labelled[i].getAttribute('aria-label')))) continue;
      var b = labelled[i].closest('[role="button"], button, a') || labelled[i];
      var r = b.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || r.bottom <= 0 || r.top >= window.innerHeight) continue;
      var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      if (!hit || !(hit === b || b.contains(hit))) continue;
      var score = r.top + r.left;
      if (score < bestScore) {
        bestScore = score;
        best = b;
      }
    }
    if (!best) return false;
    viewer.removeAttribute(ATTR_REEL_STOP);
    reels.viewer = null;
    reels.current = null;
    clickElement(best);
    return true;
  }

