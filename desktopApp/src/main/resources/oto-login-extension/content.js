// Lee el aviso de Oto (?oto_login=PUERTO.TOKEN), espera a que haya sesion y se la pasa al fondo.
const api = typeof browser !== 'undefined' ? browser : chrome;
const DEADLINE_MS = 5 * 60 * 1000;

const param = new URLSearchParams(location.search).get('oto_login');
if (param) {
  const [port, token] = param.split('.');
  if (/^\d+$/.test(port) && token) api.storage.local.set({ pending: { port, token, until: Date.now() + DEADLINE_MS } });
}

api.storage.local.get('pending').then(({ pending }) => {
  if (!pending || pending.until < Date.now()) return;
  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data || e.data.oto !== true) return;
    api.runtime.sendMessage({ type: 'session', pending, config: e.data.config }).then((ok) => {
      if (ok) api.storage.local.remove('pending');
    });
  });
  document.addEventListener('DOMContentLoaded', () => {
    const s = document.createElement('script');
    s.src = api.runtime.getURL('page.js');
    document.documentElement.appendChild(s);
  });
});
