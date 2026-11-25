  // ---------------------------------------------------------------- reels: double tap to like, volume key for sound

  /*
   * Two habits from the Instagram app (owner, 2026-11-23, DECISIONS.md #34kk):
   *
   * Double tap a reel to like it. Instagram's feed videos already do this, its reel viewers don't.
   * After a double tap on a video that has its own Like button, the page waits a moment and presses
   * that button only if it still says "Like": where Instagram liked it itself, nothing more happens,
   * and a double tap never takes a like back. In chats it works only inside the reel viewer, so a
   * double tap on a message stays Instagram's own heart reaction. Throwback's Explore reel player
   * has its own double tap (60-explore-reel-player.js).
   *
   * A volume key turns the sound on for the video on screen (the app tells the page, volumeKey()),
   * by pressing Instagram's own sound button where there is one, so the next videos keep the sound.
   * The key still changes the volume as usual.
   *
   * Instagram's English labels, as the chat gestures use (29-chat-gestures.js).
   */
  var REEL_LIKE = 'svg[aria-label="Like"], svg[aria-label="Unlike"]';
  var REEL_SOUND = 'button[aria-label="Toggle audio"], svg[aria-label="Audio is muted"]';
  var REEL_LIKE_WAIT_MS = 350; // Instagram's own double tap (where it has one) has liked by then
  var REEL_ITEM_DEPTH = 16;
  var REEL_HEART =
    '<svg aria-hidden="true" viewBox="0 0 24 24" width="96" height="96" fill="#fff">' +
    '<path d="M12 21.4 10.6 20C5.4 15.4 2 12.3 2 8.5 2 5.4 4.4 3 7.5 3c1.7 0 3.4.8 4.5 2.1C13.1 3.8 14.8 3 16.5 3 ' +
    '19.6 3 22 5.4 22 8.5c0 3.8-3.4 6.9-8.6 11.5L12 21.4z"/></svg>';

  /**
   * The reel item around an element: the nearest ancestor holding exactly one video and something
   * matching [selector] (its Like or its sound button). Null past an ancestor with several videos.
   */
  function reelItemOf(el, selector) {
    for (var n = el, depth = 0; n && n !== document.body && depth < REEL_ITEM_DEPTH; n = n.parentElement, depth++) {
      if (n.nodeType !== 1) continue;
      var vids = n.getElementsByTagName('video');
      if (vids.length > 1) return null;
      if (vids.length === 1 && n.querySelector(selector)) return { item: n, video: vids[0] };
    }
    return null;
  }

  /** The item's own Like button (the first labelled one; comments have none in these layouts). */
  function reelLikeOf(item) {
    var svg = item.querySelector(REEL_LIKE);
    if (!svg) return null;
    return { liked: svg.getAttribute('aria-label') === 'Unlike', button: svg.closest('[role="button"], button') || svg.parentElement };
  }

  function showReelHeart(x, y) {
    var h = document.createElement('div');
    h.setAttribute('data-tb-heart', '1');
    h.innerHTML = REEL_HEART;
    h.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;width:96px;height:96px;' +
      'left:' + (x - 48) + 'px;top:' + (y - 48) + 'px;opacity:0;transform:scale(.4);' +
      'filter:drop-shadow(0 2px 8px rgba(0,0,0,.35));transition:transform .18s ease-out,opacity .18s ease-out';
    document.body.appendChild(h);
    raf(function () {
      raf(function () {
        h.style.opacity = '1';
        h.style.transform = 'scale(1)';
      });
    });
    setTimeout(function () {
      h.style.transition = 'transform .25s ease-in,opacity .25s ease-in';
      h.style.opacity = '0';
      h.style.transform = 'scale(.8) translateY(-24px)';
      setTimeout(function () {
        if (h.parentNode) h.parentNode.removeChild(h);
      }, 300);
    }, 550);
  }

  function onReelDoubleTap(e) {
    if (state.covered || clipPlayer || !e.target || e.target.nodeType !== 1) return;
    // In chats, only inside the reel viewer (50-chat-reel-limit.js found it when the reel played).
    if (pageActive('direct') && !(reels.viewer && reels.viewer.isConnected && reels.viewer.contains(e.target))) return;
    var found = reelItemOf(e.target, REEL_LIKE);
    if (!found) return;
    showReelHeart(e.clientX, e.clientY);
    var item = found.item;
    setTimeout(function () {
      if (!item.isConnected) return;
      var like = reelLikeOf(item);
      if (like && !like.liked && like.button) clickElement(like.button);
    }, REEL_LIKE_WAIT_MS);
  }

  /** The video taking up most of the screen, or null. */
  function videoOnScreen() {
    var vids = document.getElementsByTagName('video');
    var best = null;
    var bestArea = window.innerWidth * window.innerHeight * 0.3; // at least 30% of the screen
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
      var h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
      var area = w > 0 && h > 0 ? w * h : 0;
      if (!vids[i].paused) area *= 2; // the playing one wins over a paused neighbour
      if (area > bestArea) {
        bestArea = area;
        best = vids[i];
      }
    }
    return best;
  }

  /** A volume key was pressed (from the app): sound on for the video on screen. */
  function onVolumeKey() {
    if (state.covered) return;
    if (clipPlayer) {
      if (clipPlayer.muted) toggleClipSound();
      return;
    }
    var v = videoOnScreen();
    if (!v || !v.muted) return;
    var found = reelItemOf(v, REEL_SOUND);
    var sound = found && found.item.querySelector(REEL_SOUND);
    if (sound) {
      clickElement(sound.closest('button, [role="button"]') || sound);
      // Instagram's button normally does it; if it didn't (another layout), the video itself.
      setTimeout(function () {
        if (v.muted) v.muted = false;
      }, 200);
    } else if (reels.viewer && reels.viewer.isConnected && reels.viewer.contains(v)) {
      tapReelForSound(v);
    } else {
      v.muted = false;
    }
  }

  /**
   * The reel viewer in chats has no labelled sound button: a tap on the reel is what switches its
   * sound, so the key taps it the same way (Instagram's own state then keeps the sound for the next
   * reels). If that tap paused it instead, a second tap plays it again and the video's own sound is
   * switched on; the same if Instagram switched the sound straight back off.
   */
  function tapReelForSound(v) {
    var r = v.getBoundingClientRect();
    var x = Math.max(0, r.left) + Math.min(r.width, window.innerWidth) / 2;
    var y = Math.max(0, r.top) + Math.min(r.height, window.innerHeight) / 2;
    var target = document.elementFromPoint(x, y);
    if (!target || !reels.viewer.contains(target) || target.closest('a, button, [role="button"]')) {
      v.muted = false;
      return;
    }
    var wasPlaying = !v.paused;
    clickElement(target);
    setTimeout(function () {
      if (wasPlaying && v.paused) {
        clickElement(target); // the tap paused it: play again
        v.muted = false;
      } else if (v.muted) {
        v.muted = false;
      }
    }, 250);
  }

  function installReelGestures() {
    document.addEventListener('dblclick', onReelDoubleTap, true);
  }
