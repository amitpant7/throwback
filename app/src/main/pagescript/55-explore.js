  // ---------------------------------------------------------------- Explore: a few minutes a day

  /*
   * The Explore grid shows for explore.dailySeconds a day (5 minutes), counted only while it is seen
   * (visible, not covered): on the grid pages and in Throwback's reel player, not while typing a
   * search. Then it is hidden as before and "Enough for today" shows in its place until the next day.
   * Search keeps working. Stored per day, so a restart doesn't reset it.
   */
  var EXPLORE_CARD_ID = '__tb_explore_card';
  var exploreRecord = dailyRecord('__tb_explore', function () {
    return { used: 0 };
  }, function (d) {
    return typeof d.used === 'number';
  });

  function exploreLog() {
    return exploreRecord.get();
  }

  function exploreAllowed() {
    var ex = state.c && state.c.explore;
    return !!(ex && ex.dailySeconds > 0 && exploreLog().used < ex.dailySeconds);
  }

  /**
   * A grid of posts: Explore itself, hashtag and place pages, keyword results (all under /explore/,
   * all on the same daily minutes; keyword results are /explore/search/keyword/?q=…). Not the search
   * page while typing (/explore/search/ itself), and not Discover people (suggested accounts show as
   * Instagram shows them, DECISIONS.md #13k).
   */
  function onExploreGrid() {
    return pageActive('explore') && !/^\/explore\/(search\/?$|people(\/|$))/.test(state.path);
  }

  var exploreWasAllowed = null;

  /** Each second (startTick): counts Explore time while it is seen; the grid opens and closes. */
  function tickExplore(seen) {
    var allowed = exploreAllowed();
    if (allowed !== exploreWasAllowed) { // time's up, or a new day began: the grid hides or shows
      exploreWasAllowed = allowed;
      applyClasses();
      schedulePass(false); // tags the grid's blocks (applyExplore) once it is closed
    }
    if (clipPlayer && !allowed) closeClipPlayer(true); // time's up: the card shows on Explore
    var ex = state.c.explore;
    if (!ex || !ex.dailySeconds || state.mode === MODE_MESSAGES || !(pageActive('explore') || clipPlayer)) return;
    // Counted on the grid and in the reel player; not while typing a search (finding a friend is free).
    if (seen && allowed && (onExploreGrid() || clipPlayer)) {
      var d = exploreLog();
      d.used++;
      if (d.used % 5 === 0 || d.used >= ex.dailySeconds) exploreRecord.save();
      if (d.used >= ex.dailySeconds) { // time's up: the grid hides now
        exploreWasAllowed = false;
        applyClasses();
        schedulePass(false);
      }
    }
    updateClipClock();
    updateExploreCard();
  }

  /** "Enough for today" in place of the grid, once the allowance is used up. */
  function updateExploreCard() {
    var card = document.getElementById(EXPLORE_CARD_ID);
    var show = !!(state.c && state.c.explore && state.c.explore.dailySeconds) && state.mode !== MODE_MESSAGES &&
      onExploreGrid() && !exploreAllowed();
    var main = show && document.querySelector('main');
    if (!main) {
      if (card && card.parentNode) card.parentNode.removeChild(card);
      return;
    }
    if (!card) card = buildExploreCard();
    if (card.parentNode !== main) main.appendChild(card);
  }

  /*
   * Explore design B (owner, 2026-11-20): a calm scene, then the three lines. One scene is picked at
   * random each time: five drawn ones, plus any photos listed in the rules (explore.images, served
   * from Throwback's own GitHub repo, so no new service is contacted).
   */
  function exploreScenes() {
    var star = function (x, y, s, o) {
      return '<div style="position:absolute;left:' + x + ';top:' + y + 'px;width:' + s + 'px;height:' + s +
        'px;border-radius:50%;background:#fff;opacity:' + o + '"></div>';
    };
    var d = function (st) { return '<div style="position:absolute;' + st + '"></div>'; };
    return [
      { label: 'Sunset over green hills', bg: 'linear-gradient(180deg,#2a2350 0%,#7b4a7d 38%,#e58a6d 68%,#f6c27a 86%)', html:
        d('left:50%;top:51%;width:120px;height:120px;margin-left:-60px;border-radius:50%;background:#ffd98a;box-shadow:0 0 60px 18px rgba(255,196,120,.45)') +
        d('left:-15%;right:-10%;bottom:-45%;height:80%;border-radius:50%;background:#2c5a46') +
        d('left:36%;right:-30%;bottom:-52%;height:80%;border-radius:50%;background:#1f4535') +
        d('left:0;right:0;bottom:0;height:19%;background:#163628') + star('10%', 40, 3, 0.8) + star('25%', 70, 2, 0.7) + star('78%', 52, 3, 0.8) },
      { label: 'Misty morning lake', bg: 'linear-gradient(180deg,#cfdde4 0%,#e9eef0 40%,#f4efe6 62%,#c9d6d3 63%,#b9cbc8 100%)', html:
        d('left:60%;top:21%;width:70px;height:70px;border-radius:50%;background:#fff6e0;box-shadow:0 0 50px 20px rgba(255,246,224,.8)') +
        d('left:-10%;width:58%;bottom:36%;height:36%;border-radius:60% 60% 0 0;background:#9fb3b4;opacity:.85') +
        d('left:31%;width:75%;bottom:36%;height:27%;border-radius:60% 60% 0 0;background:#b3c4c4;opacity:.8') +
        d('left:0;right:0;bottom:0;height:37%;background:linear-gradient(180deg,#c7d6d4,#a9bfbd)') +
        d('left:8%;right:8%;bottom:21%;height:2px;background:rgba(255,255,255,.7)') },
      { label: 'Moon over a calm sea', bg: 'linear-gradient(180deg,#0f1b33 0%,#23365c 45%,#3d5a80 62%,#0e2338 63%,#0a1a2b 100%)', html:
        d('left:70%;top:16%;width:54px;height:54px;border-radius:50%;background:#f3efe0;box-shadow:0 0 40px 10px rgba(243,239,224,.35)') +
        d('left:66%;top:64%;width:60px;height:34%;background:linear-gradient(180deg,rgba(243,239,224,.55),rgba(243,239,224,0));filter:blur(3px)') +
        star('10%', 40, 2, 0.8) + star('30%', 90, 2, 0.6) + star('52%', 30, 3, 0.8) + star('8%', 170, 2, 0.6) },
      { label: 'Northern lights over dark hills', bg: 'linear-gradient(180deg,#050b1a 0%,#0b1a2e 60%,#10283a 100%)', html:
        d('left:-10%;right:-10%;top:12%;height:42%;border-radius:50%;background:radial-gradient(ellipse at center,rgba(92,230,170,.75),rgba(92,230,170,0) 70%);filter:blur(8px);transform:rotate(-8deg)') +
        d('left:10%;right:-15%;top:27%;height:33%;border-radius:50%;background:radial-gradient(ellipse at center,rgba(140,120,255,.55),rgba(140,120,255,0) 70%);filter:blur(10px);transform:rotate(6deg)') +
        star('8%', 30, 2, 0.8) + star('78%', 40, 3, 0.8) + star('50%', 20, 2, 0.7) +
        d('left:-8%;width:68%;bottom:-12%;height:45%;border-radius:50% 50% 0 0;background:#07121c') +
        d('left:47%;right:-8%;bottom:-12%;height:36%;border-radius:50% 50% 0 0;background:#0a1620') },
      { label: 'Pastel sand dunes', bg: 'linear-gradient(180deg,#f6d6c8 0%,#f2c6b4 40%,#e9b8a6 60%)', html:
        d('left:18%;top:18%;width:90px;height:90px;border-radius:50%;background:#fff1d6') +
        d('left:-20%;right:20%;bottom:-36%;height:70%;border-radius:50%;background:#d99a83') +
        d('left:31%;right:-31%;bottom:-45%;height:73%;border-radius:50%;background:#c98670') +
        d('left:-10%;right:-10%;bottom:-58%;height:73%;border-radius:50%;background:#b8735f') }
    ];
  }

  function buildExploreCard() {
    var ex = state.c && state.c.explore;
    var images = (ex && ex.images) || [];
    var scenes = exploreScenes();
    var n = Math.floor(Math.random() * (scenes.length + images.length));
    var dark = false;
    try {
      var m = /(\d+),\s*(\d+),\s*(\d+)/.exec(getComputedStyle(document.body).color || '');
      dark = !!m && (+m[1] + +m[2] + +m[3]) / 3 > 140; // light text: Instagram's dark theme
    } catch (e) {
      // keep light
    }
    var card = document.createElement('div');
    card.id = EXPLORE_CARD_ID;
    card.setAttribute('role', 'status');
    card.style.cssText = 'display:flex;flex-direction:column;padding:8px 16px 48px;color:inherit;font-family:inherit';
    var scene = document.createElement('div');
    scene.setAttribute('role', 'img');
    scene.style.cssText = 'position:relative;overflow:hidden;height:42vh;max-height:330px;border-radius:26px';
    if (n < scenes.length) {
      scene.setAttribute('aria-label', scenes[n].label);
      scene.style.background = scenes[n].bg;
      scene.innerHTML = scenes[n].html;
    } else {
      scene.setAttribute('aria-label', 'A calm photo');
      var img = document.createElement('img');
      img.src = images[n - scenes.length];
      img.alt = '';
      img.style.cssText = 'width:100%;height:100%;object-fit:cover;display:block';
      scene.style.background = '#1f2b25';
      scene.appendChild(img);
    }
    var text = document.createElement('div');
    text.style.cssText = 'display:flex;flex-direction:column;gap:10px;padding:26px 12px 0';
    text.innerHTML =
      '<div style="font-size:30px;font-weight:800;letter-spacing:-.01em">Enough for today\u2026</div>' +
      '<div style="font-size:19px;font-weight:700;color:' + (dark ? '#5cc28f' : '#2c8a5b') + '">Your time is yours. Keep it.</div>' +
      '<div style="font-size:16px;line-height:1.5;opacity:.7">Go have an argument with your sibling instead.</div>';
    card.appendChild(scene);
    card.appendChild(text);
    return card;
  }


  /** Explore: hide every block that holds post tiles but not the search box. */
  function applyExplore() {
    var ex = state.c.explore;
    // While the grid is open the tags would do nothing (whole-page queries on every pass otherwise).
    if (!ex || !pageActive(ex.on) || exploreAllowed()) return;
    var keep = document.querySelector(ex.keep);
    var items = document.querySelectorAll(ex.items);
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      if (!keep) {
        // Search box not recognised: hide tiles only, never their surroundings.
        item.setAttribute(ATTR_EXPLORE, '1');
        continue;
      }
      var top = null;
      for (var n = item; n && n !== document.body && n.tagName !== 'MAIN'; n = n.parentElement) {
        if (n.hasAttribute(ATTR_EXPLORE)) {
          top = null;
          break; // already hidden
        }
        if (n.contains(keep) || n.hasAttribute(ATTR_NAV) || n.querySelector('[' + ATTR_NAV + ']')) break;
        top = n;
      }
      if (top) top.setAttribute(ATTR_EXPLORE, '1');
    }
  }

