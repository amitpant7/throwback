  // ---------------------------------------------------------------- Throwback's own entry point

  /** Throwback's mark as a line icon, drawn in the page's text colour like Instagram's icons. */
  var THROWBACK_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M5.4 10.4A7.2 7.2 0 1 1 5.2 15.6"/><path d="M4.8 5.4v5h5"/></svg>';

  /**
   * On the user's own profile (the only profile whose top bar has Instagram's settings gear), the
   * Threads link at the top-right is hidden and Throwback's settings icon takes its place.
   */
  function updateProfileEntry() {
    var existing = document.getElementById(PROFILE_ENTRY_ID);
    // Only profile-shaped paths (/name/): not a whole-document query every frame on every page.
    if (!existing && !/^\/(?!(explore|direct|reels?|accounts|p|stories)\/?$)[^\/]+\/?$/.test(location.pathname)) return;
    var gear = state.mode === MODE_MESSAGES ? null : document.querySelector('a[href^="/accounts/settings/"]');
    var gearBox = gear && gear.getBoundingClientRect();
    if (!gear || gearBox.width === 0 || gearBox.top > 80) {
      if (existing) existing.parentNode.removeChild(existing);
      return;
    }
    if (existing && existing.isConnected) return;
    // The other top-bar button, at the far right of the same bar (the Threads link). Once found it
    // stays tagged (and hidden), so it is found again after the bar was hidden for a while.
    var threads = document.querySelector('[' + ATTR_THREADS + ']');
    for (var up = gear.parentElement, depth = 0; up && depth < 6 && !threads; up = up.parentElement, depth++) {
      var candidates = up.querySelectorAll('a, [role="link"], [role="button"]');
      for (var i = 0; i < candidates.length; i++) {
        var c = candidates[i];
        if (c === gear || c.id === PROFILE_ENTRY_ID || !c.querySelector('svg')) continue;
        var box = c.getBoundingClientRect();
        if (box.width > 0 && box.width <= 48 && box.top < 80 && box.left > window.innerWidth * 0.7) {
          threads = c;
          break;
        }
      }
    }
    if (!threads || !threads.parentNode) return;
    var size = Math.round(threads.getBoundingClientRect().width) || +threads.getAttribute(ATTR_THREADS) || 24;
    // Match Instagram's own top-bar icons (the gear), not the link colour of the Threads link.
    var gearIcon = gear.querySelector('svg');
    var color = getComputedStyle(gearIcon || gear).color;
    threads.setAttribute(ATTR_THREADS, String(size)); // remembers its size for when it is hidden
    var btn = document.createElement('a');
    btn.id = PROFILE_ENTRY_ID;
    btn.href = '#';
    btn.setAttribute('role', 'button');
    btn.setAttribute('aria-label', 'Throwback settings');
    btn.innerHTML = THROWBACK_ICON;
    btn.style.cssText = 'display:flex;align-items:center;justify-content:center;cursor:pointer;' +
      'width:' + size + 'px;height:' + size + 'px;color:' + color + ';-webkit-tap-highlight-color:transparent';
    tapToApp(btn, { t: 'settings' });
    threads.parentNode.insertBefore(btn, threads.nextSibling);
  }

  // ---------------------------------------------------------------- settings-page section

  /*
   * On Instagram's settings page, a "Throwback" section right after "Your account" (rules:
   * settingsSection) opens Throwback's settings. Styled from Instagram's own heading and rows, so it
   * looks native; re-added on the next pass if Instagram re-renders the page.
   */
  var SETTINGS_SECTION_ID = '__tb_settings_section';

  function updateSettingsSection() {
    var ss = state.c && state.c.settingsSection;
    var existing = document.getElementById(SETTINGS_SECTION_ID);
    if (!ss || state.mode === MODE_MESSAGES || !ss.re.test(location.pathname)) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      // Its row at the end of the page too: Instagram may keep <main> across the route change.
      var appRow = document.getElementById(APP_SETTINGS_ROW_ID);
      if (appRow && appRow.parentNode) appRow.parentNode.removeChild(appRow);
      return;
    }
    // The heading is looked for only while something is missing (a whole-page text search).
    var missingRow = state.createInApp && !document.getElementById(APP_SETTINGS_ROW_ID);
    var heading = (!existing || !existing.isConnected || missingRow) ? findExactText(ss.after) : null;
    if (!existing || !existing.isConnected) {
      if (!heading) return;
      var place = sectionPlacement(heading);
      if (!place) return;
      place.parent.insertBefore(buildSettingsSection(heading, place.sample), place.before);
    }
    updateAppSettingsRow(heading);
  }

  /** The element whose whole text is one of the given texts (deepest match, in reading order). */
  function findExactText(set) {
    var root = document.querySelector('main') || document.body;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) {
      var el = t.parentElement;
      if (el && !el.closest('#' + SETTINGS_SECTION_ID) && set.has(norm(el.textContent || ''))) return el;
    }
    return null;
  }

  /**
   * Where the section goes. Usually the heading sits in a block with its rows: insert after that block.
   * If headings and rows are flat siblings in one long list, insert after the row following the heading.
   */
  function sectionPlacement(heading) {
    var label = (heading.textContent || '').trim();
    var n = heading;
    for (var depth = 0; depth < 8; depth++) {
      var p = n.parentElement;
      if (!p || p === document.body || p.tagName === 'MAIN') return null;
      var text = (p.textContent || '').trim();
      if (text.length > label.length + 2) {
        if (text.length <= 400 && p.parentElement) return { parent: p.parentElement, before: p.nextSibling, sample: p };
        var row = n.nextElementSibling;
        return { parent: p, before: row ? row.nextSibling : n.nextSibling, sample: row || p };
      }
      n = p;
    }
    return null;
  }

  /** Text styles of the first two text lines in sample after the heading (a row's label and subtitle). */
  function rowTextStyles(sample, heading) {
    var out = [];
    var walker = document.createTreeWalker(sample, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t && out.length < 2; t = walker.nextNode()) {
      var el = t.parentElement;
      if (!el || !(t.textContent || '').trim() || heading.contains(el)) continue;
      var cs = getComputedStyle(el);
      out.push({ size: cs.fontSize, weight: cs.fontWeight, color: cs.color });
    }
    return out;
  }

  function buildSettingsSection(heading, sample) {
    var hs = getComputedStyle(heading);
    var rows = rowTextStyles(sample, heading);
    var label = rows[0] || { size: '15px', weight: '400', color: hs.color };
    var sub = rows[1] || { size: '13px', weight: '400', color: label.color };
    var left = Math.max(12, Math.round(heading.getBoundingClientRect().left)) + 'px';

    var wrap = document.createElement('div');
    wrap.id = SETTINGS_SECTION_ID;
    wrap.style.cssText = 'font-family:inherit;padding:8px 0';
    var title = document.createElement('div');
    title.textContent = 'Throwback';
    title.style.cssText = 'font-size:' + hs.fontSize + ';font-weight:' + hs.fontWeight + ';color:' + hs.color +
      ';padding:12px ' + left + ' 8px ' + left;
    var row = document.createElement('div');
    row.setAttribute('role', 'button');
    row.setAttribute('tabindex', '0');
    row.setAttribute('aria-label', 'Throwback settings');
    row.style.cssText = 'display:flex;align-items:center;gap:12px;padding:12px ' + left + ';cursor:pointer;' +
      'color:' + label.color + ';-webkit-tap-highlight-color:transparent';
    row.innerHTML =
      '<span style="display:flex;width:24px;height:24px;flex:none">' + THROWBACK_ICON + '</span>' +
      '<span style="display:flex;flex-direction:column;gap:2px;flex:1;min-width:0">' +
      '<span style="font-size:' + label.size + ';font-weight:' + label.weight + '">Throwback settings</span>' +
      '<span style="font-size:' + sub.size + ';color:' + sub.color + (rows[1] ? '' : ';opacity:.65') +
      '">Your day, time limit, Instagram app</span></span>' +
      '<svg aria-hidden="true" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="opacity:.5;flex:none">' +
      '<path d="m9 6 6 6-6 6"/></svg>';
    tapToApp(row, { t: 'settings' });
    row.addEventListener('touchstart', function () {
      row.style.background = 'rgba(128,128,128,.14)';
    }, { passive: true });
    ['touchend', 'touchcancel'].forEach(function (ev) {
      row.addEventListener(ev, function () {
        row.style.background = '';
      }, { passive: true });
    });
    wrap.appendChild(title);
    wrap.appendChild(row);
    return wrap;
  }

  /**
   * Long-press on the Home tab of the bottom bar opens Throwback's settings (a normal tap still goes
   * home). The Home tab is the bottom-bar link to "/" (any feed variant).
   */
  var LONG_PRESS_MS = 550;
  var longPress = { timer: 0, fired: false, x: 0, y: 0 };
  function homeTabFrom(target) {
    var el = target && target.closest ? target.closest('a[href="/"], a[href^="/?"]') : null;
    if (!el) return null;
    var box = el.getBoundingClientRect();
    return box.top > window.innerHeight - 120 ? el : null; // only the bottom bar, not header links
  }
  function cancelLongPress() {
    if (longPress.timer) clearTimeout(longPress.timer);
    longPress.timer = 0;
  }
  function installHomeLongPress() {
    document.addEventListener('touchstart', function (e) {
      longPress.fired = false;
      if (state.mode === MODE_MESSAGES || !e.touches || e.touches.length !== 1) return;
      if (!homeTabFrom(e.target)) return;
      longPress.x = e.touches[0].clientX;
      longPress.y = e.touches[0].clientY;
      cancelLongPress();
      longPress.timer = setTimeout(function () {
        longPress.timer = 0;
        longPress.fired = true;
        send({ t: 'settings' });
      }, LONG_PRESS_MS);
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', function (e) {
      if (!longPress.timer || !e.touches || !e.touches.length) return;
      var dx = e.touches[0].clientX - longPress.x, dy = e.touches[0].clientY - longPress.y;
      if (dx * dx + dy * dy > 100) cancelLongPress(); // moved more than ~10px: not a press
    }, { capture: true, passive: true });
    document.addEventListener('touchend', cancelLongPress, { capture: true, passive: true });
    document.addEventListener('touchcancel', cancelLongPress, { capture: true, passive: true });
    // After a long-press, swallow the tap that follows so the feed doesn't reload, and the
    // browser's own long-press menu.
    // Registered on window before Throwback's own click handler, so it runs first and can stop it.
    ['click', 'contextmenu'].forEach(function (type) {
      window.addEventListener(type, function (e) {
        if (longPress.fired && homeTabFrom(e.target)) {
          e.preventDefault();
          e.stopImmediatePropagation();
          if (type === 'click') longPress.fired = false;
        }
      }, true);
    });
  }

