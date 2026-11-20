  // ---------------------------------------------------------------- end of the feed

  var END_CARD_ID = '__tb_end_card';
  var ATTR_FEED_END = 'data-tb-feedend'; // on <html> while the end card shows (hides the endless spinner)
  var ATTR_FEED_GAP = 'data-tb-feedgap'; // on <html> while only hidden posts follow the last real one, at the bottom
  var END_HIDDEN_RUN = 20; // this many hidden posts in a row at the bottom = only suggestions are left
  var END_IDLE_MS = 10000; // ... or nothing new loaded for this long at the bottom
  var ATTR_FEED_LIST = 'data-tb-feedlist'; // the feed's (virtualized) list element
  var END_PAST_MS = 400; // how long the condition must hold before entering the bottom state
  var LOOKAHEAD_SCREENS = 2; // enter the bottom state while the last real post is within this many screens
  var LOOKAHEAD_POSTS = 4; // real posts below the screen whose images load early
  var ATTR_WARM = 'data-tb-warm';

  /**
   * Instagram's ranked feed keeps loading suggested posts after the people you follow run out.
   * Throwback hides those, which left an endless spinner and blank space. When the posts after the
   * last visible one are all hidden, show "You're all caught up" and hide the spinner: the feed ends.
   */
  function updateEndCard() {
    var first = state.mode === MODE_MESSAGES || !pageActive('home') ? null : document.querySelector('article');
    var list = first && first.parentElement;
    var visible = 0, trailingHidden = 0, lastVisible = null;
    if (list) {
      if (!list.hasAttribute(ATTR_FEED_LIST)) list.setAttribute(ATTR_FEED_LIST, '1');
      for (var k = list.firstElementChild; k; k = k.nextElementSibling) {
        if (k.tagName !== 'ARTICLE') continue;
        if (k.hasAttribute(ATTR_POST)) trailingHidden++;
        else { visible++; trailingHidden = 0; lastVisible = k; }
      }
    }
    if (!list || visible === 0) {
      // A feed with no posts at all: its suggestions block is hidden (onModule); the card goes there.
      var emptyFeed = !list && pageActive('home') && state.mode !== MODE_MESSAGES &&
        document.querySelector('main [' + ATTR_POST + ']');
      if (emptyFeed) {
        state.emptyFeedCard = true;
        showEndCard(emptyFeed);
      } else {
        clearFeedBottom();
      }
      return;
    }
    if (state.emptyFeedCard) { // posts arrived after all: the card goes back to the end of the feed
      state.emptyFeedCard = false;
      clearFeedBottom();
    }
    warmUpcoming(list);
    // The data filter knows the exact end (only suggestions left): the card goes right after the last post.
    if (feedDataState.ended) {
      state.dataEnd = true;
      stopBottomWatch();
      showEndCard(list);
      return;
    }
    if (state.dataEnd) {
      state.dataEnd = false;
      clearFeedBottom();
    }
    var now = Date.now();
    // Instagram's home feed comes in waves: posts from people you follow, then a long run of
    // suggestions (hidden), then older posts from people you follow. A run of hidden posts is
    // therefore a gap, not the end. Two states, both latched until a genuinely new real post appears
    // (Instagram's virtualized list also drops and re-adds posts far from the screen, which must not
    // flip anything, or the page flickers):
    //  - at the bottom (ATTR_FEED_GAP): only hidden posts follow the last real one and the space
    //    below it is on screen. Instagram's reserved space for those hidden posts would be blank,
    //    so it is removed; Instagram's own loading spinner shows while it fetches more.
    //  - ended (ATTR_FEED_END): at the bottom and END_HIDDEN_RUN hidden posts in a row, or nothing
    //    new loaded for END_IDLE_MS. "You're all caught up", no spinner, no more loading.
    if (state.atBottom) {
      if (!hasFreshRealPost(list)) {
        var kids = list.children.length;
        // The idle clock only runs while the bottom is actually on screen (someone still reading the
        // last post isn't waiting for more).
        var bottomOnScreen = lastVisible.getBoundingClientRect().bottom < window.innerHeight * 0.9;
        if (kids !== state.bottomKids || (!bottomOnScreen && !document.documentElement.hasAttribute(ATTR_FEED_END))) {
          state.bottomKids = kids;
          state.bottomSince = now;
        }
        if (trailingHidden >= END_HIDDEN_RUN || now - state.bottomSince >= END_IDLE_MS) showEndCard(list);
        return;
      }
      clearFeedBottom();
    }
    // Look-ahead: act while the last real post is still up to LOOKAHEAD_SCREENS below, not only once
    // blank space shows. Removing the reserved space early also brings Instagram's "load more" point
    // closer, so the next batch is fetched before the user gets there.
    var nearEnd = trailingHidden > 0 && lastVisible.getBoundingClientRect().bottom < window.innerHeight * LOOKAHEAD_SCREENS;
    if (!nearEnd) {
      state.pastSince = 0;
      return;
    }
    // A short hold so a fast scroll, before Instagram renders the next posts, doesn't trigger it.
    if (!state.pastSince) {
      state.pastSince = now;
      setTimeout(updateEndCard, END_PAST_MS + 50);
      return;
    }
    if (now - state.pastSince < END_PAST_MS) return;
    state.atBottom = true;
    state.bottomLastKey = postKey(lastVisible);
    state.bottomKids = list.children.length;
    state.bottomSince = now;
    state.bottomTimer = setInterval(updateEndCard, 1000); // for the idle check
    document.documentElement.setAttribute(ATTR_FEED_GAP, '1');
  }

  /** Images of the next few real posts below the screen load now instead of when scrolled to. */
  function warmUpcoming(list) {
    noteFeedPosition(list);
    var n = 0;
    var vh = window.innerHeight;
    for (var a = list.firstElementChild; a && n < LOOKAHEAD_POSTS; a = a.nextElementSibling) {
      if (a.tagName !== 'ARTICLE' || a.hasAttribute(ATTR_POST)) continue;
      if (a.getBoundingClientRect().top < vh) continue;
      n++;
      if (a.hasAttribute(ATTR_WARM)) continue;
      a.setAttribute(ATTR_WARM, '1');
      var imgs = a.querySelectorAll('img[loading="lazy"]');
      for (var i = 0; i < imgs.length; i++) imgs[i].loading = 'eager';
    }
  }

  /**
   * A real post after the last one seen when the bottom state began: new posts arrived. (Older posts
   * re-mounted by Instagram's virtualized list while scrolling up don't count.)
   */
  function hasFreshRealPost(list) {
    var past = false;
    for (var v = list.firstElementChild; v; v = v.nextElementSibling) {
      if (v.tagName !== 'ARTICLE' || v.hasAttribute(ATTR_POST)) continue;
      if (past) return true;
      if (postKey(v) === state.bottomLastKey) past = true;
    }
    return false;
  }

  function clearFeedBottom() {
    var root = document.documentElement;
    var card = document.getElementById(END_CARD_ID);
    if (card && card.parentNode) card.parentNode.removeChild(card);
    root.removeAttribute(ATTR_FEED_END);
    root.removeAttribute(ATTR_FEED_GAP);
    stopBottomWatch();
  }

  /** Ends the DOM fallback's bottom state (its idle timer), leaving the card and attributes as they are. */
  function stopBottomWatch() {
    if (state.bottomTimer) clearInterval(state.bottomTimer);
    state.bottomTimer = 0;
    state.atBottom = false;
    state.pastSince = 0;
  }

  function showEndCard(list) {
    var root = document.documentElement;
    var card = document.getElementById(END_CARD_ID);
    if (!card) {
      card = document.createElement('div');
      card.id = END_CARD_ID;
      card.setAttribute('role', 'status');
      card.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:6px;padding:40px 24px 64px;' +
        'text-align:center;color:inherit;font-family:inherit';
      card.innerHTML =
        '<svg aria-hidden="true" viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" ' +
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/>' +
        '<path d="m7.5 12.5 3 3 6-7"/></svg>' +
        '<div style="font-size:18px;font-weight:600">You\u2019re all caught up</div>' +
        '<div style="font-size:14px;opacity:.7">That\u2019s everything new from the people you follow.</div>';
    }
    // After the feed list (React owns the list itself); hidden posts take no space, so the card sits
    // right under the last real post.
    if (card.parentNode !== list.parentNode || card.previousElementSibling !== list) {
      list.parentNode.insertBefore(card, list.nextSibling);
    }
    root.setAttribute(ATTR_FEED_END, '1');
    onEndCardSeen(card);
  }

  /**
   * The card can be built while it is still screens away (the data filter knows the end early). Its
   * once-only moments wait until it is on screen: the app's rating ask (caughtUp) and the support line
   * (which also resets the removed-counts). Called on every scroll frame while the card exists.
   */
  function onEndCardSeen(card) {
    var r = card.getBoundingClientRect();
    if (r.top >= window.innerHeight || r.bottom <= 0) return;
    if (!state.caughtUpSent) {
      state.caughtUpSent = true;
      send({ t: 'caughtUp' });
    }
    maybeSupportLine(card);
  }

  var SUPPORT_LINE_ID = '__tb_support_line';
  var SUPPORT_MIN_REMOVED = 20;

  function maybeSupportLine(card) {
    var kind = state.supportAsk;
    if (!kind || !card || card.querySelector('#' + SUPPORT_LINE_ID)) return;
    var t = totalsLog();
    var ads = t.ads || 0;
    var sugg = t.sugg || 0;
    if (ads + sugg < SUPPORT_MIN_REMOVED) return; // too little to show; try again another time
    var days = Math.max(1, Math.round((trustedNow() - t.since) / 86400000));
    var when = days <= 45 ? (kind === 'first' ? 'Your first month' : 'Your last month') : 'Your last ' + Math.round(days / 30) + ' months';
    var fmt = function (n) { return n.toLocaleString(); };
    var box = function (n, label) {
      return '<div style="flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;padding:12px 8px;' +
        'border:1px solid rgba(128,128,128,.3);border-radius:14px"><span style="font-size:22px;font-weight:700;' +
        'font-variant-numeric:tabular-nums">' + fmt(n) + '</span><span style="font-size:12px;opacity:.65">' + label + '</span></div>';
    };
    var line = document.createElement('div');
    line.id = SUPPORT_LINE_ID;
    line.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:10px;margin-top:18px;width:100%;max-width:320px';
    line.innerHTML = '<div style="font-size:13px;opacity:.65">' + when + '</div>' +
      '<div style="display:flex;gap:10px;width:100%">' + box(ads, 'ads removed') + box(sugg, 'suggested posts removed') + '</div>';
    var thanks = document.createElement('a');
    thanks.href = '#';
    thanks.textContent = 'Say thanks \u2192';
    thanks.style.cssText = 'font-weight:700;color:#2c8a5b;text-decoration:none;padding:6px 10px';
    tapToApp(thanks, { t: 'supportThanks' });
    line.appendChild(thanks);
    card.appendChild(line);
    // Shown: the next ask counts from now; the app schedules it (2 months on).
    state.supportAsk = null;
    totals = { since: trustedNow(), ads: 0, sugg: 0 };
    saveTotals();
    send({ t: 'supportAskShown' });
  }

