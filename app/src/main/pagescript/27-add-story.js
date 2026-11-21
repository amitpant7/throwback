  // ---------------------------------------------------------------- "+" on "Your story" (another story)

  /*
   * Instagram's website puts a "+" on "Your story" only while the user has no story: once one is up,
   * the bubble opens it instead, and another story is added from the top bar's "+" menu (Post /
   * Story). The Instagram app keeps a "+" on the bubble; so does Throwback (owner, 2026-11-21:
   * people often post several stories). Rules: stories.addStory.
   *
   * Tapping it does what "Your story" does without a story (DECISIONS.md #34cc): with the Instagram
   * app connected and the story budget open, Instagram's story editor through the app; otherwise
   * (budget locked, or the app not connected) the website's own story upload, reached through its
   * "+" menu: Throwback opens the menu and picks "Story" for the user. If "Story" can't be found
   * (renamed, another language), the menu stays open for the user to pick from.
   */
  var ADD_STORY_ID = '__tb_add_story';
  // True while Throwback itself clicks the website's "+" menu: those clicks aren't sent to the app.
  var addStoryClicking = false;
  var addStoryLook = { at: 0, timer: 0 };
  var ADD_STORY_LOOK_MS = 500;

  function updateAddStory() {
    var as = state.c && state.c.addStory;
    var badge = document.getElementById(ADD_STORY_ID);
    var root = document.documentElement;
    if (!as || state.mode === MODE_MESSAGES || !root || !root.classList.contains('tb-on-home')) {
      if (badge && badge.parentNode) badge.parentNode.removeChild(badge);
      return;
    }
    if (badge) {
      var current = badge.closest('[role=button]:not(#' + ADD_STORY_ID + ')');
      if (current && !current.querySelector(as.ownPlus)) return; // still on a bubble with a story
      if (badge.parentNode) badge.parentNode.removeChild(badge);
    }
    // Looking reads the first bubbles' text: at most every ADD_STORY_LOOK_MS (the feed changes on
    // every frame while scrolling), with a pass then so a tray drawn meanwhile isn't missed.
    var now = Date.now();
    if (now - addStoryLook.at < ADD_STORY_LOOK_MS) {
      if (!addStoryLook.timer) {
        addStoryLook.timer = setTimeout(function () {
          addStoryLook.timer = 0;
          schedulePass(false);
        }, ADD_STORY_LOOK_MS);
      }
      return;
    }
    addStoryLook.at = now;
    var bubble = yourStoryBubble(as);
    // No story yet: Instagram's own "+" (and upload) is there, and "Your story" already routes it.
    if (!bubble || bubble.querySelector(as.ownPlus)) return;
    var built = buildAddStory(bubble);
    if (built) tapToStory(built);
  }

  /** "Your story": the first bubble of the tray, found by its label (rules: stories.addStory.yourStory). */
  function yourStoryBubble(as) {
    var main = document.querySelector('main');
    if (!main) return null;
    var buttons = main.querySelectorAll('[role=button]');
    for (var i = 0; i < buttons.length && i < 6; i++) {
      if (as.yourStory.indexOf((buttons[i].textContent || '').trim()) !== -1) return buttons[i];
    }
    return null;
  }

  /** The badge at the avatar's lower right, in Instagram's own blue, ringed in the page colour. */
  function buildAddStory(bubble) {
    var img = bubble.querySelector('img');
    var avatar = img && (img.closest('[role=link]') || img.parentElement);
    var host = avatar && avatar.parentElement;
    if (!host || !bubble.contains(host)) return null;
    if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
    var hb = host.getBoundingClientRect();
    var ab = avatar.getBoundingClientRect();
    if (!ab.width) return null;
    var dot = Math.round(Math.max(18, Math.min(24, ab.width * 0.3)));
    var hit = dot + 10; // a larger tap area around the dot
    var b = document.createElement('div');
    b.id = ADD_STORY_ID;
    b.setAttribute('role', 'button');
    b.setAttribute('aria-label', 'Add to your story');
    b.style.cssText = 'position:absolute;z-index:2;display:flex;align-items:center;justify-content:center;' +
      'cursor:pointer;-webkit-tap-highlight-color:transparent;width:' + hit + 'px;height:' + hit + 'px;' +
      'left:' + Math.round(ab.right - hb.left - dot / 2 - hit / 2 - 2) + 'px;' +
      'top:' + Math.round(ab.bottom - hb.top - dot / 2 - hit / 2 - 2) + 'px';
    b.innerHTML = '<span style="box-sizing:border-box;display:flex;align-items:center;justify-content:center;' +
      'width:' + dot + 'px;height:' + dot + 'px;border-radius:50%;color:#fff;' +
      'background:rgb(var(--ig-primary-button, 0, 149, 246));border:2px solid rgb(var(--ig-primary-background, 255, 255, 255))">' +
      '<svg aria-hidden="true" viewBox="0 0 24 24" width="' + (dot - 8) + '" height="' + (dot - 8) + '" fill="none" ' +
      'stroke="currentColor" stroke-width="3.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg></span>';
    host.appendChild(b);
    return b;
  }

  /** Taps on the badge never reach Instagram (which would open the story already up). */
  function tapToStory(el) {
    el.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      if (state.createInApp && state.storyInApp) {
        send({ t: 'createInApp', kind: 'story' });
        return;
      }
      if (state.createInApp) send({ t: 'storyWeb' }); // the app says why (the lock); the website's upload follows
      openWebStoryUpload();
    }, true);
  }

  /** The website's story upload: its top-bar "+" menu, then "Story" (still within the user's tap). */
  function openWebStoryUpload() {
    var as = state.c.addStory;
    var plus = document.querySelector(as.menuButton);
    if (!plus) return;
    clickAsPage(plus);
    var tries = 0;
    (function pick() {
      var items = document.querySelectorAll(as.menuItem);
      for (var i = 0; i < items.length; i++) {
        var t = (items[i].textContent || '').trim();
        for (var j = 0; j < as.storyItem.length; j++) {
          var s = as.storyItem[j];
          // The menu repeats each label once (as text and for screen readers): "StoryStory".
          if (t === s || t === s + s) {
            clickAsPage(items[i]);
            return;
          }
        }
      }
      if (++tries < 20) setTimeout(pick, 50);
    })();
  }

  function clickAsPage(el) {
    addStoryClicking = true;
    try {
      el.click();
    } finally {
      addStoryClicking = false;
    }
  }
