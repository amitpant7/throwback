  // ---------------------------------------------------------------- dialogs

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

