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
      updateCallEntry();
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

