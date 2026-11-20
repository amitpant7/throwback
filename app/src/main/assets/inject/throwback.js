/*
 * Throwback page script.
 *
 * Injected into www.instagram.com documents at document start (WebViewCompat.addDocumentStartJavaScript),
 * or as early as onPageStarted on WebViews without that feature. The app replaces the token at the
 * bottom with {mode, css, rules}: rules come from filters/rules.json (validated by the app),
 * css from throwback.css. Kotlin side: app/src/main/java/app/throwback/web/ (PageScript, ScriptInjector).
 *
 * What it does:
 *  - Routing for in-page navigation (pushState/replaceState/popstate and link taps), mirroring
 *    UrlRouter.kt: Reels pages blocked (a single reel opens as a post), the home feed is always the
 *    home feed, messages-only mode keeps to /direct/.
 *  - Hides Reels links, the Explore grid, ads, suggestions, app nags, and Instagram's wordmark on
 *    the home feed, using stable signals only (hrefs, aria-labels, visible text). Everything runs in
 *    a MutationObserver callback, before the browser paints, so filtered content never flashes.
 *  - Never hides captions or video descriptions (DECISIONS.md #15): text labels only count in a
 *    post's header, before its media.
 *  - Reports location changes, readiness, touches (pull-to-refresh) and the page background to the app.
 *
 * Written for old Chromium too (fallback mode): no optional chaining, no nullish coalescing.
 */
(function (config) {
  'use strict';

  if (window.top !== window) return; // top-level document only

  // Second injection into the same document (fallback mode, or a re-registered script).
  if (window.__throwback) {
    window.__throwback.configure(config);
    return;
  }

  var MODE_FULL = 'FULL';
  var MODE_MESSAGES = 'MESSAGES_ONLY';
  var KIND_MESSAGES = 'messagesOnly';
  var ATTR = 'data-tb'; // space-separated ids of the hide / hideText rules that matched
  var ATTR_POST = 'data-tb-post'; // a feed item removed whole (ad, suggestion)
  var ATTR_OK = 'data-tb-ok'; // a home-feed post checked and allowed (posts are hidden until then)
  var ATTR_NAV = 'data-tb-nav'; // a navigation bar
  var ATTR_SLOT = 'data-tb-slot'; // a navigation slot whose only button is hidden
  var ATTR_EXPLORE = 'data-tb-explore'; // a block of the Explore grid
  var ATTR_THREADS = 'data-tb-threads'; // the Threads link on the own-profile top bar (hidden)
  var PROFILE_ENTRY_ID = '__tb_profile_entry'; // Throwback's settings icon that replaces it
  var CLASS_BLANK = 'tb-page-blocked';
  var CLASS_EXPLORE_OPEN = 'tb-explore-open'; // on <html> while today's Explore allowance lasts
  var LOOP_KEY = '__tb_redirects';
  var READY_FALLBACK_MS = 6000;
  var NAV_SCAN_MS = 1000;

  var state = {
    mode: MODE_FULL,
    rules: null,
    c: null, // compiled rules
    baseCss: '',
    css: '',
    href: null,
    path: '/',
    leaving: false, // a redirect away from this document is under way
    readySent: false,
    lastBg: null,
    bgDirty: true,
    markers: [],
    passQueued: false,
    lastNavScan: 0
  };
  var styleEl = null;
  var styleCss = null; // what styleEl currently holds
  var dialogTries = typeof WeakMap === 'function' ? new WeakMap() : null;
  var dialogChecked = typeof WeakMap === 'function' ? new WeakMap() : null;
  var DIALOG_CHECK_MS = 500;

  // ---------------------------------------------------------------- bridge

  function send(msg) {
    var s;
    try {
      s = JSON.stringify(msg);
    } catch (e) {
      return;
    }
    try {
      var native = window.throwbackNative; // WebViewCompat.addWebMessageListener
      if (native && typeof native.postMessage === 'function') {
        native.postMessage(s);
        return;
      }
      var legacy = window.throwbackBridge; // @JavascriptInterface fallback
      if (legacy && typeof legacy.postMessage === 'function') legacy.postMessage(s);
    } catch (e) {
      // The bridge goes away while the page unloads; nothing to do.
    }
  }

  // ---------------------------------------------------------------- helpers

  function norm(s) {
    return String(s || '')
      .replace(/[​-‍⁠﻿]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();
  }

  function toRegex(pattern) {
    try {
      return new RegExp(pattern);
    } catch (e) {
      return null;
    }
  }

  function regexList(list) {
    var out = [];
    (list || []).forEach(function (p) {
      var r = toRegex(p);
      if (r) out.push(r);
    });
    return out;
  }

  function anyMatch(list, s) {
    for (var i = 0; i < list.length; i++) if (list[i].test(s)) return true;
    return false;
  }

  function textSet(list) {
    var set = new Set();
    (list || []).forEach(function (t) {
      var n = norm(t);
      if (n) set.add(n);
    });
    return set;
  }

  var probe = null;
  /** A selector the browser accepts that cannot break out of a CSS rule. */
  function validSelector(sel) {
    if (typeof sel !== 'string' || !sel || /[{}<;]|\/\*/.test(sel)) return false;
    try {
      probe = probe || document.createDocumentFragment();
      probe.querySelector(sel);
      return true;
    } catch (e) {
      return false;
    }
  }

  function validId(id) {
    return typeof id === 'string' && /^[a-z0-9][a-z0-9-]{0,40}$/.test(id);
  }

  /** Splits "a, b:not(c, d)" at top-level commas. */
  function splitSelectors(sel) {
    var out = [], depth = 0, quote = null, start = 0;
    for (var i = 0; i < sel.length; i++) {
      var ch = sel[i];
      if (quote) {
        if (ch === '\\') i++;
        else if (ch === quote) quote = null;
      } else if (ch === '"' || ch === "'") quote = ch;
      else if (ch === '(' || ch === '[') depth++;
      else if (ch === ')' || ch === ']') depth--;
      else if (ch === ',' && depth === 0) {
        out.push(sel.slice(start, i).trim());
        start = i + 1;
      }
    }
    out.push(sel.slice(start).trim());
    return out.filter(Boolean);
  }

  function parseUrl(href) {
    try {
      return new URL(href, location.href);
    } catch (e) {
      return null;
    }
  }

  function hostMatches(patterns, host) {
    host = String(host || '').toLowerCase();
    for (var i = 0; i < patterns.length; i++) {
      var p = String(patterns[i]).toLowerCase();
      if (p.indexOf('*.') === 0) {
        var suffix = p.slice(1);
        if (host.length > suffix.length && host.slice(-suffix.length) === suffix) return true;
      } else if (host === p) return true;
    }
    return false;
  }

  function closestWithin(el, sel, depth) {
    for (var n = el, i = 0; n && n.nodeType === 1 && i <= depth; n = n.parentElement, i++) {
      try {
        if (n.matches(sel)) return n;
      } catch (e) {
        return null;
      }
    }
    return null;
  }

  function eachMatch(root, sel, fn) {
    try {
      if (root.matches && root.matches(sel)) fn(root);
      var list = root.querySelectorAll(sel);
      for (var i = 0; i < list.length; i++) fn(list[i]);
    } catch (e) {
      // A selector the engine doesn't support: skip this rule.
    }
  }

  function addToken(el, attr, token) {
    if (!el || el.nodeType !== 1) return;
    var cur = el.getAttribute(attr);
    if (!cur) el.setAttribute(attr, token);
    else if ((' ' + cur + ' ').indexOf(' ' + token + ' ') < 0) el.setAttribute(attr, cur + ' ' + token);
  }

  function clickElement(el) {
    if (typeof el.click === 'function') el.click();
    else el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
  }

  function raf(fn) {
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(fn);
    else setTimeout(fn, 16);
  }

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
      actions: ex.actions || null
    } : null;
    var ac = rules.appCreate;
    c.appCreate = ac && Array.isArray(ac.triggers) ? {
      triggers: ac.triggers.filter(function (t) {
        return t && ((t.selector && validSelector(t.selector)) || (Array.isArray(t.text) && t.text.length));
      })
    } : null;
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
      markers: (stR.connections || []).concat(stR.adKeys || []),
      ahead: typeof stR.prefetchAhead === 'number' ? stR.prefetchAhead : 3,
      bytes: (typeof stR.prefetchKB === 'number' ? stR.prefetchKB : 400) * 1024
    } : null;
    var rv = rules.reelViewer;
    c.reelViewer = rv ? {
      minSwipe: typeof rv.posterOnSwipeMin === 'number' ? rv.posterOnSwipeMin : 1,
      maxSwipe: typeof rv.posterOnSwipeMax === 'number' ? rv.posterOnSwipeMax : 4,
      windowMs: (typeof rv.windowMinutes === 'number' ? rv.windowMinutes : 10) * 60000,
      cooldownMs: (typeof rv.cooldownMinutes === 'number' ? rv.cooldownMinutes : 60) * 60000
    } : null;
    var ss = rules.settingsSection;
    var ssRe = ss && toRegex(ss.path);
    c.settingsSection = ssRe && ss.after && ss.after.length ? { re: ssRe, after: textSet(ss.after) } : null;
    // Text nodes longer than any matcher (plus slack for whitespace) can be skipped cheaply.
    c.maxTextLength = maxLen + 8;
    return c;
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

  // ---------------------------------------------------------------- page state

  function pageActive(on) {
    if (!on || on === 'always') return true;
    if (on === 'messagesOnly') return state.mode === MODE_MESSAGES;
    if (on === 'full') return state.mode === MODE_FULL;
    var c = state.c;
    for (var i = 0; i < c.pages.length; i++) if (c.pages[i].name === on) return c.pages[i].re.test(state.path);
    return false;
  }

  function ensureStyle() {
    var parent = document.head || document.documentElement;
    if (!parent) return;
    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = '__tb_style';
    }
    if (styleCss !== state.css) {
      styleEl.textContent = state.css;
      styleCss = state.css;
    }
    if (styleEl.parentNode !== parent) parent.appendChild(styleEl);
  }

  function applyClasses() {
    var root = document.documentElement;
    if (!root) return;
    var want = {};
    want[state.mode === MODE_MESSAGES ? 'tb-messages-only' : 'tb-full'] = true;
    state.c.pages.forEach(function (p) {
      if (p.re.test(state.path)) want['tb-on-' + p.name] = true;
    });
    if (state.leaving) want[CLASS_BLANK] = true;
    if (exploreAllowed()) want[CLASS_EXPLORE_OPEN] = true;
    var current = Array.prototype.slice.call(root.classList);
    current.forEach(function (cl) {
      if (cl.indexOf('tb-') === 0 && !want[cl]) root.classList.remove(cl);
    });
    Object.keys(want).forEach(function (cl) {
      if (!root.classList.contains(cl)) root.classList.add(cl);
    });
  }

  var VIEWPORT_LOCK = { 'maximum-scale': '1', 'user-scalable': 'no' };
  /** No pinch zoom: keep Instagram's viewport settings and add the zoom lock. */
  // ---------------------------------------------------------------- Throwback's own entry point

  /** Throwback's mark as a line icon, drawn in the page's text colour like Instagram's icons. */
  var THROWBACK_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M5.4 10.4A7.2 7.2 0 1 1 5.2 15.6"/><path d="M4.8 5.4v5h5"/></svg>';

  /**
   * On the user's own profile (the only profile whose top bar has Instagram's settings gear), the
   * Threads link at the top-right is hidden and Throwback's settings icon takes its place.
   */
  // ---------------------------------------------------------------- call button in chats

  /*
   * Instagram's mobile website has no call buttons in chats (only the desktop site does), so a
   * phone icon goes in the chat's top bar, before "Conversation information". Tapping it asks the
   * app to open this chat in the real Instagram app (with a short guard pass) to start the call.
   */
  var CALL_ENTRY_ID = '__tb_call_entry';
  var CALL_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h3l1.6 4-2 1.3a11 11 0 0 0 ' +
    '5.1 5.1l1.3-2 4 1.6v3a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/></svg>';

  function updateCallEntry() {
    var existing = document.getElementById(CALL_ENTRY_ID);
    var m = /^\/direct\/t\/(\d{5,40})\/?$/.exec(location.pathname);
    var info = m && document.querySelector('svg[aria-label="Conversation information"]');
    var infoBtn = info && (info.closest('[role="button"], button, a') || info.parentElement);
    if (!infoBtn || !infoBtn.parentNode || infoBtn.getBoundingClientRect().top > 120) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      return;
    }
    // The other person's username (one-to-one chats): the profile link in the top bar.
    var user = '';
    var bar = infoBtn.parentNode;
    for (var up = 0; bar && up < 4 && !user; up++, bar = bar.parentNode) {
      var links = bar.querySelectorAll ? bar.querySelectorAll('a[href]') : [];
      for (var i = 0; i < links.length && !user; i++) {
        var um = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(links[i].getAttribute('href') || '');
        if (um && links[i].getBoundingClientRect().top < 120) user = um[1];
      }
    }
    if (existing && existing.isConnected && existing.nextSibling === infoBtn) {
      existing.setAttribute('data-thread', m[1]);
      existing.setAttribute('data-user', user);
      return;
    }
    var size = Math.round(infoBtn.getBoundingClientRect().width) || 40;
    var btn = existing || document.createElement('div');
    btn.id = CALL_ENTRY_ID;
    btn.setAttribute('role', 'button');
    btn.setAttribute('tabindex', '0');
    btn.setAttribute('aria-label', 'Call in the Instagram app');
    btn.setAttribute('data-thread', m[1]);
    btn.setAttribute('data-user', user);
    btn.innerHTML = CALL_ICON;
    btn.style.cssText = 'display:flex;align-items:center;justify-content:center;cursor:pointer;flex:none;' +
      'width:' + size + 'px;height:' + size + 'px;color:' + getComputedStyle(info).color +
      ';-webkit-tap-highlight-color:transparent';
    if (!existing) {
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        send({ t: 'callInApp', thread: btn.getAttribute('data-thread') || '', user: btn.getAttribute('data-user') || '' });
      }, true);
    }
    infoBtn.parentNode.insertBefore(btn, infoBtn);
  }

  function updateProfileEntry() {
    var existing = document.getElementById(PROFILE_ENTRY_ID);
    // Only profile-shaped paths (/name/): not a whole-document query every frame on every page.
    if (!existing && !/^\/(?!(explore|direct|reels?|accounts|p|stories)\/?$)[^\/]+\/?$/.test(location.pathname)) return;
    var gear = state.mode === MODE_MESSAGES ? null : document.querySelector('a[href^="/accounts/settings/"]');
    var gearBox = gear && gear.getBoundingClientRect();
    if (!gear || gearBox.width === 0 || gearBox.top > 80) {
      if (existing) existing.parentNode.removeChild(existing);
      return;
    }
    if (existing && existing.isConnected) return;
    // The other top-bar button, at the far right of the same bar (the Threads link).
    var threads = null;
    for (var up = gear.parentElement, depth = 0; up && depth < 6 && !threads; up = up.parentElement, depth++) {
      var candidates = up.querySelectorAll('a, [role="link"], [role="button"]');
      for (var i = 0; i < candidates.length; i++) {
        var c = candidates[i];
        if (c === gear || c.id === PROFILE_ENTRY_ID || !c.querySelector('svg')) continue;
        var box = c.getBoundingClientRect();
        if (box.width > 0 && box.width <= 48 && box.top < 80 && box.left > window.innerWidth * 0.7) {
          threads = c;
          break;
        }
      }
    }
    if (!threads || !threads.parentNode) return;
    var size = Math.round(threads.getBoundingClientRect().width) || 24;
    // Match Instagram's own top-bar icons (the gear), not the link colour of the Threads link.
    var gearIcon = gear.querySelector('svg');
    var color = getComputedStyle(gearIcon || gear).color;
    threads.setAttribute(ATTR_THREADS, '1');
    var btn = document.createElement('a');
    btn.id = PROFILE_ENTRY_ID;
    btn.href = '#';
    btn.setAttribute('role', 'button');
    btn.setAttribute('aria-label', 'Throwback settings');
    btn.innerHTML = THROWBACK_ICON;
    btn.style.cssText = 'display:flex;align-items:center;justify-content:center;cursor:pointer;' +
      'width:' + size + 'px;height:' + size + 'px;color:' + color + ';-webkit-tap-highlight-color:transparent';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      send({ t: 'settings' });
    }, true);
    threads.parentNode.insertBefore(btn, threads.nextSibling);
  }

  // ---------------------------------------------------------------- settings-page section

  /*
   * On Instagram's settings page, a "Throwback" section right after "Your account" (rules:
   * settingsSection) opens Throwback's settings. Styled from Instagram's own heading and rows, so it
   * looks native; re-added on the next pass if Instagram re-renders the page.
   */
  var SETTINGS_SECTION_ID = '__tb_settings_section';

  function updateSettingsSection() {
    var ss = state.c && state.c.settingsSection;
    var existing = document.getElementById(SETTINGS_SECTION_ID);
    if (!ss || state.mode === MODE_MESSAGES || !ss.re.test(location.pathname)) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      return;
    }
    if (existing && existing.isConnected) return;
    var heading = findExactText(ss.after);
    if (!heading) return;
    var place = sectionPlacement(heading);
    if (!place) return;
    place.parent.insertBefore(buildSettingsSection(heading, place.sample), place.before);
  }

  /** The element whose whole text is one of the given texts (deepest match, in reading order). */
  function findExactText(set) {
    var root = document.querySelector('main') || document.body;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) {
      var el = t.parentElement;
      if (el && !el.closest('#' + SETTINGS_SECTION_ID) && set.has(norm(el.textContent || ''))) return el;
    }
    return null;
  }

  /**
   * Where the section goes. Usually the heading sits in a block with its rows: insert after that block.
   * If headings and rows are flat siblings in one long list, insert after the row following the heading.
   */
  function sectionPlacement(heading) {
    var label = (heading.textContent || '').trim();
    var n = heading;
    for (var depth = 0; depth < 8; depth++) {
      var p = n.parentElement;
      if (!p || p === document.body || p.tagName === 'MAIN') return null;
      var text = (p.textContent || '').trim();
      if (text.length > label.length + 2) {
        if (text.length <= 400 && p.parentElement) return { parent: p.parentElement, before: p.nextSibling, sample: p };
        var row = n.nextElementSibling;
        return { parent: p, before: row ? row.nextSibling : n.nextSibling, sample: row || p };
      }
      n = p;
    }
    return null;
  }

  /** Text styles of the first two text lines in sample after the heading (a row's label and subtitle). */
  function rowTextStyles(sample, heading) {
    var out = [];
    var walker = document.createTreeWalker(sample, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t && out.length < 2; t = walker.nextNode()) {
      var el = t.parentElement;
      if (!el || !(t.textContent || '').trim() || heading.contains(el)) continue;
      var cs = getComputedStyle(el);
      out.push({ size: cs.fontSize, weight: cs.fontWeight, color: cs.color });
    }
    return out;
  }

  function buildSettingsSection(heading, sample) {
    var hs = getComputedStyle(heading);
    var rows = rowTextStyles(sample, heading);
    var label = rows[0] || { size: '15px', weight: '400', color: hs.color };
    var sub = rows[1] || { size: '13px', weight: '400', color: label.color };
    var left = Math.max(12, Math.round(heading.getBoundingClientRect().left)) + 'px';

    var wrap = document.createElement('div');
    wrap.id = SETTINGS_SECTION_ID;
    wrap.style.cssText = 'font-family:inherit;padding:8px 0';
    var title = document.createElement('div');
    title.textContent = 'Throwback';
    title.style.cssText = 'font-size:' + hs.fontSize + ';font-weight:' + hs.fontWeight + ';color:' + hs.color +
      ';padding:12px ' + left + ' 8px ' + left;
    var row = document.createElement('div');
    row.setAttribute('role', 'button');
    row.setAttribute('tabindex', '0');
    row.setAttribute('aria-label', 'Throwback settings');
    row.style.cssText = 'display:flex;align-items:center;gap:12px;padding:12px ' + left + ';cursor:pointer;' +
      'color:' + label.color + ';-webkit-tap-highlight-color:transparent';
    row.innerHTML =
      '<span style="display:flex;width:24px;height:24px;flex:none">' + THROWBACK_ICON + '</span>' +
      '<span style="display:flex;flex-direction:column;gap:2px;flex:1;min-width:0">' +
      '<span style="font-size:' + label.size + ';font-weight:' + label.weight + '">Throwback settings</span>' +
      '<span style="font-size:' + sub.size + ';color:' + sub.color + (rows[1] ? '' : ';opacity:.65') +
      '">Time limit, mindful pause, messages &amp; calls</span></span>' +
      '<svg aria-hidden="true" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="opacity:.5;flex:none">' +
      '<path d="m9 6 6 6-6 6"/></svg>';
    row.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      send({ t: 'settings' });
    }, true);
    row.addEventListener('touchstart', function () {
      row.style.background = 'rgba(128,128,128,.14)';
    }, { passive: true });
    ['touchend', 'touchcancel'].forEach(function (ev) {
      row.addEventListener(ev, function () {
        row.style.background = '';
      }, { passive: true });
    });
    wrap.appendChild(title);
    wrap.appendChild(row);
    return wrap;
  }

  /**
   * Long-press on the Home tab of the bottom bar opens Throwback's settings (a normal tap still goes
   * home). The Home tab is the bottom-bar link to "/" (any feed variant).
   */
  var LONG_PRESS_MS = 550;
  var longPress = { timer: 0, fired: false, x: 0, y: 0 };
  function homeTabFrom(target) {
    var el = target && target.closest ? target.closest('a[href="/"], a[href^="/?"]') : null;
    if (!el) return null;
    var box = el.getBoundingClientRect();
    return box.top > window.innerHeight - 120 ? el : null; // only the bottom bar, not header links
  }
  function cancelLongPress() {
    if (longPress.timer) clearTimeout(longPress.timer);
    longPress.timer = 0;
  }
  function installHomeLongPress() {
    document.addEventListener('touchstart', function (e) {
      longPress.fired = false;
      if (state.mode === MODE_MESSAGES || !e.touches || e.touches.length !== 1) return;
      if (!homeTabFrom(e.target)) return;
      longPress.x = e.touches[0].clientX;
      longPress.y = e.touches[0].clientY;
      cancelLongPress();
      longPress.timer = setTimeout(function () {
        longPress.timer = 0;
        longPress.fired = true;
        send({ t: 'settings' });
      }, LONG_PRESS_MS);
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', function (e) {
      if (!longPress.timer || !e.touches || !e.touches.length) return;
      var dx = e.touches[0].clientX - longPress.x, dy = e.touches[0].clientY - longPress.y;
      if (dx * dx + dy * dy > 100) cancelLongPress(); // moved more than ~10px: not a press
    }, { capture: true, passive: true });
    document.addEventListener('touchend', cancelLongPress, { capture: true, passive: true });
    document.addEventListener('touchcancel', cancelLongPress, { capture: true, passive: true });
    // After a long-press, swallow the tap that follows so the feed doesn't reload, and the
    // browser's own long-press menu.
    // Registered on window before Throwback's own click handler, so it runs first and can stop it.
    ['click', 'contextmenu'].forEach(function (type) {
      window.addEventListener(type, function (e) {
        if (longPress.fired && homeTabFrom(e.target)) {
          e.preventDefault();
          e.stopImmediatePropagation();
          if (type === 'click') longPress.fired = false;
        }
      }, true);
    });
  }

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
      clearFeedBottom();
      return;
    }
    // The data filter knows the exact end (only suggestions left): the card goes right after the last post.
    if (feedDataState.ended) {
      state.dataEnd = true;
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
    warmUpcoming(list);
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
    state.bottomSeen = realPostKeys(list);
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

  function realPostKeys(list) {
    var keys = [];
    for (var v = list.firstElementChild; v; v = v.nextElementSibling) {
      if (v.tagName !== 'ARTICLE' || v.hasAttribute(ATTR_POST)) continue;
      var key = postKey(v);
      if (key) keys.push(key);
    }
    return keys;
  }

  function hasFreshRealPost(list) {
    var keys = realPostKeys(list);
    for (var i = 0; i < keys.length; i++) {
      if (state.bottomSeen.indexOf(keys[i]) < 0) return true;
    }
    return false;
  }

  function clearFeedBottom() {
    var root = document.documentElement;
    var card = document.getElementById(END_CARD_ID);
    if (card && card.parentNode) card.parentNode.removeChild(card);
    root.removeAttribute(ATTR_FEED_END);
    root.removeAttribute(ATTR_FEED_GAP);
    if (state.bottomTimer) clearInterval(state.bottomTimer);
    state.bottomTimer = 0;
    state.atBottom = false;
    state.pastSince = 0;
  }

  function showEndCard(list) {
    var root = document.documentElement;
    var card = document.getElementById(END_CARD_ID);
    if (!card) {
      if (!state.caughtUpSent) {
        state.caughtUpSent = true;
        send({ t: 'caughtUp' });
      }
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
  }

  function fixViewport() {
    var head = document.head;
    if (!head) return;
    var meta = head.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      meta.setAttribute('content', 'width=device-width, initial-scale=1');
      head.appendChild(meta);
    }
    var content = meta.getAttribute('content') || '';
    var parts = {};
    var order = [];
    content.split(',').forEach(function (kv) {
      var i = kv.indexOf('=');
      var k = (i < 0 ? kv : kv.slice(0, i)).trim().toLowerCase();
      if (!k) return;
      if (!(k in parts)) order.push(k);
      parts[k] = i < 0 ? '' : kv.slice(i + 1).trim();
    });
    Object.keys(VIEWPORT_LOCK).forEach(function (k) {
      if (!(k in parts)) order.push(k);
      parts[k] = VIEWPORT_LOCK[k];
    });
    var next = order.map(function (k) {
      return parts[k] === '' ? k : k + '=' + parts[k];
    }).join(', ');
    if (next !== content) meta.setAttribute('content', next);
  }

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
    } else if (!r.kind) {
      // Instagram keeps undoing a plain normalisation: give up and show the page as it is.
      state.leaving = false;
      applyClasses();
    } // Blocked content in a loop stays blank; the user can go back.
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

  /** @param source 'push', 'replace', 'pop', 'start' or 'configure' */
  function onLocation(source) {
    var href = location.href;
    if (href === state.href) return;
    var previousPath = state.path;
    state.href = href;
    state.path = location.pathname;
    if (state.leaving) return;
    if (clipPlayer && !state.clipNav && state.path !== previousPath) closeClipPlayer(false);
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
    if (!pageActive('stories')) {
      stories.store = {};
      stories.images = {};
      stories.current = -1;
    } else {
      noteStoryLocation();
    }
    if (!pageActive('home')) {
      feedAhead.store = {};
      feedAhead.images = {};
      feedAhead.current = -1;
    }
    state.bgDirty = true;
    if (state.textScanSkipped && textScanActive()) {
      // Back from DMs or stories: catch up on what changed while text scanning was off.
      state.textScanSkipped = false;
      scanAll();
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

  /*
   * Posting through the Instagram app (owner, 2026-11-20): with the app connected, "+" and "Your
   * story" open its create screen (better editor: music, filters, reels) instead of the website's.
   * The app's guard allows only those screens. Buttons from the rules (appCreate.triggers).
   */
  function createInAppTap(e) {
    var ac = state.c && state.c.appCreate;
    if (!state.createInApp || !ac || state.mode === MODE_MESSAGES || !e.target || !e.target.closest) return false;
    var hit = false;
    for (var i = 0; i < ac.triggers.length && !hit; i++) {
      var t = ac.triggers[i];
      if (t.selector) {
        var el = e.target.closest('[role=button],button,a');
        hit = !!(el && (el.matches(t.selector) || el.querySelector(t.selector)));
      } else if (t.text && t.text.length) {
        var b = e.target.closest('[role=button],button');
        hit = !!(b && t.text.indexOf((b.textContent || '').trim()) !== -1 && (!t.fileInput || b.querySelector('input[type=file]')));
      }
    }
    if (!hit) return false;
    e.preventDefault();
    e.stopImmediatePropagation();
    send({ t: 'createInApp' });
    return true;
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

  // ---------------------------------------------------------------- filtering

  function scanHide(root) {
    var c = state.c;
    c.hide.forEach(function (h) {
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
        checkTextElement(el, 0);
      }
    }
  }

  /** Checks an element whose whole text may be a label; also its parent (labels split over spans). */
  function checkTextElement(el, depth) {
    var c = state.c;
    if (el.childElementCount > 6) return; // a container, not a label (and textContent would be costly)
    var text = el.textContent;
    if (!text || text.length > c.maxTextLength) return;
    var t = norm(text);
    if (!t) return;
    var i;
    for (i = 0; i < c.labels.length; i++) {
      if (c.labels[i].set.has(t)) {
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
    if (depth < 2 && parent && !parent.querySelector('img, video')) checkTextElement(parent, depth + 1);
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

  // ---------------------------------------------------------------- navigation timing

  /*
   * How long screens take to open (stories, messages): tap → address change → page settled (no DOM
   * changes for PERF_SETTLE_MS). Page kinds and times only, kept in memory (last 30), read through
   * DevTools with __throwback.perf(). Nothing is sent anywhere.
   */
  var PERF_SETTLE_MS = 300;
  var perf = { lastTap: 0, rec: null, lastChange: 0, timer: 0, log: [] };

  function pageKind(path) {
    var c = state.c;
    for (var i = 0; c && i < c.pages.length; i++) if (c.pages[i].re.test(path)) return c.pages[i].name;
    return '/' + (path.split('/')[1] || '');
  }

  function perfStart(path) {
    var now = performance.now();
    var tap = now - perf.lastTap < 2000 ? perf.lastTap : now;
    perf.rec = { page: pageKind(path), tapToUrl: Math.round(now - tap), tap: tap };
    if (!state.readySent) perf.rec.tap = 0; // the page's own first load: count everything since it began
    perf.lastChange = now;
    perfCheck();
  }

  function perfChanged() {
    if (perf.rec) perf.lastChange = performance.now();
  }

  function perfCheck() {
    clearTimeout(perf.timer);
    perf.timer = setTimeout(function () {
      var rec = perf.rec;
      if (!rec) return;
      var idle = performance.now() - perf.lastChange;
      if (idle < PERF_SETTLE_MS) return perfCheck();
      rec.tapToSettled = Math.round(perf.lastChange - rec.tap);
      if (WARM_PAGES.indexOf(rec.page) !== -1) learnDmAssets(rec.tap - 50, perf.lastChange, rec);
      delete rec.tap;
      perf.log.push(rec);
      if (perf.log.length > 30) perf.log.shift();
      perf.rec = null;
    }, PERF_SETTLE_MS);
  }

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
  var reels = { current: null, viewer: null, events: [] };

  /** Reel-limit events with timestamps (last 100), read with __throwback.reelEvents() over DevTools. */
  function reelEvent(what, extra) {
    var e = { t: new Date().toISOString().slice(11, 23), what: what };
    if (extra) for (var k in extra) e[k] = extra[k];
    reels.events.push(e);
    if (reels.events.length > 100) reels.events.shift();
  }

  function reelLog() {
    var log = null;
    try {
      log = JSON.parse(localStorage.getItem(REELS_KEY) || 'null');
    } catch (e) {
      log = null;
    }
    if (!log || !Array.isArray(log.swipes)) log = { swipes: [], poster: 0, limit: 0 };
    return log;
  }

  function saveReelLog(log) {
    try {
      localStorage.setItem(REELS_KEY, JSON.stringify(log));
    } catch (e) {
      // Storage unavailable: the limit still works for this page.
    }
  }

  /** The swipe viewer around a video: a vertical scroll-snap container holding several videos. */
  function reelViewerOf(video) {
    var n = video.parentElement;
    for (var depth = 0; n && depth < 14 && n !== document.body; depth++, n = n.parentElement) {
      var snap = getComputedStyle(n).scrollSnapType || '';
      if (snap.indexOf('y') === 0 && n.querySelectorAll('video').length >= 2) return n;
    }
    return null;
  }

  function onMediaPlay(e) {
    var v = e.target;
    var rv = state.c && state.c.reelViewer;
    if (!rv || !v || v.tagName !== 'VIDEO' || pageActive('stories') || clipPlayer) return;
    var viewer = reelViewerOf(v);
    if (!viewer) return;
    if (viewer.hasAttribute(ATTR_REEL_STOP)) {
      v.pause();
      return;
    }
    if (reels.current === v) return; // the same reel looping
    var opened = reels.viewer !== viewer || !viewer.isConnected;
    reels.current = v;
    reels.viewer = viewer;
    if (opened) {
      reelEvent('opened');
      return; // the reel that was opened is free
    }
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
    reelEvent('swipe', { n: log.swipes.length, limit: log.limit, cooling: !!cooling });
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

  // Instagram's label on the viewer's close button (top left), in the languages the rules cover.
  var CLOSE_LABELS = ['Close', 'Schließen', 'Fermer', 'Cerrar', 'Fechar', 'Chiudi'];

  /**
   * Stops the viewer (no more swiping, videos paused), closes it with its own Close button so the
   * poster appears over the chat, and asks the app for the poster. Closing is done here, by the page,
   * at once: an app-to-page request after the poster's button proved unreliable on the phone, while
   * clicking the viewer's Close closes it every time (Instagram adds no history entry for it).
   */
  function stopReels(viewer) {
    reelEvent('trigger');
    viewer.setAttribute(ATTR_REEL_STOP, '1');
    var vids = viewer.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) vids[i].pause();
    send({ t: 'reelLimit' });
    setTimeout(function () {
      var closed = closeReelViewer();
      reelEvent('close', { ok: closed });
      if (!closed) {
        setTimeout(function () {
          perf.log.push({ page: 'reel-poster', viewerClosed: closeReelViewer(), retried: true });
        }, 400);
      } else {
        perf.log.push({ page: 'reel-poster', viewerClosed: true });
      }
    }, 50);
  }

  /**
   * Clicks the viewer's own Close: the visible, top-most element labelled Close (the chat underneath
   * has hidden ones too). True when a stopped viewer was there and the button was found.
   */
  function closeReelViewer() {
    var viewer = document.querySelector('[' + ATTR_REEL_STOP + ']');
    if (!viewer || !viewer.isConnected) return false;
    var best = null;
    var bestScore = Infinity;
    CLOSE_LABELS.forEach(function (label) {
      var els = document.querySelectorAll('[aria-label="' + label + '"]');
      for (var i = 0; i < els.length; i++) {
        var b = els[i].closest('[role="button"], button, a') || els[i];
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
    });
    if (!best) return false;
    viewer.removeAttribute(ATTR_REEL_STOP);
    reels.viewer = null;
    reels.current = null;
    clickElement(best);
    return true;
  }

  // ---------------------------------------------------------------- Explore: a few minutes a day

  /*
   * The Explore grid shows for explore.dailySeconds a day (5 minutes), counted only while actually on
   * the Explore tab (visible, not covered). Then it is hidden as before and "Enough for today"
   * shows in its place until midnight. Search keeps working. Stored per day, so a restart doesn't
   * reset it.
   */
  var EXPLORE_KEY = '__tb_explore';
  var EXPLORE_CARD_ID = '__tb_explore_card';
  var exploreDay = null;

  function exploreLog() {
    if (!exploreDay) {
      try {
        exploreDay = JSON.parse(localStorage.getItem(EXPLORE_KEY) || 'null');
      } catch (e) {
        exploreDay = null;
      }
    }
    if (!exploreDay || exploreDay.date !== today()) exploreDay = { date: today(), used: 0 };
    return exploreDay;
  }

  function exploreAllowed() {
    var ex = state.c && state.c.explore;
    return !!(ex && ex.dailySeconds > 0 && exploreLog().used < ex.dailySeconds);
  }

  /**
   * A grid of posts: Explore itself, hashtag and place pages, keyword results (all under /explore/,
   * all on the same daily minutes). Not the search page while typing.
   */
  function onExploreGrid() {
    return /^\/explore\//.test(location.pathname) && !/^\/explore\/search\/?$/.test(location.pathname);
  }

  function startExploreClock() {
    setInterval(function () {
      var ex = state.c && state.c.explore;
      if (clipPlayer && !exploreAllowed()) closeClipPlayer(true); // time's up: the card shows on Explore
      if (!ex || !ex.dailySeconds || state.mode === MODE_MESSAGES || !(pageActive('explore') || clipPlayer)) return;
      if (document.visibilityState === 'visible' && !state.covered && exploreAllowed()) {
        var d = exploreLog();
        d.used++;
        if (d.used % 5 === 0 || d.used >= ex.dailySeconds) {
          try {
            localStorage.setItem(EXPLORE_KEY, JSON.stringify(d));
          } catch (e) {
            // Storage unavailable: counted for this page only.
          }
        }
        if (d.used >= ex.dailySeconds) applyClasses(); // time's up: the grid hides now
      }
      updateClipClock();
      updateExploreCard();
    }, 1000);
  }

  /** "Enough for today" in place of the grid, once the allowance is used up. */
  function updateExploreCard() {
    var card = document.getElementById(EXPLORE_CARD_ID);
    var show = !!(state.c && state.c.explore && state.c.explore.dailySeconds) && state.mode !== MODE_MESSAGES &&
      onExploreGrid() && !exploreAllowed();
    var main = show && document.querySelector('main');
    if (!main) {
      if (card && card.parentNode) card.parentNode.removeChild(card);
      return;
    }
    if (!card) {
      card = document.createElement('div');
      card.id = EXPLORE_CARD_ID;
      card.setAttribute('role', 'status');
      card.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:8px;padding:56px 28px 64px;' +
        'text-align:center;color:inherit;font-family:inherit';
      card.innerHTML =
        '<div style="font-size:22px;font-weight:700">Enough for today\u2026</div>' +
        '<div style="font-size:17px;font-weight:600">Your time is yours. Keep it.</div>' +
        '<div style="font-size:16px;line-height:1.4">Go have an argument with your sibling instead.</div>';
    }
    if (card.parentNode !== main) main.appendChild(card);
  }


  // ---------------------------------------------------------------- reels from Explore and search

  /*
   * Reels from Explore or search (owner, 2026-11-20). Instagram's mobile site has no single-reel
   * player: a reel tile opens as a static post (checked with Throwback's script off; /reel/<id>/ is
   * turned into /p/<id>/ by Instagram itself). So, while today's Explore minutes last, tapping a reel
   * tile there opens Throwback's own full-screen swipe player with the reels from that page, in the
   * order shown. Everything it needs (MP4 address, poster, username, caption) is already in the data
   * Explore loaded; videos come from Instagram's CDN like any video; no extra logged-in requests.
   * The time counts toward the 5 minutes; when they run out the player closes and "Enough for
   * today" shows. Back or ✕ closes it. "Open post" shows the post (likes, comments, profile).
   */
  var CLIP_MAX = 300;
  var CLIP_ID = '__tb_clips';
  var clips = { byCode: {}, order: [] };
  var clipPlayer = null;
  var CDN_HOST = /(^|\.)(cdninstagram\.com|fbcdn\.net)$/;

  function onExploreOrSearch() {
    return /^\/explore\//.test(location.pathname);
  }

  function cdnUrl(url) {
    var u = typeof url === 'string' && parseUrl(url);
    return !!(u && u.protocol === 'https:' && CDN_HOST.test(u.hostname));
  }

  function learnClips(o, depth) {
    if (!o || typeof o !== 'object' || depth > 14) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) learnClips(o[i], depth + 1);
      return;
    }
    if (typeof o.code === 'string' && Array.isArray(o.video_versions) && o.video_versions.length) {
      addClip(o);
      return;
    }
    for (var k in o) {
      if (Object.prototype.hasOwnProperty.call(o, k) && o[k] && typeof o[k] === 'object') learnClips(o[k], depth + 1);
    }
  }

  /** The smallest version at least as wide as the screen (capped at 720 px), else the largest. */
  function pickClipVideo(list) {
    var want = Math.min(720, Math.round((window.innerWidth || 400) * (window.devicePixelRatio || 2)));
    var best = null;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || !cdnUrl(v.url)) continue;
      var w = v.width || 0;
      if (!best) best = v;
      else if (w >= want ? (best.width < want || w < best.width) : (best.width < want && w > best.width)) best = v;
    }
    return best;
  }

  function addClip(m) {
    if (!/^[A-Za-z0-9_-]{5,40}$/.test(m.code) || clips.byCode[m.code]) return;
    var v = pickClipVideo(m.video_versions);
    if (!v) return;
    var cands = m.image_versions2 && m.image_versions2.candidates;
    var poster = cands && cands[0] && cdnUrl(cands[0].url) ? cands[0].url : null;
    clips.byCode[m.code] = {
      code: m.code,
      video: v.url,
      poster: poster,
      user: m.user && typeof m.user.username === 'string' ? m.user.username : '',
      caption: m.caption && typeof m.caption.text === 'string' ? m.caption.text.slice(0, 500) : '',
      liked: !!m.has_liked,
      likes: typeof m.like_count === 'number' ? m.like_count : null,
      comments: typeof m.comment_count === 'number' ? m.comment_count : null,
      saved: !!m.has_viewer_saved,
    };
    clips.order.push(m.code);
    if (clips.order.length > CLIP_MAX) delete clips.byCode[clips.order.shift()];
  }

  function tileCode(a) {
    var m = a && /^\/p\/([A-Za-z0-9_-]{5,40})\/?$/.exec(a.getAttribute('href') || '');
    return m ? m[1] : null;
  }

  /** Reel codes in the order the page shows them, then any others Explore loaded. */
  function clipList() {
    var seen = {};
    var out = [];
    var tiles = document.querySelectorAll('main a[href^="/p/"]');
    for (var i = 0; i < tiles.length; i++) {
      var c = tileCode(tiles[i]);
      if (c && clips.byCode[c] && !seen[c]) {
        seen[c] = 1;
        out.push(c);
      }
    }
    for (var j = 0; j < clips.order.length; j++) {
      if (!seen[clips.order[j]]) {
        seen[clips.order[j]] = 1;
        out.push(clips.order[j]);
      }
    }
    return out;
  }

  function openClipFromTap(e) {
    if (clipPlayer || state.clipBypass || state.mode === MODE_MESSAGES || !onExploreOrSearch() || !exploreAllowed()) return false;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    var code = tileCode(a);
    if (!code || !clips.byCode[code]) return false;
    e.preventDefault();
    e.stopImmediatePropagation();
    openClipPlayer(code);
    return true;
  }

  var CLIP_CSS =
    '#' + CLIP_ID + '{position:fixed;inset:0;z-index:2147483600;background:#000;color:#fff;' +
      'font:14px/1.35 -apple-system,system-ui,Roboto,sans-serif;overscroll-behavior:contain}' +
    '#' + CLIP_ID + ' .tbc-strip{position:absolute;inset:0;overflow-y:scroll;scroll-snap-type:y mandatory;' +
      'scrollbar-width:none;overscroll-behavior:contain}' +
    '#' + CLIP_ID + ' .tbc-strip::-webkit-scrollbar{display:none}' +
    '#' + CLIP_ID + ' .tbc-slide{position:relative;height:100%;scroll-snap-align:start;scroll-snap-stop:always;' +
      'display:flex;align-items:center;justify-content:center;overflow:hidden}' +
    '#' + CLIP_ID + ' video{width:100%;height:100%;object-fit:cover;background:#000}' +
    '#' + CLIP_ID + ' .tbc-info{position:absolute;left:0;right:0;bottom:0;padding:48px 16px 24px;' +
      'background:linear-gradient(transparent,rgba(0,0,0,.65));pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-user{font-weight:600;font-size:15px}' +
    '#' + CLIP_ID + ' .tbc-cap{margin-top:4px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;' +
      'overflow:hidden;opacity:.92;white-space:pre-line}' +
    '#' + CLIP_ID + ' .tbc-top{position:absolute;left:0;right:0;top:0;display:flex;align-items:center;' +
      'justify-content:space-between;padding:12px 8px 24px 4px;background:linear-gradient(rgba(0,0,0,.45),transparent);' +
      'pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-close{pointer-events:auto;width:48px;height:48px;border:0;background:transparent;' +
      'color:#fff;display:flex;align-items:center;justify-content:center}' +
    '#' + CLIP_ID + ' .tbc-left{font-weight:600;font-size:13px;padding:6px 10px;border-radius:999px;' +
      'background:rgba(0,0,0,.4);font-variant-numeric:tabular-nums}' +
    '#' + CLIP_ID + ' .tbc-mute{position:absolute;top:50%;left:50%;width:64px;height:64px;margin:-32px 0 0 -32px;' +
      'border-radius:50%;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;' +
      'opacity:0;transition:opacity .2s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-mute.on{opacity:1}' +
    '#' + CLIP_ID + ' .tbc-info{right:72px}' +
    '#' + CLIP_ID + ' .tbc-user{pointer-events:auto;background:none;border:0;color:#fff;padding:6px 0;font:inherit;font-weight:600;font-size:15px}' +
    '#' + CLIP_ID + ' .tbc-side{position:absolute;right:6px;bottom:28px;display:flex;flex-direction:column;align-items:center;gap:14px}' +
    '#' + CLIP_ID + ' .tbc-act{width:52px;min-height:48px;border:0;background:none;color:#fff;display:flex;flex-direction:column;' +
      'align-items:center;gap:2px;font:600 12px/1.2 -apple-system,system-ui,Roboto,sans-serif;text-shadow:0 1px 2px rgba(0,0,0,.5);padding:0}' +
    '#' + CLIP_ID + ' .tbc-act svg{filter:drop-shadow(0 1px 2px rgba(0,0,0,.45))}' +
    '#' + CLIP_ID + ' .tbc-act.on .tbc-like-ic{color:#ff3040}' +
    '#' + CLIP_ID + ' .tbc-pop{position:absolute;top:50%;left:50%;width:96px;height:96px;margin:-48px 0 0 -48px;color:#fff;' +
      'opacity:0;transform:scale(.6);transition:opacity .15s,transform .25s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-pop.on{opacity:1;transform:scale(1)}' +
    '#' + CLIP_ID + ' .tbc-toast{position:absolute;left:16px;right:16px;bottom:120px;text-align:center;font-size:14px;' +
      'padding:10px 12px;border-radius:10px;background:rgba(0,0,0,.7);opacity:0;transition:opacity .2s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-toast.on{opacity:1}' +
    '@media (prefers-reduced-motion: reduce){#' + CLIP_ID + ' .tbc-pop{transition:none}}';

  var ICON_CLOSE = '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M5 5l14 14M19 5L5 19"/></svg>';
  var ICON_MUTED = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5L6 9H3v6h3l5 4z"/>' +
    '<path d="M22 9l-6 6M16 9l6 6"/></svg>';
  var ICON_SOUND = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5L6 9H3v6h3l5 4z"/>' +
    '<path d="M15.5 8.5a5 5 0 010 7M18.5 5.5a9 9 0 010 13"/></svg>';

  // Plain line icons (drawn for Throwback).
  var ICON_HEART = '<svg class="tbc-like-ic" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M12 20.5s-7.5-4.6-9.2-9.3C1.7 8.1 3.6 4.5 7.1 4.5c2.1 0 ' +
    '3.6 1.2 4.9 2.9 1.3-1.7 2.8-2.9 4.9-2.9 3.5 0 5.4 3.6 4.3 6.7-1.7 4.7-9.2 9.3-9.2 9.3z"/></svg>';
  var ICON_HEART_FILLED = ICON_HEART.replace('fill="none" stroke="currentColor"', 'fill="currentColor" stroke="currentColor"')
    .replace('width="30" height="30"', 'width="96" height="96"');
  var ICON_HEART_ON = ICON_HEART.replace('fill="none"', 'fill="currentColor"');
  var ICON_COMMENT = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linejoin="round" aria-hidden="true"><path d="M20.5 12a8.5 8.5 0 0 1-12.4 7.5L3.5 20.5l1-4.4A8.5 8.5 0 1 1 20.5 12z"/></svg>';
  var ICON_SHARE = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linejoin="round" aria-hidden="true"><path d="M21.5 3.5 2.5 10.6l7.3 2.9 2.9 7.4z"/><path d="M21.5 3.5 9.8 13.5"/></svg>';
  var ICON_SAVE = '<svg class="tbc-save-ic" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M18.5 21 12 15.5 5.5 21V4.5a1 1 0 0 1 1-1h11a1 1 0 0 1 1 1z"/></svg>';
  var ICON_SAVE_ON = ICON_SAVE.replace('fill="none"', 'fill="currentColor"');

  function countText(n) {
    if (typeof n !== 'number') return '';
    if (n >= 1e6) return (Math.round(n / 1e5) / 10) + 'M';
    if (n >= 1e4) return Math.round(n / 1e3) + 'K';
    if (n >= 1e3) return (Math.round(n / 100) / 10) + 'K';
    return String(n);
  }

  function clipActButton(label, icon, count, onTap) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'tbc-act';
    b.setAttribute('aria-label', label);
    b.innerHTML = icon + '<span>' + countText(count) + '</span>';
    b.addEventListener('click', function (e) {
      e.stopPropagation();
      onTap(b);
    });
    return b;
  }

  /** The right-hand buttons: like, comments, share, save (Instagram's reel layout). */
  function clipSide(code) {
    var c = clips.byCode[code];
    var side = document.createElement('div');
    side.className = 'tbc-side';
    var like = clipActButton('Like', c.liked ? ICON_HEART_ON : ICON_HEART, c.likes, function () {
      setClipLiked(code, !clips.byCode[code].liked);
    });
    if (c.liked) like.classList.add('on');
    like.setAttribute('data-tbc', 'like');
    side.appendChild(like);
    side.appendChild(clipActButton('Comments', ICON_COMMENT, c.comments, function () {
      openClipPath('/p/' + code + '/comments/', code);
    }));
    side.appendChild(clipActButton('Share', ICON_SHARE, null, function () {
      send({ t: 'share', url: 'https://www.instagram.com/reel/' + code + '/' });
    }));
    var save = clipActButton('Save', c.saved ? ICON_SAVE_ON : ICON_SAVE, null, function () {
      setClipSaved(code, !clips.byCode[code].saved);
    });
    save.setAttribute('data-tbc', 'save');
    side.appendChild(save);
    return side;
  }

  function clipSlideOf(code) {
    var p = clipPlayer;
    return p ? p.slides[p.list.indexOf(code)] : null;
  }

  function showClipLiked(code) {
    var c = clips.byCode[code];
    var b = clipSlideOf(code) && clipSlideOf(code).querySelector('[data-tbc=like]');
    if (!b) return;
    b.classList.toggle('on', c.liked);
    b.innerHTML = (c.liked ? ICON_HEART_ON : ICON_HEART) + '<span>' + countText(c.likes) + '</span>';
  }

  function showClipSaved(code) {
    var c = clips.byCode[code];
    var b = clipSlideOf(code) && clipSlideOf(code).querySelector('[data-tbc=save]');
    if (b) b.innerHTML = (c.saved ? ICON_SAVE_ON : ICON_SAVE) + '<span></span>';
  }

  function setClipLiked(code, liked) {
    var c = clips.byCode[code];
    if (!c || c.liked === liked) return;
    c.liked = liked;
    if (typeof c.likes === 'number') c.likes += liked ? 1 : -1;
    showClipLiked(code); // at once; undone if Instagram's own button can't be pressed
    clipAct(code, liked ? 'like' : 'unlike', function (ok) {
      if (ok) return;
      c.liked = !liked;
      if (typeof c.likes === 'number') c.likes += liked ? -1 : 1;
      showClipLiked(code);
      clipToast(liked ? 'Couldn’t like this one. Try “Comments” to open the post.' : 'Couldn’t undo the like.');
    });
  }

  function setClipSaved(code, saved) {
    var c = clips.byCode[code];
    if (!c || c.saved === saved) return;
    c.saved = saved;
    showClipSaved(code);
    clipAct(code, saved ? 'save' : 'unsave', function (ok) {
      if (ok) {
        clipToast(saved ? 'Saved' : 'Removed from saved');
        return;
      }
      c.saved = !saved;
      showClipSaved(code);
      clipToast('Couldn’t save this one.');
    });
  }

  function clipToast(text) {
    var p = clipPlayer;
    if (!p) return;
    if (!p.toast) {
      p.toast = document.createElement('div');
      p.toast.className = 'tbc-toast';
      p.toast.setAttribute('role', 'status');
      p.root.appendChild(p.toast);
    }
    p.toast.textContent = text;
    p.toast.className = 'tbc-toast on';
    clearTimeout(p.toastTimer);
    p.toastTimer = setTimeout(function () {
      if (p.toast) p.toast.className = 'tbc-toast';
    }, 1800);
  }

  /** Single tap: sound on/off. Double tap: like (with the big heart), as on Instagram. */
  function onClipVideoTap(code) {
    var p = clipPlayer;
    if (!p) return;
    if (p.tapTimer) {
      clearTimeout(p.tapTimer);
      p.tapTimer = 0;
      var slide = clipSlideOf(code);
      var pop = slide && slide.querySelector('.tbc-pop');
      if (pop) {
        pop.className = 'tbc-pop on';
        setTimeout(function () {
          pop.className = 'tbc-pop';
        }, 600);
      }
      setClipLiked(code, true);
      return;
    }
    p.tapTimer = setTimeout(function () {
      p.tapTimer = 0;
      toggleClipSound();
    }, 260);
  }

  /** Switches Instagram's page (under the player, no reload) to [path]: its router renders it in place. */
  function clipUnderneath(path) {
    if (location.pathname === path) return;
    state.clipNav = true;
    try {
      history.replaceState(history.state, '', path);
      window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    } finally {
      state.clipNav = false;
    }
    if (clipPlayer) clipPlayer.dirty = true;
  }

  /**
   * Like/unlike/save/unsave through Instagram's own button: the post's page is rendered under the
   * player and its button is pressed, so Instagram's own code sends the request (Throwback sends
   * none). Labels come from the rules (explore.actions). done(true) once pressed.
   */
  function clipAct(code, action, done) {
    var ex = state.c && state.c.explore;
    var labels = ex && ex.actions && ex.actions[action];
    if (!labels || !labels.length || !clipPlayer) return done(false);
    clipUnderneath('/p/' + code + '/');
    var tries = 0;
    (function look() {
      if (!clipPlayer) return done(false);
      var main = document.querySelector('main');
      var btn = null;
      if (main && main.querySelector('a[href*="/' + code + '/"]')) {
        var svgs = main.querySelectorAll('svg[aria-label]');
        for (var i = 0; i < svgs.length && !btn; i++) {
          if (labels.indexOf(svgs[i].getAttribute('aria-label')) !== -1) {
            btn = svgs[i].closest('[role=button],button') || svgs[i].parentElement;
          }
        }
      }
      if (btn) {
        clickElement(btn);
        pauseUnderPlayer();
        return done(true);
      }
      if (++tries > 40) return done(false); // ~6 s
      setTimeout(look, 150);
    })();
  }

  /** Nothing under the player may play (the post page underneath autoplays its video). */
  function pauseUnderPlayer() {
    var p = clipPlayer;
    if (!p) return;
    var media = document.querySelectorAll('video, audio');
    for (var i = 0; i < media.length; i++) if (!p.root.contains(media[i]) && !media[i].paused) media[i].pause();
  }

  function onClipUnderPlay(e) {
    var p = clipPlayer;
    var m = e.target;
    if (p && m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO') && !p.root.contains(m)) m.pause();
  }

  /**
   * Leaves the player for an Instagram page (comments, a profile) without a reload; with [resume],
   * coming Back to Explore reopens the player at that reel.
   */
  function openClipPath(path, resume) {
    closeClipPlayer(true);
    setTimeout(function () {
      state.clipResume = resume ? { code: resume, at: location.pathname } : null;
      state.clipBypass = true;
      try {
        history.pushState(null, '', path);
        window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      } finally {
        state.clipBypass = false;
      }
    }, 150);
  }

  function clipText(tag, cls, text) {
    var el = document.createElement(tag);
    el.className = cls;
    el.textContent = text;
    return el;
  }

  function openClipPlayer(code) {
    var list = clipList();
    var start = Math.max(0, list.indexOf(code));
    var root = document.createElement('div');
    root.id = CLIP_ID;
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-label', 'Reels');
    var style = document.createElement('style');
    style.textContent = CLIP_CSS;
    root.appendChild(style);
    var strip = document.createElement('div');
    strip.className = 'tbc-strip';
    root.appendChild(strip);
    var top = document.createElement('div');
    top.className = 'tbc-top';
    var close = document.createElement('button');
    close.className = 'tbc-close';
    close.type = 'button';
    close.setAttribute('aria-label', 'Close');
    close.innerHTML = ICON_CLOSE;
    close.addEventListener('click', function () {
      closeClipPlayer(true);
    });
    var left = clipText('div', 'tbc-left', '');
    top.appendChild(close);
    top.appendChild(left);
    root.appendChild(top);
    var mute = document.createElement('div');
    mute.className = 'tbc-mute';
    root.appendChild(mute);
    clipPlayer = { root: root, strip: strip, left: left, mute: mute, list: [], slides: [], current: -1,
      muted: false, observer: null, muteTimer: 0 };
    for (var i = 0; i < list.length; i++) addClipSlide(list[i]);
    clipPlayer.observer = typeof IntersectionObserver === 'function' ? new IntersectionObserver(function (entries) {
      for (var k = 0; k < entries.length; k++) {
        if (entries[k].isIntersecting) setClipCurrent(clipPlayer.slides.indexOf(entries[k].target));
      }
    }, { root: strip, threshold: 0.6 }) : null;
    if (clipPlayer.observer) for (var s = 0; s < clipPlayer.slides.length; s++) clipPlayer.observer.observe(clipPlayer.slides[s]);
    (document.body || document.documentElement).appendChild(root);
    pauseAllMedia();
    try {
      history.pushState({ __tbClips: 1 }, '', location.href); // Back closes the player, not the page
      clipPlayer.pushed = true;
    } catch (e) {
      clipPlayer.pushed = false;
    }
    strip.scrollTop = start * strip.clientHeight;
    setClipCurrent(start);
    updateClipClock();
  }

  function addClipSlide(code) {
    var c = clips.byCode[code];
    if (!c || !clipPlayer || clipPlayer.list.indexOf(code) !== -1) return;
    var slide = document.createElement('section');
    slide.className = 'tbc-slide';
    var v = document.createElement('video');
    v.setAttribute('playsinline', '');
    v.loop = true;
    v.preload = 'none';
    if (c.poster) v.poster = c.poster;
    v.addEventListener('click', function () {
      onClipVideoTap(code);
    });
    slide.appendChild(v);
    var heart = document.createElement('div');
    heart.className = 'tbc-pop';
    heart.innerHTML = ICON_HEART_FILLED;
    slide.appendChild(heart);
    var info = document.createElement('div');
    info.className = 'tbc-info';
    if (c.user) {
      var user = clipText('button', 'tbc-user', c.user);
      user.type = 'button';
      user.addEventListener('click', function () {
        openClipPath('/' + c.user + '/', null);
      });
      info.appendChild(user);
    }
    if (c.caption) info.appendChild(clipText('div', 'tbc-cap', c.caption));
    slide.appendChild(info);
    slide.appendChild(clipSide(code));
    clipPlayer.strip.appendChild(slide);
    clipPlayer.list.push(code);
    clipPlayer.slides.push(slide);
    if (clipPlayer.observer) clipPlayer.observer.observe(slide);
  }

  /** Plays slide i; only it and its neighbours keep a video address (memory), the rest are emptied. */
  function setClipCurrent(i) {
    var p = clipPlayer;
    if (!p || i < 0 || i >= p.slides.length) return;
    p.current = i;
    for (var k = 0; k < p.slides.length; k++) {
      var v = p.slides[k].firstChild;
      var c = clips.byCode[p.list[k]];
      if (Math.abs(k - i) <= 1 && c) {
        if (v.getAttribute('src') !== c.video) {
          v.src = c.video;
          v.preload = k === i ? 'auto' : 'metadata';
        }
      } else if (v.hasAttribute('src')) {
        v.pause();
        v.removeAttribute('src');
        v.load();
      }
      if (k !== i && !v.paused) v.pause();
    }
    playClip(p.slides[i].firstChild);
    // Near the end: let Explore load its next page underneath, and take what it learned.
    if (i >= p.slides.length - 3) {
      if (onExploreGrid()) window.scrollBy(0, (window.innerHeight || 800) * 3);
      var more = clipList();
      for (var j = 0; j < more.length; j++) addClipSlide(more[j]);
    }
  }

  function playClip(v) {
    if (!v || state.covered || document.visibilityState !== 'visible') return;
    v.muted = clipPlayer.muted;
    var pr = v.play();
    if (pr && typeof pr.catch === 'function') {
      pr.catch(function () {
        // Sound needs a tap first: play muted (a tap on the video turns sound on).
        if (!clipPlayer || v.muted) return;
        clipPlayer.muted = true;
        v.muted = true;
        v.play().catch(function () {});
      });
    }
  }

  function toggleClipSound() {
    var p = clipPlayer;
    if (!p) return;
    p.muted = !p.muted;
    var v = p.slides[p.current] && p.slides[p.current].firstChild;
    if (v) {
      v.muted = p.muted;
      if (v.paused) playClip(v);
    }
    p.mute.innerHTML = p.muted ? ICON_MUTED : ICON_SOUND;
    p.mute.className = 'tbc-mute on';
    clearTimeout(p.muteTimer);
    p.muteTimer = setTimeout(function () {
      if (clipPlayer) clipPlayer.mute.className = 'tbc-mute';
    }, 700);
  }

  function updateClipClock() {
    var p = clipPlayer;
    var ex = state.c && state.c.explore;
    if (!p || !ex) return;
    var left = Math.max(0, ex.dailySeconds - exploreLog().used);
    var t = Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + ' left today';
    if (p.left.textContent !== t) p.left.textContent = t;
  }

  /** @param viaHistory true when closed by ✕ or time-out: also drops the history entry it added. */
  function closeClipPlayer(viaHistory) {
    var p = clipPlayer;
    if (!p) return;
    clipPlayer = null;
    clearTimeout(p.muteTimer);
    if (p.observer) p.observer.disconnect();
    for (var k = 0; k < p.slides.length; k++) {
      var v = p.slides[k].firstChild;
      v.pause();
      if (v.hasAttribute('src')) {
        v.removeAttribute('src');
        v.load();
      }
    }
    if (p.root.parentNode) p.root.parentNode.removeChild(p.root);
    clearTimeout(p.tapTimer);
    clearTimeout(p.toastTimer);
    // The page under the player may show a post (like/save); Instagram renders the address again.
    state.clipRerender = !!p.dirty;
    if (viaHistory && p.pushed && history.state && history.state.__tbClips) {
      state.clipPopIgnore = true;
      history.back();
    } else if (p.dirty) {
      rerenderUnderneath();
    }
    updateExploreCard();
  }



  /** After the player closes, Instagram shows the page of the current address again. */
  function rerenderUnderneath() {
    if (!state.clipRerender) return;
    state.clipRerender = false;
    setTimeout(function () {
      window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    }, 0);
  }

  /** Back while the player is open closes it; Instagram doesn't see that history step. */
  function onClipPop(e) {
    if (state.clipNav) return; // the player's own switch of the page underneath: Instagram renders it
    if (state.clipPopIgnore) {
      state.clipPopIgnore = false;
      e.stopImmediatePropagation();
      rerenderUnderneath();
      return;
    }
    if (!clipPlayer) {
      // Back from comments or a profile opened from the player: reopen it at that reel.
      var r = state.clipResume;
      if (r && location.pathname === r.at) {
        state.clipResume = null;
        setTimeout(function () {
          if (clips.byCode[r.code] && exploreAllowed()) openClipPlayer(r.code);
        }, 400);
      }
      return;
    }
    e.stopImmediatePropagation();
    closeClipPlayer(false);
  }

  function onClipVisibility() {
    if (!clipPlayer) return;
    var v = clipPlayer.slides[clipPlayer.current] && clipPlayer.slides[clipPlayer.current].firstChild;
    if (!v) return;
    if (document.visibilityState === 'visible') playClip(v);
    else v.pause();
  }

  // ---------------------------------------------------------------- your day (stats)

  /*
   * "Your day" in Throwback's settings (owner, 2026-11-20): time on each part of Instagram today and
   * what Throwback took out of the feed. Counted on the device only, per day; the app reads it with
   * __throwback.dayStats() when the screen opens. Time counts while the page is visible and not
   * covered by a Throwback screen.
   */
  var DAY_KEY = '__tb_day';
  var dayRec = null;

  function dayLog() {
    if (!dayRec) {
      try {
        dayRec = JSON.parse(localStorage.getItem(DAY_KEY) || 'null');
      } catch (e) {
        dayRec = null;
      }
    }
    if (!dayRec || dayRec.date !== today() || typeof dayRec.sec !== 'object' || !dayRec.sec) {
      dayRec = { date: today(), sec: {}, ads: 0, sugg: 0 };
    }
    return dayRec;
  }

  function saveDay() {
    try {
      localStorage.setItem(DAY_KEY, JSON.stringify(dayRec));
    } catch (e) {
      // Storage unavailable: today's counts last for this page only.
    }
  }

  function dayArea() {
    if (clipPlayer) return 'explore';
    var p = location.pathname;
    if (state.mode === MODE_MESSAGES || /^\/direct\//.test(p)) return 'messages';
    if (/^\/stories\//.test(p)) return 'stories';
    if (/^\/explore\//.test(p)) return 'explore';
    if (p === '/') return 'feed';
    return 'other'; // profiles, single posts, settings
  }

  function startDayClock() {
    setInterval(function () {
      if (document.visibilityState !== 'visible' || state.covered) return;
      var d = dayLog();
      var a = dayArea();
      d.sec[a] = (d.sec[a] || 0) + 1;
      d.dirty = (d.dirty || 0) + 1;
      if (d.dirty >= 10) {
        d.dirty = 0;
        saveDay();
      }
    }, 1000);
  }

  function countRemoved(field) {
    var d = dayLog();
    d[field] = (d[field] || 0) + 1;
  }

  function dayStats() {
    var d = dayLog();
    var ex = state.c && state.c.explore;
    var rv = state.c && state.c.reelViewer;
    var log = reelLog();
    var cool = rv && log.poster ? Math.max(0, log.poster + rv.cooldownMs - trustedNow()) : 0;
    var ds = discState();
    return JSON.stringify({
      areas: d.sec,
      adsRemoved: d.ads || 0,
      suggestionsRemoved: d.sugg || 0,
      exploreUsedSec: exploreLog().used,
      exploreAllowanceSec: ex && ex.dailySeconds ? ex.dailySeconds : 0,
      discoverShown: ds.count || 0,
      discoverMax: DISCOVER.perDay,
      reelCooldownMs: cool
    });
  }

  // ---------------------------------------------------------------- nothing plays while covered

  /*
   * While a Throwback screen (Settings, the lock, the pause, the reel poster) covers the page, nothing
   * may keep playing underneath: what plays is paused, and anything that starts while covered is
   * paused at once. Nothing is resumed afterwards; Instagram starts videos again itself on scroll.
   */
  function pauseAllMedia() {
    var media = document.querySelectorAll('video, audio');
    for (var i = 0; i < media.length; i++) if (!media[i].paused) media[i].pause();
  }

  function onCoveredPlay(e) {
    var m = e.target;
    if (state.covered && m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO')) m.pause();
  }

  // ---------------------------------------------------------------- messages and stories warm-up

  /*
   * Messages and the story viewer are separate parts of Instagram's code, loaded the first time they
   * open (messages measured at 16 of 22 code files downloaded). The first time they load, Throwback
   * notes which static code files (Instagram's public CDN, the same for every user) were used. On later starts, once the feed is ready and
   * the phone is idle, it prefetches just those files into the browser cache. No logged-in requests,
   * no inbox data, no change to "active" status; skipped with Data Saver on.
   */
  var WARM_KEY = '__tb_dm_assets';
  var WARM_MAX = 120;
  // Sections whose code is loaded on first use: messages and the story viewer.
  var WARM_PAGES = ['inbox', 'direct', 'stories'];
  var WARM_MAX_AGE_MS = 7 * 24 * 3600 * 1000;
  var warmDone = false;

  function staticAsset(url) {
    var u = parseUrl(url);
    if (!u || u.protocol !== 'https:') return false;
    if (!/(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname)) return false;
    return /\.(js|css)$/.test(u.pathname) || u.pathname.indexOf('/rsrc.php/') !== -1;
  }

  /**
   * After opening messages (a full load, or Instagram's in-page switch): remember the code files loaded
   * since `since` (performance time) and record how many came from the network into `rec`.
   */
  function learnDmAssets(since, until, rec) {
    if (!performance.getEntriesByType) return;
    var res = performance.getEntriesByType('resource').filter(function (r) {
      return r.startTime >= since && r.startTime <= until &&
        (r.initiatorType === 'script' || r.initiatorType === 'link' || r.initiatorType === 'css') && staticAsset(r.name);
    });
    if (!res.length) {
      rec.codeFiles = 0;
      return;
    }
    var fromNetwork = 0, bytes = 0;
    res.forEach(function (r) {
      if (r.transferSize > 0) {
        fromNetwork++;
        bytes += r.transferSize;
      }
    });
    rec.codeFiles = res.length;
    rec.codeFromNetwork = fromNetwork;
    rec.codeNetworkKB = Math.round(bytes / 1024);
    var saved = null;
    try {
      saved = JSON.parse(localStorage.getItem(WARM_KEY) || 'null');
    } catch (e) {
      saved = null;
    }
    var urls = (saved && saved.urls) || [];
    res.forEach(function (r) {
      if (urls.indexOf(r.name) === -1) urls.push(r.name);
    });
    try {
      localStorage.setItem(WARM_KEY, JSON.stringify({ t: Date.now(), urls: urls.slice(-WARM_MAX) }));
    } catch (e) {
      // No storage: nothing to warm next time.
    }
  }

  /** On the feed, when idle: prefetch the remembered messages code files (once per page). */
  function warmDmAssets() {
    if (warmDone || !pageActive('home') || state.mode === MODE_MESSAGES) return;
    warmDone = true;
    var conn = navigator.connection;
    if (conn && conn.saveData) return;
    var saved = null;
    try {
      saved = JSON.parse(localStorage.getItem(WARM_KEY) || 'null');
    } catch (e) {
      saved = null;
    }
    if (!saved || !saved.urls || Date.now() - saved.t > WARM_MAX_AGE_MS) return;
    var have = {};
    performance.getEntriesByType('resource').forEach(function (r) {
      have[r.name] = 1;
    });
    var n = 0;
    saved.urls.forEach(function (url) {
      if (have[url] || !staticAsset(url)) return;
      var link = document.createElement('link');
      link.rel = 'prefetch';
      link.href = url;
      (document.head || document.documentElement).appendChild(link);
      n++;
    });
    perf.log.push({ page: 'messages-warmup', prefetched: n });
  }

  function scheduleWarmups() {
    var idle = window.requestIdleCallback || function (fn) {
      return setTimeout(fn, 1);
    };
    // Late enough not to compete with the feed's own loading.
    setTimeout(function () {
      idle(function () {
        if (!pageActive('direct')) warmDmAssets();
      }, { timeout: 5000 });
    }, 5000);
  }

  // ---------------------------------------------------------------- feed data filter (option 1)

  /*
   * Instagram's page downloads the feed as JSON pages of edges; each node holds a post from someone you
   * follow ("media"), a suggested post ("explore_story") or an ad (rules: feedData). Throwback removes
   * ads and suggestions from that data before Instagram's code sees it, so the page never draws them:
   * no hiding afterwards, no flicker, correct scrolling. The first batch comes built into the page;
   * it is parsed with JSON.parse too, and the page-level filter below stays as the safety net.
   * Nothing extra is requested from Instagram: only data the page already downloaded is filtered.
   */
  var feedDataState = { emptyPages: 0, sinceDiscovery: 0, cursors: {}, ended: false };
  var dataFilterInstalled = false;

  function installDataFilter() {
    if (dataFilterInstalled) return;
    dataFilterInstalled = true;
    var nativeParse = JSON.parse;
    var parse = function parse(text, reviver) {
      var out = nativeParse.apply(JSON, arguments);
      var st = state.c && state.c.stories;
      if (st && typeof text === 'string' && text.length > 200 && out && typeof out === 'object') {
        for (var j = 0; j < st.markers.length; j++) {
          if (text.indexOf(st.markers[j]) === -1) continue;
          try {
            filterStoryData(out, st, 0);
          } catch (e) {
            // Never break Instagram's own parsing.
          }
          break;
        }
      }
      if (typeof text === 'string' && text.length > 200 && out && typeof out === 'object' &&
        onExploreOrSearch() && text.indexOf('"video_versions"') !== -1) {
        try {
          learnClips(out, 0);
        } catch (e) {
          // Never break Instagram's own parsing.
        }
      }
      var fd = state.c && state.c.feedData;
      if (fd && typeof text === 'string' && text.length > 200 && out && typeof out === 'object') {
        for (var i = 0; i < fd.connections.length; i++) {
          if (text.indexOf(fd.connections[i]) === -1) continue;
          try {
            filterFeedData(out, fd, 0);
          } catch (e) {
            // Never break Instagram's own parsing; the page-level filter still applies.
          }
          break;
        }
      }
      return out;
    };
    try {
      JSON.parse = parse;
    } catch (e) {
      return;
    }
    // Story videos are fetched in pieces; pieces already looked ahead are handed over from memory.
    if (typeof window.fetch === 'function') {
      nativeFetch = window.fetch;
      window.fetch = function (input) {
        try {
          var url = typeof input === 'string' ? input : input && input.url;
          var hit = url && storyFetchHit(url);
          if (hit) return hit;
        } catch (e) {
          // Fall through to the network.
        }
        return nativeFetch.apply(this, arguments);
      };
    }
    // fetch() responses read with .json() bypass JSON.parse; route them through it.
    if (window.Response && Response.prototype && typeof Response.prototype.json === 'function') {
      Response.prototype.json = function () {
        return this.text().then(function (t) {
          return JSON.parse(t);
        });
      };
    }
  }

  function filterFeedData(o, fd, depth) {
    if (!o || typeof o !== 'object' || depth > 16) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) filterFeedData(o[i], fd, depth + 1);
      return;
    }
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      var v = o[k];
      if (!v || typeof v !== 'object') continue;
      if (fd.connections.indexOf(k) !== -1 && Array.isArray(v.edges)) filterConnection(v, fd);
      else filterFeedData(v, fd, depth + 1);
    }
  }

  function fieldIn(n, fields) {
    for (var i = 0; i < fields.length; i++) if (n[fields[i]] != null) return fields[i];
    return null;
  }

  function valueAt(o, path) {
    var parts = path.split('.');
    for (var i = 0; i < parts.length && o != null; i++) o = o[parts[i]];
    return o;
  }

  function anyTest(o, tests) {
    for (var i = 0; i < tests.length; i++) {
      var t = tests[i];
      var v = valueAt(o, t.path);
      if (t.present != null ? (v != null) === !!t.present : v != null && String(v) === t.equals) return true;
    }
    return false;
  }

  function filterConnection(conn, fd) {
    var info = conn.page_info;
    var cursor = info && info.end_cursor;
    // The same page can be parsed more than once (cache, re-render): count it only the first time.
    var fresh = !cursor || !feedDataState.cursors[cursor];
    if (cursor) feedDataState.cursors[cursor] = 1;
    var kept = [];
    var followed = 0;
    var endAt = false;
    for (var i = 0; i < conn.edges.length; i++) {
      var e = conn.edges[i];
      var n = e && e.node;
      if (!n || typeof n !== 'object') {
        kept.push(e);
        continue;
      }
      if (fieldIn(n, fd.endMarkers)) {
        if (fd.endAtMarker) {
          endAt = true;
          break;
        }
        continue; // Instagram's mid-feed "caught up" marker; Throwback shows its own at the real end
      }
      if (fieldIn(n, fd.adNodes) || fieldIn(n, fd.dropNodes)) {
        if (fresh && fieldIn(n, fd.adNodes)) countRemoved('ads');
        continue;
      }
      var sField = fieldIn(n, fd.suggestionNodes);
      var media = sField ? n[sField] && n[sField][fd.mediaField] : n[fd.mediaField];
      if (media && typeof media === 'object' && anyTest(media, fd.adMedia)) {
        if (fresh) countRemoved('ads');
        continue;
      }
      if (sField || (media && anyTest(media, fd.suggestedMedia))) {
        if (media && dataDiscovery(media, fresh)) {
          kept.push(e);
          learnFeedMedia(media);
        } else if (fresh) {
          countRemoved('sugg');
        }
        continue;
      }
      kept.push(e);
      learnFeedMedia(media);
      if (media) {
        followed++;
        if (fresh) feedDataState.sinceDiscovery++;
      }
    }
    conn.edges = kept;
    if (fresh) feedDataState.emptyPages = followed > 0 ? 0 : feedDataState.emptyPages + 1;
    var ended = endAt || feedDataState.emptyPages >= fd.endAfterEmptyPages;
    // Only suggestions are left: tell Instagram there are no more pages, so it stops loading.
    if (ended && info) info.has_next_page = false;
    if (fresh) {
      feedDataState.ended = ended;
      if (state.c) schedulePass(false);
    }
  }

  /** A suggested post from the data: kept when the discovery allowance has room (or it was kept before). */
  function dataDiscovery(media, fresh) {
    var key = typeof media.code === 'string' ? media.code : null;
    var d = discState();
    if (key && discoveredKeys[key]) return true;
    if (!fresh || state.mode === MODE_MESSAGES || feedDataState.ended) return false;
    var kind = media.media_type === 2 || media.product_type === 'clips' ? 'video' : 'photo';
    if (!takeDiscovery(d, kind, feedDataState.sinceDiscovery >= DISCOVER.spacing, key)) return false;
    feedDataState.sinceDiscovery = 0;
    return true;
  }

  // ---------------------------------------------------------------- stories: ads and video look-ahead

  /*
   * Stories (rules: stories). Instagram loads story data in batches that already cover the next
   * people, and fetches each video in pieces (bytestart/byteend in the address). Two things:
   *  - story ads (their own data key) are emptied before the page uses them;
   *  - look-ahead: while a story plays, the first prefetchKB of the next prefetchAhead videos (and a
   *    little of their audio) are downloaded and kept in memory; when the player asks for a piece
   *    inside that range it is handed over from memory, otherwise it goes to the network as usual.
   * Memory stays small (under about 3 MB with the defaults: the current story plus 5 ahead): entries behind the current story or past the
   * look-ahead are dropped, and everything is dropped when leaving stories. Off with Data Saver.
   */
  var nativeFetch = null;
  var STORY_AUDIO_BYTES = 64 * 1024;
  // A look-ahead state: items in viewing order, what is kept in memory (store) or was sent to the
  // disk cache (images), the quality/size Instagram uses, and where the user is. One for stories,
  // one for the home feed; each is cleared when leaving its page.
  function newAhead() {
    return {
      items: [], ids: {}, store: {}, images: {}, height: 0, imgWidth: 0, current: -1,
      stats: { learned: 0, prefetched: 0, hits: 0, failed: 0, images: 0 }
    };
  }
  var stories = newAhead();
  var feedAhead = newAhead();

  function filterStoryData(o, st, depth) {
    if (!o || typeof o !== 'object' || depth > 16) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) filterStoryData(o[i], st, depth + 1);
      return;
    }
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      var v = o[k];
      if (!v || typeof v !== 'object') continue;
      if (st.adKeys.indexOf(k) !== -1) {
        emptyLists(v, 0); // no story ads
        continue;
      }
      if (k === 'items' && Array.isArray(v) && v.length && v[0] && (v[0].video_dash_manifest || v[0].video_versions || v[0].image_versions2)) {
        learnStoryItems(v);
      }
      filterStoryData(v, st, depth + 1);
    }
  }

  function emptyLists(o, depth) {
    if (!o || typeof o !== 'object' || depth > 8) return;
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      if (Array.isArray(o[k])) o[k] = [];
      else if (o[k] && typeof o[k] === 'object') emptyLists(o[k], depth + 1);
    }
  }

  /** Story items in viewing order, each with its video (and audio) versions from the DASH manifest. */
  function learnStoryItems(items) {
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (!it) continue;
      var id = String(it.pk || it.id || '');
      if (!id || stories.ids[id]) continue;
      var reps = storyReps(it);
      var cands = reps.length ? [] : storyImages(it);
      if (!reps.length && !cands.length) continue;
      stories.ids[id] = 1;
      stories.items.push({ id: id.split('_')[0], reps: reps, cands: cands });
      stories.stats.learned++;
    }
    if (stories.items.length > 300) stories.items.splice(0, stories.items.length - 300);
    noteStoryLocation(); // the data may arrive after the address already shows the story
  }

  /**
   * Which story is on screen: the address is /stories/<user>/<story id>/ for each story. This is the
   * only signal used for "where we are" (image loads and video requests can't tell Instagram's own
   * preloading and Throwback's look-ahead apart from what's on screen).
   */
  function noteStoryLocation() {
    if (!pageActive('stories')) return;
    var m = /\/(\d{6,})\/?$/.exec(location.pathname);
    if (!m) return;
    for (var i = stories.items.length - 1; i >= 0; i--) {
      if (stories.items[i].id !== m[1]) continue;
      if (stories.current !== i) {
        stories.current = i;
        setTimeout(storyLookAhead, 0);
      }
      return;
    }
  }

  function storyReps(it) {
    var reps = [];
    if (typeof it.video_dash_manifest === 'string' && window.DOMParser) {
      try {
        var doc = new DOMParser().parseFromString(it.video_dash_manifest, 'application/xml');
        var sets = doc.getElementsByTagName('AdaptationSet');
        for (var s = 0; s < sets.length; s++) {
          var audio = /audio/.test(sets[s].getAttribute('contentType') || sets[s].getAttribute('mimeType') || '');
          var rs = sets[s].getElementsByTagName('Representation');
          for (var r = 0; r < rs.length; r++) {
            var base = rs[r].getElementsByTagName('BaseURL')[0];
            if (!audio) audio = /audio/.test(rs[r].getAttribute('mimeType') || '');
            var url = base && base.textContent && base.textContent.trim();
            var u = url && parseUrl(url);
            if (!u) continue;
            reps.push({ url: url, path: u.pathname, audio: audio, height: +(rs[r].getAttribute('height') || 0) });
          }
        }
      } catch (e) {
        reps = [];
      }
    }
    if (!reps.length && it.video_versions) {
      it.video_versions.forEach(function (v) {
        var u = v && v.url && parseUrl(v.url);
        if (u) reps.push({ url: v.url, path: u.pathname, audio: false, height: +(v.height || 0) });
      });
    }
    return reps;
  }

  /** A photo story's sizes (candidates), for the photo look-ahead. */
  function storyImages(it) {
    var list = (it.image_versions2 && it.image_versions2.candidates) || [];
    var out = [];
    list.forEach(function (c) {
      var u = c && c.url && parseUrl(c.url);
      if (u) out.push({ url: c.url, path: u.pathname, width: +(c.width || 0) });
    });
    return out;
  }

  /** A photo loaded on the page: learn the size Instagram shows (not our own look-ahead loads). */
  function noteStoryImage(path) {
    [stories, feedAhead].forEach(function (la) {
      if (la.images[path]) return;
      for (var i = la.items.length - 1; i >= 0; i--) {
        var cands = la.items[i].cands;
        if (!cands) continue; // not parsed yet (feed posts far from the screen)
        for (var c = 0; c < cands.length; c++) {
          if (cands[c].path !== path) continue;
          if (cands[c].width) la.imgWidth = cands[c].width;
          return;
        }
      }
    });
  }

  function watchStoryImages() {
    if (!window.PerformanceObserver) return;
    try {
      new PerformanceObserver(function (list) {
        if (!state.c || !(pageActive('stories') || pageActive('home'))) return;
        list.getEntries().forEach(function (e) {
          if (e.initiatorType !== 'img') return;
          var u = parseUrl(e.name);
          if (u && storyCdn(u)) noteStoryImage(u.pathname);
        });
      }).observe({ type: 'resource', buffered: false });
    } catch (e) {
      // Not supported: the video look-ahead still works.
    }
  }

  /** The photo size closest to what Instagram showed last (else to the screen width). */
  function pickImage(la, cands) {
    var target = la.imgWidth || Math.round(window.innerWidth * (window.devicePixelRatio || 1));
    return cands.slice().sort(function (a, b) {
      return Math.abs(a.width - target) - Math.abs(b.width - target);
    })[0];
  }

  /** Into the browser's disk cache (not memory): Instagram's own image then loads from there. */
  function prefetchImage(la, c) {
    if (!c || la.images[c.path]) return;
    la.images[c.path] = 1;
    var img = new Image();
    img.decoding = 'async';
    img.onload = function () {
      la.stats.images++;
    };
    img.src = c.url;
  }

  function storyCdn(u) {
    return /(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname);
  }

  /** The player asks for a piece of a video: learn its quality, and hand it over from memory if we can. */
  function storyFetchHit(url) {
    if (url.indexOf('bytestart=') === -1 || !state.c) return null;
    var u = parseUrl(url);
    if (!u || !storyCdn(u)) return null;
    noteStoryPlaying(u.pathname);
    var la = stories.store[u.pathname] ? stories : feedAhead;
    var e = la.store[u.pathname];
    var start = +u.searchParams.get('bytestart');
    var end = +u.searchParams.get('byteend');
    if (!e || !e.buf || !(start >= 0) || !(end >= start) || end >= e.buf.byteLength) return null;
    la.stats.hits++;
    return Promise.resolve(new Response(e.buf.slice(start, end + 1), {
      status: 200,
      headers: { 'Content-Type': e.audio ? 'audio/mp4' : 'video/mp4' }
    }));
  }

  function noteStoryPlaying(path) {
    [stories, feedAhead].forEach(function (la) {
      for (var i = la.items.length - 1; i >= 0; i--) {
        var reps = la.items[i].reps;
        if (!reps) continue; // not parsed yet
        for (var r = 0; r < reps.length; r++) {
          if (reps[r].path !== path) continue;
          if (!reps[r].audio && reps[r].height) la.height = reps[r].height; // the quality the player picks
          return;
        }
      }
    });
  }

  /** Keeps the start of the next few videos in memory; drops everything else. */
  function storyLookAhead() {
    var st = state.c && state.c.stories;
    if (!st || !nativeFetch || stories.current < 0 || !pageActive('stories')) return;
    var conn = navigator.connection;
    var wanted = {};
    // The story on screen keeps what was already looked ahead for it: the player may ask for it a
    // moment after the address changes.
    var now = stories.items[stories.current];
    if (now) now.reps.forEach(function (rp) {
      wanted[rp.path] = 1;
    });
    if (!(conn && conn.saveData)) {
      for (var i = stories.current + 1; i <= stories.current + st.ahead && i < stories.items.length; i++) {
        if (stories.items[i].cands.length) {
          prefetchImage(stories, pickImage(stories, stories.items[i].cands));
          continue;
        }
        pickReps(stories, stories.items[i].reps).forEach(function (rp) {
          wanted[rp.path] = 1;
          if (!stories.store[rp.path]) prefetchPiece(stories, rp, rp.audio ? Math.min(STORY_AUDIO_BYTES, st.bytes) : st.bytes);
        });
      }
    }
    Object.keys(stories.store).forEach(function (path) {
      if (!wanted[path]) delete stories.store[path];
    });
  }

  /** The video version closest to what the player last used (else the smallest), plus one audio. */
  function pickReps(la, reps) {
    var videos = reps.filter(function (r) {
      return !r.audio;
    });
    var audio = reps.filter(function (r) {
      return r.audio;
    })[0];
    var target = la.height;
    videos.sort(function (a, b) {
      return target ? Math.abs(a.height - target) - Math.abs(b.height - target) : a.height - b.height;
    });
    var out = videos.slice(0, 1);
    if (audio) out.push(audio);
    return out;
  }

  function prefetchPiece(la, rp, bytes) {
    if (!bytes) return;
    var entry = { audio: rp.audio, buf: null };
    la.store[rp.path] = entry;
    var url = rp.url + (rp.url.indexOf('?') === -1 ? '?' : '&') + 'bytestart=0&byteend=' + (bytes - 1);
    nativeFetch.call(window, url, { credentials: 'omit', mode: 'cors' }).then(function (res) {
      if (!res.ok) throw new Error('status ' + res.status);
      return res.arrayBuffer();
    }).then(function (ab) {
      if (la.store[rp.path] !== entry) return; // dropped meanwhile
      entry.buf = new Uint8Array(ab);
      la.stats.prefetched++;
    }).catch(function () {
      la.stats.failed++;
      if (la.store[rp.path] === entry) delete la.store[rp.path];
    });
  }

  function memoryKB(la) {
    var b = 0;
    Object.keys(la.store).forEach(function (p) {
      if (la.store[p].buf) b += la.store[p].buf.byteLength;
    });
    return Math.round(b / 1024);
  }

  // ---------------------------------------------------------------- feed: bigger batches, look-ahead

  /*
   * Feed (rules: feedData). Instagram's feed request asks for 12 entries (`first`); after removing
   * ads and suggestions only ~2-3 real posts are left, so it keeps waiting on slow requests (4.5 s
   * measured). Its own request is raised to pageSize (24): the same request at the same moment, with
   * more entries. If Instagram ever answers that with an error, it goes back to 12 for the session.
   * Look-ahead as in stories: photos/thumbnails of the next lookAheadPosts posts go into the disk
   * cache; the start of the next lookAheadVideos videos is kept in memory (cleared off the feed).
   */
  var feedTweak = { off: false, raised: 0, refused: 0, minAhead: null };

  var tweakInstalled = false;

  function installRequestTweak() {
    var X = window.XMLHttpRequest;
    if (tweakInstalled || !X || !X.prototype) return;
    tweakInstalled = true;
    var open = X.prototype.open;
    var send = X.prototype.send;
    X.prototype.open = function (method, url) {
      this.__tbUrl = String(url || '');
      return open.apply(this, arguments);
    };
    X.prototype.send = function (body) {
      var out = body;
      try {
        if (typeof body === 'string' && this.__tbUrl.indexOf('/graphql') !== -1) {
          out = raiseFeedPage(body);
          if (out !== body) {
            var xhr = this;
            xhr.addEventListener('load', function () {
              var t = '';
              try {
                t = typeof xhr.responseText === 'string' ? xhr.responseText.slice(0, 300) : '';
              } catch (e) {
                t = '';
              }
              if (xhr.status !== 200 || /"errors"\s*:/.test(t)) {
                feedTweak.off = true; // Instagram refused the bigger batch: back to its own size
                feedTweak.refused++;
              }
            });
          }
        }
      } catch (e) {
        out = body;
      }
      return send.call(this, out);
    };
  }

  function raiseFeedPage(body) {
    var fd = state.c && state.c.feedData;
    if (!fd || !fd.pageSize || feedTweak.off || state.mode === MODE_MESSAGES) return body;
    var form = new URLSearchParams(body);
    if (fd.paginationQueries.indexOf(form.get('fb_api_req_friendly_name') || '') === -1) return body;
    var vars = JSON.parse(form.get('variables') || 'null');
    if (!vars || typeof vars.first !== 'number' || vars.first >= fd.pageSize) return body;
    vars.first = fd.pageSize;
    form.set('variables', JSON.stringify(vars));
    feedTweak.raised++;
    return form.toString();
  }

  /** A post the data filter kept: remember it in feed order, with its photo sizes and video versions. */
  function learnFeedMedia(media) {
    if (!media || typeof media !== 'object') return;
    var id = typeof media.code === 'string' ? media.code : null;
    if (!id || feedAhead.ids[id]) return;
    var first = media.carousel_media && media.carousel_media[0] ? media.carousel_media[0] : media;
    feedAhead.ids[id] = 1;
    // Parsed later, only for the posts that come into the look-ahead window (parsing every post's
    // video manifest here ran inside Instagram's JSON.parse: ~24 ms per feed page on a slow phone).
    feedAhead.items.push({ id: id, media: first, reps: null, cands: null });
    feedAhead.stats.learned++;
    if (feedAhead.items.length > 400) feedAhead.items.splice(0, feedAhead.items.length - 400);
  }

  /** The post on screen (from the feed list) → look ahead from there. */
  function noteFeedPosition(list) {
    var fd = state.c && state.c.feedData;
    if (!fd || !fd.lookAheadPosts || !list) return;
    var vh = window.innerHeight;
    for (var a = list.firstElementChild; a; a = a.nextElementSibling) {
      if (a.tagName !== 'ARTICLE' || a.hasAttribute(ATTR_POST)) continue;
      if (a.getBoundingClientRect().bottom < vh * 0.3) continue;
      var key = postKey(a);
      if (!key) return;
      for (var i = feedAhead.items.length - 1; i >= 0; i--) {
        if (feedAhead.items[i].id !== key) continue;
        var ahead = feedAhead.items.length - 1 - i;
        if (feedTweak.minAhead === null || ahead < feedTweak.minAhead) feedTweak.minAhead = ahead;
        if (feedAhead.current !== i) {
          feedAhead.current = i;
          setTimeout(feedLookAhead, 0);
        }
        return;
      }
      return;
    }
  }

  /** The post's video pieces and image candidates, parsed on first use. */
  function aheadParsed(it) {
    if (it && it.reps === null) {
      try {
        it.reps = storyReps(it.media);
        it.cands = storyImages(it.media);
      } catch (e) {
        it.reps = [];
        it.cands = [];
      }
      it.media = null;
    }
    return it;
  }

  function feedLookAhead() {
    var fd = state.c && state.c.feedData;
    if (!fd || !nativeFetch || feedAhead.current < 0 || !pageActive('home')) return;
    var conn = navigator.connection;
    var wanted = {};
    var now = aheadParsed(feedAhead.items[feedAhead.current]);
    if (now) now.reps.forEach(function (rp) {
      wanted[rp.path] = 1;
    });
    if (!(conn && conn.saveData)) {
      var videos = 0;
      var bytes = fd.videoKB * 1024;
      for (var i = feedAhead.current + 1; i <= feedAhead.current + fd.lookAheadPosts && i < feedAhead.items.length; i++) {
        var it = aheadParsed(feedAhead.items[i]);
        if (it.cands.length) prefetchImage(feedAhead, pickImage(feedAhead, it.cands)); // photo, or a video's thumbnail
        if (!it.reps.length || videos >= fd.lookAheadVideos) continue;
        videos++;
        pickReps(feedAhead, it.reps).forEach(function (rp) {
          wanted[rp.path] = 1;
          if (!feedAhead.store[rp.path]) prefetchPiece(feedAhead, rp, rp.audio ? Math.min(STORY_AUDIO_BYTES, bytes) : bytes);
        });
      }
    }
    Object.keys(feedAhead.store).forEach(function (path) {
      if (!wanted[path]) delete feedAhead.store[path];
    });
  }

  // ---------------------------------------------------------------- hide until approved (option 2)

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
    // video below the header, so a "Sponsored" label in the header has been seen too). A post without
    // media is let through after APPROVE_WAIT_MS, so nothing can stay hidden for good.
    if (!p.querySelector('img, video') && approveSeen) {
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

  /*
   * Trusted time (owner, 2026-11-20). The app passes how far its tamper-resistant clock is from the
   * phone's, and the UTC offset of the time zone days are counted in (a new zone only counts from the
   * next midnight). So changing the phone's clock or time zone neither starts a new day for the
   * daily counters (Explore minutes, Discover, Your day) nor ends the chat-reel cooldown early. The
   * day never goes backwards either. Without the app (tests) the phone's own clock is used.
   */
  var clock = { offset: 0, zoneMin: null, day: null, nextAt: 0 };
  var LASTDAY_KEY = '__tb_lastday';
  var lastDay = null;

  function trustedNow() {
    return Date.now() + clock.offset;
  }

  function today() {
    var ms = trustedNow();
    var zoneMin = clock.zoneMin === null ? -new Date(ms).getTimezoneOffset() : clock.zoneMin;
    // The app's day while it lasts (it knows when the next one may start); after that, count on.
    var n = clock.day !== null && ms < clock.nextAt ? clock.day : Math.floor((ms + zoneMin * 60000) / 86400000);
    var local = new Date(n * 86400000);
    if (!lastDay) {
      try {
        lastDay = JSON.parse(localStorage.getItem(LASTDAY_KEY) || 'null');
      } catch (e) {
        lastDay = null;
      }
    }
    if (lastDay && typeof lastDay.n === 'number' && n < lastDay.n) return lastDay.key; // never backwards
    var key = new Date(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()).toDateString();
    if (!lastDay || lastDay.n !== n) {
      lastDay = { n: n, key: key };
      try {
        localStorage.setItem(LASTDAY_KEY, JSON.stringify(lastDay));
      } catch (e) {
        // Storage unavailable: fine for this page.
      }
    }
    return key;
  }

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

  /** "Suggested for you" carousels and similar blocks between posts. */
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

  function dismissDialogs() {
    var c = state.c;
    if (!c.dialogs.length || !dialogTries) return;
    // At most every 200 ms (a whole-document query); a timer makes sure the check still runs.
    var nowD = Date.now();
    if (nowD - (state.lastDialogScan || 0) < 200) {
      if (!state.dialogRecheck) {
        state.dialogRecheck = setTimeout(function () {
          state.dialogRecheck = 0;
          schedulePass(false);
        }, 200);
      }
      return;
    }
    state.lastDialogScan = nowD;
    var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      var tries = dialogTries.get(d) || 0;
      if (tries >= c.dismissMaxAttempts) continue;
      // Reading a dialog's text is costly (it may be a whole DM thread or story viewer): at most every 500 ms.
      var now = Date.now();
      if (now - (dialogChecked.get(d) || 0) < DIALOG_CHECK_MS) {
        // Check it again once the wait is over, even if nothing else changes on the page by then.
        if (!state.dialogRecheck) {
          state.dialogRecheck = setTimeout(function () {
            state.dialogRecheck = 0;
            schedulePass(false);
          }, DIALOG_CHECK_MS);
        }
        continue;
      }
      dialogChecked.set(d, now);
      var text = norm((d.textContent || '').slice(0, 4000));
      for (var j = 0; j < c.dialogs.length; j++) {
        var rule = c.dialogs[j];
        var hit = rule.when.some(function (w) {
          return text.indexOf(w) >= 0;
        });
        if (!hit) continue;
        dialogTries.set(d, tries + 1);
        var button = findButton(d, rule);
        if (button) {
          dialogTries.set(d, Infinity);
          clickElement(button);
        }
        break;
      }
    }
  }

  function findButton(dialog, rule) {
    var candidates = dialog.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < candidates.length; i++) {
      var t = candidates[i].textContent;
      if (t && t.length <= 120 && rule.click.has(norm(t))) return candidates[i];
    }
    if (rule.clickSelector) {
      var x = dialog.querySelector(rule.clickSelector);
      if (x) return closestWithin(x, 'button, [role="button"], a', 4) || x;
    }
    return null;
  }

  /** Explore: hide every block that holds post tiles but not the search box. */
  function applyExplore() {
    var ex = state.c.explore;
    if (!ex || !pageActive(ex.on)) return;
    var keep = document.querySelector(ex.keep);
    var items = document.querySelectorAll(ex.items);
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      if (!keep) {
        // Search box not recognised: hide tiles only, never their surroundings.
        item.setAttribute(ATTR_EXPLORE, '1');
        continue;
      }
      var top = null;
      for (var n = item; n && n !== document.body && n.tagName !== 'MAIN'; n = n.parentElement) {
        if (n.hasAttribute(ATTR_EXPLORE)) {
          top = null;
          break; // already hidden
        }
        if (n.contains(keep) || n.hasAttribute(ATTR_NAV) || n.querySelector('[' + ATTR_NAV + ']')) break;
        top = n;
      }
      if (top) top.setAttribute(ATTR_EXPLORE, '1');
    }
  }

  /**
   * Navigation bars: the smallest blocks holding at least minLinks nav links and no content.
   * Hidden in messages-only mode; slots whose only button is hidden collapse.
   */
  function detectNav(force) {
    var nav = state.c.nav;
    if (!nav) return;
    var now = Date.now();
    if (force || now - state.lastNavScan > NAV_SCAN_MS) {
      state.lastNavScan = now;
      var links = document.querySelectorAll(nav.links);
      for (var i = 0; i < links.length; i++) {
        // Links inside a post (hashtags, mentions) are never the navigation bar.
        if (links[i].closest('[' + ATTR_NAV + '], article')) continue;
        var n = links[i].parentElement;
        for (var depth = 0; n && depth < 10 && n !== document.body; depth++, n = n.parentElement) {
          if (n.querySelector('main, article, input, textarea, [contenteditable="true"], [role="main"]')) break;
          if (n.querySelectorAll(nav.links).length >= nav.minLinks) {
            if (n.querySelectorAll('a[href]').length <= 12) n.setAttribute(ATTR_NAV, '1');
            break;
          }
        }
      }
      if (state.mode === MODE_FULL) {
        var bars = document.querySelectorAll('[' + ATTR_NAV + ']');
        for (var b = 0; b < bars.length; b++) collapseSlots(bars[b]);
      }
    }
  }

  /** display:none on el or an ancestor below stop (stop itself excluded). */
  function hiddenBelow(el, stop) {
    for (var n = el; n && n !== stop; n = n.parentElement) {
      if (getComputedStyle(n).display === 'none') return true;
    }
    return false;
  }

  function collapseSlots(bar) {
    var buttons = bar.querySelectorAll('a[href], button, [role="button"], [role="link"]');
    for (var i = 0; i < buttons.length; i++) {
      var button = buttons[i];
      var slot = button;
      while (slot.parentElement && slot.parentElement !== bar &&
             slot.parentElement.querySelectorAll('a[href], button, [role="button"], [role="link"]').length <= 1) {
        slot = slot.parentElement;
      }
      if (slot === button) continue;
      // The slot's own collapse is excluded, so a button that shows again un-collapses its slot.
      var collapse = hiddenBelow(button, slot);
      if (collapse && !slot.hasAttribute(ATTR_SLOT)) slot.setAttribute(ATTR_SLOT, '1');
      else if (!collapse && slot.hasAttribute(ATTR_SLOT)) slot.removeAttribute(ATTR_SLOT);
    }
  }

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
    sendReady();
  }

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

  // ---------------------------------------------------------------- passes

  function scanAll() {
    if (!document.documentElement) return;
    scanHide(document.documentElement);
    scanText(document.documentElement);
    approvePosts(document.documentElement);
    schedulePass(true);
  }

  /** Page-wide work, at most once per frame (before that frame paints). */
  function schedulePass(forceNav) {
    if (forceNav) state.lastNavScan = 0;
    if (state.passQueued) return;
    state.passQueued = true;
    raf(function () {
      state.passQueued = false;
      if (!state.c) return;
      ensureStyle();
      fixViewport();
      updateProfileEntry();
      updateCallEntry();
      updateSettingsSection();
      approvePosts(document.querySelector('main'));
      updateEndCard();
      dismissDialogs();
      applyExplore();
      detectNav(false);
      hideAfterMarkers();
      checkReady();
      if (state.readySent && state.bgDirty) {
        state.bgDirty = false;
        reportBackground();
      }
    });
  }

  var rootWatched = null;
  /** Instagram may rewrite <html class> (themes); put Throwback's state classes back right away. */
  function watchRootClass() {
    var root = document.documentElement;
    if (!root || root === rootWatched) return;
    rootWatched = root;
    new MutationObserver(function () {
      if (!root.classList.contains(state.mode === MODE_MESSAGES ? 'tb-messages-only' : 'tb-full')) applyClasses();
    }).observe(root, { attributes: true, attributeFilter: ['class'] });
  }

  function onMutations(records) {
    if (!state.c) return;
    perfChanged();
    ensureStyle();
    watchRootClass();
    var root = document.documentElement;
    if (root && !root.classList.contains(state.mode === MODE_MESSAGES ? 'tb-messages-only' : 'tb-full')) applyClasses();
    var added = [];
    var texts = [];
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'childList') {
        for (var j = 0; j < r.addedNodes.length; j++) {
          var n = r.addedNodes[j];
          if (n.nodeType === 1) added.push(n);
          else if (n.nodeType === 3 && n.parentElement) texts.push(n.parentElement);
        }
      } else if (r.type === 'characterData' && r.target.parentElement) {
        texts.push(r.target.parentElement);
      }
    }
    // Scan only the top-most added nodes: one whose parent was added too is inside that parent's
    // scan. (Was: a whole-document scan for batches over 400 nodes, 7 times during page load.)
    if (added.length > 1) {
      var addedSet = new Set(added);
      var roots = [];
      for (var q = 0; q < added.length; q++) if (!addedSet.has(added[q].parentNode)) roots.push(added[q]);
      added = roots;
    }
    for (var k = 0; k < added.length; k++) {
      if (!added[k].isConnected) continue;
      scanHide(added[k]);
      scanText(added[k]);
    }
    var scanTexts = textScanActive();
    for (var t = 0; scanTexts && t < texts.length; t++) {
      if (texts[t].isConnected) checkTextElement(texts[t], 0);
    }
    // After the label checks, in the same task (before paint): reveal the posts that passed.
    if (pageActive('home') && state.mode !== MODE_MESSAGES) {
      var postSel = state.c.postContainer;
      for (var a = 0; a < added.length; a++) {
        if (!added[a].isConnected) continue;
        approvePosts(added[a]);
        var host = added[a].parentElement && added[a].parentElement.closest(postSel);
        if (host) approvePost(host);
      }
      for (var b = 0; b < texts.length; b++) {
        var hostT = texts[b].isConnected && texts[b].closest(postSel);
        if (hostT) approvePost(hostT);
      }
    }
    if (added.length || texts.length) schedulePass(false);
  }

  // ---------------------------------------------------------------- setup

  /** App features passed in the config (see PageFeatures.kt). */
  function applyFeatures(cfg) {
    if (cfg && typeof cfg.createInApp === 'boolean') state.createInApp = cfg.createInApp;
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
    if (cfg.rules) {
      state.rules = cfg.rules;
      state.c = compile(cfg.rules);
    }
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
      reelEvent('covered', { on: !!covered });
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
    reelEvents: function () {
      return reels.events.slice();
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
    state.rules = config.rules;
    state.c = compile(config.rules);
    state.css = buildCss(state.c, state.baseCss);
    ensureStyle();
    applyClasses();
    installDataFilter(); // before Instagram's own scripts parse any feed data
    installRequestTweak(); // before Instagram's scripts send any feed request
    start();
  }
})(__THROWBACK_CONFIG__);
