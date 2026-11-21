  // ---------------------------------------------------------------- helpers

  /*
   * Errors caught where one failing part must not stop the others (the steps of a pass, navigation
   * events): the last ERRORS_MAX, for DevTools (__throwback.errors()). They are not rethrown, so they
   * never reach Instagram's own error reporting.
   */
  var ERRORS_MAX = 20;
  var errorLog = [];

  function noteError(where, e) {
    errorLog.push({ where: where, error: String((e && e.message) || e).slice(0, 200), at: Math.round(performance.now()) });
    if (errorLog.length > ERRORS_MAX) errorLog.shift();
  }

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

  /** A JSON value from localStorage, or null (missing, unreadable, or storage unavailable). */
  function loadJson(key) {
    try {
      return JSON.parse(localStorage.getItem(key) || 'null');
    } catch (e) {
      return null;
    }
  }

  /** Stores a JSON value in localStorage; without storage the value lasts for this page only. */
  function saveJson(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      // Storage unavailable or full.
    }
  }

  /** Instagram's media and code CDN (photos, videos, static files). */
  function isCdnHost(hostname) {
    return /(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(hostname);
  }

  /** True when text contains any of the strings in list. */
  function containsAny(text, list) {
    for (var i = 0; i < list.length; i++) if (text.indexOf(list[i]) !== -1) return true;
    return false;
  }

  /**
   * Walks parsed JSON down to maxDepth levels: visit(key, value) is called for every object or array
   * inside it (key is the property name, or the index in an array); when visit returns true, the walk
   * doesn't go into that value.
   */
  function walkJson(o, maxDepth, visit) {
    (function walk(node, depth) {
      if (depth > maxDepth) return;
      var isArray = Array.isArray(node);
      for (var k in node) {
        if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
        var v = node[k];
        if (!v || typeof v !== 'object') continue;
        if (!visit(isArray ? +k : k, v)) walk(v, depth + 1);
      }
    })(o, 0);
  }

