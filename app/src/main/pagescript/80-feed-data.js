  // ---------------------------------------------------------------- feed data filter

  /*
   * Instagram's page downloads the feed as JSON pages of edges; each node holds a post from someone you
   * follow ("media"), a suggested post ("explore_story") or an ad (rules: feedData). Throwback removes
   * ads and suggestions from that data before Instagram's code sees it, so the page never draws them:
   * no hiding afterwards, no flicker, correct scrolling. The first batch comes built into the page;
   * it is parsed with JSON.parse too, and the page-level filters (40-filtering.js, 86-approve-posts.js)
   * stay as the safety net.
   * Nothing extra is requested from Instagram: only data the page already downloaded is filtered.
   */
  var feedDataState = { emptyPages: 0, sinceDiscovery: 0, cursors: {}, ended: false, endCursor: null };
  var dataFilterInstalled = false;

  function installDataFilter() {
    if (dataFilterInstalled) return;
    dataFilterInstalled = true;
    var nativeParse = JSON.parse;
    var parse = function parse(text, reviver) {
      var out = nativeParse.apply(JSON, arguments);
      // Only large objects hold feed, story or Explore data (Instagram parses many small ones). Each
      // filter is on its own: one failing never breaks Instagram's parsing or the other filters (the
      // page-level filters still apply).
      if (!state.c || typeof text !== 'string' || text.length <= 200 || !out || typeof out !== 'object') return out;
      var st = state.c.stories;
      if (st && containsAny(text, st.markers)) {
        try {
          filterStoryData(out, st);
        } catch (e) {}
      }
      if (onExploreOrSearch() && text.indexOf('"video_versions"') !== -1) {
        try {
          learnClips(out);
        } catch (e) {}
      }
      var fd = state.c.feedData;
      if (fd && containsAny(text, fd.connections)) {
        try {
          filterFeedData(out, fd);
        } catch (e) {}
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

  function filterFeedData(o, fd) {
    walkJson(o, 16, function (k, v) {
      if (fd.connections.indexOf(k) === -1 || !Array.isArray(v.edges)) return false;
      filterConnection(v, fd);
      return true;
    });
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
    // A page parsed again later (Instagram's cache, coming back to the feed) ends it only if it is
    // the page that ended it, not any page once the end has been reached.
    var ended = endAt || (fresh ? feedDataState.emptyPages >= fd.endAfterEmptyPages : !!cursor && cursor === feedDataState.endCursor);
    // Only suggestions are left: tell Instagram there are no more pages, so it stops loading.
    if (ended && info) info.has_next_page = false;
    if (fresh) {
      feedDataState.ended = ended;
      if (ended) feedDataState.endCursor = cursor || null;
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

