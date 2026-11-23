  // ---------------------------------------------------------------- swipe to reply, hold to react (chats)

  /*
   * The Instagram app's two chat gestures, which the mobile website lacks (owner, 2026-11-23,
   * DECISIONS.md #34jj): swipe a message to the right to reply to it, and hold it to open the
   * reaction bar straight away. Both press Instagram's own buttons from the message's action
   * toolbar, which the website keeps in the page for each message (drawn on hover on a computer, on
   * a hold on a phone). Throwback never sends anything: Reply opens the reply box and React opens
   * the emoji bar; the user types or picks as usual.
   *
   * Instagram's English labels, as the call button uses (26-chat-call.js).
   */
  var CHAT_ROW = 'div[role="group"]:not([aria-label])';
  var CHAT_BUBBLE = 'div[role="button"]';
  var CHAT_ACTIONS = '[aria-label="Message actions"]';
  var CHAT_REPLY = '[role="button"][aria-label^="Reply to message"]';
  var CHAT_REACT = '[role="button"][aria-label^="React to message"]';
  var SWIPE_START_PX = 12; // sideways movement before a touch counts as a swipe
  var SWIPE_REPLY_PX = 64; // let go past this: reply
  var SWIPE_MAX_PX = 96; // how far the message follows the finger
  var SWIPE_EDGE_PX = 24; // touches from the screen's edge are Android's back gesture
  var HOLD_MS = 450;
  var REPLY_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 14 4 9l5-5"/>' +
    '<path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/></svg>';
  var cg = null; // the touch in progress on a message: {row, bubble, x, y, dx, swiping, held, timer, hint}

  /** The message under a touch: its row (avatar and bubble) and the bubble, or null. */
  function chatMessageAt(target) {
    if (!/^\/direct\/t\//.test(location.pathname) || !target || !target.closest) return null;
    if (target.closest(CHAT_ACTIONS)) return null; // the toolbar's own buttons
    var row = target.closest(CHAT_ROW);
    if (!row || !row.querySelector(CHAT_BUBBLE)) return null;
    return { row: row, bubble: target.closest(CHAT_BUBBLE) };
  }

  /**
   * Presses one of the message's toolbar buttons. The website keeps them in the page; if a message
   * has none yet, a hover (what a computer's mouse does) makes Instagram add them.
   */
  function pressChatButton(row, selector) {
    var tries = 0;
    (function attempt() {
      if (!document.contains(row)) return;
      var b = row.querySelector(selector);
      if (b) {
        b.click();
        return;
      }
      if (tries === 0) row.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, relatedTarget: document.body, view: window }));
      if (++tries < 6) setTimeout(attempt, 100);
    })();
  }

  function chatSwipeHint(g) {
    if (g.hint) return g.hint;
    var box = g.row.getBoundingClientRect();
    var h = document.createElement('div');
    h.innerHTML = REPLY_ICON;
    h.style.cssText = 'position:fixed;z-index:2147483000;pointer-events:none;width:32px;height:32px;border-radius:50%;' +
      'display:flex;align-items:center;justify-content:center;background:rgba(128,128,128,.18);opacity:0;' +
      'left:' + Math.max(4, box.left) + 'px;top:' + Math.round(box.top + box.height / 2 - 16) + 'px;' +
      'color:' + getComputedStyle(g.row).color;
    document.documentElement.appendChild(h);
    g.hint = h;
    return h;
  }

  /** The message slides back; the hint goes. */
  function endChatGesture() {
    var g = cg;
    cg = null;
    if (!g) return;
    if (g.timer) clearTimeout(g.timer);
    if (g.hint && g.hint.parentNode) g.hint.parentNode.removeChild(g.hint);
    if (g.swiping) {
      var row = g.row;
      row.style.transition = 'transform .18s ease-out';
      row.style.transform = '';
      setTimeout(function () {
        row.style.transition = '';
      }, 200);
    }
  }

  function installChatGestures() {
    document.addEventListener('touchstart', function (e) {
      endChatGesture();
      if (!e.touches || e.touches.length !== 1) return;
      var t = e.touches[0];
      if (t.clientX < SWIPE_EDGE_PX || t.clientX > window.innerWidth - SWIPE_EDGE_PX) return;
      var m = chatMessageAt(e.target);
      if (!m) return;
      var g = { row: m.row, bubble: m.bubble, x: t.clientX, y: t.clientY, dx: 0, swiping: false, held: false, timer: 0, hint: null };
      // Holding the bubble itself: Instagram draws its toolbar; on letting go, its emoji bar opens.
      if (m.bubble) {
        g.timer = setTimeout(function () {
          g.timer = 0;
          g.held = true;
        }, HOLD_MS);
      }
      cg = g;
    }, { capture: true, passive: true });

    // Passive, so scrolling stays smooth on every page: a chat doesn't scroll sideways, so a swipe
    // has nothing to fight with.
    document.addEventListener('touchmove', function (e) {
      var g = cg;
      if (!g || !e.touches || e.touches.length !== 1) return;
      var dx = e.touches[0].clientX - g.x, dy = e.touches[0].clientY - g.y;
      if (!g.swiping) {
        if (dx * dx + dy * dy > 100) {
          if (g.timer) clearTimeout(g.timer);
          g.timer = 0;
          if (!g.held && dx > SWIPE_START_PX && dx > Math.abs(dy) * 1.5) g.swiping = true;
          else if (!g.held || Math.abs(dy) > SWIPE_START_PX) { endChatGesture(); return; } // scrolling
        }
        if (!g.swiping) return;
      }
      g.dx = Math.max(0, Math.min(SWIPE_MAX_PX, dx - SWIPE_START_PX));
      g.row.style.transition = '';
      g.row.style.transform = 'translateX(' + g.dx + 'px)';
      chatSwipeHint(g).style.opacity = String(Math.min(1, g.dx / SWIPE_REPLY_PX));
    }, { capture: true, passive: true });

    document.addEventListener('touchend', function () {
      var g = cg;
      if (!g) return;
      var reply = g.swiping && g.dx >= SWIPE_REPLY_PX;
      var react = !g.swiping && g.held;
      endChatGesture();
      // Right away, while the touch still counts as the user's: the reply box can bring up the keyboard.
      if (reply) pressChatButton(g.row, CHAT_REPLY);
      // A moment later: after any tap the browser makes of the hold, which would close the emoji bar.
      else if (react) setTimeout(function () { pressChatButton(g.row, CHAT_REACT); }, 60);
    }, { capture: true, passive: true });
    document.addEventListener('touchcancel', endChatGesture, { capture: true, passive: true });
  }

