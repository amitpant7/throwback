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

