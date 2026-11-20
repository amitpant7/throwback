  // ---------------------------------------------------------------- stories: ads and video look-ahead

  /*
   * Stories (rules: stories). Instagram loads story data in batches that already cover the next
   * people, and fetches each video in pieces (bytestart/byteend in the address). Two things:
   *  - story ads (their own data key) are emptied before the page uses them;
   *  - look-ahead: while a story plays, the first prefetchKB of the next prefetchAhead videos (and a
   *    little of their audio) are downloaded and kept in memory; when the player asks for a piece
   *    inside that range it is handed over from memory, otherwise it goes to the network as usual.
   * Memory stays small (under about 3 MB with the defaults: the current story plus 5 ahead): entries behind the current story or past the
   * look-ahead are dropped, and everything is dropped when leaving stories. Off with Data Saver.
   */
  var nativeFetch = null;
  var STORY_AUDIO_BYTES = 64 * 1024;
  // A look-ahead state: items in viewing order, what is kept in memory (store) or was sent to the
  // disk cache (images), the quality/size Instagram uses, and where the user is. One for stories,
  // one for the home feed; each is cleared when leaving its page.
  function newAhead() {
    return {
      items: [], ids: {}, store: {}, images: {}, height: 0, imgWidth: 0, current: -1,
      stats: { learned: 0, prefetched: 0, hits: 0, failed: 0, images: 0 }
    };
  }
  var stories = newAhead();
  var feedAhead = newAhead();

  function filterStoryData(o, st, depth) {
    if (!o || typeof o !== 'object' || depth > 16) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) filterStoryData(o[i], st, depth + 1);
      return;
    }
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      var v = o[k];
      if (!v || typeof v !== 'object') continue;
      if (st.adKeys.indexOf(k) !== -1) {
        emptyLists(v, 0); // no story ads
        continue;
      }
      if (k === 'items' && Array.isArray(v) && v.length && v[0] && (v[0].video_dash_manifest || v[0].video_versions || v[0].image_versions2)) {
        learnStoryItems(v);
      }
      filterStoryData(v, st, depth + 1);
    }
  }

  function emptyLists(o, depth) {
    if (!o || typeof o !== 'object' || depth > 8) return;
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      if (Array.isArray(o[k])) o[k] = [];
      else if (o[k] && typeof o[k] === 'object') emptyLists(o[k], depth + 1);
    }
  }

  /** Story items in viewing order, each with its video (and audio) versions from the DASH manifest. */
  function learnStoryItems(items) {
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (!it) continue;
      var id = String(it.pk || it.id || '');
      if (!id || stories.ids[id]) continue;
      var reps = storyReps(it);
      var cands = reps.length ? [] : storyImages(it);
      if (!reps.length && !cands.length) continue;
      stories.ids[id] = 1;
      stories.items.push({ id: id.split('_')[0], reps: reps, cands: cands });
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
        if (!state.c || !(pageActive('stories') || pageActive('home'))) return;
        list.getEntries().forEach(function (e) {
          if (e.initiatorType !== 'img') return;
          var u = parseUrl(e.name);
          if (u && storyCdn(u)) noteStoryImage(u.pathname);
        });
      }).observe({ type: 'resource', buffered: false });
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
    la.images[c.path] = 1;
    var img = new Image();
    img.decoding = 'async';
    img.onload = function () {
      la.stats.images++;
    };
    img.src = c.url;
  }

  function storyCdn(u) {
    return /(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname);
  }

  /** The player asks for a piece of a video: learn its quality, and hand it over from memory if we can. */
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
    la.stats.hits++;
    return Promise.resolve(new Response(e.buf.slice(start, end + 1), {
      status: 200,
      headers: { 'Content-Type': e.audio ? 'audio/mp4' : 'video/mp4' }
    }));
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

  /** Keeps the start of the next few videos in memory; drops everything else. */
  function storyLookAhead() {
    var st = state.c && state.c.stories;
    if (!st || !nativeFetch || stories.current < 0 || !pageActive('stories')) return;
    var conn = navigator.connection;
    var wanted = {};
    // The story on screen keeps what was already looked ahead for it: the player may ask for it a
    // moment after the address changes.
    var now = stories.items[stories.current];
    if (now) now.reps.forEach(function (rp) {
      wanted[rp.path] = 1;
    });
    if (!(conn && conn.saveData)) {
      for (var i = stories.current + 1; i <= stories.current + st.ahead && i < stories.items.length; i++) {
        if (stories.items[i].cands.length) {
          prefetchImage(stories, pickImage(stories, stories.items[i].cands));
          continue;
        }
        pickReps(stories, stories.items[i].reps).forEach(function (rp) {
          wanted[rp.path] = 1;
          if (!stories.store[rp.path]) prefetchPiece(stories, rp, rp.audio ? Math.min(STORY_AUDIO_BYTES, st.bytes) : st.bytes);
        });
      }
    }
    Object.keys(stories.store).forEach(function (path) {
      if (!wanted[path]) delete stories.store[path];
    });
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
    var entry = { audio: rp.audio, buf: null };
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

