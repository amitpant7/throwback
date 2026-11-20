  // ---------------------------------------------------------------- trusted clock

  /*
   * Trusted time (owner, 2026-11-20). The app passes how far its tamper-resistant clock is from the
   * phone's, and the UTC offset of the time zone days are counted in (a new zone only counts from the
   * next midnight). So changing the phone's clock or time zone neither starts a new day for the
   * daily counters (Explore minutes, Discover, Your day) nor ends the chat-reel cooldown early. The
   * day never goes backwards either. Without the app (tests) the phone's own clock is used.
   */
  var clock = { offset: 0, zoneMin: null, day: null, nextAt: 0 };
  var LASTDAY_KEY = '__tb_lastday';
  var lastDay = null;

  function trustedNow() {
    return Date.now() + clock.offset;
  }

  function today() {
    var ms = trustedNow();
    var zoneMin = clock.zoneMin === null ? -new Date(ms).getTimezoneOffset() : clock.zoneMin;
    // The app's day while it lasts (it knows when the next one may start); after that, count on.
    var n = clock.day !== null && ms < clock.nextAt ? clock.day : Math.floor((ms + zoneMin * 60000) / 86400000);
    var local = new Date(n * 86400000);
    if (!lastDay) {
      try {
        lastDay = JSON.parse(localStorage.getItem(LASTDAY_KEY) || 'null');
      } catch (e) {
        lastDay = null;
      }
    }
    if (lastDay && typeof lastDay.n === 'number' && n < lastDay.n) return lastDay.key; // never backwards
    var key = new Date(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate()).toDateString();
    if (!lastDay || lastDay.n !== n) {
      lastDay = { n: n, key: key };
      try {
        localStorage.setItem(LASTDAY_KEY, JSON.stringify(lastDay));
      } catch (e) {
        // Storage unavailable: fine for this page.
      }
    }
    return key;
  }

