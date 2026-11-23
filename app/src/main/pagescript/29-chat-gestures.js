  // ---------------------------------------------------------------- swipe to reply, hold to react (chats)

  /*
   * The Instagram app's two chat gestures, which the mobile website lacks (owner, 2026-11-23,
   * DECISIONS.md #34jj): swipe a message sideways (either way) to reply to it, and hold it to open the
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
  var HOLD_MS = 400;
  var HOLD_SWALLOW_MS = 1000; // after the emoji bar opens, the tap the browser makes of the hold
  var REPLY_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 14 4 9l5-5"/>' +
    '<path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/></svg>';
  var cg = null; // the touch in progress on a message: {row, bubble, x, y, dx, swiping, held, timer, hint, frame}
  var chatHeldRow = null, chatHeldAt = 0; // the row whose emoji bar a hold opened, and when

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
    var until = Date.now() + 600;
    (function attempt() {
      if (!document.contains(row)) return;
      var b = row.querySelector(selector);
      if (b) {
        b.click();
        return;
      }
      warmChatRow(row);
      if (Date.now() < until) requestAnimationFrame(attempt); // checked every frame, not every 100 ms
    })();
  }

  /** Makes Instagram add the message's toolbar (a hover), if it isn't in the page already. */
  function warmChatRow(row) {
    if (row.querySelector(CHAT_ACTIONS) || row.getAttribute('data-tb-warm')) return;
    row.setAttribute('data-tb-warm', '1');
    row.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, relatedTarget: document.body, view: window }));
  }

  function chatSwipeHint(g) {
    if (g.hint) return g.hint;
    var box = g.row.getBoundingClientRect();
    var left = g.dir > 0 ? Math.max(4, box.left) : Math.min(window.innerWidth - 36, box.right - 32);
    var h = document.createElement('div');
    h.innerHTML = REPLY_ICON;
    h.style.cssText = 'position:fixed;z-index:2147483000;pointer-events:none;width:32px;height:32px;border-radius:50%;' +
      'display:flex;align-items:center;justify-content:center;background:rgba(128,128,128,.18);opacity:0;' +
      'left:' + left + 'px;top:' + Math.round(box.top + box.height / 2 - 16) + 'px;' +
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
    if (g.frame) cancelAnimationFrame(g.frame);
    if (g.hint && g.hint.parentNode) g.hint.parentNode.removeChild(g.hint);
    if (g.swiping) {
      var row = g.row;
      row.style.transition = 'transform .18s ease-out';
      row.style.transform = '';
      setTimeout(function () {
        row.style.transition = '';
        row.style.willChange = '';
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
      var g = { row: m.row, bubble: m.bubble, x: t.clientX, y: t.clientY, dx: 0, dir: 1, swiping: false, held: false, timer: 0, hint: null, frame: 0 };
      // Holding the bubble itself opens the emoji bar while the finger is still down, as in the app.
      if (m.bubble) {
        g.timer = setTimeout(function () {
          g.timer = 0;
          g.held = true;
          chatHeldRow = g.row;
          chatHeldAt = Date.now();
          pressChatButton(g.row, CHAT_REACT);
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
        // Moved more than a wobble: no longer a hold.
        if (g.timer && dx * dx + dy * dy > 100) {
          clearTimeout(g.timer);
          g.timer = 0;
        }
        var ax = Math.abs(dx), ay = Math.abs(dy);
        if (!g.held && ax > SWIPE_START_PX && ax > ay * 1.5) {
          g.swiping = true;
          g.dir = dx > 0 ? 1 : -1;
          warmChatRow(g.row); // the Reply button is ready by the time the finger lifts
        } else if (ay > SWIPE_START_PX || (g.held ? false : ax > SWIPE_START_PX)) {
          endChatGesture(); // scrolling, or a diagonal drag: not a swipe
          return;
        }
        // Otherwise not clear yet which way the finger is going: wait for the next move.
        if (!g.swiping) return;
      }
      g.dx = Math.max(0, Math.min(SWIPE_MAX_PX, dx * g.dir - SWIPE_START_PX)); // distance the chosen way
      // One style change a frame, however many touch events arrive.
      if (!g.frame) {
        g.frame = requestAnimationFrame(function () {
          g.frame = 0;
          if (cg !== g) return;
          g.row.style.transition = '';
          g.row.style.willChange = 'transform';
          g.row.style.transform = 'translate3d(' + g.dx * g.dir + 'px,0,0)';
          chatSwipeHint(g).style.opacity = String(Math.min(1, g.dx / SWIPE_REPLY_PX));
        });
      }
    }, { capture: true, passive: true });

    document.addEventListener('touchend', function () {
      var g = cg;
      if (!g) return;
      var reply = g.swiping && g.dx >= SWIPE_REPLY_PX;
      endChatGesture();
      // Right away, while the touch still counts as the user's: the reply box can bring up the keyboard.
      if (reply) pressChatButton(g.row, CHAT_REPLY);
    }, { capture: true, passive: true });
    document.addEventListener('touchcancel', endChatGesture, { capture: true, passive: true });
    // After a hold opened the emoji bar, the tap or long-press menu the browser makes of that same
    // touch would land on the message and close the bar again: swallowed. Registered on window, so
    // it runs before Instagram's handlers.
    ['click', 'contextmenu'].forEach(function (type) {
      window.addEventListener(type, function (e) {
        if (!chatHeldRow || Date.now() - chatHeldAt > HOLD_SWALLOW_MS) return;
        if (!e.target || !e.target.closest || !chatHeldRow.contains(e.target) || e.target.closest(CHAT_ACTIONS)) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        if (type === 'click') chatHeldRow = null;
      }, true);
    });
  }

