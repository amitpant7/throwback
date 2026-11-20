  // ---------------------------------------------------------------- reels from Explore and search

  /*
   * Reels from Explore or search (owner, 2026-11-20). Instagram's mobile site has no single-reel
   * player: a reel tile opens as a static post (checked with Throwback's script off; /reel/<id>/ is
   * turned into /p/<id>/ by Instagram itself). So, while today's Explore minutes last, tapping a reel
   * tile there opens Throwback's own full-screen swipe player with the reels from that page, in the
   * order shown. Everything it needs (MP4 address, poster, username, caption) is already in the data
   * Explore loaded; videos come from Instagram's CDN like any video; no extra logged-in requests.
   * The time counts toward the 5 minutes; when they run out the player closes and "Enough for
   * today" shows. Back or ✕ closes it. Like and save press Instagram's own buttons on the post's page,
   * rendered under the player; Comments and the username open Instagram's pages.
   */
  var CLIP_MAX = 300;
  var CLIP_ID = '__tb_clips';
  var clips = { byCode: {}, order: [] };
  var clipPlayer = null;

  function onExploreOrSearch() {
    return !!state.c && pageActive('explore');
  }

  function cdnUrl(url) {
    var u = typeof url === 'string' && parseUrl(url);
    return !!(u && u.protocol === 'https:' && isCdnHost(u.hostname));
  }

  function learnClips(o, depth) {
    if (!o || typeof o !== 'object' || depth > 14) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) learnClips(o[i], depth + 1);
      return;
    }
    if (typeof o.code === 'string' && Array.isArray(o.video_versions) && o.video_versions.length) {
      addClip(o);
      return;
    }
    for (var k in o) {
      if (Object.prototype.hasOwnProperty.call(o, k) && o[k] && typeof o[k] === 'object') learnClips(o[k], depth + 1);
    }
  }

  /** The smallest version at least as wide as the screen (capped at 720 px), else the largest. */
  function pickClipVideo(list) {
    var want = Math.min(720, Math.round((window.innerWidth || 400) * (window.devicePixelRatio || 2)));
    var best = null;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || !cdnUrl(v.url)) continue;
      var w = v.width || 0;
      if (!best) best = v;
      else if (w >= want ? (best.width < want || w < best.width) : (best.width < want && w > best.width)) best = v;
    }
    return best;
  }

  function addClip(m) {
    if (!/^[A-Za-z0-9_-]{5,40}$/.test(m.code) || clips.byCode[m.code]) return;
    var v = pickClipVideo(m.video_versions);
    if (!v) return;
    var cands = m.image_versions2 && m.image_versions2.candidates;
    var poster = cands && cands[0] && cdnUrl(cands[0].url) ? cands[0].url : null;
    clips.byCode[m.code] = {
      code: m.code,
      video: v.url,
      poster: poster,
      user: m.user && typeof m.user.username === 'string' ? m.user.username : '',
      caption: m.caption && typeof m.caption.text === 'string' ? m.caption.text.slice(0, 500) : '',
      liked: !!m.has_liked,
      likes: typeof m.like_count === 'number' ? m.like_count : null,
      comments: typeof m.comment_count === 'number' ? m.comment_count : null,
      saved: !!m.has_viewer_saved,
    };
    clips.order.push(m.code);
    if (clips.order.length > CLIP_MAX) delete clips.byCode[clips.order.shift()];
  }

  function tileCode(a) {
    var m = a && /^\/p\/([A-Za-z0-9_-]{5,40})\/?$/.exec(a.getAttribute('href') || '');
    return m ? m[1] : null;
  }

  /** Reel codes in the order the page shows them, then any others Explore loaded. */
  function clipList() {
    var seen = {};
    var out = [];
    var tiles = document.querySelectorAll('main a[href^="/p/"]');
    for (var i = 0; i < tiles.length; i++) {
      var c = tileCode(tiles[i]);
      if (c && clips.byCode[c] && !seen[c]) {
        seen[c] = 1;
        out.push(c);
      }
    }
    for (var j = 0; j < clips.order.length; j++) {
      if (!seen[clips.order[j]]) {
        seen[clips.order[j]] = 1;
        out.push(clips.order[j]);
      }
    }
    return out;
  }

  function openClipFromTap(e) {
    if (clipPlayer || state.mode === MODE_MESSAGES || !onExploreOrSearch() || !exploreAllowed()) return false;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    var code = tileCode(a);
    if (!code || !clips.byCode[code]) return false;
    e.preventDefault();
    e.stopImmediatePropagation();
    openClipPlayer(code);
    return true;
  }

  var CLIP_CSS =
    '#' + CLIP_ID + '{position:fixed;inset:0;z-index:2147483600;background:#000;color:#fff;' +
      'font:14px/1.35 -apple-system,system-ui,Roboto,sans-serif;overscroll-behavior:contain}' +
    '#' + CLIP_ID + ' .tbc-strip{position:absolute;inset:0;overflow-y:scroll;scroll-snap-type:y mandatory;' +
      'scrollbar-width:none;overscroll-behavior:contain}' +
    '#' + CLIP_ID + ' .tbc-strip::-webkit-scrollbar{display:none}' +
    '#' + CLIP_ID + ' .tbc-slide{position:relative;height:100%;scroll-snap-align:start;scroll-snap-stop:always;' +
      'display:flex;align-items:center;justify-content:center;overflow:hidden}' +
    '#' + CLIP_ID + ' video{width:100%;height:100%;object-fit:cover;background:#000}' +
    '#' + CLIP_ID + ' .tbc-info{position:absolute;left:0;right:72px;bottom:0;padding:48px 16px 24px;' +
      'background:linear-gradient(transparent,rgba(0,0,0,.65));pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-user{pointer-events:auto;background:none;border:0;color:#fff;padding:6px 0;font:inherit;' +
      'font-weight:600;font-size:15px}' +
    '#' + CLIP_ID + ' .tbc-cap{margin-top:4px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;' +
      'overflow:hidden;opacity:.92;white-space:pre-line}' +
    '#' + CLIP_ID + ' .tbc-top{position:absolute;left:0;right:0;top:0;display:flex;align-items:center;' +
      'justify-content:space-between;padding:12px 8px 24px 4px;background:linear-gradient(rgba(0,0,0,.45),transparent);' +
      'pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-close{pointer-events:auto;width:48px;height:48px;border:0;background:transparent;' +
      'color:#fff;display:flex;align-items:center;justify-content:center}' +
    '#' + CLIP_ID + ' .tbc-left{font-weight:600;font-size:13px;padding:6px 10px;border-radius:999px;' +
      'background:rgba(0,0,0,.4);font-variant-numeric:tabular-nums}' +
    '#' + CLIP_ID + ' .tbc-mute{position:absolute;top:50%;left:50%;width:64px;height:64px;margin:-32px 0 0 -32px;' +
      'border-radius:50%;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;' +
      'opacity:0;transition:opacity .2s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-mute.on{opacity:1}' +
    '#' + CLIP_ID + ' .tbc-side{position:absolute;right:6px;bottom:28px;display:flex;flex-direction:column;align-items:center;gap:14px}' +
    '#' + CLIP_ID + ' .tbc-act{width:52px;min-height:48px;border:0;background:none;color:#fff;display:flex;flex-direction:column;' +
      'align-items:center;gap:2px;font:600 12px/1.2 -apple-system,system-ui,Roboto,sans-serif;text-shadow:0 1px 2px rgba(0,0,0,.5);padding:0}' +
    '#' + CLIP_ID + ' .tbc-act svg{filter:drop-shadow(0 1px 2px rgba(0,0,0,.45))}' +
    '#' + CLIP_ID + ' .tbc-act.on .tbc-like-ic{color:#ff3040}' +
    '#' + CLIP_ID + ' .tbc-pop{position:absolute;top:50%;left:50%;width:96px;height:96px;margin:-48px 0 0 -48px;color:#fff;' +
      'opacity:0;transform:scale(.6);transition:opacity .15s,transform .25s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-pop.on{opacity:1;transform:scale(1)}' +
    '#' + CLIP_ID + ' .tbc-toast{position:absolute;left:16px;right:16px;bottom:120px;text-align:center;font-size:14px;' +
      'padding:10px 12px;border-radius:10px;background:rgba(0,0,0,.7);opacity:0;transition:opacity .2s;pointer-events:none}' +
    '#' + CLIP_ID + ' .tbc-toast.on{opacity:1}' +
    '@media (prefers-reduced-motion: reduce){#' + CLIP_ID + ' .tbc-pop{transition:none}}';

  var ICON_CLOSE = '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M5 5l14 14M19 5L5 19"/></svg>';
  var ICON_MUTED = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5L6 9H3v6h3l5 4z"/>' +
    '<path d="M22 9l-6 6M16 9l6 6"/></svg>';
  var ICON_SOUND = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5L6 9H3v6h3l5 4z"/>' +
    '<path d="M15.5 8.5a5 5 0 010 7M18.5 5.5a9 9 0 010 13"/></svg>';

  // Plain line icons (drawn for Throwback).
  var ICON_HEART = '<svg class="tbc-like-ic" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M12 20.5s-7.5-4.6-9.2-9.3C1.7 8.1 3.6 4.5 7.1 4.5c2.1 0 ' +
    '3.6 1.2 4.9 2.9 1.3-1.7 2.8-2.9 4.9-2.9 3.5 0 5.4 3.6 4.3 6.7-1.7 4.7-9.2 9.3-9.2 9.3z"/></svg>';
  var ICON_HEART_FILLED = ICON_HEART.replace('fill="none" stroke="currentColor"', 'fill="currentColor" stroke="currentColor"')
    .replace('width="30" height="30"', 'width="96" height="96"');
  var ICON_HEART_ON = ICON_HEART.replace('fill="none"', 'fill="currentColor"');
  var ICON_COMMENT = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linejoin="round" aria-hidden="true"><path d="M20.5 12a8.5 8.5 0 0 1-12.4 7.5L3.5 20.5l1-4.4A8.5 8.5 0 1 1 20.5 12z"/></svg>';
  var ICON_SHARE = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linejoin="round" aria-hidden="true"><path d="M21.5 3.5 2.5 10.6l7.3 2.9 2.9 7.4z"/><path d="M21.5 3.5 9.8 13.5"/></svg>';
  var ICON_SAVE = '<svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M18.5 21 12 15.5 5.5 21V4.5a1 1 0 0 1 1-1h11a1 1 0 0 1 1 1z"/></svg>';
  var ICON_SAVE_ON = ICON_SAVE.replace('fill="none"', 'fill="currentColor"');

  function countText(n) {
    if (typeof n !== 'number') return '';
    if (n >= 1e6) return (Math.round(n / 1e5) / 10) + 'M';
    if (n >= 1e4) return Math.round(n / 1e3) + 'K';
    if (n >= 1e3) return (Math.round(n / 100) / 10) + 'K';
    return String(n);
  }

  function clipActButton(label, icon, count, onTap) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'tbc-act';
    b.setAttribute('aria-label', label);
    b.innerHTML = icon + '<span>' + countText(count) + '</span>';
    b.addEventListener('click', function (e) {
      e.stopPropagation();
      onTap(b);
    });
    return b;
  }

  /** The right-hand buttons: like, comments, share, save (Instagram's reel layout). */
  function clipSide(c) {
    var code = c.code;
    var side = document.createElement('div');
    side.className = 'tbc-side';
    var like = clipActButton('Like', c.liked ? ICON_HEART_ON : ICON_HEART, c.likes, function () {
      setClipLiked(c, !c.liked);
    });
    if (c.liked) like.classList.add('on');
    like.setAttribute('data-tbc', 'like');
    side.appendChild(like);
    side.appendChild(clipActButton('Comments', ICON_COMMENT, c.comments, function () {
      openClipPath('/p/' + code + '/comments/', code);
    }));
    side.appendChild(clipActButton('Share', ICON_SHARE, null, function () {
      send({ t: 'share', url: 'https://www.instagram.com/reel/' + code + '/' });
    }));
    var save = clipActButton('Save', c.saved ? ICON_SAVE_ON : ICON_SAVE, null, function () {
      setClipSaved(c, !c.saved);
    });
    save.setAttribute('data-tbc', 'save');
    side.appendChild(save);
    return side;
  }

  function clipSlideOf(code) {
    var p = clipPlayer;
    return p ? p.slides[p.list.indexOf(code)] : null;
  }

  function showClipLiked(c) {
    var slide = clipSlideOf(c.code);
    var b = slide && slide.querySelector('[data-tbc=like]');
    if (!b) return;
    b.classList.toggle('on', c.liked);
    b.innerHTML = (c.liked ? ICON_HEART_ON : ICON_HEART) + '<span>' + countText(c.likes) + '</span>';
  }

  function showClipSaved(c) {
    var slide = clipSlideOf(c.code);
    var b = slide && slide.querySelector('[data-tbc=save]');
    if (b) b.innerHTML = (c.saved ? ICON_SAVE_ON : ICON_SAVE) + '<span></span>';
  }

  function setClipLiked(c, liked) {
    if (!c || c.liked === liked) return;
    c.liked = liked;
    if (typeof c.likes === 'number') c.likes += liked ? 1 : -1;
    showClipLiked(c); // at once; undone if Instagram's own button can't be pressed
    clipAct(c.code, liked ? 'like' : 'unlike', function (ok) {
      if (ok) return;
      c.liked = !liked;
      if (typeof c.likes === 'number') c.likes += liked ? -1 : 1;
      showClipLiked(c);
      clipToast(liked ? 'Couldn’t like this one. Try “Comments” to open the post.' : 'Couldn’t undo the like.');
    });
  }

  function setClipSaved(c, saved) {
    if (!c || c.saved === saved) return;
    c.saved = saved;
    showClipSaved(c);
    clipAct(c.code, saved ? 'save' : 'unsave', function (ok) {
      if (ok) {
        clipToast(saved ? 'Saved' : 'Removed from saved');
        return;
      }
      c.saved = !saved;
      showClipSaved(c);
      clipToast('Couldn’t save this one.');
    });
  }

  function clipToast(text) {
    var p = clipPlayer;
    if (!p) return;
    if (!p.toast) {
      p.toast = document.createElement('div');
      p.toast.className = 'tbc-toast';
      p.toast.setAttribute('role', 'status');
      p.root.appendChild(p.toast);
    }
    p.toast.textContent = text;
    p.toast.className = 'tbc-toast on';
    clearTimeout(p.toastTimer);
    p.toastTimer = setTimeout(function () {
      if (p.toast) p.toast.className = 'tbc-toast';
    }, 1800);
  }

  /** Single tap: sound on/off. Double tap: like (with the big heart), as on Instagram. */
  function onClipVideoTap(c) {
    var p = clipPlayer;
    if (!p) return;
    if (p.tapTimer) {
      clearTimeout(p.tapTimer);
      p.tapTimer = 0;
      var slide = clipSlideOf(c.code);
      var pop = slide && slide.querySelector('.tbc-pop');
      if (pop) {
        pop.className = 'tbc-pop on';
        setTimeout(function () {
          pop.className = 'tbc-pop';
        }, 600);
      }
      setClipLiked(c, true);
      return;
    }
    p.tapTimer = setTimeout(function () {
      p.tapTimer = 0;
      toggleClipSound();
    }, 260);
  }

  /** Switches Instagram's page (under the player, no reload) to [path]: its router renders it in place. */
  function clipUnderneath(path) {
    if (location.pathname === path) return;
    state.clipNav = true;
    try {
      history.replaceState(history.state, '', path);
      window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    } finally {
      state.clipNav = false;
    }
    if (clipPlayer) clipPlayer.dirty = true;
  }

  /**
   * Like/unlike/save/unsave through Instagram's own button: the post's page is rendered under the
   * player and its button is pressed, so Instagram's own code sends the request (Throwback sends
   * none). Labels come from the rules (explore.actions). done(true) once pressed. One at a time, in
   * order (a second like must not replace the first one's page before its button is pressed), and
   * closing the player waits for them (closeClipPlayer).
   */
  var clipActs = [];

  function clipAct(code, action, done) {
    clipActs.push({ code: code, action: action, done: done });
    if (clipActs.length === 1) runClipAct();
  }

  function runClipAct() {
    var a = clipActs[0];
    if (!a) {
      var leave = state.clipLeave; // the player was closed while these were pending
      state.clipLeave = null;
      if (leave) leaveClipPlayer(leave);
      return;
    }
    var finish = function (ok) {
      clipActs.shift();
      a.done(ok);
      runClipAct();
    };
    var ex = state.c && state.c.explore;
    var labels = ex && ex.actions && ex.actions[a.action];
    if (!labels || !labels.length) return finish(false);
    var code = a.code;
    clipUnderneath('/p/' + code + '/');
    var tries = 0;
    (function look() {
      var main = document.querySelector('main');
      var btn = null;
      if (main && main.querySelector('a[href*="/' + code + '/"]')) {
        var svgs = main.querySelectorAll('svg[aria-label]');
        for (var i = 0; i < svgs.length && !btn; i++) {
          if (labels.indexOf(svgs[i].getAttribute('aria-label')) !== -1) {
            btn = svgs[i].closest('[role=button],button') || svgs[i].parentElement;
          }
        }
      }
      if (btn) {
        clickElement(btn);
        pauseUnderPlayer();
        return finish(true);
      }
      if (++tries > 40) return finish(false); // ~6 s
      setTimeout(look, 150);
    })();
  }

  /** Nothing under the player may play (the post page underneath autoplays its video). */
  function pauseUnderPlayer() {
    var p = clipPlayer;
    if (!p) return;
    var media = document.querySelectorAll('video, audio');
    for (var i = 0; i < media.length; i++) if (!p.root.contains(media[i]) && !media[i].paused) media[i].pause();
  }

  function onClipUnderPlay(e) {
    var p = clipPlayer;
    var m = e.target;
    if (p && m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO') && !p.root.contains(m)) m.pause();
  }

  /**
   * Leaves the player for an Instagram page (comments, a profile) without a reload; with [resume],
   * coming Back to Explore reopens the player at that reel.
   */
  function openClipPath(path, resume) {
    closeClipPlayer(true, function () {
      var at = location.pathname; // Explore, where Back returns to
      history.pushState(null, '', path);
      window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      state.clipResume = resume ? { code: resume, at: at } : null; // after the push (a push clears it)
    });
  }

  function clipText(tag, cls, text) {
    var el = document.createElement(tag);
    el.className = cls;
    el.textContent = text;
    return el;
  }

  function openClipPlayer(code) {
    var list = clipList();
    var start = Math.max(0, list.indexOf(code));
    var root = document.createElement('div');
    root.id = CLIP_ID;
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-label', 'Reels');
    var style = document.createElement('style');
    style.textContent = CLIP_CSS;
    root.appendChild(style);
    var strip = document.createElement('div');
    strip.className = 'tbc-strip';
    root.appendChild(strip);
    var top = document.createElement('div');
    top.className = 'tbc-top';
    var close = document.createElement('button');
    close.className = 'tbc-close';
    close.type = 'button';
    close.setAttribute('aria-label', 'Close');
    close.innerHTML = ICON_CLOSE;
    close.addEventListener('click', function () {
      closeClipPlayer(true);
    });
    var left = clipText('div', 'tbc-left', '');
    top.appendChild(close);
    top.appendChild(left);
    root.appendChild(top);
    var mute = document.createElement('div');
    mute.className = 'tbc-mute';
    root.appendChild(mute);
    clipPlayer = { root: root, strip: strip, left: left, mute: mute, list: [], clips: [], slides: [], current: -1,
      muted: false, observer: null, muteTimer: 0 };
    for (var i = 0; i < list.length; i++) addClipSlide(list[i]);
    clipPlayer.observer = typeof IntersectionObserver === 'function' ? new IntersectionObserver(function (entries) {
      for (var k = 0; k < entries.length; k++) {
        if (entries[k].isIntersecting) setClipCurrent(clipPlayer.slides.indexOf(entries[k].target));
      }
    }, { root: strip, threshold: 0.6 }) : null;
    if (clipPlayer.observer) for (var s = 0; s < clipPlayer.slides.length; s++) clipPlayer.observer.observe(clipPlayer.slides[s]);
    (document.body || document.documentElement).appendChild(root);
    pauseAllMedia();
    try {
      history.pushState({ __tbClips: 1 }, '', location.href); // Back closes the player, not the page
      clipPlayer.pushed = true;
    } catch (e) {
      clipPlayer.pushed = false;
    }
    strip.scrollTop = start * strip.clientHeight;
    setClipCurrent(start);
    updateClipClock();
  }

  function addClipSlide(code) {
    var c = clips.byCode[code];
    if (!c || !clipPlayer || clipPlayer.list.indexOf(code) !== -1) return;
    var slide = document.createElement('section');
    slide.className = 'tbc-slide';
    var v = document.createElement('video');
    v.setAttribute('playsinline', '');
    v.loop = true;
    v.preload = 'none'; // address and poster only near the reel on screen (setClipCurrent)
    v.addEventListener('click', function () {
      onClipVideoTap(c);
    });
    slide.appendChild(v);
    var heart = document.createElement('div');
    heart.className = 'tbc-pop';
    heart.innerHTML = ICON_HEART_FILLED;
    slide.appendChild(heart);
    var info = document.createElement('div');
    info.className = 'tbc-info';
    if (c.user) {
      var user = clipText('button', 'tbc-user', c.user);
      user.type = 'button';
      user.addEventListener('click', function () {
        openClipPath('/' + c.user + '/', null);
      });
      info.appendChild(user);
    }
    if (c.caption) info.appendChild(clipText('div', 'tbc-cap', c.caption));
    slide.appendChild(info);
    slide.appendChild(clipSide(c));
    clipPlayer.strip.appendChild(slide);
    clipPlayer.list.push(code);
    clipPlayer.clips.push(c); // its own reference: the shared list may drop old reels meanwhile
    clipPlayer.slides.push(slide);
    if (clipPlayer.observer) clipPlayer.observer.observe(slide);
  }

  /**
   * Plays slide i; only it and its neighbours keep a video address, and the next two on each side a
   * poster (each poster is a download: a player can hold hundreds of reels).
   */
  function setClipCurrent(i) {
    var p = clipPlayer;
    if (!p || i < 0 || i >= p.slides.length) return;
    p.current = i;
    for (var k = 0; k < p.slides.length; k++) {
      var v = p.slides[k].firstChild;
      var c = p.clips[k];
      if (Math.abs(k - i) <= 2 && c.poster) {
        if (v.getAttribute('poster') !== c.poster) v.poster = c.poster;
      } else if (v.hasAttribute('poster')) {
        v.removeAttribute('poster');
      }
      if (Math.abs(k - i) <= 1) {
        if (v.getAttribute('src') !== c.video) {
          v.src = c.video;
          v.preload = k === i ? 'auto' : 'metadata';
        }
      } else if (v.hasAttribute('src')) {
        v.pause();
        v.removeAttribute('src');
        v.load();
      }
      if (k !== i && !v.paused) v.pause();
    }
    playClip(p.slides[i].firstChild);
    // Near the end: let Explore load its next page underneath, and take what it learned.
    if (i >= p.slides.length - 3) {
      if (onExploreGrid()) window.scrollBy(0, (window.innerHeight || 800) * 3);
      var more = clipList();
      for (var j = 0; j < more.length; j++) addClipSlide(more[j]);
    }
  }

  function playClip(v) {
    if (!v || state.covered || document.visibilityState !== 'visible') return;
    v.muted = clipPlayer.muted;
    var pr = v.play();
    if (pr && typeof pr.catch === 'function') {
      pr.catch(function () {
        // Sound needs a tap first: play muted (a tap on the video turns sound on).
        if (!clipPlayer || v.muted) return;
        clipPlayer.muted = true;
        v.muted = true;
        v.play().catch(function () {});
      });
    }
  }

  function toggleClipSound() {
    var p = clipPlayer;
    if (!p) return;
    p.muted = !p.muted;
    var v = p.slides[p.current] && p.slides[p.current].firstChild;
    if (v) {
      v.muted = p.muted;
      if (v.paused) playClip(v);
    }
    p.mute.innerHTML = p.muted ? ICON_MUTED : ICON_SOUND;
    p.mute.className = 'tbc-mute on';
    clearTimeout(p.muteTimer);
    p.muteTimer = setTimeout(function () {
      if (clipPlayer) clipPlayer.mute.className = 'tbc-mute';
    }, 700);
  }

  function updateClipClock() {
    var p = clipPlayer;
    var ex = state.c && state.c.explore;
    if (!p || !ex) return;
    var left = Math.max(0, ex.dailySeconds - exploreLog().used);
    var t = Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + ' left today';
    if (p.left.textContent !== t) p.left.textContent = t;
  }

  /**
   * @param viaHistory true when closed by ✕ or time-out: also drops the history entry it added.
   * @param then optional: runs once the page under the player is back (after the Back has landed).
   */
  function closeClipPlayer(viaHistory, then) {
    var p = clipPlayer;
    if (!p) {
      if (then) then();
      return;
    }
    clipPlayer = null;
    clearTimeout(p.muteTimer);
    if (p.observer) p.observer.disconnect();
    for (var k = 0; k < p.slides.length; k++) {
      var v = p.slides[k].firstChild;
      v.pause();
      if (v.hasAttribute('src')) {
        v.removeAttribute('src');
        v.load();
      }
    }
    if (p.root.parentNode) p.root.parentNode.removeChild(p.root);
    clearTimeout(p.tapTimer);
    clearTimeout(p.toastTimer);
    var leave = { back: !!(viaHistory && p.pushed), dirty: !!p.dirty, then: then || null };
    // A like or save still waiting for Instagram's button needs the post's page under the player.
    if (clipActs.length) state.clipLeave = leave;
    else leaveClipPlayer(leave);
    updateExploreCard();
  }

  /**
   * Back to the page under the player: drops the player's history entry (Instagram doesn't see that
   * Back), or has Instagram render the address again if the player put a post's page there.
   */
  function leaveClipPlayer(leave) {
    state.clipRerender = leave.dirty;
    if (leave.back) {
      state.clipPopIgnore = true;
      state.clipAfterBack = leave.then;
      history.back();
      return;
    }
    rerenderUnderneath();
    if (leave.then) setTimeout(leave.then, 0);
  }



  /** After the player closes, Instagram shows the page of the current address again. */
  function rerenderUnderneath() {
    if (!state.clipRerender) return;
    state.clipRerender = false;
    setTimeout(function () {
      window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    }, 0);
  }

  /** Back while the player is open closes it; Instagram doesn't see that history step. */
  function onClipPop(e) {
    if (state.clipNav) return; // the player's own switch of the page underneath: Instagram renders it
    if (state.clipPopIgnore) {
      state.clipPopIgnore = false;
      e.stopImmediatePropagation();
      rerenderUnderneath();
      var then = state.clipAfterBack;
      state.clipAfterBack = null;
      if (then) setTimeout(then, 0);
      return;
    }
    if (!clipPlayer) {
      // Back from comments or a profile opened from the player: reopen it at that reel.
      var r = state.clipResume;
      if (r && location.pathname === r.at) {
        state.clipResume = null;
        setTimeout(function () {
          if (clips.byCode[r.code] && exploreAllowed()) openClipPlayer(r.code);
        }, 400);
      }
      return;
    }
    e.stopImmediatePropagation();
    closeClipPlayer(false);
  }

  function onClipVisibility() {
    if (!clipPlayer) return;
    var v = clipPlayer.slides[clipPlayer.current] && clipPlayer.slides[clipPlayer.current].firstChild;
    if (!v) return;
    if (document.visibilityState === 'visible') playClip(v);
    else v.pause();
  }

