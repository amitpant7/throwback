  // ---------------------------------------------------------------- call button in chats

  /*
   * Instagram's mobile website has no call buttons in chats (only the desktop site does), so a
   * phone icon goes in the chat's top bar, before "Conversation information". Tapping it asks the
   * app to open this chat in the real Instagram app (with a short guard pass) to start the call.
   */
  var CALL_ENTRY_ID = '__tb_call_entry';
  var CALL_ICON =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 4h3l1.6 4-2 1.3a11 11 0 0 0 ' +
    '5.1 5.1l1.3-2 4 1.6v3a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/></svg>';

  function updateCallEntry() {
    var existing = document.getElementById(CALL_ENTRY_ID);
    var m = /^\/direct\/t\/(\d{5,40})\/?$/.exec(location.pathname);
    var info = m && document.querySelector('svg[aria-label="Conversation information"]');
    var infoBtn = info && (info.closest('[role="button"], button, a') || info.parentElement);
    if (!infoBtn || !infoBtn.parentNode || infoBtn.getBoundingClientRect().top > 120) {
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
      return;
    }
    // The other person's username (one-to-one chats): the profile link in the top bar.
    var user = '';
    var bar = infoBtn.parentNode;
    for (var up = 0; bar && up < 4 && !user; up++, bar = bar.parentNode) {
      var links = bar.querySelectorAll ? bar.querySelectorAll('a[href]') : [];
      for (var i = 0; i < links.length && !user; i++) {
        var um = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(links[i].getAttribute('href') || '');
        if (um && links[i].getBoundingClientRect().top < 120) user = um[1];
      }
    }
    if (existing && existing.isConnected && existing.nextSibling === infoBtn) {
      existing.setAttribute('data-thread', m[1]);
      existing.setAttribute('data-user', user);
      return;
    }
    var size = Math.round(infoBtn.getBoundingClientRect().width) || 40;
    var btn = existing || document.createElement('div');
    btn.id = CALL_ENTRY_ID;
    btn.setAttribute('role', 'button');
    btn.setAttribute('tabindex', '0');
    btn.setAttribute('aria-label', 'Call in the Instagram app');
    btn.setAttribute('data-thread', m[1]);
    btn.setAttribute('data-user', user);
    btn.innerHTML = CALL_ICON;
    btn.style.cssText = 'display:flex;align-items:center;justify-content:center;cursor:pointer;flex:none;' +
      'width:' + size + 'px;height:' + size + 'px;color:' + getComputedStyle(info).color +
      ';-webkit-tap-highlight-color:transparent';
    if (!existing) {
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        send({ t: 'callInApp', thread: btn.getAttribute('data-thread') || '', user: btn.getAttribute('data-user') || '' });
      }, true);
    }
    infoBtn.parentNode.insertBefore(btn, infoBtn);
  }

