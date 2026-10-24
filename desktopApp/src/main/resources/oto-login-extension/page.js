// Corre en la pagina: lee la configuracion de YouTube Music cuando la sesion ya esta puesta.
(function () {
  let tries = 0;
  const timer = setInterval(() => {
    const c = window.yt && window.yt.config_;
    if (c && c.VISITOR_DATA && c.LOGGED_IN) {
      clearInterval(timer);
      window.postMessage({ oto: true, config: { v: c.VISITOR_DATA, d: c.DATASYNC_ID || '', u: String(c.SESSION_INDEX || 0) } }, '*');
    } else if (++tries > 600) clearInterval(timer);
  }, 500);
})();
