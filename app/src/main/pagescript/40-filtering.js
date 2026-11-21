  // ---------------------------------------------------------------- filtering

  function scanHide(root) {
    var c = state.c;
    c.hideTagged.forEach(function (h) {
      // A mode switch rescans the whole page (configure), so the other mode's rules can wait.
      if ((h.on === 'messagesOnly' && state.mode !== MODE_MESSAGES) || (h.on === 'full' && state.mode !== MODE_FULL)) return;
      eachMatch(root, h.selector, function (el) {
        var target = h.closest ? closestWithin(el, h.closest, h.depth) || el : el;
        addToken(target, ATTR, h.id);
      });
    });
    c.hideText.forEach(function (h) {
      eachMatch(root, h.elements, function (el) {
        if (el.childElementCount > 6) return;
        var text = el.textContent;
        if (text && text.length <= c.maxTextLength && h.set.has(norm(text))) addToken(el, ATTR, h.id);
      });
    });
  }

  /**
   * Feed labels never occur in DMs or the story viewer, which change text constantly (typing,
   * timestamps, progress): no text scanning there (pages 'direct' and 'stories' in the rules).
   */
  function textScanActive() {
    return !pageActive('direct') && !pageActive('stories');
  }

  function scanText(root) {
    var c = state.c;
    if (!c.labels.length && !c.modules.length && !c.markers.length) return;
    if (!textScanActive()) return;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var seen = new Set();
    var node;
    while ((node = walker.nextNode())) {
      var v = node.nodeValue;
      if (!v || v.length > c.maxTextLength) continue;
      var el = node.parentElement;
      if (el && !seen.has(el)) {
        seen.add(el);
        checkTextElement(el, 0, seen);
      }
    }
  }

  /**
   * Checks an element whose whole text may be a label; also its parent and grandparent (labels split
   * over spans). `seen` holds the elements already checked in this pass, so each is checked once.
   */
  function checkTextElement(el, depth, seen) {
    var c = state.c;
    if (el.childElementCount > 6) return; // a container, not a label (and textContent would be costly)
    var text = el.textContent;
    if (!text || text.length > c.maxTextLength) return;
    var t = norm(text);
    if (!t) return;
    var i;
    for (i = 0; i < c.labels.length; i++) {
      if (c.labels[i].set.has(t) && pageActive(c.labels[i].on)) {
        onPostLabel(el, c.labels[i].id);
        break;
      }
    }
    for (i = 0; i < c.modules.length; i++) {
      if (c.modules[i].set.has(t) && pageActive(c.modules[i].on)) {
        onModule(el, c.modules[i].id);
        break;
      }
    }
    for (i = 0; i < c.markers.length; i++) {
      if (c.markers[i].set.has(t) && pageActive(c.markers[i].on)) {
        onMarker(el, c.markers[i].id);
        break;
      }
    }
    // Labels can be split over spans ("Spon" + "sored"): also try the parent, but never a block
    // with images or videos in it, which could hold a photo and its caption together.
    var parent = el.parentElement;
    if (depth >= 2 || !parent || seen.has(parent)) return;
    seen.add(parent);
    if (!parent.querySelector('img, video')) checkTextElement(parent, depth + 1, seen);
  }

  /** How many images/videos of the post come before el (0 or 1 = at most the avatar: el is in the header). */
  function mediaBefore(post, el) {
    var media = post.querySelectorAll('img, video');
    var n = 0;
    for (var i = 0; i < media.length; i++) {
      if (media[i].compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) n++;
      else break; // document order: the rest come after el
    }
    return n;
  }

  /**
   * Whether el is in the post's header rather than its caption (DECISIONS.md #15: captions are never
   * hidden). Two independent checks: no photo/video before it, and, when the post is laid out, a
   * position near the top. Layout is only read here, for the rare exact label matches.
   */
  function inPostHeader(post, el) {
    if (mediaBefore(post, el) > state.c.maxMediaBefore) return false;
    var box = post.getBoundingClientRect();
    if (box.height > 0) {
      var top = el.getBoundingClientRect().top - box.top;
      if (top / box.height > state.c.headerMaxFraction) return false;
    }
    return true;
  }

  /** "Sponsored" / "Suggested for you" in a post's header removes the post (unless discovery lets it in). */
  function onPostLabel(el, id) {
    var post = el.closest(state.c.postContainer);
    if (!post || post === el || post.hasAttribute(ATTR_POST) || post.hasAttribute(ATTR_DISCOVER)) return;
    if (!inPostHeader(post, el)) return; // caption text, never hidden
    if (id === 'suggested' && allowDiscovery(post)) {
      post.setAttribute(ATTR_DISCOVER, '1');
      return;
    }
    post.setAttribute(ATTR_POST, id);
  }

  /**
   * The feed item containing el: its highest ancestor whose parent also holds a post outside it.
   * Null when no such level exists (nothing is hidden rather than too much).
   */
  function feedItemOf(el) {
    var container = state.c.postContainer;
    var n = el;
    for (var depth = 0; depth < 30 && n && n.parentElement; depth++) {
      var p = n.parentElement;
      if (p === document.body || p === document.documentElement) return null;
      var posts = p.querySelectorAll(container);
      for (var i = 0; i < posts.length; i++) {
        if (!n.contains(posts[i])) return safeItem(n) ? n : null;
      }
      if (p.tagName === 'MAIN' || p.getAttribute('role') === 'main') return null;
      n = p;
    }
    return null;
  }

  /** Never remove posts, navigation, inputs or page structure as a "block". */
  function safeItem(n) {
    if (n.matches('main, nav, [role="main"], [role="navigation"]')) return false;
    return !n.querySelector(state.c.postContainer + ', main, nav, [role="main"], [' + ATTR_NAV + '], input, textarea, [contenteditable="true"]');
  }

  /** Suggested reels and threads carousels and similar blocks between posts (rules: modules). */
  function onModule(el, id) {
    if (el.closest(state.c.postContainer)) return; // labels inside posts are handled by onPostLabel
    var item = feedItemOf(el);
    if (item) item.setAttribute(ATTR_POST, id);
  }

  /** "Suggested posts" heading: it and everything after it in the feed. */
  function onMarker(el, id) {
    if (el.closest(state.c.postContainer)) return;
    var item = feedItemOf(el);
    if (!item) return;
    item.setAttribute(ATTR_POST, id);
    if (state.markers.indexOf(item) < 0) state.markers.push(item);
    hideAfterMarkers();
  }

  function hideAfterMarkers() {
    state.markers = state.markers.filter(function (m) {
      return m.isConnected;
    });
    state.markers.forEach(function (m) {
      for (var s = m.nextElementSibling; s; s = s.nextElementSibling) {
        if (s.hasAttribute(ATTR_POST)) continue;
        if (s.matches('nav, [role="navigation"]') || s.querySelector('nav, main, [' + ATTR_NAV + '], input, textarea')) continue;
        s.setAttribute(ATTR_POST, m.getAttribute(ATTR_POST) || 'suggested');
      }
    });
  }

