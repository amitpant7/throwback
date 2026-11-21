  // ---------------------------------------------------------------- dialogs

  // Each dialog is read at most the rules' dismissMaxAttempts times, at most every DIALOG_CHECK_MS: one
  // that never matches (the story viewer) or whose button never shows stops being read. Capped, so a
  // large value can't keep a dialog being read for minutes.
  var DIALOG_MAX_CHECKS = 120; // a minute
  var DIALOG_TEXT_MAX = 4000;

  function dismissDialogs() {
    var c = state.c;
    if (!c.dialogs.length || !dialogChecked) return;
    // At most every 200 ms (a whole-document query); a timer makes sure the check still runs.
    var nowD = Date.now();
    if (nowD - (state.lastDialogScan || 0) < 200) {
      recheckDialogsIn(200);
      return;
    }
    state.lastDialogScan = nowD;
    var maxChecks = Math.min(c.dismissMaxAttempts, DIALOG_MAX_CHECKS);
    var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      // Reading a dialog's text costs (it may be a whole DM thread or story viewer): at most every
      // 500 ms, and at most maxChecks times (n is Infinity once dismissed).
      var now = Date.now();
      var seen = dialogChecked.get(d) || { at: 0, n: 0 };
      if (seen.n >= maxChecks) continue;
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
        var button = findButton(d, rule);
        if (button) {
          dialogChecked.set(d, { at: now, n: Infinity });
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
      if (!x) return null;
      var button = closestWithin(x, 'button, [role="button"], a', 4) || x;
      // Only a dismiss button (DECISIONS.md #34d): the element's label must be one of the rule's
      // texts, which the app accepts only from its list of dismiss labels. A selector alone can match anything.
      var labelled = [x, button].some(function (e) {
        var label = e.getAttribute('aria-label') || e.textContent;
        return label && label.length <= 120 && rule.click.has(norm(label));
      });
      if (labelled) return button;
    }
    return null;
  }

