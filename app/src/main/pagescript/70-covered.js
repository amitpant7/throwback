  // ---------------------------------------------------------------- nothing plays while covered

  /*
   * While a Throwback screen (Settings, the lock, the pause, the reel poster) covers the page, nothing
   * may keep playing underneath: what plays is paused, and anything that starts while covered is
   * paused at once. Nothing is resumed afterwards; Instagram starts videos again itself on scroll.
   */
  function pauseAllMedia() {
    var media = document.querySelectorAll('video, audio');
    for (var i = 0; i < media.length; i++) if (!media[i].paused) media[i].pause();
  }

  function onCoveredPlay(e) {
    var m = e.target;
    if (state.covered && m && (m.tagName === 'VIDEO' || m.tagName === 'AUDIO')) m.pause();
  }

