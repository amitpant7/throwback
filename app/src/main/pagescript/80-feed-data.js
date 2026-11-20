  // ---------------------------------------------------------------- feed data filter (option 1)

  /*
   * Instagram's page downloads the feed as JSON pages of edges; each node holds a post from someone you
   * follow ("media"), a suggested post ("explore_story") or an ad (rules: feedData). Throwback removes
   * ads and suggestions from that data before Instagram's code sees it, so the page never draws them:
   * no hiding afterwards, no flicker, correct scrolling. The first batch comes built into the page;
   * it is parsed with JSON.parse too, and the page-level filter below stays as the safety net.
   * Nothing extra is requested from Instagram: only data the page already downloaded is filtered.
   */
  var feedDataState = { emptyPages: 0, sinceDiscovery: 0, cursors: {}, ended: false };
  var dataFilterInstalled = false;

  function installDataFilter() {
    if (dataFilterInstalled) return;
    dataFilterInstalled = true;
    var nativeParse = JSON.parse;
    var parse = function parse(text, reviver) {
      var out = nativeParse.apply(JSON, arguments);
      var st = state.c && state.c.stories;
      if (st && typeof text === 'string' && text.length > 200 && out && typeof out === 'object') {
        for (var j = 0; j < st.markers.length; j++) {
          if (text.indexOf(st.markers[j]) === -1) continue;
          try {
            filterStoryData(out, st, 0);
          } catch (e) {
            // Never break Instagram's own parsing.
          }
          break;
        }
      }
      if (typeof text === 'string' && text.length > 200 && out && typeof out === 'object' &&
        onExploreOrSearch() && text.indexOf('"video_versions"') !== -1) {
        try {
          learnClips(out, 0);
        } catch (e) {
          // Never break Instagram's own parsing.
        }
      }
      var fd = state.c && state.c.feedData;
      if (fd && typeof text === 'string' && text.length > 200 && out && typeof out === 'object') {
        for (var i = 0; i < fd.connections.length; i++) {
          if (text.indexOf(fd.connections[i]) === -1) continue;
          try {
            filterFeedData(out, fd, 0);
          } catch (e) {
            // Never break Instagram's own parsing; the page-level filter still applies.
          }
          break;
        }
      }
      return out;
    };
    try {
      JSON.parse = parse;
    } catch (e) {
      return;
    }
    // Story videos are fetched in pieces; pieces already looked ahead are handed over from memory.
    if (typeof window.fetch === 'function') {
      nativeFetch = window.fetch;
      window.fetch = function (input) {
        try {
          var url = typeof input === 'string' ? input : input && input.url;
          var hit = url && storyFetchHit(url);
          if (hit) return hit;
        } catch (e) {
          // Fall through to the network.
        }
        return nativeFetch.apply(this, arguments);
      };
    }
    // fetch() responses read with .json() bypass JSON.parse; route them through it.
    if (window.Response && Response.prototype && typeof Response.prototype.json === 'function') {
      Response.prototype.json = function () {
        return this.text().then(function (t) {
          return JSON.parse(t);
        });
      };
    }
  }

  function filterFeedData(o, fd, depth) {
    if (!o || typeof o !== 'object' || depth > 16) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) filterFeedData(o[i], fd, depth + 1);
      return;
    }
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      var v = o[k];
      if (!v || typeof v !== 'object') continue;
      if (fd.connections.indexOf(k) !== -1 && Array.isArray(v.edges)) filterConnection(v, fd);
      else filterFeedData(v, fd, depth + 1);
    }
  }

  function fieldIn(n, fields) {
    for (var i = 0; i < fields.length; i++) if (n[fields[i]] != null) return fields[i];
    return null;
  }

  function valueAt(o, path) {
    var parts = path.split('.');
    for (var i = 0; i < parts.length && o != null; i++) o = o[parts[i]];
    return o;
  }

  function anyTest(o, tests) {
    for (var i = 0; i < tests.length; i++) {
      var t = tests[i];
      var v = valueAt(o, t.path);
      if (t.present != null ? (v != null) === !!t.present : v != null && String(v) === t.equals) return true;
    }
    return false;
  }

  function filterConnection(conn, fd) {
    var info = conn.page_info;
    var cursor = info && info.end_cursor;
    // The same page can be parsed more than once (cache, re-render): count it only the first time.
    var fresh = !cursor || !feedDataState.cursors[cursor];
    if (cursor) feedDataState.cursors[cursor] = 1;
    var kept = [];
    var followed = 0;
    var endAt = false;
    for (var i = 0; i < conn.edges.length; i++) {
      var e = conn.edges[i];
      var n = e && e.node;
      if (!n || typeof n !== 'object') {
        kept.push(e);
        continue;
      }
      if (fieldIn(n, fd.endMarkers)) {
        if (fd.endAtMarker) {
          endAt = true;
          break;
        }
        continue; // Instagram's mid-feed "caught up" marker; Throwback shows its own at the real end
      }
      if (fieldIn(n, fd.adNodes) || fieldIn(n, fd.dropNodes)) {
        if (fresh && fieldIn(n, fd.adNodes)) countRemoved('ads');
        continue;
      }
      var sField = fieldIn(n, fd.suggestionNodes);
      var media = sField ? n[sField] && n[sField][fd.mediaField] : n[fd.mediaField];
      if (media && typeof media === 'object' && anyTest(media, fd.adMedia)) {
        if (fresh) countRemoved('ads');
        continue;
      }
      if (sField || (media && anyTest(media, fd.suggestedMedia))) {
        if (media && dataDiscovery(media, fresh)) {
          kept.push(e);
          learnFeedMedia(media);
        } else if (fresh) {
          countRemoved('sugg');
        }
        continue;
      }
      kept.push(e);
      learnFeedMedia(media);
      if (media) {
        followed++;
        if (fresh) feedDataState.sinceDiscovery++;
      }
    }
    conn.edges = kept;
    if (fresh) feedDataState.emptyPages = followed > 0 ? 0 : feedDataState.emptyPages + 1;
    var ended = endAt || feedDataState.emptyPages >= fd.endAfterEmptyPages;
    // Only suggestions are left: tell Instagram there are no more pages, so it stops loading.
    if (ended && info) info.has_next_page = false;
    if (fresh) {
      feedDataState.ended = ended;
      if (state.c) schedulePass(false);
    }
  }

  /** A suggested post from the data: kept when the discovery allowance has room (or it was kept before). */
  function dataDiscovery(media, fresh) {
    var key = typeof media.code === 'string' ? media.code : null;
    var d = discState();
    if (key && discoveredKeys[key]) return true;
    if (!fresh || state.mode === MODE_MESSAGES || feedDataState.ended) return false;
    var kind = media.media_type === 2 || media.product_type === 'clips' ? 'video' : 'photo';
    if (!takeDiscovery(d, kind, feedDataState.sinceDiscovery >= DISCOVER.spacing, key)) return false;
    feedDataState.sinceDiscovery = 0;
    return true;
  }

