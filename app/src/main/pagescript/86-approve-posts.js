  // ---------------------------------------------------------------- hide until approved

  /** Allows home-feed posts in root that have been checked (their header rendered, no ad/suggestion label). */
  function approvePosts(root) {
    if (!state.c || state.mode === MODE_MESSAGES || !pageActive('home') || !root) return;
    var sel = state.c.postContainer + ':not([' + ATTR_OK + '])';
    if (root.nodeType === 1 && root.matches(sel)) approvePost(root);
    if (root.querySelectorAll) {
      var posts = root.querySelectorAll(sel);
      for (var i = 0; i < posts.length; i++) approvePost(posts[i]);
    }
  }

  var APPROVE_WAIT_MS = 300;
  var approveSeen = typeof WeakMap === 'function' ? new WeakMap() : null;

  function approvePost(p) {
    if (p.hasAttribute(ATTR_OK) || p.hasAttribute(ATTR_POST) || p.hasAttribute(ATTR_DISCOVER)) return;
    // A suggestion the data filter let through: tag it, whatever its header says.
    var key = postKey(p);
    if (key && discoveredKeys[key]) {
      p.setAttribute(ATTR_DISCOVER, '1');
      return;
    }
    if (!p.querySelector('a[href]')) return;
    // Already checked in the data: the data filter kept it (a followed post). Show it at once; waiting
    // for its photo element left the lower half of the first screen blank for a moment.
    if (key && feedAhead.ids[key]) {
      p.setAttribute(ATTR_OK, '1');
      return;
    }
    // Not seen in the data: checked once its header has rendered (the profile link, and the photo or
    // video below the header, so a "Sponsored" label in the header has been seen too; the header's own
    // avatar doesn't count). A post without media is let through after APPROVE_WAIT_MS, so nothing can
    // stay hidden for good.
    if (p.querySelectorAll('img, video').length <= state.c.maxMediaBefore && approveSeen) {
      var first = approveSeen.get(p);
      var now = Date.now();
      if (!first) {
        approveSeen.set(p, now);
        // Directly, not via the next frame: frames don't run while nothing on screen changes.
        setTimeout(function () {
          if (p.isConnected && pageActive('home')) approvePost(p);
        }, APPROVE_WAIT_MS + 50);
        return;
      }
      if (now - first < APPROVE_WAIT_MS) return;
    }
    p.setAttribute(ATTR_OK, '1');
  }

