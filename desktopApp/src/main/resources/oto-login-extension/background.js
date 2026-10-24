const api = typeof browser !== 'undefined' ? browser : chrome;

api.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.type !== 'session') return;
  (async () => {
    const cookies = await api.cookies.getAll({ url: 'https://music.youtube.com' });
    const cookie = cookies.map((c) => c.name + '=' + c.value).join('; ');
    const { port, token } = msg.pending;
    const res = await fetch('http://127.0.0.1:' + port + '/session?token=' + encodeURIComponent(token), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cookie, v: msg.config.v, d: msg.config.d, u: msg.config.u }),
    });
    reply(res.ok);
  })().catch(() => reply(false));
  return true;
});
