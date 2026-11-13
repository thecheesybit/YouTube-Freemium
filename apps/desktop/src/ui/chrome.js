'use strict';
// Shell chrome: back / forward / reload for the active site view. State comes from main (state.nav); never shows a URL.
(function () {
  var back = document.getElementById('back');
  var forward = document.getElementById('forward');
  var reload = document.getElementById('reload');
  var title = document.getElementById('title');

  function act(type) { window.movy.act(type); }
  back.addEventListener('click', function () { act('nav-back'); });
  forward.addEventListener('click', function () { act('nav-forward'); });
  reload.addEventListener('click', function () { act('reload'); });

  function apply(state) {
    var nav = state && state.nav;
    if (!nav) return;
    back.disabled = !nav.canGoBack;
    forward.disabled = !nav.canGoForward;
    title.textContent = nav.sourceName || '';
    document.body.classList.toggle('fullscreen', !!nav.fullscreen);
  }

  window.movy.onPush(function (msg) {
    if (msg.type === 'state') apply(msg.state);
  });
  window.movy.state().then(apply);
})();
