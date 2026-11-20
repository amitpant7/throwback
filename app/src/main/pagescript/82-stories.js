  // ---------------------------------------------------------------- stories: ads and video look-ahead

  /*
   * Stories (rules: stories). Instagram loads story data in batches that already cover the next
   * people, and fetches each video in pieces (bytestart/byteend in the address). Two things:
   *  - story ads (their own data key) are emptied before the page uses them;
   *  - look-ahead: while a story plays, the first prefetchKB of the next prefetchAhead videos (and a
   *    little of their audio) are downloaded and kept in memory; when the player asks for a piece
   *    inside that range it is handed over from memory, otherwise it goes to the network as usual.
   * Memory stays small (under about 3 MB with the defaults: the current story plus 5 ahead): video
   * pieces behind the current story or past the look-ahead are dropped, and all of them when leaving
   * stories; what was parsed for stories well behind the current one is released. Off with Data Saver.
   */
  var nativeFetch = null;
  var STORY_AUDIO_BYTES = 64 * 1024;
  // A look-ahead state: items in viewing order, what is kept in memory (store) or was sent to the
  // disk cache (images), the quality/size Instagram uses, and where the user is. One for stories,
  // one for the home feed; each is cleared when leaving its page.
  function newAhead() {
    return {
      items: [], ids: {}, store: {}, images: {}, nImages: 0, height: 0, imgWidth: 0, current: -1,
      stats: { learned: 0, prefetched: 0, hits: 0, failed: 0, images: 0 }
    };
  }

  /** Leaving the page: nothing is kept in memory or remembered as prefetched; the items stay. */
  function resetAhead(la) {
    la.store = {};
    la.images = {};
    la.nImages = 0;
    la.current = -1;
  }
  var stories = newAhead();
  var feedAhead = newAhead();

  function filterStoryData(o, st) {
    walkJson(o, 16, function (k, v) {
      if (st.adKeys.indexOf(k) !== -1) {
        emptyLists(v, 0); // no story ads
        return true;
      }
      if (k === 'items' && Array.isArray(v) && v.length && v[0] && (v[0].video_dash_manifest || v[0].video_versions || v[0].image_versions2)) {
        learnStoryItems(v);
      }
      return false;
    });
  }

  function emptyLists(o, depth) {
    if (!o || typeof o !== 'object' || depth > 8) return;
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      if (Array.isArray(o[k])) o[k] = [];
      else if (o[k] && typeof o[k] === 'object') emptyLists(o[k], depth + 1);
    }
  }

  /**
   * Story items in viewing order. This runs inside Instagram's JSON.parse, so only the shape is
   * checked here; the video manifest is parsed when the story comes into the look-ahead window
   * (aheadParsed), as for the feed.
   */
  function learnStoryItems(items) {
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (!it) continue;
      var id = String(it.pk || it.id || '');
      if (!id || stories.ids[id]) continue;
      if (typeof it.video_dash_manifest !== 'string' && !it.video_versions && !(it.image_versions2 && it.image_versions2.candidates)) continue;
      stories.ids[id] = 1;
      stories.items.push({ id: id.split('_')[0], media: it, reps: null, cands: null });
      stories.stats.learned++;
    }
    if (stories.items.length > 300) stories.items.splice(0, stories.items.length - 300);
    noteStoryLocation(); // the data may arrive after the address already shows the story
  }

  /**
   * Which story is on screen: the address is /stories/<user>/<story id>/ for each story. This is the
   * only signal used for "where we are" (image loads and video requests can't tell Instagram's own
   * preloading and Throwback's look-ahead apart from what's on screen).
   */
  function noteStoryLocation() {
    if (!pageActive('stories')) return;
    var m = /\/(\d{6,})\/?$/.exec(location.pathname);
    if (!m) return;
    for (var i = stories.items.length - 1; i >= 0; i--) {
      if (stories.items[i].id !== m[1]) continue;
      if (stories.current !== i) {
        stories.current = i;
        setTimeout(storyLookAhead, 0);
      }
      return;
    }
  }

  function storyReps(it) {
    var reps = [];
    if (typeof it.video_dash_manifest === 'string' && window.DOMParser) {
      try {
        var doc = new DOMParser().parseFromString(it.video_dash_manifest, 'application/xml');
        var sets = doc.getElementsByTagName('AdaptationSet');
        for (var s = 0; s < sets.length; s++) {
          var audio = /audio/.test(sets[s].getAttribute('contentType') || sets[s].getAttribute('mimeType') || '');
          var rs = sets[s].getElementsByTagName('Representation');
          for (var r = 0; r < rs.length; r++) {
            var base = rs[r].getElementsByTagName('BaseURL')[0];
            if (!audio) audio = /audio/.test(rs[r].getAttribute('mimeType') || '');
            var url = base && base.textContent && base.textContent.trim();
            var u = url && parseUrl(url);
            if (!u) continue;
            reps.push({ url: url, path: u.pathname, audio: audio, height: +(rs[r].getAttribute('height') || 0) });
          }
        }
      } catch (e) {
        reps = [];
      }
    }
    if (!reps.length && it.video_versions) {
      it.video_versions.forEach(function (v) {
        var u = v && v.url && parseUrl(v.url);
        if (u) reps.push({ url: v.url, path: u.pathname, audio: false, height: +(v.height || 0) });
      });
    }
    return reps;
  }

  /** A photo story's sizes (candidates), for the photo look-ahead. */
  function storyImages(it) {
    var list = (it.image_versions2 && it.image_versions2.candidates) || [];
    var out = [];
    list.forEach(function (c) {
      var u = c && c.url && parseUrl(c.url);
      if (u) out.push({ url: c.url, path: u.pathname, width: +(c.width || 0) });
    });
    return out;
  }

  /** A photo loaded on the page: learn the size Instagram shows (not our own look-ahead loads). */
  function noteStoryImage(path) {
    [stories, feedAhead].forEach(function (la) {
      if (la.images[path]) return;
      for (var i = la.items.length - 1; i >= 0; i--) {
        var cands = la.items[i].cands;
        if (!cands) continue; // not parsed yet (feed posts far from the screen)
        for (var c = 0; c < cands.length; c++) {
          if (cands[c].path !== path) continue;
          if (cands[c].width) la.imgWidth = cands[c].width;
          return;
        }
      }
    });
  }

  function watchStoryImages() {
    if (!window.PerformanceObserver) return;
    try {
      new PerformanceObserver(function (list) {
        var images = !!state.c && (pageActive('stories') || pageActive('home'));
        list.getEntries().forEach(function (e) {
          if (e.initiatorType !== 'img') {
            noteCodeEntry(e); // for the warm-up (72-warmup.js)
            return;
          }
          var u = images && parseUrl(e.name);
          if (u && storyCdn(u)) noteStoryImage(u.pathname);
        });
      }).observe({ type: 'resource', buffered: true });
    } catch (e) {
      // Not supported: the video look-ahead still works.
    }
  }

  /** The photo size closest to what Instagram showed last (else to the screen width). */
  function pickImage(la, cands) {
    var target = la.imgWidth || Math.round(window.innerWidth * (window.devicePixelRatio || 1));
    return cands.slice().sort(function (a, b) {
      return Math.abs(a.width - target) - Math.abs(b.width - target);
    })[0];
  }

  /** Into the browser's disk cache (not memory): Instagram's own image then loads from there. */
  function prefetchImage(la, c) {
    if (!c || la.images[c.path]) return;
    if (++la.nImages > 1000) { // a long session: forget the oldest ones rather than grow
      la.images = {};
      la.nImages = 1;
    }
    la.images[c.path] = 1;
    var img = new Image();
    img.decoding = 'async';
    img.onload = function () {
      la.stats.images++;
    };
    img.src = c.url;
  }

  function storyCdn(u) {
    return isCdnHost(u.hostname);
  }

  /**
   * The player asks for a piece of a video: learn its quality, and hand it over from memory if we can.
   * Each piece is handed over once, and on a later task, never at once. Instagram's player can ask for
   * the same piece again and again (seen on the emulator, 2026-11-20: the last piece of a short audio
   * track, about twice a millisecond). Over the network each ask waits for an answer; from memory,
   * answered at once, that became an endless loop that froze the page and grew memory by ~20 MB/s. A
   * repeated ask now goes to the network, as it would without Throwback.
   */
  function storyFetchHit(url) {
    if (url.indexOf('bytestart=') === -1 || !state.c) return null;
    var u = parseUrl(url);
    if (!u || !storyCdn(u)) return null;
    noteStoryPlaying(u.pathname);
    var la = stories.store[u.pathname] ? stories : feedAhead;
    var e = la.store[u.pathname];
    var start = +u.searchParams.get('bytestart');
    var end = +u.searchParams.get('byteend');
    if (!e || !e.buf || !(start >= 0) || !(end >= start) || end >= e.buf.byteLength) return null;
    var range = start + '-' + end;
    if (e.served[range]) return null;
    e.served[range] = true;
    la.stats.hits++;
    var body = e.buf.slice(start, end + 1);
    var headers = { 'Content-Type': e.audio ? 'audio/mp4' : 'video/mp4', 'Content-Length': String(body.byteLength) };
    return new Promise(function (resolve) {
      setTimeout(function () {
        resolve(new Response(body, { status: 200, headers: headers }));
      }, 0);
    });
  }

  function noteStoryPlaying(path) {
    [stories, feedAhead].forEach(function (la) {
      for (var i = la.items.length - 1; i >= 0; i--) {
        var reps = la.items[i].reps;
        if (!reps) continue; // not parsed yet
        for (var r = 0; r < reps.length; r++) {
          if (reps[r].path !== path) continue;
          if (!reps[r].audio && reps[r].height) la.height = reps[r].height; // the quality the player picks
          return;
        }
      }
    });
  }

  function storyLookAhead() {
    var st = state.c && state.c.stories;
    if (!st || !nativeFetch || stories.current < 0 || !pageActive('stories')) return;
    lookAhead(stories, st.ahead, st.ahead, st.bytes, false);
  }

  /**
   * From the item on screen (la.current): the next `count` items' photos go to the disk cache (with
   * `thumbs`, video thumbnails too), and the start of up to `maxVideos` videos (`bytes`, plus a little
   * audio) is kept in memory. The item on screen keeps what it had: the player may ask for it a moment
   * after the address changes. Everything else in memory is dropped. Off with Data Saver.
   */
  function lookAhead(la, count, maxVideos, bytes, thumbs) {
    var conn = navigator.connection;
    var wanted = {};
    var now = aheadParsed(la.items[la.current]);
    if (now) now.reps.forEach(function (rp) {
      wanted[rp.path] = 1;
    });
    if (!(conn && conn.saveData)) {
      var videos = 0;
      for (var i = la.current + 1; i <= la.current + count && i < la.items.length; i++) {
        var it = aheadParsed(la.items[i]);
        if (it.cands.length && (thumbs || !it.reps.length)) prefetchImage(la, pickImage(la, it.cands));
        if (!it.reps.length || videos >= maxVideos) continue;
        videos++;
        pickReps(la, it.reps).forEach(function (rp) {
          wanted[rp.path] = 1;
          if (!la.store[rp.path]) prefetchPiece(la, rp, rp.audio ? Math.min(STORY_AUDIO_BYTES, bytes) : bytes);
        });
      }
    }
    Object.keys(la.store).forEach(function (path) {
      if (!wanted[path]) delete la.store[path];
    });
    releaseBehind(la);
  }

  var RELEASED = [];

  /** Items well behind the one on screen won't be looked ahead again: drop what was parsed for them. */
  function releaseBehind(la) {
    for (var j = la.current - 10; j >= 0 && la.items[j].reps !== RELEASED; j--) {
      la.items[j].reps = la.items[j].cands = RELEASED;
      la.items[j].media = null;
    }
  }

  /** The video version closest to what the player last used (else the smallest), plus one audio. */
  function pickReps(la, reps) {
    var videos = reps.filter(function (r) {
      return !r.audio;
    });
    var audio = reps.filter(function (r) {
      return r.audio;
    })[0];
    var target = la.height;
    videos.sort(function (a, b) {
      return target ? Math.abs(a.height - target) - Math.abs(b.height - target) : a.height - b.height;
    });
    var out = videos.slice(0, 1);
    if (audio) out.push(audio);
    return out;
  }

  function prefetchPiece(la, rp, bytes) {
    if (!bytes) return;
    var entry = { audio: rp.audio, buf: null, served: {} };
    la.store[rp.path] = entry;
    var url = rp.url + (rp.url.indexOf('?') === -1 ? '?' : '&') + 'bytestart=0&byteend=' + (bytes - 1);
    nativeFetch.call(window, url, { credentials: 'omit', mode: 'cors' }).then(function (res) {
      if (!res.ok) throw new Error('status ' + res.status);
      return res.arrayBuffer();
    }).then(function (ab) {
      if (la.store[rp.path] !== entry) return; // dropped meanwhile
      entry.buf = new Uint8Array(ab);
      la.stats.prefetched++;
    }).catch(function () {
      la.stats.failed++;
      if (la.store[rp.path] === entry) delete la.store[rp.path];
    });
  }

  function memoryKB(la) {
    var b = 0;
    Object.keys(la.store).forEach(function (p) {
      if (la.store[p].buf) b += la.store[p].buf.byteLength;
    });
    return Math.round(b / 1024);
  }

