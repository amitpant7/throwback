  // ---------------------------------------------------------------- navigation bars

  /**
   * Navigation bars: the smallest blocks holding at least minLinks nav links and no content.
   * Hidden in messages-only mode; slots whose only button is hidden collapse.
   */
  function detectNav(force) {
    var nav = state.c.nav;
    if (!nav) return;
    var now = Date.now();
    if (force || now - state.lastNavScan > NAV_SCAN_MS) {
      state.lastNavScan = now;
      var links = document.querySelectorAll(nav.links);
      for (var i = 0; i < links.length; i++) {
        // Links inside a post (hashtags, mentions) are never the navigation bar.
        if (links[i].closest('[' + ATTR_NAV + '], article')) continue;
        var n = links[i].parentElement;
        for (var depth = 0; n && depth < 10 && n !== document.body; depth++, n = n.parentElement) {
          if (n.querySelector('main, article, input, textarea, [contenteditable="true"], [role="main"]')) break;
          if (n.querySelectorAll(nav.links).length >= nav.minLinks) {
            if (n.querySelectorAll('a[href]').length <= 12) n.setAttribute(ATTR_NAV, '1');
            break;
          }
        }
      }
      if (state.mode === MODE_FULL) {
        var bars = document.querySelectorAll('[' + ATTR_NAV + ']');
        for (var b = 0; b < bars.length; b++) collapseSlots(bars[b]);
      }
    }
  }

  /** display:none on el or an ancestor below stop (stop itself excluded). */
  function hiddenBelow(el, stop) {
    for (var n = el; n && n !== stop; n = n.parentElement) {
      if (getComputedStyle(n).display === 'none') return true;
    }
    return false;
  }

  function collapseSlots(bar) {
    var buttons = bar.querySelectorAll('a[href], button, [role="button"], [role="link"]');
    for (var i = 0; i < buttons.length; i++) {
      var button = buttons[i];
      var slot = button;
      while (slot.parentElement && slot.parentElement !== bar &&
             slot.parentElement.querySelectorAll('a[href], button, [role="button"], [role="link"]').length <= 1) {
        slot = slot.parentElement;
      }
      if (slot === button) continue;
      // The slot's own collapse is excluded, so a button that shows again un-collapses its slot.
      var collapse = hiddenBelow(button, slot);
      if (collapse && !slot.hasAttribute(ATTR_SLOT)) slot.setAttribute(ATTR_SLOT, '1');
      else if (!collapse && slot.hasAttribute(ATTR_SLOT)) slot.removeAttribute(ATTR_SLOT);
    }
  }

