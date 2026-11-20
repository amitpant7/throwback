  // ---------------------------------------------------------------- Instagram app hand-offs (posting, its settings)

  /*
   * Posting through the Instagram app (owner, 2026-11-20): with the app connected, "+" and "Your
   * story" open its create screen (better editor: music, filters, reels) instead of the website's.
   * The app's guard allows only those screens. Buttons from the rules (appCreate.triggers).
   */
  function createInAppTap(e) {
    var ac = state.c && state.c.appCreate;
    if (!state.createInApp || !ac || state.mode === MODE_MESSAGES || !e.target || !e.target.closest) return false;
    var hit = false;
    var kind = 'post';
    for (var i = 0; i < ac.triggers.length && !hit; i++) {
      var t = ac.triggers[i];
      kind = t.kind === 'story' ? 'story' : 'post';
      if (t.selector) {
        var el = e.target.closest('[role=button],[role=link],button,a');
        hit = !!(el && (el.matches(t.selector) || el.querySelector(t.selector)));
      } else if (t.text && t.text.length) {
        var b = e.target.closest('[role=button],[role=link],button');
        hit = !!(b && t.text.indexOf((b.textContent || '').trim()) !== -1 && (!t.fileInput || b.querySelector('input[type=file]')));
      }
    }
    if (!hit) return false;
    // Story through the app only when a story pass is available (30 min apart, a few a day);
    // otherwise the website's own story upload runs as usual.
    if (kind === 'story' && !state.storyInApp) {
      send({ t: 'storyWeb' }); // the app says why (the wait), then the website's own upload runs
      return false;
    }
    e.preventDefault();
    e.stopImmediatePropagation();
    send({ t: 'createInApp', kind: kind });
    return true;
  }

  /*
   * With the Instagram app connected (owner, 2026-11-20): a row at the very end of Instagram's
   * settings page opens the app's own "Settings and activity" (some settings exist only there). The
   * app's guard allows its settings screens, and other screens for a few minutes in total.
   */
  var APP_SETTINGS_ROW_ID = '__tb_app_settings';

  function updateAppSettingsRow(heading) {
    var row = document.getElementById(APP_SETTINGS_ROW_ID);
    var main = document.querySelector('main');
    if (!state.createInApp || !main) {
      if (row && row.parentNode) row.parentNode.removeChild(row);
      return;
    }
    if (row && row.isConnected && !row.nextElementSibling) return; // already last
    if (!row) {
      if (!heading) return;
      var section = document.getElementById(SETTINGS_SECTION_ID);
      var styleRow = section && section.querySelector('[role=button]');
      row = buildAppSettingsRow(heading, styleRow);
    }
    main.appendChild(row);
  }

  function buildAppSettingsRow(heading, styleRow) {
    var hs = getComputedStyle(heading);
    var left = Math.max(12, Math.round(heading.getBoundingClientRect().left)) + 'px';
    var color = styleRow ? getComputedStyle(styleRow).color : hs.color;
    var wrap = document.createElement('div');
    wrap.id = APP_SETTINGS_ROW_ID;
    wrap.style.cssText = 'font-family:inherit;padding:8px 0 24px';
    wrap.appendChild(appRow({
      icon: '<svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" ' +
        'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><rect x="3.5" y="3.5" ' +
        'width="17" height="17" rx="5"/><circle cx="12" cy="12" r="4"/><circle cx="17" cy="7" r=".8" fill="currentColor"/></svg>',
      title: 'Open settings in the Instagram app',
      sub: 'For settings this page doesn’t have',
      subStyle: 'font-size:12px;opacity:.65',
      trail: '<svg aria-hidden="true" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" ' +
        'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="opacity:.5;flex:none">' +
        '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
      color: color,
      left: left,
      msg: { t: 'settingsInApp' }
    }));
    return wrap;
  }

