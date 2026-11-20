  // ---------------------------------------------------------------- dialogs

  var DIALOG_MAX_CHECKS = 20; // a dialog that never matches (a chat, the story viewer) stops being read
  var DIALOG_TEXT_MAX = 4000;

  function dismissDialogs() {
    var c = state.c;
    if (!c.dialogs.length || !dialogTries) return;
    // At most every 200 ms (a whole-document query); a timer makes sure the check still runs.
    var nowD = Date.now();
    if (nowD - (state.lastDialogScan || 0) < 200) {
      recheckDialogsIn(200);
      return;
    }
    state.lastDialogScan = nowD;
    var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      var tries = dialogTries.get(d) || 0;
      if (tries >= c.dismissMaxAttempts) continue;
      // Reading a dialog's text costs (it may be a whole DM thread or story viewer): at most every
      // 500 ms, and at most DIALOG_MAX_CHECKS times.
      var now = Date.now();
      var seen = dialogChecked.get(d) || { at: 0, n: 0 };
      if (seen.n >= DIALOG_MAX_CHECKS) continue;
      if (now - seen.at < DIALOG_CHECK_MS) {
        recheckDialogsIn(DIALOG_CHECK_MS); // even if nothing else changes on the page by then
        continue;
      }
      dialogChecked.set(d, { at: now, n: seen.n + 1 });
      var text = norm(textStart(d, DIALOG_TEXT_MAX));
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

  function recheckDialogsIn(ms) {
    if (state.dialogRecheck) return;
    state.dialogRecheck = setTimeout(function () {
      state.dialogRecheck = 0;
      schedulePass(false);
    }, ms);
  }

  /** The first max characters of el's text, without reading the rest of a large subtree. */
  function textStart(el, max) {
    var out = '';
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n;
    while (out.length < max && (n = walker.nextNode())) out += n.nodeValue;
    return out.slice(0, max);
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

