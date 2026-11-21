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
    if (state.leaving || state.stuck) want[CLASS_BLANK] = true;
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

