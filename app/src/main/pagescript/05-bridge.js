  // ---------------------------------------------------------------- bridge

  function send(msg) {
    var s;
    try {
      s = JSON.stringify(msg);
    } catch (e) {
      return;
    }
    try {
      var native = window.throwbackNative; // WebViewCompat.addWebMessageListener
      if (native && typeof native.postMessage === 'function') {
        native.postMessage(s);
        return;
      }
      var legacy = window.throwbackBridge; // @JavascriptInterface fallback
      if (legacy && typeof legacy.postMessage === 'function') legacy.postMessage(s);
    } catch (e) {
      // The bridge goes away while the page unloads; nothing to do.
    }
  }

  /**
   * Taps on el (Throwback's own buttons and rows in the page) go to the app as a bridge message, never
   * to Instagram's handlers. msg is the message, or a function that builds it at tap time.
   */
  function tapToApp(el, msg) {
    tapTo(el, function () {
      send(typeof msg === 'function' ? msg() : msg);
    });
  }

  /** Taps on el (one of Throwback's own elements) run handler and never reach Instagram's handlers. */
  function tapTo(el, handler) {
    el.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      handler();
    }, true);
  }

