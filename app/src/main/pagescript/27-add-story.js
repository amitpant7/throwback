  // ---------------------------------------------------------------- "+" on "Your story" (another story)

  /*
   * Instagram's website puts a "+" on "Your story" only while the user has no story: once one is up,
   * the bubble opens it instead, and another story is added from the top bar's "+" menu (Post /
   * Story). The Instagram app keeps a "+" on the bubble; so does Throwback (owner, 2026-11-21:
   * people often post several stories). Rules: stories.addStory.
   *
   * A tap goes where "Your story" goes without a story (storyRoute, DECISIONS.md #34cc): the Instagram
   * app's story route while it is connected and the story budget is open, otherwise the website's own
   * story upload, reached through its "+" menu: Throwback opens the menu and picks "Story". If
   * "Story" can't be found (renamed, another language), the menu stays open for the user.
   */
  var ADD_STORY_ID = '__tb_add_story';
  var ATTR_ADD_STORY_HOST = 'data-tb-add-story-host';
  var ADD_STORY_LOOK_MS = 500;
  // Monotonic times (performance.now): the phone's clock can be set back.
  var addStory = { lookAt: -Infinity, timer: 0, bubble: null, busyUntil: 0 };

  /**
   * Where a story tap goes: 'app' (the Instagram app, story budget open), 'web-why' (the website's
   * upload; the app says why: the budget's lock) or 'web' (the website's upload). The app route needs
   * the rules' appCreate with a story trigger, so the rules can send stories back to the website.
   */
  function storyRoute() {
    var ac = state.c && state.c.appCreate;
    if (!state.createInApp || !ac || !ac.hasStory) return 'web';
    return state.storyInApp ? 'app' : 'web-why';
  }

  function updateAddStory() {
    var as = state.c && state.c.addStory;
    var badge = document.getElementById(ADD_STORY_ID);
    var root = document.documentElement;
    if (!as || state.mode === MODE_MESSAGES || !root || !root.classList.contains('tb-on-home')) {
      removeAddStory(badge);
      return;
    }
    if (badge) {
      var current = badge.closest('[role=button]:not(#' + ADD_STORY_ID + ')');
      if (current && !current.querySelector(as.ownPlus)) return; // still on a bubble with a story
      removeAddStory(badge);
    }
    // Looking reads the bubble's text: at most every ADD_STORY_LOOK_MS while it isn't there (the feed
    // changes on every frame while scrolling), with a pass then so a tray drawn meanwhile isn't missed.
    var now = performance.now();
    var wait = ADD_STORY_LOOK_MS - (now - addStory.lookAt);
    if (wait > 0) {
      if (!addStory.timer) {
        addStory.timer = setTimeout(function () {
          addStory.timer = 0;
          schedulePass(false);
        }, wait);
      }
      return;
    }
    addStory.lookAt = now;
    var bubble = yourStoryBubble(as);
    // No story yet: Instagram's own "+" (and upload) is there, and "Your story" already routes it.
    if (!bubble || bubble.querySelector(as.ownPlus)) return;
    var built = buildAddStory(bubble);
    if (built) tapToStory(built);
  }

  /**
   * "Your story": the tray's first bubble, which is the first button in <main> (measured), found by
   * its label (rules: stories.addStory.yourStory). Kept while it stays in the page.
   */
  function yourStoryBubble(as) {
    if (addStory.bubble && addStory.bubble.isConnected) return addStory.bubble;
    addStory.bubble = null;
    var main = document.querySelector('main');
    var first = main && main.querySelector('[role=button]');
    if (first && as.yourStory.has(norm(first.textContent))) addStory.bubble = first;
    return addStory.bubble;
  }

  /**
   * The badge at the avatar's lower right, in Instagram's own blue, ringed in the page colour.
   * Placed from the host's right and bottom edges, so it stays on the avatar when the width changes.
   */
  function buildAddStory(bubble) {
    var img = bubble.querySelector('img');
    var avatar = img && (img.closest('[role=link]') || img.parentElement);
    var host = avatar && avatar.parentElement;
    if (!host || !bubble.contains(host)) return null;
    if (getComputedStyle(host).position === 'static') {
      host.style.position = 'relative';
      host.setAttribute(ATTR_ADD_STORY_HOST, '');
    }
    var hb = host.getBoundingClientRect();
    var ab = avatar.getBoundingClientRect();
    if (!ab.width) return null;
    var dot = Math.round(Math.max(18, Math.min(24, ab.width * 0.3)));
    var hit = dot + 10; // a larger tap area around the dot
    var edge = (hit - dot) / 2 - 2; // the dot sits 2 px inside the avatar's corner
    var b = document.createElement('div');
    b.id = ADD_STORY_ID;
    b.setAttribute('role', 'button');
    b.setAttribute('tabindex', '0');
    b.setAttribute('aria-label', 'Add to your story');
    b.style.cssText = 'position:absolute;z-index:2;display:flex;align-items:center;justify-content:center;' +
      'cursor:pointer;-webkit-tap-highlight-color:transparent;width:' + hit + 'px;height:' + hit + 'px;' +
      'right:' + Math.round(hb.right - ab.right - edge) + 'px;bottom:' + Math.round(hb.bottom - ab.bottom - edge) + 'px';
    b.innerHTML = '<span style="box-sizing:border-box;display:flex;align-items:center;justify-content:center;' +
      'width:' + dot + 'px;height:' + dot + 'px;border-radius:50%;color:#fff;' +
      'background:rgb(var(--ig-primary-button, 0, 149, 246));border:2px solid rgb(var(--ig-primary-background, 255, 255, 255))">' +
      '<svg aria-hidden="true" viewBox="0 0 24 24" width="' + (dot - 8) + '" height="' + (dot - 8) + '" fill="none" ' +
      'stroke="currentColor" stroke-width="3.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg></span>';
    host.appendChild(b);
    return b;
  }

  /** Removes the badge, and the position it gave Instagram's element. */
  function removeAddStory(badge) {
    if (!badge) return;
    var host = badge.parentNode;
    if (host) host.removeChild(badge);
    if (host && host.hasAttribute && host.hasAttribute(ATTR_ADD_STORY_HOST)) {
      host.style.position = '';
      host.removeAttribute(ATTR_ADD_STORY_HOST);
    }
  }

  /** Taps (and Enter/Space) on the badge never reach Instagram, which would open the story already up. */
  function tapToStory(el) {
    tapTo(el, addStoryTapped);
    el.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      e.preventDefault();
      e.stopPropagation();
      addStoryTapped();
    }, true);
  }

  function addStoryTapped() {
    var now = performance.now();
    if (now < addStory.busyUntil) return; // a double tap would close the menu it just opened
    addStory.busyUntil = now + 1000;
    var route = storyRoute();
    if (route === 'app') {
      send({ t: 'createInApp', kind: 'story' });
      return;
    }
    var as = state.c && state.c.addStory;
    var mark = as && document.querySelector(as.menuButton);
    var plus = mark && mark.closest('a,[role=button],button');
    if (!plus) {
      noteError('addStory', 'the website\'s "+" menu was not found');
      return;
    }
    if (route === 'web-why') send({ t: 'storyWeb' }); // the app says why (the lock)
    openWebStoryUpload(as, plus);
  }

  /**
   * The website's story upload: its top-bar "+" menu, then "Story". Both are clicks by this script
   * (not trusted), so the app hand-off in createInAppTap lets them through; "Story" is clicked within
   * a second of the user's tap, inside the browser's user-activation window, so the photo picker opens.
   */
  function openWebStoryUpload(as, plus) {
    clickElement(plus);
    var tries = 0;
    (function pick() {
      var items = document.querySelectorAll(as.menuItem);
      for (var i = 0; i < items.length; i++) {
        var t = norm(items[i].textContent);
        var half = t.slice(0, t.length / 2);
        // The menu repeats each label once (as text and for screen readers): "StoryStory".
        if (as.storyItem.has(t) || (t.length % 2 === 0 && half + half === t && as.storyItem.has(half))) {
          clickElement(items[i]);
          return;
        }
      }
      if (++tries < 20) setTimeout(pick, 50);
    })();
  }
