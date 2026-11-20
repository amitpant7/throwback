  // ---------------------------------------------------------------- discovery

  /*
   * A little discovery so the feed isn't sealed shut (owner's decision, 2026-11-20): of the posts
   * Instagram suggests from accounts you don't follow, a few are shown, tagged "Discover":
   *  - per 5 minutes of time actually spent on the feed: at most 1 video/reel and 1 photo post;
   *  - at most 20 a day;
   *  - spread out: only after DISCOVER.spacing posts from people you follow since the last one;
   *  - never once the feed has ended ("You're all caught up"); never ads (only 'suggested' labels).
   */
  var ATTR_DISCOVER = 'data-tb-discover';
  var DISCOVER = { windowSec: 300, perDay: 20, spacing: 8, storageKey: '__tb_discover' };
  var disc = null;
  // Posts let through on this page, so a re-render keeps them. Deliberately not stored: when Instagram
  // suggests the same post again after a restart, it counts against the allowance like any other.
  var discoveredKeys = {};

  function discState() {
    if (!disc) {
      try {
        disc = JSON.parse(localStorage.getItem(DISCOVER.storageKey) || 'null');
      } catch (e) {
        disc = null;
      }
    }
    if (!disc || disc.date !== today()) {
      disc = { date: today(), count: 0, feedSec: 0, win: -1, video: 0, photo: 0 };
    }
    return disc;
  }

  function saveDisc() {
    try {
      localStorage.setItem(DISCOVER.storageKey, JSON.stringify(disc));
    } catch (e) {
      // Storage unavailable: the allowance just resets with the page.
    }
  }

  /** Key of a post (its /p/ or /reel/ code), so a post shown once stays shown when Instagram re-renders it. */
  function postKey(post) {
    var a = post.querySelector('a[href*="/p/"], a[href*="/reel/"]');
    var m = a && /\/(?:p|reels?)\/([A-Za-z0-9_-]+)/.exec(a.getAttribute('href') || '');
    return m ? m[1] : null;
  }

  function allowDiscovery(post) {
    if (state.mode === MODE_MESSAGES || !pageActive('home')) return false;
    var key = postKey(post);
    if (key && discoveredKeys[key]) return true; // already let through on this page (or by the data filter)
    if (document.documentElement.hasAttribute(ATTR_FEED_END)) return false;
    var d = discState();
    var kind = post.querySelector('video') ? 'video' : 'photo';
    // Spread out: enough posts from people you follow since the previous discovery (or the top).
    var followed = 0;
    for (var s = post.previousElementSibling; s; s = s.previousElementSibling) {
      if (s.hasAttribute(ATTR_DISCOVER)) break;
      if (s.tagName === 'ARTICLE' && !s.hasAttribute(ATTR_POST)) followed++;
    }
    return takeDiscovery(d, kind, followed >= DISCOVER.spacing, key);
  }

  /** The shared allowance: 1 video and 1 photo per 5 minutes of feed time, 20 a day, spaced out. */
  function takeDiscovery(d, kind, spacedOut, key) {
    if (d.count >= DISCOVER.perDay) return false;
    var win = Math.floor(d.feedSec / DISCOVER.windowSec);
    if (win !== d.win) {
      d.win = win;
      d.video = 0;
      d.photo = 0;
    }
    if (d[kind] >= 1 || !spacedOut) return false;
    d[kind]++;
    d.count++;
    if (key) discoveredKeys[key] = 1;
    saveDisc();
    return true;
  }

  /** Counts time actually spent looking at the feed (the 5-minute windows). */
  function startFeedClock() {
    setInterval(function () {
      if (document.visibilityState !== 'visible' || state.covered || state.mode === MODE_MESSAGES || !state.c ||
        !pageActive('home')) return;
      var d = discState();
      d.feedSec++;
      if (d.feedSec % 10 === 0) saveDisc();
    }, 1000);
  }

