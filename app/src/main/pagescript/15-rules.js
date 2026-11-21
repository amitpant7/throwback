  // ---------------------------------------------------------------- rules

  function compile(rules) {
    var c = {};
    var hosts = rules.hosts || {};
    var paths = rules.paths || {};
    c.instagramHosts = hosts.instagram || [];
    c.homeUrl = rules.homeUrl;
    c.home = parseUrl(rules.homeUrl);
    c.homeVariants = (rules.homeVariants || []).slice();
    if (c.home) c.homeVariants.push(c.home.searchParams.get('variant'));
    c.messagesUrl = rules.messagesUrl;
    c.blocked = [];
    (paths.blocked || []).forEach(function (b) {
      var re = toRegex(b.pattern);
      var redirect = typeof b.redirect === 'string' && /^\/(?!\/)/.test(b.redirect) ? b.redirect : null;
      if (re) c.blocked.push({ re: re, redirect: redirect, kind: b.kind || 'hidden' });
    });
    c.alwaysAllowed = regexList(paths.alwaysAllowed);
    c.messagesOnly = regexList(paths.messagesOnly);
    c.pull = regexList(paths.pullToRefresh);
    c.pages = [];
    Object.keys(rules.pages || {}).forEach(function (name) {
      var re = /^[a-z][a-z0-9-]*$/.test(name) ? toRegex(rules.pages[name]) : null;
      if (re) c.pages.push({ name: name, re: re });
    });
    c.readySelector = validSelector(rules.readySelector) ? rules.readySelector : 'main, nav, article, form';

    var maxLen = 0;
    function track(set) {
      set.forEach(function (t) {
        if (t.length > maxLen) maxLen = t.length;
      });
      return set;
    }

    c.hide = (rules.hide || []).filter(function (h) {
      return validId(h.id) && validSelector(h.selector) && (!h.closest || validSelector(h.closest));
    }).map(function (h) {
      return {
        id: h.id,
        selector: h.selector,
        closest: h.closest || null,
        depth: typeof h.closestDepth === 'number' ? h.closestDepth : 6,
        on: h.on || 'always',
        invisible: h.style === 'invisible'
      };
    });
    // Rules without 'closest' are hidden by the CSS directly (buildCss); only the others need tagging.
    c.hideTagged = c.hide.filter(function (h) {
      return !!h.closest;
    });
    c.hideText = (rules.hideText || []).filter(function (h) {
      return validId(h.id) && validSelector(h.elements);
    }).map(function (h) {
      return { id: h.id, elements: h.elements, on: h.on || 'always', set: track(textSet(h.text)) };
    });

    var posts = rules.posts || {};
    c.postContainer = validSelector(posts.container) ? posts.container : 'article';
    c.maxMediaBefore = typeof posts.maxMediaBefore === 'number' ? posts.maxMediaBefore : 1;
    c.headerMaxFraction = typeof posts.headerMaxFraction === 'number' ? posts.headerMaxFraction : 0.3;
    function textRules(list) {
      return (list || []).filter(function (t) {
        return validId(t.id);
      }).map(function (t) {
        return { id: t.id, on: t.on || 'always', set: track(textSet(t.text)) };
      });
    }
    c.labels = textRules(posts.labels);
    c.modules = textRules(rules.modules);
    c.markers = textRules(rules.sectionMarkers);
    c.dialogs = (rules.dialogs || []).map(function (d) {
      return {
        id: d.id,
        when: (d.whenText || []).map(norm).filter(Boolean),
        click: textSet(d.click),
        clickSelector: validSelector(d.clickSelector) ? d.clickSelector : null
      };
    });
    c.dismissMaxAttempts = typeof rules.dismissMaxAttempts === 'number' ? rules.dismissMaxAttempts : 40;
    var ex = rules.explore;
    c.explore = ex && validSelector(ex.keep) && validSelector(ex.items) ? {
      on: ex.on || 'explore', keep: ex.keep, items: ex.items,
      dailySeconds: typeof ex.dailySeconds === 'number' ? ex.dailySeconds : 0,
      actions: ex.actions || null,
      images: Array.isArray(ex.images) ? ex.images.filter(function (u) {
        return typeof u === 'string' && /^https:\/\/raw\.githubusercontent\.com\/amitpant7\/throwback\/[A-Za-z0-9._\/-]+\.(jpg|jpeg|png|webp)$/.test(u);
      }) : []
    } : null;
    var ac = rules.appCreate;
    c.appCreate = ac && Array.isArray(ac.triggers) ? {
      // An invalid selector is dropped on its own (a trigger with text still works by its text): kept,
      // el.matches would throw on every tap.
      triggers: ac.triggers.map(function (t) {
        var selector = t && validSelector(t.selector) ? t.selector : null;
        var text = t && Array.isArray(t.text) && t.text.length ? t.text : null;
        return selector || text ? { selector: selector, text: text, kind: t.kind, fileInput: !!t.fileInput } : null;
      }).filter(Boolean)
    } : null;
    if (c.appCreate) c.appCreate.hasStory = c.appCreate.triggers.some(function (t) { return t.kind === 'story'; });
    var nav = rules.nav;
    c.nav = nav && validSelector(nav.links) ? { links: nav.links, minLinks: nav.minLinks || 3 } : null;
    var fd = rules.feedData;
    c.feedData = fd && fd.connections && fd.connections.length ? {
      connections: fd.connections.slice(),
      mediaField: fd.mediaField || 'media',
      adNodes: fd.adNodeFields || [],
      suggestionNodes: fd.suggestionNodeFields || [],
      dropNodes: fd.dropNodeFields || [],
      adMedia: fd.adMedia || [],
      suggestedMedia: fd.suggestedMedia || [],
      endMarkers: fd.endMarkerFields || [],
      endAtMarker: !!fd.endAtMarker,
      endAfterEmptyPages: typeof fd.endAfterEmptyPages === 'number' ? fd.endAfterEmptyPages : 3,
      pageSize: typeof fd.pageSize === 'number' ? fd.pageSize : 0,
      paginationQueries: fd.paginationQueries || [],
      lookAheadPosts: typeof fd.lookAheadPosts === 'number' ? fd.lookAheadPosts : 0,
      lookAheadVideos: typeof fd.lookAheadVideos === 'number' ? fd.lookAheadVideos : 0,
      videoKB: typeof fd.videoKB === 'number' ? fd.videoKB : 400
    } : null;
    var stR = rules.stories;
    c.stories = stR ? {
      connections: stR.connections || [],
      adKeys: stR.adKeys || [],
      markers: shortestMarkers((stR.connections || []).concat(stR.adKeys || [])),
      ahead: typeof stR.prefetchAhead === 'number' ? stR.prefetchAhead : 3,
      bytes: (typeof stR.prefetchKB === 'number' ? stR.prefetchKB : 400) * 1024
    } : null;
    // The "Your story" labels: the rules' own list, else the story trigger's text (one list to keep).
    var asR = stR && stR.addStory;
    var storyLabels = asR && Array.isArray(asR.yourStory) && asR.yourStory.length ? asR.yourStory :
      ((ac && ac.triggers) || []).reduce(function (all, t) {
        return t && t.kind === 'story' && Array.isArray(t.text) ? all.concat(t.text) : all;
      }, []);
    c.addStory = asR && validSelector(asR.menuButton) && validSelector(asR.menuItem) && validSelector(asR.ownPlus) &&
      storyLabels.length && Array.isArray(asR.storyItem) && asR.storyItem.length ? {
        yourStory: textSet(storyLabels),
        storyItem: textSet(asR.storyItem),
        menuButton: asR.menuButton,
        menuItem: asR.menuItem,
        ownPlus: asR.ownPlus
      } : null;
    var rv = rules.reelViewer;
    c.reelViewer = rv ? {
      minSwipe: typeof rv.posterOnSwipeMin === 'number' ? rv.posterOnSwipeMin : 1,
      maxSwipe: typeof rv.posterOnSwipeMax === 'number' ? rv.posterOnSwipeMax : 4,
      windowMs: (typeof rv.windowMinutes === 'number' ? rv.windowMinutes : 10) * 60000,
      cooldownMs: (typeof rv.cooldownMinutes === 'number' ? rv.cooldownMinutes : 60) * 60000,
      selector: validSelector(rv.viewerSelector) ? rv.viewerSelector : null,
      snapAxis: typeof rv.snapAxis === 'string' ? rv.snapAxis : 'y',
      minVideos: typeof rv.minVideos === 'number' ? rv.minVideos : 2,
      maxDepth: typeof rv.maxDepth === 'number' ? rv.maxDepth : 14,
      closeLabels: textSet(rv.closeLabels && rv.closeLabels.length ? rv.closeLabels : ['Close'])
    } : null;
    var ss = rules.settingsSection;
    var ssRe = ss && toRegex(ss.path);
    c.settingsSection = ssRe && ss.after && ss.after.length ? { re: ssRe, after: textSet(ss.after) } : null;
    // Text nodes longer than any matcher (plus slack for whitespace) can be skipped cheaply.
    c.maxTextLength = maxLen + 8;
    return c;
  }

  /**
   * Markers for a text search: one that contains another marker is dropped (wherever it occurs, the
   * shorter one does too), saving a full scan of every large response.
   */
  function shortestMarkers(list) {
    return list.filter(function (m, i) {
      return m && !list.some(function (o, j) {
        return j !== i && o && o.length < m.length && m.indexOf(o) !== -1;
      });
    });
  }

  function scopeFor(on) {
    if (!on || on === 'always') return 'html';
    if (on === 'messagesOnly') return 'html.tb-messages-only';
    if (on === 'full') return 'html.tb-full';
    // Explore hiding applies only once the day's Explore allowance is used up.
    if (on === 'explore') return 'html.tb-on-explore:not(.' + CLASS_EXPLORE_OPEN + ')';
    return 'html.tb-on-' + on;
  }

  function buildCss(c, base) {
    var out = [base || ''];
    var HIDE = '{display:none!important}';
    var INVISIBLE = '{visibility:hidden!important;pointer-events:none!important}';
    c.hide.forEach(function (h) {
      var decl = h.invisible ? INVISIBLE : HIDE;
      var scope = scopeFor(h.on);
      // Direct rules apply from the first paint; one rule per selector so that a selector this
      // engine doesn't support only drops itself. Rules with 'closest' rely on tagging below.
      if (!h.closest) {
        splitSelectors(h.selector).forEach(function (s) {
          out.push(scope + ' ' + s + decl);
        });
      }
      out.push(scope + ' [' + ATTR + '~="' + h.id + '"]' + decl);
    });
    c.hideText.forEach(function (h) {
      out.push(scopeFor(h.on) + ' [' + ATTR + '~="' + h.id + '"]' + HIDE);
    });
    out.push('[' + ATTR_POST + ']' + HIDE);
    // Home feed: every post stays hidden until Throwback has checked it (approvePost), so a suggestion
    // or ad can never flash on screen. The check runs before the frame paints.
    out.push('html.tb-full.tb-on-home main ' + c.postContainer + ':not([' + ATTR_OK + ']):not([' + ATTR_DISCOVER + '])' + HIDE);
    out.push('[' + ATTR_SLOT + ']' + HIDE);
    out.push('[' + ATTR_THREADS + ']' + HIDE);
    // A stopped reel viewer can't be swiped any further.
    out.push('[' + ATTR_REEL_STOP + ']{overflow:hidden!important;touch-action:none!important}');
    // Discovery posts get a small tag so you always know it's not someone you follow.
    out.push('article[' + ATTR_DISCOVER + ']::before{content:"Discover \u00b7 not someone you follow";display:block;' +
      'font-size:12px;opacity:.65;padding:10px 16px 2px;letter-spacing:.01em}');
    // While the end card shows, the feed's loading spinner (it would only load hidden suggestions).
    out.push('html[' + ATTR_FEED_END + '] main [role="progressbar"]' + HIDE);
    out.push('html[' + ATTR_FEED_END + '] main svg[aria-label="Loading..."]' + HIDE);
    // ... and the list's reserved space for more (hidden) posts, so the page ends at the end card.
    out.push('html[' + ATTR_FEED_GAP + '] [' + ATTR_FEED_LIST + '],html[' + ATTR_FEED_END + '] [' + ATTR_FEED_LIST +
      ']{padding-bottom:0!important}');
    out.push('html.tb-messages-only [' + ATTR_NAV + ']' + HIDE);
    // Explore grid blocks stay hidden even while leaving the Explore page (the page marker goes away
    // a moment before Instagram removes the page, which flashed the grid on the way to Home).
    if (c.explore) out.push('html:not(.' + CLASS_EXPLORE_OPEN + ') [' + ATTR_EXPLORE + ']' + HIDE);
    out.push('html.' + CLASS_BLANK + ' body{visibility:hidden!important}');
    return out.join('\n');
  }

