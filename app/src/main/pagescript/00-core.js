/*
 * Throwback page script.
 *
 * Injected into www.instagram.com documents at document start (WebViewCompat.addDocumentStartJavaScript),
 * or as early as onPageStarted on WebViews without that feature. The app replaces the token at the
 * bottom with the config {mode, css, rules, clock, features}: rules come from filters/rules.json
 * (validated by the app), css from throwback.css. Kotlin side: app/src/main/java/app/throwback/web/
 * (PageScript, ScriptInjector, BridgeMessage). The parts of this script and the rules for editing
 * them are in README.md next to this file.
 *
 * What it does:
 *  - Routing for in-page navigation (pushState/replaceState/popstate and link taps), mirroring
 *    UrlRouter.kt: Reels pages blocked (a single reel opens as a post), the home feed is always the
 *    home feed, messages-only mode keeps to /direct/.
 *  - Removes ads and suggestions from the feed data before Instagram draws them, and hides Reels
 *    links, the Explore grid (after its daily minutes) and app nags using stable signals only (hrefs,
 *    aria-labels, visible text). DOM work runs in a MutationObserver callback, before the browser
 *    paints, so filtered content never flashes.
 *  - Never hides captions or video descriptions (DECISIONS.md #15): text labels only count in a
 *    post's header, before its media.
 *  - Reports location changes, readiness, touches (pull-to-refresh) and the page background to the app.
 *
 * Written for old Chromium too (fallback mode): ES5 style, no optional chaining, no nullish coalescing.
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
    c: null, // compiled rules
    baseCss: '',
    css: '',
    href: null,
    path: '/',
    leaving: false, // a redirect away from this document is under way
    stuck: false, // blocked content in a redirect loop: blank until the address changes
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

