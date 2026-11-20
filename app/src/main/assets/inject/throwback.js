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
    c.explore = ex && validSelector(ex.keep) && validSelector(ex.items) ? { on: ex.on || 'explore', keep: ex.keep, items: ex.items } : null;
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
      endAfterEmptyPages: typeof fd.endAfterEmptyPages === 'number' ? fd.endAfterEmptyPages : 3
    } : null;
    var rv = rules.reelViewer;
    c.reelViewer = rv ? {
      minSwipe: typeof rv.posterOnSwipeMin === 'number' ? rv.posterOnSwipeMin : 2,
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
    if (c.explore) out.push(scopeFor(c.explore.on) + ' [' + ATTR_EXPLORE + ']' + HIDE);
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
  function updateProfileEntry() {
    var existing = document.getElementById(PROFILE_ENTRY_ID);
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
    send({ t: 'loc', url: href });
    perfStart(state.path);
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

  /** Link taps, before Instagram's router sees them. */
  function onClick(e) {
    if (!state.c || e.defaultPrevented || e.button !== 0) return;
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
   * reopening doesn't reset it. The poster shows on a random swipe (2nd to 4th): the videos stop and
   * the app shows its poster.
   * After the poster, no free swipes for the cooldown (an hour): opening other reels to get "3 more"
   * each time doesn't work. Swipes and the last poster time are stored, so a restart doesn't reset.
   */
  var ATTR_REEL_STOP = 'data-tb-reelstop';
  var REELS_KEY = '__tb_reels';
  var reels = { current: null, viewer: null };

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
    if (!rv || !v || v.tagName !== 'VIDEO' || pageActive('stories')) return;
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
    if (opened) return; // the reel that was opened is free
    var now = Date.now();
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

  function stopReels(viewer) {
    viewer.setAttribute(ATTR_REEL_STOP, '1');
    var vids = viewer.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) vids[i].pause();
    send({ t: 'reelLimit' });
  }

  /** "Back to chat" on the poster: close Instagram's viewer (its close button, else history back). */
  function closeReels() {
    var viewer = document.querySelector('[' + ATTR_REEL_STOP + ']');
    if (!viewer) return;
    var close = null;
    for (var n = viewer; n && n !== document.body && !close; n = n.parentElement) {
      var icon = n.querySelector('svg[aria-label="Close"], [aria-label="Close"][role="button"], button[aria-label="Close"]');
      if (icon) close = icon.closest('button, [role="button"], a') || icon;
    }
    if (close) clickElement(close);
    else history.back();
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
      if (fieldIn(n, fd.adNodes) || fieldIn(n, fd.dropNodes)) continue;
      var sField = fieldIn(n, fd.suggestionNodes);
      var media = sField ? n[sField] && n[sField][fd.mediaField] : n[fd.mediaField];
      if (media && typeof media === 'object' && anyTest(media, fd.adMedia)) continue;
      if (sField || (media && anyTest(media, fd.suggestedMedia))) {
        if (media && dataDiscovery(media, fresh)) kept.push(e);
        continue;
      }
      kept.push(e);
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

  var APPROVE_WAIT_MS = 800;
  var approveSeen = typeof WeakMap === 'function' ? new WeakMap() : null;

  function approvePost(p) {
    if (p.hasAttribute(ATTR_OK) || p.hasAttribute(ATTR_POST) || p.hasAttribute(ATTR_DISCOVER)) return;
    // A suggestion the data filter let through: tag it, whatever its header says.
    var key = postKey(p);
    if (key && discoveredKeys[key]) {
      p.setAttribute(ATTR_DISCOVER, '1');
      return;
    }
    // Checked only once its header has rendered: the profile link, and the photo or video below the
    // header (so a "Sponsored" label in the header has been seen too). A post without media is let
    // through after APPROVE_WAIT_MS, so nothing can stay hidden for good.
    if (!p.querySelector('a[href]')) return;
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

  function today() {
    return new Date().toDateString();
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
        if (links[i].closest('[' + ATTR_NAV + ']')) continue;
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
    if (added.length > 400) {
      scanAll();
    } else {
      for (var k = 0; k < added.length; k++) {
        if (!added[k].isConnected) continue;
        scanHide(added[k]);
        scanText(added[k]);
      }
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

  function configure(cfg) {
    if (!cfg) return false;
    var modeChanged = false;
    if (cfg.rules) {
      state.rules = cfg.rules;
      state.c = compile(cfg.rules);
    }
    if (!state.c) return false;
    installDataFilter();
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
    document.addEventListener('play', onMediaPlay, true); // media events don't bubble; capture sees them
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
    },
    closeReels: closeReels
  };
  try {
    Object.defineProperty(window, '__throwback', { value: api, enumerable: false, configurable: false, writable: false });
  } catch (e) {
    window.__throwback = api;
  }

  state.mode = config && config.mode === MODE_MESSAGES ? MODE_MESSAGES : MODE_FULL;
  state.baseCss = (config && config.css) || '';
  state.path = location.pathname;
  if (config && config.rules) {
    state.rules = config.rules;
    state.c = compile(config.rules);
    state.css = buildCss(state.c, state.baseCss);
    ensureStyle();
    applyClasses();
    installDataFilter(); // before Instagram's own scripts parse any feed data
    start();
  }
})(__THROWBACK_CONFIG__);
