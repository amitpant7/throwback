  // ---------------------------------------------------------------- passes

  function scanAll() {
    if (!document.documentElement) return;
    scanHide(document.documentElement);
    scanText(document.documentElement);
    approvePosts(document.documentElement);
    schedulePass(true);
  }

  /**
   * The steps of a pass, in order. Each runs on its own: one that throws (after an Instagram change)
   * is noted and the others still run, checkReady above all (the app waits for it).
   */
  var PASS_STEPS = [
    function missedLocation() {
      if (location.href !== state.href) onLocation('replace'); // a change the history hooks missed
    },
    ensureStyle,
    fixViewport,
    updateProfileEntry,
    updateCallEntry,
    updateSettingsSection,
    function approveMain() {
      approvePosts(document.querySelector('main'));
    },
    updateEndCard,
    dismissDialogs,
    applyExplore,
    function navBars() {
      detectNav(false);
    },
    hideAfterMarkers,
    checkReady,
    function background() {
      if (state.readySent && state.bgDirty) {
        state.bgDirty = false;
        reportBackground();
      }
    }
  ];

  /** Page-wide work, at most once per frame (before that frame paints). */
  function schedulePass(forceNav) {
    if (forceNav) state.lastNavScan = 0;
    if (state.passQueued) return;
    state.passQueued = true;
    raf(function () {
      state.passQueued = false;
      if (!state.c) return;
      for (var i = 0; i < PASS_STEPS.length; i++) {
        try {
          PASS_STEPS[i]();
        } catch (e) {
          noteError(PASS_STEPS[i].name || 'pass', e);
        }
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

  var TEXT_PASS_MS = 250;

  function onMutations(records) {
    if (!state.c) return;
    perfChanged();
    ensureStyle();
    watchRootClass();
    var root = document.documentElement;
    if (root && !root.classList.contains(state.mode === MODE_MESSAGES ? 'tb-messages-only' : 'tb-full')) applyClasses();
    var added = [];
    var texts = []; // parents of new or changed text, each once
    var textSet = new Set();
    var noteText = function (t) {
      var p = t.parentElement;
      if (p && !textSet.has(p) && /\S/.test(t.nodeValue || '')) {
        textSet.add(p);
        texts.push(p);
      }
    };
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'childList') {
        for (var j = 0; j < r.addedNodes.length; j++) {
          var n = r.addedNodes[j];
          if (n.nodeType === 1) added.push(n);
          else if (n.nodeType === 3) noteText(n);
        }
      } else if (r.type === 'characterData') {
        noteText(r.target);
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
    var checked = new Set();
    for (var t = 0; scanTexts && t < texts.length; t++) {
      if (texts[t].isConnected && !checked.has(texts[t])) {
        checked.add(texts[t]);
        checkTextElement(texts[t], 0, checked);
      }
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
    // A pass after new elements, or text that is scanned. Text that changes all the time where it
    // isn't scanned (typing and timestamps in chats, the story viewer) gets at most one pass per
    // TEXT_PASS_MS, which still lets dialogs and the call button see their text.
    if (added.length || (texts.length && scanTexts)) schedulePass(false);
    else if (texts.length && !state.textPassTimer) {
      state.textPassTimer = setTimeout(function () {
        state.textPassTimer = 0;
        schedulePass(false);
      }, TEXT_PASS_MS);
    }
  }

