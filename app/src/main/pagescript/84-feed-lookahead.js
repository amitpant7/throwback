  // ---------------------------------------------------------------- feed: bigger batches, look-ahead

  /*
   * Feed (rules: feedData). Instagram's feed request asks for 12 entries (`first`); after removing
   * ads and suggestions only ~2-3 real posts are left, so it keeps waiting on slow requests (4.5 s
   * measured). Its own request is raised to pageSize (24): the same request at the same moment, with
   * more entries. If Instagram ever answers that with an error, it goes back to 12 for the session.
   * Look-ahead as in stories: photos/thumbnails of the next lookAheadPosts posts go into the disk
   * cache; the start of the next lookAheadVideos videos is kept in memory (cleared off the feed).
   */
  var feedTweak = { off: false, raised: 0, refused: 0, minAhead: null };

  var tweakInstalled = false;

  function installRequestTweak() {
    var X = window.XMLHttpRequest;
    if (tweakInstalled || !X || !X.prototype) return;
    tweakInstalled = true;
    var open = X.prototype.open;
    var send = X.prototype.send;
    X.prototype.open = function (method, url) {
      this.__tbUrl = String(url || '');
      return open.apply(this, arguments);
    };
    X.prototype.send = function (body) {
      var out = body;
      try {
        if (typeof body === 'string' && this.__tbUrl.indexOf('/graphql') !== -1) {
          out = raiseFeedPage(body);
          if (out !== body) {
            var xhr = this;
            xhr.addEventListener('load', function () {
              var t = '';
              try {
                t = typeof xhr.responseText === 'string' ? xhr.responseText.slice(0, 300) : '';
              } catch (e) {
                t = '';
              }
              if (xhr.status !== 200 || /"errors"\s*:/.test(t)) {
                feedTweak.off = true; // Instagram refused the bigger batch: back to its own size
                feedTweak.refused++;
              }
            });
          }
        }
      } catch (e) {
        out = body;
      }
      return send.call(this, out);
    };
  }

  function raiseFeedPage(body) {
    var fd = state.c && state.c.feedData;
    if (!fd || !fd.pageSize || feedTweak.off || state.mode === MODE_MESSAGES) return body;
    var form = new URLSearchParams(body);
    if (fd.paginationQueries.indexOf(form.get('fb_api_req_friendly_name') || '') === -1) return body;
    var vars = JSON.parse(form.get('variables') || 'null');
    if (!vars || typeof vars.first !== 'number' || vars.first >= fd.pageSize) return body;
    vars.first = fd.pageSize;
    form.set('variables', JSON.stringify(vars));
    feedTweak.raised++;
    return form.toString();
  }

  /** A post the data filter kept: remember it in feed order, with its photo sizes and video versions. */
  function learnFeedMedia(media) {
    if (!media || typeof media !== 'object') return;
    var id = typeof media.code === 'string' ? media.code : null;
    if (!id || feedAhead.ids[id]) return;
    var first = media.carousel_media && media.carousel_media[0] ? media.carousel_media[0] : media;
    feedAhead.ids[id] = 1;
    // Parsed later, only for the posts that come into the look-ahead window (parsing every post's
    // video manifest here ran inside Instagram's JSON.parse: ~24 ms per feed page on a slow phone).
    feedAhead.items.push({ id: id, media: first, reps: null, cands: null });
    feedAhead.stats.learned++;
    if (feedAhead.items.length > 400) feedAhead.items.splice(0, feedAhead.items.length - 400);
  }

  /** The post on screen (from the feed list) → look ahead from there. */
  function noteFeedPosition(list) {
    var fd = state.c && state.c.feedData;
    if (!fd || !fd.lookAheadPosts || !list) return;
    var vh = window.innerHeight;
    for (var a = list.firstElementChild; a; a = a.nextElementSibling) {
      if (a.tagName !== 'ARTICLE' || a.hasAttribute(ATTR_POST)) continue;
      if (a.getBoundingClientRect().bottom < vh * 0.3) continue;
      var key = postKey(a);
      if (!key) return;
      for (var i = feedAhead.items.length - 1; i >= 0; i--) {
        if (feedAhead.items[i].id !== key) continue;
        var ahead = feedAhead.items.length - 1 - i;
        if (feedTweak.minAhead === null || ahead < feedTweak.minAhead) feedTweak.minAhead = ahead;
        if (feedAhead.current !== i) {
          feedAhead.current = i;
          setTimeout(feedLookAhead, 0);
        }
        return;
      }
      return;
    }
  }

  /** The post's video pieces and image candidates, parsed on first use. */
  function aheadParsed(it) {
    if (it && it.reps === null) {
      try {
        it.reps = storyReps(it.media);
        it.cands = storyImages(it.media);
      } catch (e) {
        it.reps = [];
        it.cands = [];
      }
      it.media = null;
    }
    return it;
  }

  function feedLookAhead() {
    var fd = state.c && state.c.feedData;
    if (!fd || !nativeFetch || feedAhead.current < 0 || !pageActive('home')) return;
    var conn = navigator.connection;
    var wanted = {};
    var now = aheadParsed(feedAhead.items[feedAhead.current]);
    if (now) now.reps.forEach(function (rp) {
      wanted[rp.path] = 1;
    });
    if (!(conn && conn.saveData)) {
      var videos = 0;
      var bytes = fd.videoKB * 1024;
      for (var i = feedAhead.current + 1; i <= feedAhead.current + fd.lookAheadPosts && i < feedAhead.items.length; i++) {
        var it = aheadParsed(feedAhead.items[i]);
        if (it.cands.length) prefetchImage(feedAhead, pickImage(feedAhead, it.cands)); // photo, or a video's thumbnail
        if (!it.reps.length || videos >= fd.lookAheadVideos) continue;
        videos++;
        pickReps(feedAhead, it.reps).forEach(function (rp) {
          wanted[rp.path] = 1;
          if (!feedAhead.store[rp.path]) prefetchPiece(feedAhead, rp, rp.audio ? Math.min(STORY_AUDIO_BYTES, bytes) : bytes);
        });
      }
    }
    Object.keys(feedAhead.store).forEach(function (path) {
      if (!wanted[path]) delete feedAhead.store[path];
    });
  }

