'use strict';

/* =====================================================================
   Aura Player — свой интерфейс для Spotify.
   Музыку играет официальное приложение Spotify (на телефоне, ПК, колонке),
   Aura показывает, что играет, и управляет воспроизведением через Web API.
   ===================================================================== */

/* ---------- Настройки ---------- */
const CONFIG = {
  // Можно вписать сюда Client ID из Spotify Dashboard, тогда поле на экране входа не появится.
  clientId: '78df86d806e945d48c9c151febadaa98',
  scopes: [
    'user-read-playback-state',
    'user-modify-playback-state',
    'user-read-currently-playing',
    'playlist-read-private',
    'playlist-read-collaborative',
    'user-library-read',
    'user-library-modify',
    'user-top-read',
    'user-read-recently-played',
    'playlist-modify-public',
    'playlist-modify-private',
  ].join(' '),
  // Увеличивай, когда меняется список scopes — Aura попросит войти заново.
  scopesVersion: 2,
  pollMs: 3000,
};
const REDIRECT_URI = location.origin + location.pathname.replace(/index\.html$/, '');
const API = 'https://api.spotify.com/v1';
// Мост к Android-приложению Aura (есть только внутри него)
const NATIVE = window.AuraNative || null;
const APK_URL = 'https://github.com/SAFA-254/aura-player/releases/latest/download/Aura.apk';
const DEFAULT_THEME = { bg1: '#2a1f3d', bg2: '#120e18', accent: '#c9a7ff', onAccent: '#120e18', meta: '#16121c' };

/* ---------- Утилиты ---------- */
const $ = (sel, root = document) => root.querySelector(sel);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
// fetch с тайм-аутом: на мобильной сети запрос может «повиснуть» навсегда, и плеер перестаёт обновляться
function fetchT(url, opts = {}, ms = 10000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  return fetch(url, { ...opts, signal: ctl.signal }).finally(() => clearTimeout(timer));
}

const store = {
  get(key, fallback = null) {
    // В Android-приложении вход хранится в самом приложении — им пользуются виджет и будильник
    if (NATIVE && key === 'token') {
      try { const v = NATIVE.getToken(); return v ? JSON.parse(v) : fallback; } catch { return fallback; }
    }
    try {
      const v = localStorage.getItem('aura.' + key);
      return v === null ? fallback : JSON.parse(v);
    } catch { return fallback; }
  },
  set(key, value) {
    if (NATIVE && key === 'token') return NATIVE.saveToken(JSON.stringify(value));
    try { localStorage.setItem('aura.' + key, JSON.stringify(value)); } catch {}
  },
  del(key) {
    if (NATIVE && key === 'token') return NATIVE.saveToken('');
    try { localStorage.removeItem('aura.' + key); } catch {}
  },
};

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'html') node.innerHTML = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    node.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return node;
}

function fmtTime(ms) {
  const s = Math.floor(Math.max(0, ms || 0) / 1000);
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

function pickImage(images, min = 300) {
  if (!images || !images.length) return '';
  const sorted = [...images].sort((a, b) => (a.width || 640) - (b.width || 640));
  return (sorted.find((i) => (i.width || 640) >= min) || sorted[sorted.length - 1]).url;
}

function itemImages(item) { return item?.album?.images || item?.images || item?.show?.images || []; }
function itemArtists(item) { return item?.artists?.map((a) => a.name).join(', ') || item?.show?.name || ''; }
function plural(n, one, few, many) {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

let toastTimer;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
}

/* ---------- Иконки ---------- */
const NEXT_PATH = '<path d="M5 6.3v11.4a1.1 1.1 0 0 0 1.7.92l8.3-5.7a1.1 1.1 0 0 0 0-1.84L6.7 5.38A1.1 1.1 0 0 0 5 6.3z"/><rect x="16.4" y="5" width="2.6" height="14" rx="1.3"/>';
const REPEAT_PATH = '<path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/>';
const HEART_PATH = '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21.2l7.8-7.7 1-1.1a5.5 5.5 0 0 0 0-7.8z"/>';
const ICONS = {
  play: ['f', '<path d="M8 5.6v12.8a1.1 1.1 0 0 0 1.68.94l10.2-6.4a1.1 1.1 0 0 0 0-1.88L9.68 4.66A1.1 1.1 0 0 0 8 5.6z"/>'],
  pause: ['f', '<rect x="6" y="4.5" width="4.2" height="15" rx="1.4"/><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.4"/>'],
  next: ['f', NEXT_PATH],
  prev: ['f', '<g transform="matrix(-1 0 0 1 24 0)">' + NEXT_PATH + '</g>'],
  shuffle: ['s', '<path d="M16 3h5v5"/><path d="M4 20 21 3"/><path d="M21 16v5h-5"/><path d="m15 15 6 6"/><path d="m4 4 5 5"/>'],
  repeat: ['s', REPEAT_PATH],
  repeat1: ['s', REPEAT_PATH + '<path d="M11 10.5 12.5 9.5v5" stroke-width="1.8"/>'],
  heart: ['s', HEART_PATH],
  heartFill: ['f', HEART_PATH],
  headphones: ['s', '<path d="M3 18v-6a9 9 0 0 1 18 0v6"/><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"/>'],
  earbuds: ['s', '<path d="M7 3.5A3.5 3.5 0 0 1 10.5 7v2.5a3 3 0 0 1-2 2.83V19a1.5 1.5 0 0 1-3 0v-6.9A3.5 3.5 0 0 1 3.5 8.6V7A3.5 3.5 0 0 1 7 3.5z"/><path d="M17 3.5A3.5 3.5 0 0 0 13.5 7v2.5a3 3 0 0 0 2 2.83V19a1.5 1.5 0 0 0 3 0v-6.9a3.5 3.5 0 0 0 2-3.5V7A3.5 3.5 0 0 0 17 3.5z"/>'],
  speaker: ['s', '<rect x="5" y="2.5" width="14" height="19" rx="2.5"/><circle cx="12" cy="14" r="3.5"/><path d="M12 6.5h.01"/>'],
  phone: ['s', '<rect x="6.5" y="2" width="11" height="20" rx="2.5"/><path d="M11 18h2"/>'],
  computer: ['s', '<rect x="2.5" y="4" width="19" height="12.5" rx="2"/><path d="M8 20.5h8M12 16.5v4"/>'],
  queue: ['s', '<path d="M4 6h11M4 11h11M4 16h7"/><path d="M19 5v10.5"/><circle cx="17" cy="16.5" r="2.5"/>'],
  addQueue: ['s', '<path d="M4 7h10M4 12h10M4 17h6"/><path d="M18 13v8M14 17h8"/>'],
  search: ['s', '<circle cx="11" cy="11" r="7"/><path d="m20.5 20.5-4.5-4.5"/>'],
  library: ['s', '<path d="M4.5 4v16M9.5 4v16"/><path d="m14 4.6 5.5 15"/>'],
  more: ['f', '<circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/>'],
  close: ['s', '<path d="M18 6 6 18M6 6l12 12"/>'],
  back: ['s', '<path d="m15 18-6-6 6-6"/>'],
  copy: ['s', '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>'],
  volume: ['s', '<path d="M11 5 6 9H3v6h3l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9.5 9.5 0 0 1 0 13"/>'],
  download: ['s', '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/>'],
  music: ['s', '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>'],
  refresh: ['s', '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>'],
  logout: ['s', '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>'],
  plus: ['s', '<path d="M12 5v14M5 12h14"/>'],
  lyrics: ['s', '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 8.5h8M8 12.5h5"/>'],
  share: ['s', '<path d="M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7"/><path d="m16 6-4-4-4 4"/><path d="M12 2v13"/>'],
  user: ['s', '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>'],
  disc: ['s', '<circle cx="12" cy="12" r="9.5"/><circle cx="12" cy="12" r="2.5"/>'],
  stats: ['s', '<path d="M6 20V11M12 20V4M18 20v-6"/>'],
  expand: ['s', '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>'],
  spotify: ['f', '<path fill-rule="evenodd" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.6 14.4a.7.7 0 0 1-.96.24c-2.6-1.6-5.9-1.96-9.8-1.07a.7.7 0 1 1-.31-1.36c4.24-.97 7.9-.55 10.83 1.23.33.2.44.63.24.96zm1.2-2.7a.87.87 0 0 1-1.2.29c-3-1.84-7.55-2.37-11.08-1.3a.87.87 0 0 1-.5-1.67c4.04-1.22 9.06-.63 12.5 1.48.4.25.53.79.28 1.2zm.1-2.8C14.3 8.76 8.37 8.56 4.94 9.6a1.05 1.05 0 1 1-.6-2c3.94-1.2 10.5-.97 14.64 1.49a1.05 1.05 0 0 1-1.07 1.8z"/>'],
};

function icon(name) {
  const [kind, body] = ICONS[name];
  const attrs = kind === 'f'
    ? 'fill="currentColor"'
    : 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  return `<svg viewBox="0 0 24 24" ${attrs} aria-hidden="true">${body}</svg>`;
}
function setIcon(node, name) {
  if (node.dataset.cur === name) return;
  node.dataset.cur = name;
  node.innerHTML = icon(name);
}

/* =====================================================================
   Вход через Spotify (Authorization Code + PKCE, без сервера)
   ===================================================================== */
function randomString(len) {
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  return base64url(bytes).slice(0, len);
}
function base64url(bytes) {
  let s = '';
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function tokenRequest(params) {
  const res = await fetchT('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  }, 15000);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error_description || data.error || 'Ошибка авторизации');
    err.code = data.error;
    throw err;
  }
  return data;
}

// v — версия набора разрешений, с которыми входили (при обновлении токена она не меняется)
function saveToken(d, v) {
  const t = { access_token: d.access_token, refresh_token: d.refresh_token, expires_at: Date.now() + d.expires_in * 1000, v };
  store.set('token', t);
  return t;
}

const Auth = {
  _refreshing: null,

  clientId() { return CONFIG.clientId || store.get('clientId', ''); },

  async login() {
    const verifier = randomString(64);
    const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
    // Из приложения входим через обычный браузер (там уже есть вход в Spotify и Google),
    // а страница в браузере по метке app- вернёт код обратно в приложение.
    const state = (NATIVE ? 'app-' : '') + randomString(16);
    store.set('pkce', { verifier, state });
    const params = new URLSearchParams({
      client_id: this.clientId(),
      response_type: 'code',
      redirect_uri: REDIRECT_URI,
      code_challenge_method: 'S256',
      code_challenge: challenge,
      scope: CONFIG.scopes,
      state,
    });
    const url = 'https://accounts.spotify.com/authorize?' + params;
    if (NATIVE) NATIVE.openExternal(url);
    else location.assign(url);
  },

  // Возвращает true, если мы только что вернулись со страницы входа Spotify.
  async handleRedirect() {
    const q = new URLSearchParams(location.search);
    if (!q.has('code') && !q.has('error')) return false;
    history.replaceState(null, '', REDIRECT_URI);
    if (q.get('error')) throw new Error(q.get('error') === 'access_denied' ? 'Вход отменён' : 'Ошибка входа: ' + q.get('error'));
    const pkce = store.get('pkce');
    store.del('pkce');
    if (!pkce || pkce.state !== q.get('state')) throw new Error('Не получилось войти, попробуй ещё раз');
    const data = await tokenRequest({
      grant_type: 'authorization_code',
      code: q.get('code'),
      redirect_uri: REDIRECT_URI,
      code_verifier: pkce.verifier,
      client_id: this.clientId(),
    });
    saveToken(data, CONFIG.scopesVersion);
    return true;
  },

  async accessToken() {
    const t = store.get('token');
    if (!t) return null;
    if (Date.now() < t.expires_at - 60_000) return t.access_token;
    const fresh = await this.refresh();
    return fresh ? fresh.access_token : null;
  },

  refresh() {
    if (!this._refreshing) {
      const t = store.get('token');
      if (!t || !t.refresh_token) return Promise.resolve(null);
      this._refreshing = tokenRequest({ grant_type: 'refresh_token', refresh_token: t.refresh_token, client_id: this.clientId() })
        .then((d) => {
          if (!d.refresh_token) d.refresh_token = t.refresh_token;
          return saveToken(d, t.v);
        })
        .catch((e) => {
          if (e.code === 'invalid_grant') store.del('token');
          throw e;
        })
        .finally(() => { this._refreshing = null; });
    }
    return this._refreshing;
  },

  logout() { store.del('token'); },
};

/* =====================================================================
   Spotify Web API
   ===================================================================== */
class ApiError extends Error {
  constructor(status, message, reason) {
    super(message);
    this.status = status;
    this.reason = reason;
  }
}

class SpotifyApi {
  constructor() { this.backoffUntil = 0; this.isDemo = false; }

  async req(method, path, { query, body, retry = true } = {}) {
    const token = await Auth.accessToken();
    if (!token) throw new ApiError(401, 'Нужно войти');
    const url = new URL(API + path);
    if (query) for (const [k, v] of Object.entries(query)) if (v != null) url.searchParams.set(k, v);
    const headers = { Authorization: 'Bearer ' + token };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetchT(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });

    if (res.status === 401 && retry) {
      await Auth.refresh();
      return this.req(method, path, { query, body, retry: false });
    }
    if (res.status === 204 || res.status === 202) return null;
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch {}
    if (!res.ok) {
      if (res.status === 429) this.backoffUntil = Date.now() + (Number(res.headers.get('Retry-After')) || 5) * 1000;
      throw new ApiError(res.status, data?.error?.message || 'Ошибка ' + res.status, data?.error?.reason);
    }
    return data;
  }

  me() { return this.req('GET', '/me'); }
  playerState() { return this.req('GET', '/me/player', { query: { additional_types: 'episode' } }); }
  devices() { return this.req('GET', '/me/player/devices').then((d) => d?.devices || []); }
  play(opts = {}, deviceId) {
    return this.req('PUT', '/me/player/play', {
      query: deviceId ? { device_id: deviceId } : undefined,
      body: Object.keys(opts).length ? opts : undefined,
    });
  }
  pause() { return this.req('PUT', '/me/player/pause'); }
  next() { return this.req('POST', '/me/player/next'); }
  prev() { return this.req('POST', '/me/player/previous'); }
  seek(ms) { return this.req('PUT', '/me/player/seek', { query: { position_ms: Math.round(ms) } }); }
  shuffle(on) { return this.req('PUT', '/me/player/shuffle', { query: { state: on } }); }
  repeat(mode) { return this.req('PUT', '/me/player/repeat', { query: { state: mode } }); }
  volume(pct) { return this.req('PUT', '/me/player/volume', { query: { volume_percent: pct } }); }
  transfer(deviceId, play = true) { return this.req('PUT', '/me/player', { body: { device_ids: [deviceId], play } }); }
  queue() { return this.req('GET', '/me/player/queue'); }
  addToQueue(uri) { return this.req('POST', '/me/player/queue', { query: { uri } }); }
  playlists(offset = 0) { return this.req('GET', '/me/playlists', { query: { limit: 50, offset } }); }
  playlist(id) { return this.req('GET', '/playlists/' + id, { query: { fields: 'name,images,uri,id,owner(display_name)' } }); }
  // С февраля 2026 Spotify отдаёт треки только твоих (или совместных) плейлистов.
  playlistItems(id, offset = 0) { return this.req('GET', `/playlists/${id}/items`, { query: { limit: 50, offset, additional_types: 'track,episode' } }); }
  likedTracks(offset = 0) { return this.req('GET', '/me/tracks', { query: { limit: 50, offset } }); }
  search(q) { return this.req('GET', '/search', { query: { q, type: 'track,album,playlist', limit: 10 } }); }
  isSaved(uri) { return this.req('GET', '/me/library/contains', { query: { uris: uri } }); }
  save(uri) { return this.req('PUT', '/me/library', { query: { uris: uri } }); }
  unsave(uri) { return this.req('DELETE', '/me/library', { query: { uris: uri } }); }
  topItems(type, range, offset = 0) { return this.req('GET', '/me/top/' + type, { query: { time_range: range, limit: 50, offset } }); }
  recentlyPlayed() { return this.req('GET', '/me/player/recently-played', { query: { limit: 50 } }); }
  artist(id) { return this.req('GET', '/artists/' + id); }
  // С 2026 года Spotify отдаёт максимум 10 альбомов за запрос, остальные подгружаются при прокрутке
  artistAlbums(id, offset = 0) { return this.req('GET', `/artists/${id}/albums`, { query: { include_groups: 'album,single', limit: 10, offset } }); }
  album(id) { return this.req('GET', '/albums/' + id); }
  albumTracks(id, offset = 0) { return this.req('GET', `/albums/${id}/tracks`, { query: { limit: 50, offset } }); }
  createPlaylist(name) { return this.req('POST', '/me/playlists', { body: { name, public: false, description: 'Создан в Aura' } }); }
  addToPlaylist(id, uris) { return this.req('POST', `/playlists/${id}/items`, { body: { uris } }); }
}

/* =====================================================================
   Демо-режим: те же методы, но без Spotify (чтобы посмотреть дизайн)
   ===================================================================== */
function demoCover(title, artist, [c1, c2], style) {
  const c = document.createElement('canvas');
  c.width = c.height = 480;
  const x = c.getContext('2d');
  const g = x.createLinearGradient(0, 0, 480, 480);
  g.addColorStop(0, c1);
  g.addColorStop(1, c2);
  x.fillStyle = g;
  x.fillRect(0, 0, 480, 480);
  x.save();
  switch (style % 4) {
    case 0: // кольца
      for (let i = 0; i < 6; i++) {
        x.beginPath();
        x.arc(240, 290, 200 - i * 34, 0, Math.PI * 2);
        x.strokeStyle = `rgba(255,255,255,${0.08 + i * 0.07})`;
        x.lineWidth = 12;
        x.stroke();
      }
      break;
    case 1: { // закатное солнце
      const sg = x.createLinearGradient(0, 160, 0, 400);
      sg.addColorStop(0, 'rgba(255,255,255,0.95)');
      sg.addColorStop(1, c1);
      x.fillStyle = sg;
      x.beginPath();
      x.arc(240, 300, 140, 0, Math.PI * 2);
      x.fill();
      x.fillStyle = c2;
      for (let i = 0; i < 6; i++) x.fillRect(0, 310 + i * 26, 480, 6 + i * 2);
      break;
    }
    case 2: // волны
      x.lineWidth = 8;
      for (let k = 0; k < 7; k++) {
        x.beginPath();
        for (let px = 0; px <= 480; px += 8) {
          const py = 200 + k * 34 + Math.sin(px / 46 + k) * 22;
          px ? x.lineTo(px, py) : x.moveTo(px, py);
        }
        x.strokeStyle = `rgba(255,255,255,${0.12 + k * 0.08})`;
        x.stroke();
      }
      break;
    default: // сетка точек
      for (let i = 0; i < 9; i++) {
        for (let j = 0; j < 7; j++) {
          x.beginPath();
          x.arc(48 + i * 48, 170 + j * 44, 6 + ((i + j) % 4) * 4, 0, Math.PI * 2);
          x.fillStyle = `rgba(255,255,255,${0.15 + ((i * j) % 5) * 0.12})`;
          x.fill();
        }
      }
  }
  x.restore();
  x.fillStyle = 'rgba(255,255,255,0.95)';
  x.font = '800 34px Manrope, system-ui, sans-serif';
  x.fillText(title.toUpperCase(), 34, 76, 412);
  x.fillStyle = 'rgba(255,255,255,0.7)';
  x.font = '600 22px Manrope, system-ui, sans-serif';
  x.fillText(artist, 34, 110, 412);
  return c.toDataURL('image/jpeg', 0.86);
}

class DemoApi {
  constructor() {
    this.isDemo = true;
    this.backoffUntil = 0;
    const raw = [
      ['Неоновый дождь', 'Полночный город', 'Огни после полуночи', ['#ff3d7f', '#3a0ca3'], 214000],
      ['Golden Hour Drive', 'Saltwater Kids', 'Coastline', ['#ffb347', '#d6336c'], 187000],
      ['Северный ветер', 'Тайга', 'Хвоя', ['#43cea2', '#185a9d'], 241000],
      ['Velvet Static', 'Mira Lane', 'Afterglow', ['#9b5de5', '#2b0a5c'], 198000],
      ['Лимонад', 'Солнечные зайцы', 'Лето во дворе', ['#f9f871', '#2f9e44'], 176000],
      ['Deep Blue Hours', 'Harbor Lights', 'Low Tide', ['#00c6ff', '#0b1f4b'], 263000],
      ['Кофе на балконе', 'Трио «Лёгкий джаз»', 'Утро', ['#d4a373', '#3e2723'], 229000],
      ['Petal Theory', 'Juniper Fields', 'Bloom', ['#ff9a9e', '#7b5cd6'], 205000],
    ];
    this.tracks = raw.map(([name, artist, album, colors, dur], i) => ({
      type: 'track',
      id: 'demo' + i,
      uri: 'spotify:track:demo' + i,
      name,
      duration_ms: dur,
      artists: [{ name: artist, id: 'art' + i, uri: 'spotify:artist:art' + i }],
      album: {
        id: 'alb' + i, uri: 'spotify:album:alb' + i, name: album, album_type: i % 3 ? 'album' : 'single', release_date: String(2019 + i),
        artists: [{ name: artist, id: 'art' + i }],
        images: [{ url: demoCover(album, artist, colors, i), width: 480, height: 480 }],
      },
    }));
    const t = this.tracks;
    const pl = (id, name, list, owner = 'Spotify') => ({
      id, uri: 'spotify:playlist:' + id, name, owner: { display_name: owner, id: owner === 'Ты' ? 'demo' : 'spotify' },
      images: t[list[0]].album.images, items: { total: list.length }, _tracks: list.map((i) => t[i]),
    });
    this.lists = [
      pl('road', 'Для дороги', [1, 5, 0, 7, 3]),
      pl('home', 'Вечер дома', [6, 2, 3, 5]),
      pl('morning', 'Утренний заряд', [4, 1, 7, 0, 2]),
      pl('mine', 'Мой микс', [0, 1, 2, 3, 4, 5, 6, 7], 'Ты'),
    ];
    this.devs = [
      { id: 'phone', name: 'Galaxy (этот телефон)', type: 'Smartphone', is_active: true, volume_percent: 70, supports_volume: true },
      { id: 'pc', name: 'Компьютер', type: 'Computer', is_active: false, volume_percent: 50, supports_volume: true },
      { id: 'tv', name: 'Телевизор в зале', type: 'TV', is_active: false, volume_percent: 30, supports_volume: true },
    ];
    this.saved = new Set(['spotify:track:demo1', 'spotify:track:demo5']);
    this.list = this.lists[0]._tracks;
    this.ctx = { type: 'playlist', uri: this.lists[0].uri };
    this.idx = 0;
    this.playing = true;
    this.posBase = 48000;
    this.t0 = Date.now();
    this.shuf = false;
    this.rep = 'off';
  }

  async _lag() { await sleep(90 + Math.random() * 120); }
  _cur() { return this.list[this.idx]; }
  _pos() {
    let p = this.posBase + (this.playing ? Date.now() - this.t0 : 0);
    while (p >= this._cur().duration_ms) {
      p -= this._cur().duration_ms;
      if (this.rep !== 'track') this.idx = (this.idx + 1) % this.list.length;
      this.posBase = p;
      this.t0 = Date.now();
    }
    return p;
  }
  _jump(i) { this.idx = (i + this.list.length) % this.list.length; this.posBase = 0; this.t0 = Date.now(); }

  async me() { return { id: 'demo', display_name: 'Демо-пользователь' }; }
  async playerState() {
    await this._lag();
    const p = this._pos();
    return {
      device: this.devs.find((d) => d.is_active),
      is_playing: this.playing,
      progress_ms: p,
      item: this._cur(),
      shuffle_state: this.shuf,
      repeat_state: this.rep,
      context: this.ctx,
      actions: { disallows: {} },
    };
  }
  async devices() { await this._lag(); return this.devs; }
  async play(opts = {}) {
    await this._lag();
    if (opts.context_uri) {
      const [, type, id] = opts.context_uri.split(':');
      const found = this.lists.find((l) => l.uri === opts.context_uri);
      if (type === 'album') this.list = this.tracks.filter((x) => x.album.id === id);
      else if (type === 'artist') this.list = this.tracks.filter((x) => x.artists[0].id === id);
      else this.list = found ? found._tracks : this.tracks;
      if (!this.list.length) this.list = this.tracks;
      this.ctx = { type, uri: opts.context_uri };
      this._jump(opts.offset?.position || 0);
    } else if (opts.uris) {
      this.list = opts.uris.map((u) => this.tracks.find((t) => t.uri === u)).filter(Boolean);
      this.ctx = null;
      this._jump(opts.offset?.position || 0);
    } else {
      this.posBase = this._pos();
      this.t0 = Date.now();
    }
    this.playing = true;
  }
  async pause() { await this._lag(); this.posBase = this._pos(); this.playing = false; }
  async next() { await this._lag(); this._jump(this.idx + 1); }
  async prev() { await this._lag(); this._pos() > 3000 ? this._jump(this.idx) : this._jump(this.idx - 1); }
  async seek(ms) { await this._lag(); this.posBase = ms; this.t0 = Date.now(); }
  async shuffle(on) { await this._lag(); this.shuf = on; }
  async repeat(mode) { await this._lag(); this.rep = mode; }
  async volume(v) { await this._lag(); const d = this.devs.find((x) => x.is_active); if (d) d.volume_percent = v; }
  async transfer(id) { await this._lag(); this.devs.forEach((d) => { d.is_active = d.id === id; }); this.playing = true; this.t0 = Date.now(); }
  async queue() {
    await this._lag();
    const q = [];
    for (let i = 1; i <= Math.min(10, this.list.length * 2); i++) q.push(this.list[(this.idx + i) % this.list.length]);
    return { currently_playing: this._cur(), queue: q };
  }
  async addToQueue() { await this._lag(); }
  async playlists() { await this._lag(); return { items: this.lists, next: null }; }
  async playlist(id) { return this.lists.find((l) => l.id === id); }
  async playlistItems(id) {
    await this._lag();
    const l = this.lists.find((x) => x.id === id);
    return { items: l._tracks.map((t) => ({ item: t })), next: null };
  }
  async likedTracks() {
    await this._lag();
    return { items: this.tracks.filter((t) => this.saved.has(t.uri)).map((t) => ({ track: t })), next: null };
  }
  async search(q) {
    await this._lag();
    const s = q.toLowerCase();
    const match = (str) => str.toLowerCase().includes(s);
    return {
      tracks: { items: this.tracks.filter((t) => match(t.name) || match(itemArtists(t))) },
      albums: { items: [] },
      playlists: { items: this.lists.filter((l) => match(l.name)) },
    };
  }
  async isSaved(uri) { return [this.saved.has(uri)]; }
  async save(uri) { await this._lag(); this.saved.add(uri); }
  async unsave(uri) { await this._lag(); this.saved.delete(uri); }

  _artists() {
    return this.tracks.map((x) => ({ ...x.artists[0], images: x.album.images, genres: ['инди', 'поп'], type: 'artist' }));
  }
  async topItems(type, range) {
    await this._lag();
    const shift = { short_term: 0, medium_term: 3, long_term: 5 }[range] || 0;
    const rot = (arr) => arr.slice(shift).concat(arr.slice(0, shift));
    return { items: type === 'artists' ? rot(this._artists()) : rot(this.tracks), next: null };
  }
  async recentlyPlayed() {
    await this._lag();
    const now = Date.now();
    return { items: this.tracks.map((x, i) => ({ track: x, played_at: new Date(now - (i * 37 + 3) * 60000).toISOString() })) };
  }
  async artist(id) { await this._lag(); return this._artists().find((a) => a.id === id); }
  async artistAlbums(id) { await this._lag(); return { items: this.tracks.filter((x) => x.artists[0].id === id).map((x) => x.album), next: null }; }
  async album(id) {
    await this._lag();
    const tr = this.tracks.filter((x) => x.album.id === id);
    return { ...tr[0].album, tracks: { items: tr, next: null } };
  }
  async albumTracks(id) { return (await this.album(id)).tracks; }
  async createPlaylist(name) {
    await this._lag();
    const p = { id: 'new' + Date.now(), name, owner: { display_name: 'Ты', id: 'demo' }, images: [], items: { total: 0 }, _tracks: [] };
    p.uri = 'spotify:playlist:' + p.id;
    this.lists.push(p);
    return p;
  }
  async addToPlaylist(id, uris) {
    await this._lag();
    const p = this.lists.find((l) => l.id === id);
    uris.forEach((u) => { const x = this.tracks.find((tr) => tr.uri === u); if (x) p._tracks.push(x); });
    p.items.total = p._tracks.length;
    if (!p.images.length && p._tracks[0]) p.images = p._tracks[0].album.images;
  }
  // В демо тексты придуманы, чтобы было видно, как работает синхронизация
  async demoLyrics(item) {
    await this._lag();
    const pool = [
      'Город спит, а мы ещё нет', 'Фары режут темноту', 'Я ловлю твой тихий свет', 'И снова слышу ту мечту',
      'Ветер шепчет нам вперёд', 'Каждый шаг звучит как бит', 'Пусть весь мир нас подождёт', 'Это небо не молчит',
      '', 'Лови волну, лови момент', 'Мы громче, чем вчера', 'Ещё один эксперимент', 'И музыка до утра',
    ];
    const n = Math.floor(item.duration_ms / 4200);
    const lines = [];
    for (let i = 0; i < n; i++) lines.push(`[${String(Math.floor((i * 4.2 + 2) / 60)).padStart(2, '0')}:${((i * 4.2 + 2) % 60).toFixed(2).padStart(5, '0')}] ${pool[i % pool.length]}`);
    return { syncedLyrics: lines.join('\n') };
  }
}

/* =====================================================================
   Цвета из обложки
   ===================================================================== */
function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h;
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return [h / 6, s, l];
}
function hslToHex(h, s, l) {
  const f = (n) => {
    const k = (n + h * 12) % 12;
    const a = s * Math.min(l, 1 - l);
    const c = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(c * 255).toString(16).padStart(2, '0');
  };
  return '#' + f(0) + f(8) + f(4);
}

function extractPalette(img) {
  const n = 40;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = n;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(img, 0, 0, n, n);
  let data;
  try { data = ctx.getImageData(0, 0, n, n).data; } catch { return null; }

  const buckets = new Map();
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 200) continue;
    const r = data[i], g = data[i + 1], b = data[i + 2];
    const key = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
    let bk = buckets.get(key);
    if (!bk) buckets.set(key, (bk = { n: 0, r: 0, g: 0, b: 0 }));
    bk.n++; bk.r += r; bk.g += g; bk.b += b;
  }
  const cols = [...buckets.values()].map((bk) => {
    const [h, s, l] = rgbToHsl(bk.r / bk.n, bk.g / bk.n, bk.b / bk.n);
    return { h, s, l, n: bk.n };
  });
  if (!cols.length) return null;
  const total = cols.reduce((a, c) => a + c.n, 0);
  const score = (c) => Math.sqrt(c.n / total) * Math.pow(c.s, 1.2) * Math.max(0, 1 - Math.abs(c.l - 0.55) * 1.5);
  const hueDist = (a, b) => { const d = Math.abs(a - b); return Math.min(d, 1 - d); };
  const dominant = cols.reduce((a, c) => (c.n > a.n ? c : a));
  const vibrant = cols.reduce((a, c) => (score(c) > score(a) ? c : a));
  const second = cols
    .filter((c) => hueDist(c.h, vibrant.h) > 0.08 && c.n / total > 0.02 && c.s > 0.15)
    .sort((a, b) => score(b) - score(a))[0] || dominant;
  return { dominant, vibrant, second };
}

function buildTheme(p) {
  if (!p) return DEFAULT_THEME;
  const base = p.vibrant.s > 0.14 ? p.vibrant : p.dominant;
  if (base.s < 0.14) {
    // Чёрно-белая обложка — спокойная графитовая тема
    const h = base.h;
    return { bg1: hslToHex(h, 0.06, 0.26), bg2: hslToHex(h, 0.05, 0.07), accent: '#f2f2f2', onAccent: '#111111', meta: hslToHex(h, 0.05, 0.1) };
  }
  const s2 = p.second;
  const bg1 = hslToHex(base.h, clamp(base.s, 0.35, 0.8), 0.3);
  const bg2 = hslToHex(s2.s > 0.15 ? s2.h : base.h, clamp(s2.s, 0.3, 0.65), 0.1);
  const accent = hslToHex(base.h, clamp(base.s, 0.55, 0.95), clamp(base.l, 0.64, 0.74));
  const onAccent = hslToHex(base.h, 0.6, 0.12);
  return { bg1, bg2, accent, onAccent, meta: hslToHex(base.h, clamp(base.s, 0.35, 0.8), 0.2) };
}

function applyTheme(t) {
  const root = document.documentElement.style;
  root.setProperty('--bg1', t.bg1);
  root.setProperty('--bg2', t.bg2);
  root.setProperty('--accent', t.accent);
  root.setProperty('--on-accent', t.onAccent);
  $('#themeColor').setAttribute('content', t.meta);
  NATIVE?.setBarColor(t.meta, t.bg2);
  widgetAccent = t.accent;
  pushWidget();
}

// Состояние для виджета на главном экране (только в приложении)
let widgetAccent = DEFAULT_THEME.accent, widgetLast = '';
function pushWidget() {
  if (!NATIVE || !api || api.isDemo) return;
  const s = ui.pb, item = s?.item;
  const w = item
    ? { title: item.name, artist: itemArtists(item), cover: pickImage(itemImages(item), 300), playing: !!s.is_playing, accent: widgetAccent,
        end: s.is_playing ? Date.now() + item.duration_ms - currentPos() : 0 }
    : { title: 'Ничего не играет', artist: 'Нажми ▶, чтобы продолжить', cover: '', playing: false, accent: widgetAccent };
  // end меняется каждую секунду — сравниваем без него
  const key = JSON.stringify({ ...w, end: 0 });
  if (key === widgetLast) return;
  widgetLast = key;
  NATIVE.nowPlaying(JSON.stringify(w));
}

let bgFront = null;
// Размываем обложку один раз на маленьком холсте: CSS-blur(70px) на слое размером в два экрана
// телефон пересчитывал на каждом кадре анимации фона — отсюда подтормаживания.
function blurredCover(img) {
  try {
    const c = document.createElement('canvas');
    c.width = c.height = 96;
    const x = c.getContext('2d');
    x.filter = 'blur(10px) saturate(1.5)';
    x.drawImage(img, -16, -16, 128, 128);
    return c.toDataURL('image/jpeg', 0.85);
  } catch { return null; }
}
function setBackground(url, img) {
  const a = $('#bgA'), b = $('#bgB');
  const next = bgFront === a ? b : a;
  const pre = img && blurredCover(img);
  next.classList.toggle('raw', !pre);
  next.style.backgroundImage = `url("${pre || url}")`;
  requestAnimationFrame(() => {
    next.classList.add('on');
    if (bgFront) bgFront.classList.remove('on');
    bgFront = next;
  });
}
function clearBackground() {
  $('#bgA').classList.remove('on');
  $('#bgB').classList.remove('on');
  bgFront = null;
}

/* =====================================================================
   Плеер: состояние, опрос и отрисовка
   ===================================================================== */
let api = null;
const ui = {
  pb: null,          // последнее состояние плеера
  at: 0,             // когда оно получено (для плавного прогресса)
  trackUri: null,
  coverUrl: null,
  liked: false,
  seeking: false,
  seq: 0,            // растёт при каждой команде — устаревшие ответы опроса отбрасываем
  timer: null,
  raf: 0,
  lastSec: -1,
  lastV: -1,
  lastErrorAt: 0,
  contextNames: new Map(),
};

function currentPos() {
  const pb = ui.pb;
  if (!pb || !pb.item) return 0;
  const p = pb.progress_ms + (pb.is_playing ? Date.now() - ui.at : 0);
  return clamp(p, 0, pb.item.duration_ms);
}
function snapPos() {
  if (!ui.pb) return;
  ui.pb.progress_ms = currentPos();
  ui.at = Date.now();
}

async function poll() {
  clearTimeout(ui.timer);
  if (!api || document.hidden) return;
  const seq = ui.seq;
  try {
    const s = await api.playerState();
    if (api && seq === ui.seq) render(s, true);
  } catch (e) {
    handleError(e, true);
  } finally {
    if (api) schedulePoll();
  }
}

function schedulePoll(ms) {
  clearTimeout(ui.timer);
  if (!api) return;
  let delay = ms ?? CONFIG.pollMs;
  const pb = ui.pb;
  if (ms == null && pb?.is_playing && pb.item) {
    const left = pb.item.duration_ms - currentPos();
    if (left < delay) delay = Math.max(400, left + 350);
  }
  if (api.backoffUntil > Date.now()) delay = Math.max(delay, api.backoffUntil - Date.now());
  ui.timer = setTimeout(poll, delay);
}

function render(s, fresh) {
  if (fresh) { ui.pb = s; ui.at = Date.now(); }
  // Устройство есть, а трека нет дольше 5 секунд — скорее всего, Spotify уснул
  const stalled = !!s && !s.item && s.currently_playing_type !== 'ad';
  if (!stalled) ui.stalledSince = 0;
  else if (!ui.stalledSince) ui.stalledSince = Date.now();
  setOverlay(!s ? 'none' : stalled && Date.now() - ui.stalledSince > 5000 ? 'asleep' : null, s);
  if (!s) {
    $('#art').classList.add('paused');
    return;
  }
  const item = s.item;
  if (item && item.uri !== ui.trackUri) onTrackChange(item);
  if (!item) {
    $('#trackName').textContent = s.currently_playing_type === 'ad' ? 'Реклама' : 'Загрузка…';
    $('#artistName').textContent = s.device?.name || '';
  }

  setIcon($('#btnPlay'), s.is_playing ? 'pause' : 'play');
  $('#btnPlay').setAttribute('aria-label', s.is_playing ? 'Пауза' : 'Играть');
  $('#art').classList.toggle('paused', !s.is_playing);

  $('#btnShuffle').classList.toggle('on', !!s.shuffle_state);
  const rep = s.repeat_state || 'off';
  setIcon($('#btnRepeat'), rep === 'track' ? 'repeat1' : 'repeat');
  $('#btnRepeat').classList.toggle('on', rep !== 'off');

  const dis = s.actions?.disallows || {};
  $('#btnPrev').disabled = !!dis.skipping_prev;
  $('#btnNext').disabled = !!dis.skipping_next;
  $('#btnShuffle').disabled = !!dis.toggling_shuffle;
  $('#seek').disabled = !!dis.seeking || !item;

  updateDeviceChip();
  updateContext(s);
  pushWidget();
  updateProgress(true);
}

function setOverlay(kind, s) {
  $('#noDevice').hidden = !kind;
  if (!kind) return;
  const asleep = kind === 'asleep';
  $('#ndTitle').textContent = asleep ? 'Spotify не отвечает' : 'Ничего не играет';
  $('#ndText').textContent = asleep
    ? `Похоже, телефон усыпил Spotify в фоне («${s?.device?.name || 'устройство'}»). Открой Spotify — музыка пойдёт, и Aura сразу подхватит. Чтобы такого не было: Настройки → Приложения → Spotify → Батарея → «Без ограничений».`
    : 'Открой Spotify на телефоне или компьютере и включи любой трек — Aura сразу подхватит.';
  $('#btnRetry').hidden = !asleep;
  $('#btnOpenLibrary').hidden = asleep;
}

async function retryWake() {
  const id = ui.pb?.device?.id;
  ui.stalledSince = Date.now();
  setOverlay(null);
  ui.seq++;
  try {
    if (id) await api.transfer(id, true);
    else await api.play();
  } catch (e) {
    handleError(e, false);
  } finally {
    schedulePoll(1200);
  }
}

function onTrackChange(item) {
  ui.trackUri = item.uri;
  const title = item.name || '—';
  const artist = itemArtists(item);
  $('#trackName').textContent = title;
  $('#artistName').textContent = artist;
  document.title = `${title} — ${artist}`;
  updateMarquee();
  setLiked(false, false);
  const uri = item.uri;
  if (uri && !uri.startsWith('spotify:local:')) {
    api.isSaved(uri).then((r) => { if (ui.trackUri === uri) setLiked(!!(r && r[0]), false); }).catch(() => {});
  }
  loadCover(pickImage(itemImages(item), 600));
  lyricsTrackChanged(item);
}

function loadCover(url) {
  if (url === ui.coverUrl) return;
  ui.coverUrl = url;
  const cover = $('#cover');
  if (!url) {
    cover.removeAttribute('src');
    clearBackground();
    applyTheme(DEFAULT_THEME);
    return;
  }
  cover.classList.add('loading');
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.onload = () => {
    if (ui.coverUrl !== url) return;
    cover.crossOrigin = 'anonymous';
    cover.src = url;
    cover.classList.remove('loading');
    setBackground(url, img);
    applyTheme(buildTheme(extractPalette(img)));
  };
  img.onerror = () => {
    // Картинка без CORS — показываем, но цвета берём стандартные
    if (ui.coverUrl !== url) return;
    cover.removeAttribute('crossorigin');
    cover.src = url;
    cover.classList.remove('loading');
    setBackground(url);
    applyTheme(DEFAULT_THEME);
  };
  img.src = url;
}

function updateMarquee() {
  const wrap = $('#trackNameWrap'), h1 = $('#trackName');
  wrap.classList.remove('scrolling');
  requestAnimationFrame(() => {
    const over = h1.scrollWidth - wrap.clientWidth;
    if (over > 4) {
      wrap.style.setProperty('--marquee-dist', `-${over + 12}px`);
      wrap.style.setProperty('--marquee-time', `${(over + 12) / 28 + 3}s`);
      wrap.classList.add('scrolling');
    }
  });
}

function updateContext(s) {
  const ctx = s.context, item = s.item;
  let type = 'Сейчас играет', name = item?.album?.name || item?.show?.name || '';
  if (ctx?.type === 'playlist') {
    type = 'Плейлист';
    const id = ctx.uri.split(':').pop();
    if (!ui.contextNames.has(ctx.uri)) {
      ui.contextNames.set(ctx.uri, '');
      api.playlist(id)
        .then((p) => {
          ui.contextNames.set(ctx.uri, p?.name || 'Плейлист');
          if (ui.pb?.context?.uri === ctx.uri) $('#contextName').textContent = p?.name || 'Плейлист';
        })
        .catch(() => ui.contextNames.set(ctx.uri, 'Плейлист'));
    }
    name = ui.contextNames.get(ctx.uri) || '…';
  } else if (ctx?.type === 'album') {
    type = 'Альбом';
  } else if (ctx?.type === 'artist') {
    type = 'Артист';
    name = item?.artists?.[0]?.name || name;
  } else if (ctx?.type === 'collection') {
    type = 'Медиатека';
    name = 'Любимые треки';
  } else if (ctx?.type === 'show') {
    type = 'Подкаст';
  }
  $('#contextType').textContent = type;
  $('#contextName').textContent = name || '—';
}

function setLiked(v, animate) {
  ui.liked = v;
  const b = $('#btnLike');
  setIcon(b, v ? 'heartFill' : 'heart');
  b.classList.toggle('on', v);
  b.setAttribute('aria-label', v ? 'Убрать из любимых' : 'Нравится');
  if (animate) {
    b.classList.remove('pop');
    void b.offsetWidth;
    b.classList.add('pop');
  }
}

function updateProgress(force) {
  const pb = ui.pb;
  const dur = pb?.item?.duration_ms || 0;
  const seek = $('#seek');
  if (!ui.seeking) {
    const pos = currentPos();
    const v = dur ? Math.round((pos / dur) * 1000) : 0;
    if (force || v !== ui.lastV) {
      ui.lastV = v;
      seek.value = v;
      seek.style.setProperty('--p', v / 10 + '%');
    }
    const sec = Math.floor(pos / 1000);
    if (force || sec !== ui.lastSec) {
      ui.lastSec = sec;
      $('#tCur').textContent = fmtTime(pos);
      $('#tDur').textContent = fmtTime(dur);
    }
  }
}

function progressLoop() {
  updateProgress(false);
  syncLyrics();
  updateClock();
  ui.raf = requestAnimationFrame(progressLoop);
}

/* ---------- Команды ---------- */
/* ---------- Наушники (только в Android-приложении) ---------- */
const hp = { cur: null, lowWarned: false };

// «Buds3 FE пользователя Бекар…» → «Buds3 FE», «WH-1000XM6 (Bekarys)» → «WH-1000XM6»
function cleanDeviceName(n) {
  return (n || '').replace(/\s+пользователя(\s.*)?$/i, '').replace(/^.+?['’]s\s+/, '').replace(/\s*\([^)]*\)\s*$/, '').trim() || 'Наушники';
}
function headphonesLabel(h) {
  return cleanDeviceName(h.name) + (h.battery != null ? ` · ${h.battery}%` : '');
}

function updateDeviceChip() {
  const d = ui.pb?.device;
  // Наушники показываем, только когда музыка играет на этом телефоне
  const h = hp.cur && (!d || d.type === 'Smartphone') ? hp.cur : null;
  setIcon($('#deviceIcon'), h ? (/buds|pods|wf-|ear/i.test(h.name) ? 'earbuds' : 'headphones') : 'speaker');
  $('#deviceName').textContent = h ? headphonesLabel(h) : d?.name || 'Устройство';
  $('#btnDevices').classList.toggle('low', !!h && h.battery != null && h.battery <= 15);
}

// Приложение вызывает это при подключении/отключении наушников и смене заряда
window.auraHeadphones = (h) => {
  const prev = hp.cur;
  hp.cur = h?.connected ? h : null;
  const cur = hp.cur;
  if (cur && prev?.address !== cur.address) {
    hp.lowWarned = false;
    if (api) toast('Подключены ' + headphonesLabel(cur));
  }
  if (cur?.battery != null) {
    if (cur.battery <= 15 && !hp.lowWarned) {
      hp.lowWarned = true;
      toast(`У ${cleanDeviceName(cur.name)} осталось ${cur.battery}% — пора зарядить`);
    } else if (cur.battery > 20) hp.lowWarned = false;
  }
  updateDeviceChip();
};

/* ---------- Шумоподавление Sony (только в приложении) ---------- */
const SONY_MODES = [['nc', 'Шумодав'], ['ambient', 'Звуковой фон'], ['off', 'Выкл']];
const sony = { state: null, busy: false, render: null };

window.auraSony = (r) => {
  sony.busy = false;
  if (r.ok) {
    sony.state = r;
    store.set('sony', r);
  } else {
    sony.state = sony.before;
    toast(r.error || 'Наушники не ответили');
  }
  sony.render?.();
};

// Кнопку подсвечиваем сразу, не дожидаясь наушников; при ошибке вернём как было
function sonyAsk(mode = '', level = 0, voice = '') {
  sony.busy = true;
  sony.before = sony.state;
  if (mode) sony.state = { ...sony.state, mode, ...(level ? { level } : {}), ...(voice ? { voice: voice === '1' } : {}) };
  sony.render?.();
  NATIVE.sony(mode, level, voice);
}

function sonyCard() {
  const card = el('div', { class: 'settings-card sony-card' });
  sony.render = () => {
    const st = sony.state;
    const modes = el('div', { class: 'tabs' });
    for (const [v, label] of SONY_MODES) {
      modes.append(el('button', {
        class: 'tab' + (st?.mode === v ? ' active' : ''), type: 'button', disabled: sony.busy,
        onclick: () => sonyAsk(v, st?.level || 0, ''),
      }, label));
    }
    const parts = [
      el('h3', {}, cleanDeviceName(hp.cur?.name) + (sony.busy ? ' · …' : '')),
      el('p', {}, sony.busy ? 'Связываюсь с наушниками…' : 'Режим шума'),
      modes,
    ];
    if (st?.mode === 'ambient') {
      const range = el('input', { type: 'range', class: 'range', min: 1, max: 20, value: st.level || 10 });
      range.addEventListener('change', () => sonyAsk('ambient', Number(range.value), ''));
      parts.push(el('p', { style: 'margin-top:12px' }, 'Сколько слышно вокруг'), range,
        switchRow('Фокус на голосе', !!st.voice, (on) => sonyAsk('ambient', st.level || 10, on ? '1' : '0')));
    }
    card.replaceChildren(...parts);
  };
  // Сами не подключаемся: каждое подключение отбирает наушники у Sony Sound Connect.
  // Показываем последний известный режим, а к наушникам идём только по нажатию.
  if (!sony.state) sony.state = store.get('sony');
  sony.render();
  return card;
}

/* ---------- Будильник (только в приложении) ---------- */
const DAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

function untilText(ms) {
  const min = Math.max(1, Math.round((ms - Date.now()) / 60000));
  const h = Math.floor(min / 60), m = min % 60;
  return 'Прозвенит через ' + (h ? `${h} ${plural(h, 'час', 'часа', 'часов')} ` : '') + `${m} мин`;
}

function alarmCard() {
  const a = JSON.parse(NATIVE.getAlarm());
  const info = el('p', { style: 'margin-top:10px' });
  const save = () => {
    const next = Number(NATIVE.setAlarm(JSON.stringify(a)));
    info.textContent = a.enabled ? (next ? untilText(next) : 'Выбери хотя бы один день') : 'Выключен';
  };
  const pad = (n) => String(n).padStart(2, '0');

  const time = el('input', { type: 'time', class: 'time-input', value: `${pad(a.hour)}:${pad(a.minute)}` });
  time.addEventListener('change', () => {
    const [h, m] = time.value.split(':').map(Number);
    if (Number.isNaN(h)) return;
    a.hour = h; a.minute = m;
    save();
  });

  const days = el('div', { class: 'tabs' });
  DAYS.forEach((d, i) => {
    const n = i + 1;
    const b = el('button', { class: 'tab' + (a.days.includes(n) ? ' active' : ''), type: 'button' }, d);
    b.addEventListener('click', () => {
      a.days = a.days.includes(n) ? a.days.filter((x) => x !== n) : [...a.days, n].sort();
      b.classList.toggle('active', a.days.includes(n));
      save();
    });
    days.append(b);
  });

  const pick = el('select', { class: 'select' }, el('option', { value: '' }, 'Что играло последним'));
  const addOpt = (uri, name) => pick.append(el('option', { value: uri, selected: uri === a.uri }, name));
  if (a.uri) addOpt(a.uri, a.name || 'Выбранный плейлист');
  (async () => {
    try {
      const me = await getMe();
      const liked = `spotify:user:${me.id}:collection`;
      if (liked !== a.uri) addOpt(liked, 'Любимые треки');
      const page = await api.playlists(0);
      for (const p of page.items || []) if (p && p.uri !== a.uri) addOpt(p.uri, p.name);
    } catch {}
  })();
  pick.addEventListener('change', () => {
    a.uri = pick.value;
    a.name = pick.value ? pick.selectedOptions[0].textContent : '';
    save();
  });

  const vol = el('input', { type: 'range', class: 'range', min: 10, max: 100, step: 5, value: a.volume });
  vol.addEventListener('change', () => { a.volume = Number(vol.value); save(); });

  const card = el('div', { class: 'settings-card' },
    el('h3', {}, 'Будильник с музыкой'),
    el('p', {}, 'Включит музыку в Spotify на этом телефоне и плавно прибавит громкость за минуту. Если Spotify не ответит — зазвенит обычный будильник.'),
    switchRow('Включён', a.enabled, (on) => { a.enabled = on; save(); }),
    time, days,
    el('p', { style: 'margin-top:12px' }, 'Что играть'), pick,
    switchRow('Перемешать', a.shuffle, (on) => { a.shuffle = on; save(); }),
    el('p', { style: 'margin-top:12px' }, 'Громкость'), vol,
    info);
  info.textContent = a.enabled ? 'Включён' : 'Выключен';
  if (a.enabled) save();
  return card;
}

async function command(fn, optimistic) {
  ui.seq++;
  if (optimistic && ui.pb) {
    snapPos();
    optimistic(ui.pb);
    render(ui.pb, false);
  }
  try {
    await fn();
  } catch (e) {
    handleError(e, false);
  } finally {
    schedulePoll(450);
  }
}

// Android мог усыпить Spotify в фоне: команду он принял, но музыка не пошла.
// Через 2 секунды проверяем и один раз повторяем, указав устройство явно — это часто его будит.
async function ensureStarted(opts, deviceId) {
  const seq = ui.seq;
  await sleep(2000);
  if (!api || seq !== ui.seq) return;
  const s = await api.playerState().catch(() => null);
  if (!api || seq !== ui.seq || (s?.item && s.is_playing)) return;
  const id = deviceId || s?.device?.id;
  try { await api.play(opts, id); } catch {}
  schedulePoll(800);
}

// Включить что-то: если активного устройства нет — выбираем телефон или первое доступное.
async function playOn(opts = {}) {
  ui.seq++;
  try {
    let deviceId = null;
    try {
      await api.play(opts);
    } catch (e) {
      if (e.status !== 404) throw e;
      const devs = await api.devices();
      const target = devs.find((d) => d.is_active) || devs.find((d) => d.type === 'Smartphone') || devs[0];
      if (!target) {
        openDevices('Spotify сейчас нигде не открыт. Запусти приложение Spotify на телефоне и попробуй снова.');
        return false;
      }
      deviceId = target.id;
      await api.play(opts, deviceId);
    }
    ensureStarted(opts, deviceId);
    return true;
  } catch (e) {
    handleError(e, false);
    return false;
  } finally {
    schedulePoll(600);
  }
}

function handleError(e, fromPoll) {
  console.warn(e);
  if (!api) return;
  if (!api.isDemo && (e.status === 401 || e.code === 'invalid_grant')) {
    Auth.logout();
    showLogin('Сессия закончилась, войди ещё раз');
    return;
  }
  if (e.status === 404) {
    if (!fromPoll) openDevices('Выбери, где играть музыку');
    return;
  }
  if (e.status === 403) {
    if (fromPoll) return;
    if (e.reason === 'PREMIUM_REQUIRED') toast('Для управления нужен Spotify Premium');
    else if (/not registered|developer dashboard/i.test(e.message)) toast('Этот аккаунт не добавлен в Users Management приложения в Spotify Dashboard');
    else if (/scope/i.test(e.message)) toast('Нужно войти заново, чтобы разрешить это действие (Настройки → Выйти)');
    else toast('Сейчас это действие недоступно');
    return;
  }
  if (e.status === 429) {
    if (!fromPoll) toast('Слишком много запросов, подожди пару секунд');
    return;
  }
  if (fromPoll) {
    if (Date.now() - ui.lastErrorAt > 20000) toast('Нет связи со Spotify');
    ui.lastErrorAt = Date.now();
    return;
  }
  toast(e.message || 'Что-то пошло не так');
}

function togglePlay() {
  const pb = ui.pb;
  if (!pb) { playOn({}); return; }
  const playing = pb.is_playing;
  command(() => (playing ? api.pause() : api.play()), (s) => { s.is_playing = !playing; });
}
function skip(dir) {
  const art = $('#art');
  art.classList.remove('swipe-l', 'swipe-r');
  void art.offsetWidth;
  art.classList.add(dir > 0 ? 'swipe-l' : 'swipe-r');
  command(() => (dir > 0 ? api.next() : api.prev()));
}
function toggleShuffle() {
  const on = !ui.pb?.shuffle_state;
  command(() => api.shuffle(on), (s) => { s.shuffle_state = on; });
  toast(on ? 'Перемешивание включено' : 'Перемешивание выключено');
}
function cycleRepeat() {
  const order = ['off', 'context', 'track'];
  const next = order[(order.indexOf(ui.pb?.repeat_state || 'off') + 1) % 3];
  command(() => api.repeat(next), (s) => { s.repeat_state = next; });
  toast({ off: 'Повтор выключен', context: 'Повтор всего', track: 'Повтор трека' }[next]);
}
async function toggleLike(forceOn) {
  const uri = ui.trackUri;
  if (!uri) return;
  const want = forceOn ? true : !ui.liked;
  if (want === ui.liked) { setLiked(want, true); return; }
  setLiked(want, true);
  try {
    await (want ? api.save(uri) : api.unsave(uri));
    toast(want ? 'Добавлено в любимые' : 'Убрано из любимых');
  } catch (e) {
    if (ui.trackUri === uri) setLiked(!want, false);
    handleError(e, false);
  }
}

/* =====================================================================
   Нижняя шторка (медиатека, поиск, очередь, устройства, настройки)
   ===================================================================== */
const sheet = { stack: [], open: false, hideTimer: 0 };

function openSheet(view) {
  sheet.stack = [view];
  renderView();
  if (!sheet.open) {
    sheet.open = true;
    clearTimeout(sheet.hideTimer);
    const s = $('#sheet'), b = $('#sheetBackdrop');
    s.hidden = false;
    b.hidden = false;
    s.style.transform = '';
    void s.offsetHeight;
    s.classList.add('open');
    b.classList.add('open');
    history.pushState({ sheet: true }, '');
  }
}
function pushView(view) { sheet.stack.push(view); renderView(); }
function popView() { if (sheet.stack.length > 1) { sheet.stack.pop(); renderView(); } }
function renderView() {
  const v = sheet.stack[sheet.stack.length - 1];
  $('#sheetTitle').textContent = v.title;
  $('#sheetBack').hidden = sheet.stack.length < 2;
  const body = $('#sheetBody');
  body.replaceChildren();
  body.scrollTop = 0;
  v.render(body);
}
function closeSheet(fromPop) {
  if (!sheet.open) return;
  sheet.open = false;
  const s = $('#sheet'), b = $('#sheetBackdrop');
  s.classList.remove('open');
  b.classList.remove('open');
  s.style.transform = '';
  sheet.hideTimer = setTimeout(() => {
    if (!sheet.open) { s.hidden = true; b.hidden = true; $('#sheetBody').replaceChildren(); }
  }, 400);
  if (!fromPop && history.state?.sheet) history.back();
}
// Кнопка/жест «назад» на Android закрывает шторку, а не приложение
window.addEventListener('popstate', () => {
  if (!sheet.open) return;
  if (sheet.stack.length > 1) {
    popView();
    history.pushState({ sheet: true }, '');
  } else {
    closeSheet(true);
  }
});

/* ---------- Строительные блоки списков ---------- */
function eqBars() { return el('div', { class: 'eq', 'aria-hidden': 'true' }, el('i'), el('i'), el('i')); }

function row({ img, iconName, iconClass, round, title, sub, current, onClick, action }) {
  const thumb = img
    ? el('img', { class: 'thumb' + (round ? ' round' : ''), src: img, alt: '', loading: 'lazy' })
    : el('div', { class: 'thumb-icon ' + (iconClass || ''), html: icon(iconName || 'music') });
  const node = el('div', { class: 'row' + (current ? ' current' : ''), role: onClick ? 'button' : null, tabindex: onClick ? '0' : null },
    thumb,
    el('div', { class: 'row-text' }, el('div', { class: 'row-title' }, title), sub ? el('div', { class: 'row-sub' }, sub) : null),
    current ? eqBars() : null,
    action || null,
  );
  if (onClick) {
    node.addEventListener('click', onClick);
    node.addEventListener('keydown', (e) => { if (e.key === 'Enter') onClick(e); });
  }
  return node;
}

function trackRow(t, onClick, withQueue = true, sub) {
  if (!t) return null;
  const action = withQueue && t.uri && !t.uri.startsWith('spotify:local:')
    ? el('button', {
        class: 'row-action', type: 'button', 'aria-label': 'Добавить в очередь', html: icon('addQueue'),
        onclick: async (e) => {
          e.stopPropagation();
          try { await api.addToQueue(t.uri); toast('Добавлено в очередь'); } catch (err) { handleError(err, false); }
        },
      })
    : null;
  return row({
    img: pickImage(itemImages(t), 64),
    title: t.name,
    sub: sub ?? itemArtists(t),
    current: t.uri === ui.trackUri,
    onClick,
    action,
  });
}

function loader() { return el('div', { class: 'loader' }); }
function note(text) { return el('div', { class: 'empty-note' }, text); }

// Список с подгрузкой страниц при прокрутке
function pagedList(parent, fetchPage, renderItem, emptyText) {
  const list = el('div', { class: 'list' });
  const spin = loader();
  const sentinel = el('div', { style: 'height:1px' });
  parent.append(list, spin, sentinel);
  let offset = 0, done = false, loading = false;
  const io = new IntersectionObserver((entries) => { if (entries.some((x) => x.isIntersecting)) load(); }, { root: $('#sheetBody'), rootMargin: '300px' });

  async function load() {
    if (loading || done || !list.isConnected && offset > 0) return;
    loading = true;
    spin.hidden = false;
    try {
      const page = await fetchPage(offset);
      const items = page?.items || [];
      items.forEach((it, i) => { const n = renderItem(it, offset + i); if (n) list.append(n); });
      offset += items.length;
      done = !page?.next || items.length === 0;
      if (offset === 0 && emptyText) list.append(note(emptyText));
    } catch (e) {
      done = true;
      list.append(note(e.status === 403 || e.status === 404
        ? 'Spotify не даёт показать этот список.'
        : e.status ? `Spotify не отдал список (ошибка ${e.status}).` : 'Не удалось загрузить. Проверь интернет.'));
    } finally {
      loading = false;
      spin.hidden = true;
      if (done) io.disconnect();
    }
  }
  io.observe(sentinel);
  load();
  return list;
}

/* ---------- Медиатека ---------- */
function libraryView() {
  return {
    title: 'Медиатека',
    render(body) {
      const liked = row({
        iconName: 'heartFill', iconClass: 'liked', title: 'Любимые треки', sub: 'Всё, что ты лайкнул',
        onClick: () => pushView(likedView()),
      });
      const stats = row({
        iconName: 'stats', title: 'Моя статистика', sub: 'Топ треков и артистов, история',
        onClick: () => pushView(statsView()),
      });
      body.append(el('div', { class: 'list' }, liked, stats), el('div', { class: 'section-label' }, 'Плейлисты'));
      pagedList(body, (o) => api.playlists(o), (p) => {
        if (!p) return null;
        const total = (p.items || p.tracks)?.total;
        return row({
          img: pickImage(p.images, 64),
          iconName: 'music',
          title: p.name,
          sub: [p.owner?.display_name, total != null ? `${total} ${plural(total, 'трек', 'трека', 'треков')}` : null].filter(Boolean).join(' · '),
          current: ui.pb?.context?.uri === p.uri,
          onClick: () => pushView(playlistView(p)),
        });
      }, 'Плейлистов пока нет');
    },
  };
}

function playlistView(p) {
  return {
    title: p.name,
    render(body) {
      const img = pickImage(p.images, 300);
      const hero = el('div', { class: 'hero' },
        img ? el('img', { src: img, alt: '' }) : el('div', { class: 'thumb-icon', html: icon('music'), style: 'width:180px;height:180px;border-radius:18px' }),
        el('div', {}, el('div', { class: 'row-title', style: 'font-size:20px;white-space:normal' }, p.name),
          el('div', { class: 'row-sub' }, p.owner?.display_name || '')),
        el('div', { class: 'hero-actions' },
          el('button', { class: 'btn-primary', type: 'button', html: icon('play') + 'Слушать',
            onclick: async () => { await setShuffleQuiet(false); if (await playOn({ context_uri: p.uri })) closeSheet(); } }),
          el('button', { class: 'btn-ghost', type: 'button', html: icon('shuffle') + 'Вперемешку',
            onclick: async () => { await setShuffleQuiet(true); if (await playOn({ context_uri: p.uri })) closeSheet(); } }),
        ),
      );
      body.append(hero);
      pagedList(body, (o) => api.playlistItems(p.id, o), (it, i) => {
        const t = it?.item || it?.track;
        return trackRow(t, async () => { if (await playOn({ context_uri: p.uri, offset: { position: i } })) closeSheet(); });
      }, 'В плейлисте пока пусто');
    },
  };
}

async function setShuffleQuiet(on) {
  if (!ui.pb || ui.pb.shuffle_state === on) return;
  try { await api.shuffle(on); ui.pb.shuffle_state = on; } catch {}
}

function likedView() {
  const uris = [];
  return {
    title: 'Любимые треки',
    render(body) {
      body.append(el('div', { class: 'hero-actions', style: 'margin:0 auto 10px;padding:0 8px' },
        el('button', { class: 'btn-primary', type: 'button', html: icon('play') + 'Слушать',
          onclick: async () => { if (uris.length && await playOn({ uris: uris.slice(0, 100) })) closeSheet(); } }),
        el('button', { class: 'btn-ghost', type: 'button', html: icon('shuffle') + 'Вперемешку',
          onclick: async () => {
            if (!uris.length) return;
            const mixed = [...uris].sort(() => Math.random() - 0.5).slice(0, 100);
            if (await playOn({ uris: mixed })) closeSheet();
          } }),
      ));
      pagedList(body, (o) => api.likedTracks(o), (it, i) => {
        const t = it?.track;
        if (!t) return null;
        uris[i] = t.uri;
        return trackRow(t, async () => {
          if (await playOn({ uris: uris.slice(i, i + 100) })) closeSheet();
        });
      }, 'Лайкни что-нибудь — и оно появится здесь');
    },
  };
}

/* ---------- Поиск ---------- */
function searchView() {
  return {
    title: 'Поиск',
    render(body) {
      const input = el('input', { class: 'search-input', type: 'search', placeholder: 'Трек, артист, альбом, плейлист', enterkeyhint: 'search', autocomplete: 'off' });
      const results = el('div');
      body.append(el('div', { class: 'search-bar' }, input), results);
      results.append(note('Начни вводить — результаты появятся сразу'));
      let timer, reqId = 0;
      input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => run(input.value.trim()), 350);
      });
      setTimeout(() => input.focus(), 350);

      async function run(q) {
        const my = ++reqId;
        if (!q) { results.replaceChildren(note('Начни вводить — результаты появятся сразу')); return; }
        results.replaceChildren(loader());
        try {
          const r = await api.search(q);
          if (my !== reqId) return;
          results.replaceChildren();
          const tracks = (r.tracks?.items || []).filter(Boolean);
          const albums = (r.albums?.items || []).filter(Boolean);
          const lists = (r.playlists?.items || []).filter(Boolean);
          if (!tracks.length && !albums.length && !lists.length) { results.append(note('Ничего не нашлось')); return; }
          if (tracks.length) {
            results.append(el('div', { class: 'section-label' }, 'Треки'),
              el('div', { class: 'list' }, tracks.map((t) => trackRow(t, async () => { if (await playOn({ uris: [t.uri] })) closeSheet(); }))));
          }
          if (albums.length) {
            results.append(el('div', { class: 'section-label' }, 'Альбомы'),
              el('div', { class: 'list' }, albums.map((a) => row({
                img: pickImage(a.images, 64), title: a.name, sub: 'Альбом · ' + itemArtists(a),
                onClick: async () => { if (await playOn({ context_uri: a.uri })) closeSheet(); },
              }))));
          }
          if (lists.length) {
            results.append(el('div', { class: 'section-label' }, 'Плейлисты'),
              el('div', { class: 'list' }, lists.map((p) => row({
                img: pickImage(p.images, 64), title: p.name, sub: 'Плейлист · ' + (p.owner?.display_name || ''),
                onClick: () => pushView(playlistView(p)),
              }))));
          }
        } catch (e) {
          if (my === reqId) results.replaceChildren(note('Не получилось найти. Проверь интернет.'));
        }
      }
    },
  };
}

/* ---------- Очередь ---------- */
function queueView() {
  return {
    title: 'Очередь',
    render(body) {
      body.append(loader());
      api.queue().then((q) => {
        body.replaceChildren();
        if (q?.currently_playing) {
          body.append(el('div', { class: 'section-label' }, 'Сейчас играет'),
            el('div', { class: 'list' }, trackRow(q.currently_playing, null, false)));
        }
        const items = (q?.queue || []).filter(Boolean);
        body.append(el('div', { class: 'section-label' }, 'Далее'));
        body.append(items.length
          ? el('div', { class: 'list' }, items.slice(0, 30).map((t) => trackRow(t, null, false)))
          : note('Очередь пуста'));
      }).catch((e) => {
        body.replaceChildren(note(e.status === 404 ? 'Сейчас ничего не играет' : 'Не удалось загрузить очередь'));
      });
    },
  };
}

/* ---------- Устройства ---------- */
const DEVICE_TYPES = { Smartphone: ['phone', 'Телефон'], Computer: ['computer', 'Компьютер'], Tablet: ['phone', 'Планшет'], Speaker: ['speaker', 'Колонка'], TV: ['computer', 'Телевизор'], CastVideo: ['computer', 'Chromecast'], CastAudio: ['speaker', 'Колонка'], AVR: ['speaker', 'Ресивер'], Automobile: ['speaker', 'Машина'] };

function devicesView(message) {
  return {
    title: 'Где играть',
    render(body) {
      if (message) body.append(note(message));
      if (NATIVE && hp.cur?.sony) body.append(sonyCard());
      const list = el('div', { class: 'list' });
      const extra = el('div');
      body.append(list, extra);

      const load = async () => {
        list.replaceChildren(loader());
        extra.replaceChildren();
        try {
          const devs = await api.devices();
          list.replaceChildren();
          if (!devs.length) list.append(note('Устройств не найдено. Открой Spotify на телефоне или компьютере — он появится здесь.'));
          devs.forEach((d) => {
            const [ic, label] = DEVICE_TYPES[d.type] || ['speaker', d.type];
            list.append(row({
              iconName: ic, title: cleanDeviceName(d.name), sub: d.is_active ? 'Играет сейчас' : label, current: d.is_active,
              onClick: async () => {
                if (d.is_active) { closeSheet(); return; }
                ui.seq++;
                try { await api.transfer(d.id, true); toast('Играет на «' + d.name + '»'); closeSheet(); }
                catch (e) { handleError(e, false); }
                finally { schedulePoll(800); }
              },
            }));
          });
          const active = devs.find((d) => d.is_active && d.supports_volume);
          if (active) {
            const range = el('input', { type: 'range', min: '0', max: '100', value: String(active.volume_percent ?? 50), 'aria-label': 'Громкость' });
            range.addEventListener('change', () => api.volume(Number(range.value)).catch((e) => handleError(e, false)));
            extra.append(el('div', { class: 'section-label' }, 'Громкость · ' + active.name),
              el('div', { class: 'volume', html: icon('volume') }, range));
          }
        } catch (e) {
          list.replaceChildren(note('Не удалось получить список устройств'));
        }
        extra.append(el('div', { class: 'settings-card' },
          el('p', {}, 'Телефон появляется в списке, когда приложение Spotify на нём открыто. Если его нет — открой Spotify и вернись сюда.'),
          el('a', { class: 'btn-primary', href: 'spotify:', html: icon('spotify') + 'Открыть Spotify' }),
          el('button', { class: 'btn-ghost', type: 'button', html: icon('refresh') + 'Обновить список', onclick: load }),
        ));
      };
      load();
    },
  };
}
function openDevices(message) {
  if (sheet.open) pushView(devicesView(message));
  else openSheet(devicesView(message));
}

/* ---------- Настройки ---------- */
function settingsView() {
  return {
    title: 'Настройки',
    render(body) {
      const user = el('p', {}, '…');
      api.me().then((m) => { user.textContent = m?.display_name || m?.id || 'Spotify'; }).catch(() => { user.textContent = 'Spotify'; });
      body.append(el('div', { class: 'settings-card' }, el('h3', {}, api.isDemo ? 'Демо-режим' : 'Аккаунт'), user));

      body.append(el('div', { class: 'settings-card' },
        el('h3', {}, 'Тема'),
        el('p', {}, 'Цвета всё равно подстраиваются под обложку.'),
        chipGroup([['aura', 'Аура'], ['vinyl', 'Винил'], ['neon', 'Неон'], ['minimal', 'Минимализм']], settings.theme, (v) => {
          settings.theme = v;
          saveSettings();
          applyLook();
        })));

      body.append(el('div', { class: 'settings-card' },
        el('h3', {}, 'Визуализатор'),
        el('p', {}, '«Декор» — анимация в такт без доступа к звуку. «Микрофон» — настоящая реакция на музыку из динамика (нужно разрешение).'),
        chipGroup([['auto', 'Авто'], ['off', 'Выкл'], ['decor', 'Декор'], ['mic', 'Микрофон']], settings.viz, (v) => {
          settings.viz = v;
          saveSettings();
          updateViz();
        })));

      if (NATIVE) {
        const auto = JSON.parse(NATIVE.getAuto());
        const saveAuto = () => NATIVE.setAuto(JSON.stringify(auto));
        body.append(el('div', { class: 'settings-card' },
          el('h3', {}, 'Наушники'),
          el('p', {}, hp.cur
            ? 'Сейчас подключены: ' + headphonesLabel(hp.cur) + '. Модель и заряд видны на кнопке устройства внизу.'
            : 'Подключи Bluetooth-наушники — модель и заряд появятся на кнопке устройства внизу. Если не появляются, разреши Aura «Устройства поблизости» в настройках Android.'),
          switchRow('Продолжать музыку, когда наушники подключились', auto.resume, (on) => { auto.resume = on; saveAuto(); }),
          switchRow('Открывать Aura, когда наушники подключились', auto.open, (on) => {
            auto.open = on;
            saveAuto();
            if (on && !NATIVE.overlayAllowed()) {
              toast('Разреши Aura «Поверх других приложений» — иначе придёт уведомление вместо открытия');
              NATIVE.openOverlaySettings();
            }
          })));
        if (hp.cur?.sony) body.append(sonyCard());
        body.append(alarmCard());
      }

      body.append(el('div', { class: 'settings-card' },
        el('h3', {}, 'Экран'),
        switchRow('Не гасить экран, пока Aura открыта', settings.wakeLock, (on) => {
          settings.wakeLock = on;
          saveSettings();
          updateWakeLock();
        }),
        el('button', { class: 'btn-ghost', type: 'button', html: icon('expand') + 'Полный экран', onclick: toggleFullscreen }),
        el('p', { style: 'margin-top:10px;font-size:13px' }, 'Поверни планшет или открой Aura на компьютере — включится режим большого экрана с часами и текстом песни.')));

      const install = el('div', { class: 'settings-card' },
        el('h3', {}, 'Установить на телефон'),
        NATIVE
          ? el('p', {}, 'Ты в приложении Aura для Android. Новые версии — по ссылке ', el('a', { href: APK_URL }, 'Aura.apk'), '.')
          : /Android/i.test(navigator.userAgent)
          ? el('div', {},
              el('p', {}, 'Приложение Aura для Android показывает модель и заряд наушников. Скачай и открой файл, затем разреши установку.'),
              el('a', { class: 'btn-primary', href: APK_URL, html: icon('download') + 'Скачать Aura.apk' }))
          : isStandalone()
          ? el('p', {}, 'Aura уже установлена как приложение.')
          : el('div', {},
              el('p', {}, 'Aura ставится на главный экран как обычное приложение и открывается без браузера.'),
              el('ol', {},
                el('li', {}, 'Chrome: ⋮ → «Добавить на главный экран» → «Установить»'),
                el('li', {}, 'Samsung Internet: ≡ → «Добавить страницу на» → «Главный экран»')),
              deferredPrompt ? el('button', { class: 'btn-primary', type: 'button', html: icon('download') + 'Установить', onclick: promptInstall }) : null));
      body.append(install);

      if (!api.isDemo) {
        body.append(el('div', { class: 'settings-card' },
          el('h3', {}, 'Spotify Dashboard'),
          el('p', {}, 'Client ID: ', el('code', {}, Auth.clientId() || '—')),
          el('p', { style: 'margin-top:6px' }, 'Redirect URI: ', el('code', {}, REDIRECT_URI))));
      }

      body.append(el('div', { class: 'settings-card' },
        el('button', { class: 'btn-ghost', type: 'button', html: icon('logout') + (api.isDemo ? 'Выйти из демо' : 'Выйти из аккаунта'), onclick: logout })));
    },
  };
}

/* =====================================================================
   Жесты
   ===================================================================== */
// Обложка: свайп — переключить трек, двойной тап — лайк, долгое нажатие — меню трека
function bindArtGestures() {
  const art = $('#art');
  let x0 = null, y0 = 0, lastTap = 0, pressTimer = 0, longPressed = false;
  art.addEventListener('pointerdown', (e) => {
    x0 = e.clientX; y0 = e.clientY; longPressed = false;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      longPressed = true;
      navigator.vibrate?.(15);
      openSheet(trackMenuView());
    }, 520);
  });
  art.addEventListener('pointermove', (e) => {
    if (x0 != null && Math.hypot(e.clientX - x0, e.clientY - y0) > 10) clearTimeout(pressTimer);
  });
  art.addEventListener('pointerup', (e) => {
    clearTimeout(pressTimer);
    if (x0 == null || longPressed) { x0 = null; return; }
    const dx = e.clientX - x0, dy = e.clientY - y0;
    x0 = null;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) { skip(dx < 0 ? 1 : -1); return; }
    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
      const now = Date.now();
      if (now - lastTap < 320) { toggleLike(true); lastTap = 0; } // двойной тап — лайк
      else lastTap = now;
    }
  });
  art.addEventListener('pointercancel', () => { x0 = null; clearTimeout(pressTimer); });
  art.addEventListener('contextmenu', (e) => e.preventDefault());
}

// Весь экран плеера: свайп вверх — очередь, вниз — медиатека
function bindPlayerSwipes() {
  const p = $('#player');
  let s = null;
  p.addEventListener('pointerdown', (e) => {
    s = sheet.open || e.target.closest('input, .lyrics, .no-device') ? null : { x: e.clientX, y: e.clientY, t: Date.now() };
  });
  p.addEventListener('pointerup', (e) => {
    if (!s) return;
    const dx = e.clientX - s.x, dy = e.clientY - s.y, quick = Date.now() - s.t < 700;
    s = null;
    if (!quick || Math.abs(dy) < 70 || Math.abs(dy) < Math.abs(dx) * 1.4) return;
    openSheet(dy < 0 ? queueView() : libraryView());
  });
  p.addEventListener('pointercancel', () => { s = null; });
}

/* =====================================================================
   Текст песни (LRCLIB — открытая бесплатная база текстов)
   ===================================================================== */
const WIDE_MQ = matchMedia('(min-width: 900px) and (orientation: landscape)');
const lyr = { uri: null, data: null, lines: null, idx: -1, loading: null, userScrollAt: 0, cache: new Map() };

function lyricsVisible() { return !!api && ($('#player').classList.contains('lyrics-mode') || WIDE_MQ.matches); }

function lyricsTrackChanged(item) {
  lyr.uri = item.uri;
  lyr.data = null;
  lyr.lines = null;
  lyr.idx = -1;
  if (lyricsVisible()) loadLyrics();
  else $('#lyricsInner').replaceChildren();
}

function toggleLyrics(force) {
  const p = $('#player');
  const on = force ?? !p.classList.contains('lyrics-mode');
  p.classList.toggle('lyrics-mode', on);
  $('#btnLyrics').classList.toggle('on', on);
  if (lyricsVisible()) {
    lyr.userScrollAt = 0;
    loadLyrics();
    requestAnimationFrame(() => syncLyrics(true));
  }
}

function cleanTitle(name) {
  return name
    .replace(/\s*[-–]\s*(\d{4}\s*)?(remaster(ed)?|live|mono|stereo|radio edit|edit|version|single version|bonus).*$/i, '')
    .replace(/\s*[([](feat|ft|with)\.?\s[^)\]]*[)\]]/i, '')
    .trim();
}

async function fetchLyrics(item) {
  if (api.isDemo) return api.demoLyrics(item);
  const artist = item.artists?.[0]?.name || '';
  const duration = Math.round(item.duration_ms / 1000);
  const exact = await fetchT('https://lrclib.net/api/get?' + new URLSearchParams({
    track_name: item.name, artist_name: artist, album_name: item.album?.name || '', duration,
  })).catch(() => null);
  if (exact?.ok) {
    const d = await exact.json();
    if (d.syncedLyrics || d.plainLyrics || d.instrumental) return d;
  }
  // Точного совпадения нет (или сервис ответил ошибкой) — ищем по упрощённому названию
  const res = await fetchT('https://lrclib.net/api/search?' + new URLSearchParams({ track_name: cleanTitle(item.name), artist_name: artist }));
  if (!res.ok) throw new Error('lrclib ' + res.status);
  const list = (await res.json()) || [];
  const close = list.filter((x) => Math.abs((x.duration || 0) - duration) <= 4);
  return close.find((x) => x.syncedLyrics) || close.find((x) => x.plainLyrics) || list.find((x) => x.syncedLyrics) || null;
}

async function loadLyrics() {
  const item = ui.pb?.item;
  const box = $('#lyricsInner');
  if (!item) { box.replaceChildren(el('div', { class: 'ly-note' }, 'Сейчас ничего не играет')); return; }
  const uri = item.uri;
  if ((lyr.data && lyr.uri === uri) || lyr.loading === uri) return;
  if (item.type === 'episode') { showLyrics(uri, { note: 'У подкастов нет текста' }); return; }
  let data = lyr.cache.get(uri);
  if (!data) {
    lyr.loading = uri;
    box.replaceChildren(loader());
    try {
      data = (await fetchLyrics(item)) || { none: true };
      lyr.cache.set(uri, data);
    } catch {
      data = { error: true };
    } finally {
      if (lyr.loading === uri) lyr.loading = null;
    }
  }
  if (ui.trackUri === uri) showLyrics(uri, data);
}

function parseLRC(text) {
  const out = [];
  for (const raw of text.split('\n')) {
    const tags = [...raw.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];
    if (!tags.length) continue;
    const words = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const t of tags) out.push({ t: (Number(t[1]) * 60 + Number(t[2].replace(':', '.'))) * 1000, text: words });
  }
  return out.sort((a, b) => a.t - b.t);
}

function showLyrics(uri, data) {
  lyr.uri = uri;
  lyr.data = data.error ? null : data; // при ошибке сети попробуем ещё раз позже
  lyr.lines = null;
  lyr.idx = -1;
  const box = $('#lyricsInner');
  box.replaceChildren();
  const noteEl = (t) => el('div', { class: 'ly-note' }, t);
  if (data.error) { box.append(noteEl('Не удалось загрузить текст. Проверь интернет.')); return; }
  if (data.instrumental) { box.append(noteEl('Инструментал — просто слушай ♪')); return; }
  if (data.syncedLyrics) {
    lyr.lines = parseLRC(data.syncedLyrics);
    lyr.lines.forEach((l) => box.append(el('button', {
      class: 'ly-line' + (l.text ? '' : ' gap'), type: 'button',
      onclick: () => { lyr.userScrollAt = 0; command(() => api.seek(l.t), (s) => { s.progress_ms = l.t; }); },
    }, l.text || '♪ ♪ ♪')));
  } else if (data.plainLyrics) {
    box.append(noteEl('Текст без синхронизации'), el('div', { class: 'ly-plain' }, data.plainLyrics));
  } else {
    box.append(noteEl(data.note || 'Текста для этой песни не нашлось'));
    return;
  }
  box.append(el('div', { class: 'ly-source' }, api.isDemo ? 'Демо-текст' : 'Текст: LRCLIB'));
  $('#lyrics').scrollTop = 0;
  syncLyrics(true);
}

function syncLyrics(force) {
  if (!lyr.lines || lyr.uri !== ui.trackUri || !lyricsVisible()) return;
  const pos = currentPos() + 300;
  let i = lyr.idx;
  if (i >= 0 && lyr.lines[i].t > pos) i = -1; // перемотали назад
  while (i + 1 < lyr.lines.length && lyr.lines[i + 1].t <= pos) i++;
  if (i === lyr.idx && !force) return;
  const prev = lyr.idx;
  lyr.idx = i;
  const nodes = $('#lyricsInner').children;
  // Обычно сдвиг на одну строку — трогаем только изменившиеся, а не весь текст
  const from = force ? 0 : Math.max(0, Math.min(prev, i)), to = force ? lyr.lines.length - 1 : Math.max(prev, i);
  for (let k = from; k <= to; k++) {
    nodes[k].classList.toggle('active', k === i);
    nodes[k].classList.toggle('past', k < i);
  }
  // Если человек сам листает текст — не мешаем 4 секунды
  if (Date.now() - lyr.userScrollAt > 4000) {
    const c = $('#lyrics'), target = nodes[Math.max(i, 0)];
    c.scrollTo({ top: target.offsetTop - c.clientHeight * 0.38, behavior: force ? 'auto' : 'smooth' });
  }
}

let clockMin = -1;
function updateClock() {
  if (!WIDE_MQ.matches) return;
  const d = new Date();
  if (d.getMinutes() === clockMin) return;
  clockMin = d.getMinutes();
  $('#clock').textContent = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/* =====================================================================
   Статистика
   ===================================================================== */
function chipGroup(options, value, onPick) {
  const wrap = el('div', { class: 'tabs' });
  for (const [v, label] of options) {
    const b = el('button', { class: 'tab' + (v === value ? ' active' : ''), type: 'button' }, label);
    b.addEventListener('click', () => {
      wrap.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === b));
      onPick(v);
    });
    wrap.append(b);
  }
  return wrap;
}

function switchRow(label, checked, onChange) {
  const input = el('input', { type: 'checkbox', checked });
  input.addEventListener('change', () => onChange(input.checked));
  return el('label', { class: 'switch-row' }, el('span', {}, label), el('span', { class: 'switch' }, input, el('i')));
}

function timeAgo(iso) {
  const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (m < 1) return 'только что';
  if (m < 60) return `${m} мин назад`;
  if (m < 24 * 60) return `${Math.round(m / 60)} ч назад`;
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

function needRelogin(e) {
  if (e.status === 401 || e.status === 403) {
    return el('div', {},
      note('Нужно разрешить Aura доступ к этим данным. Войди ещё раз — это займёт пару секунд.'),
      el('button', { class: 'btn-primary more-btn', type: 'button', onclick: () => Auth.login() }, 'Войти заново'));
  }
  return note('Не удалось загрузить. Проверь интернет.');
}

function statsView() {
  let tab = 'tracks', range = 'short_term', seq = 0;
  return {
    title: 'Моя статистика',
    render(body) {
      const out = el('div');
      const rangeBox = chipGroup([['short_term', '4 недели'], ['medium_term', '6 месяцев'], ['long_term', 'Всё время']], range, (v) => { range = v; load(); });
      const tabs = chipGroup([['tracks', 'Треки'], ['artists', 'Артисты'], ['recent', 'Недавние']], tab, (v) => {
        tab = v;
        rangeBox.hidden = v === 'recent';
        load();
      });
      body.append(tabs, rangeBox, out);
      const ranked = (i, node) => { node.prepend(el('span', { class: 'rank' }, i + 1)); return node; };

      async function load() {
        const my = ++seq;
        out.replaceChildren(loader());
        try {
          let rows;
          if (tab === 'recent') {
            const r = await api.recentlyPlayed();
            rows = (r?.items || []).filter((x) => x?.track).map((x) => trackRow(x.track,
              async () => { if (await playOn({ uris: [x.track.uri] })) closeSheet(); },
              true, `${itemArtists(x.track)} · ${timeAgo(x.played_at)}`));
          } else if (tab === 'tracks') {
            const items = ((await api.topItems('tracks', range))?.items || []).filter(Boolean);
            const uris = items.map((t) => t.uri);
            rows = items.map((t, i) => ranked(i, trackRow(t, async () => { if (await playOn({ uris: uris.slice(i, i + 50) })) closeSheet(); })));
          } else {
            const items = ((await api.topItems('artists', range))?.items || []).filter(Boolean);
            rows = items.map((a, i) => ranked(i, row({
              img: pickImage(a.images, 64), iconName: 'user', round: true, title: a.name,
              sub: (a.genres || []).slice(0, 2).join(', ') || 'Артист',
              onClick: () => pushView(artistView(a)),
            })));
          }
          if (my !== seq) return;
          out.replaceChildren(rows.length ? el('div', { class: 'list' }, rows) : note('Пока мало данных — послушай побольше музыки'));
        } catch (e) {
          if (my === seq) out.replaceChildren(needRelogin(e));
        }
      }
      load();
    },
  };
}

/* =====================================================================
   Карточка «Сейчас слушаю» для сторис
   ===================================================================== */
function loadImg(url) {
  return new Promise((resolve, reject) => {
    if (!url) { reject(new Error('no url')); return; }
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

// Пишет текст в несколько строк, возвращает y после последней строки
function wrapText(x, text, left, top, maxW, lineH, maxLines) {
  const words = text.split(/\s+/);
  const lines = [];
  let cur = '';
  for (const w of words) {
    const test = cur ? cur + ' ' + w : w;
    if (x.measureText(test).width > maxW && cur) { lines.push(cur); cur = w; } else cur = test;
  }
  if (cur) lines.push(cur);
  if (lines.length > maxLines) {
    lines.length = maxLines;
    let last = lines[maxLines - 1];
    while (x.measureText(last + '…').width > maxW && last.length) last = last.slice(0, -1);
    lines[maxLines - 1] = last + '…';
  }
  lines.forEach((l, i) => x.fillText(l, left, top + i * lineH));
  return top + (lines.length - 1) * lineH;
}

async function shareCard() {
  const item = ui.pb?.item;
  if (!item) { toast('Сейчас ничего не играет'); return; }
  toast('Готовлю картинку…');
  const W = 1080, H = 1920;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const x = c.getContext('2d');
  const css = getComputedStyle(document.documentElement);
  const bg1 = css.getPropertyValue('--bg1').trim() || DEFAULT_THEME.bg1;
  const bg2 = css.getPropertyValue('--bg2').trim() || DEFAULT_THEME.bg2;
  const accent = css.getPropertyValue('--accent').trim() || DEFAULT_THEME.accent;

  const g = x.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, bg1);
  g.addColorStop(1, bg2);
  x.fillStyle = g;
  x.fillRect(0, 0, W, H);

  const img = await loadImg(pickImage(itemImages(item), 600)).catch(() => null);
  const S = 820, cx = (W - S) / 2, cy = 300;
  if (img) {
    x.save();
    x.filter = 'blur(90px) saturate(1.5)';
    x.globalAlpha = 0.6;
    x.drawImage(img, -300, -250, W + 600, W + 600);
    x.restore();
    const fade = x.createLinearGradient(0, H * 0.35, 0, H);
    fade.addColorStop(0, 'rgba(0,0,0,0)');
    fade.addColorStop(1, bg2);
    x.fillStyle = fade;
    x.fillRect(0, 0, W, H);
    x.save();
    x.shadowColor = 'rgba(0,0,0,0.55)';
    x.shadowBlur = 90;
    x.shadowOffsetY = 40;
    x.beginPath(); x.roundRect(cx, cy, S, S, 44); x.fillStyle = '#000'; x.fill();
    x.restore();
    x.save();
    x.beginPath(); x.roundRect(cx, cy, S, S, 44); x.clip();
    x.drawImage(img, cx, cy, S, S);
    x.restore();
  }

  const left = cx;
  x.fillStyle = 'rgba(255,255,255,0.6)';
  x.font = '800 32px Manrope, system-ui, sans-serif';
  x.letterSpacing = '6px';
  x.fillText('СЕЙЧАС СЛУШАЮ', left, 1250);
  x.letterSpacing = '0px';
  x.fillStyle = '#fff';
  x.font = '800 78px Manrope, system-ui, sans-serif';
  let y = wrapText(x, item.name, left, 1350, S, 90, 2);
  x.fillStyle = 'rgba(255,255,255,0.75)';
  x.font = '600 44px Manrope, system-ui, sans-serif';
  y = wrapText(x, itemArtists(item), left, y + 72, S, 54, 1);

  // полоска прогресса
  const pos = currentPos(), dur = item.duration_ms || 1;
  const barY = Math.max(y + 90, 1600);
  x.fillStyle = 'rgba(255,255,255,0.22)';
  x.beginPath(); x.roundRect(left, barY, S, 10, 5); x.fill();
  x.fillStyle = accent;
  x.beginPath(); x.roundRect(left, barY, Math.max(10, S * (pos / dur)), 10, 5); x.fill();
  x.fillStyle = 'rgba(255,255,255,0.55)';
  x.font = '700 30px Manrope, system-ui, sans-serif';
  x.fillText(fmtTime(pos), left, barY + 56);
  x.textAlign = 'right';
  x.fillText(fmtTime(dur), left + S, barY + 56);

  // подпись внизу
  x.textAlign = 'center';
  x.fillStyle = 'rgba(255,255,255,0.5)';
  x.font = '800 30px Manrope, system-ui, sans-serif';
  x.letterSpacing = '4px';
  x.fillText('AURA PLAYER', W / 2, H - 90);

  let blob;
  try {
    blob = await new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error('blob'))), 'image/png'));
  } catch {
    toast('Не получилось сделать картинку');
    return;
  }
  if (NATIVE && NATIVE.shareImage(c.toDataURL('image/png'), `${item.name} — ${itemArtists(item)}`)) return;
  const file = new File([blob], 'aura-now-playing.png', { type: 'image/png' });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: item.name, text: `${item.name} — ${itemArtists(item)}` });
      return;
    } catch (e) {
      if (e.name === 'AbortError') return;
    }
  }
  const a = el('a', { href: URL.createObjectURL(blob), download: 'aura-now-playing.png' });
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  toast('Картинка сохранена');
}

/* =====================================================================
   Визуализатор
   ===================================================================== */
const viz = { mode: 'off', raf: 0, analyser: null, stream: null, actx: null, data: null, level: 0, accent: '#fff', accentAt: 0, starting: false };

function vizMode() {
  if (settings.viz === 'auto') return settings.theme === 'neon' ? 'decor' : 'off';
  return settings.viz;
}

function updateViz() {
  const mode = api ? vizMode() : 'off';
  $('#viz').hidden = mode === 'off';
  if (mode !== 'mic' || document.hidden) stopMic();
  viz.mode = mode;
  cancelAnimationFrame(viz.raf);
  if (mode === 'off') return;
  if (mode === 'mic' && !viz.analyser && !document.hidden) startMic();
  drawViz();
}

async function startMic() {
  if (viz.starting) return;
  viz.starting = true;
  try {
    viz.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });
    viz.actx = new AudioContext();
    const an = viz.actx.createAnalyser();
    an.fftSize = 512;
    an.smoothingTimeConstant = 0.78;
    viz.actx.createMediaStreamSource(viz.stream).connect(an);
    viz.analyser = an;
    viz.data = new Uint8Array(an.frequencyBinCount);
    // Браузер запускает звук только после касания экрана
    if (viz.actx.state === 'suspended') document.addEventListener('pointerdown', () => viz.actx?.resume(), { once: true });
  } catch {
    stopMic();
    toast('Нет доступа к микрофону — включаю декоративный режим');
    settings.viz = 'decor';
    saveSettings();
    updateViz();
  } finally {
    viz.starting = false;
  }
}

function stopMic() {
  viz.stream?.getTracks().forEach((t) => t.stop());
  viz.actx?.close().catch(() => {});
  viz.stream = viz.actx = viz.analyser = null;
}

function drawViz(now) {
  viz.raf = requestAnimationFrame(drawViz);
  if (now && now - (viz.lastFrame || 0) < 30) return; // ~30 кадров/с хватает глазу и бережёт батарею
  viz.lastFrame = now || 0;
  const c = $('#viz');
  const w = c.clientWidth, h = c.clientHeight;
  if (!w || !h) return; // скрыт (например, открыт текст песни)
  const dpr = devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
  }
  const x = c.getContext('2d');
  x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.clearRect(0, 0, w, h);
  if (Date.now() - viz.accentAt > 500) {
    viz.accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#fff';
    viz.accentAt = Date.now();
  }
  const n = clamp(Math.floor(w / 9), 16, 72);
  const gap = 3, bw = (w - gap * (n - 1)) / n;
  viz.level += ((ui.pb?.is_playing ? 1 : 0.05) - viz.level) * 0.05;
  const t = performance.now() / 1000;
  const mic = viz.mode === 'mic' && viz.analyser;
  if (mic) viz.analyser.getByteFrequencyData(viz.data);
  x.fillStyle = viz.accent;
  for (let i = 0; i < n; i++) {
    let v;
    if (mic) {
      // квадратичная шкала: больше полос отдаём басам и середине
      const len = viz.data.length * 0.7;
      const lo = Math.floor((i / n) ** 2 * len), hi = Math.max(lo + 1, Math.floor(((i + 1) / n) ** 2 * len));
      let s = 0;
      for (let k = lo; k < hi; k++) s += viz.data[k];
      v = s / (hi - lo) / 255;
    } else {
      const center = 1 - (Math.abs(i - n / 2) / (n / 2)) * 0.55;
      v = (Math.sin(t * 2.1 + i * 0.55) + Math.sin(t * 3.3 + i * 1.31) + Math.sin(t * 1.3 + i * 2.7) + 3) / 6;
      v = v ** 1.6 * center * viz.level;
    }
    const bh = Math.max(3, v * h);
    x.globalAlpha = 0.35 + v * 0.65;
    x.beginPath();
    x.roundRect(i * (bw + gap), (h - bh) / 2, bw, bh, Math.min(bw / 2, 3));
    x.fill();
  }
  x.globalAlpha = 1;
}

/* =====================================================================
   Внешний вид, экран
   ===================================================================== */
const settings = Object.assign({ theme: 'aura', viz: 'auto', wakeLock: false }, store.get('settings', {}));
function saveSettings() { store.set('settings', settings); }
function applyLook() {
  document.body.dataset.theme = settings.theme;
  updateViz();
}

const wake = { sentinel: null };
async function updateWakeLock() {
  const want = settings.wakeLock && !!api && !document.hidden;
  if (NATIVE) { NATIVE.keepScreenOn(want); return; }
  if (want && !wake.sentinel && 'wakeLock' in navigator) {
    try {
      wake.sentinel = await navigator.wakeLock.request('screen');
      wake.sentinel.addEventListener('release', () => { wake.sentinel = null; });
    } catch {}
  } else if (!want && wake.sentinel) {
    wake.sentinel.release().catch(() => {});
    wake.sentinel = null;
  }
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen?.().catch(() => toast('Полный экран тут недоступен'));
}

/* =====================================================================
   Артисты, альбомы, плейлисты, меню трека
   ===================================================================== */
function playButtons(uri) {
  return el('div', { class: 'hero-actions' },
    el('button', { class: 'btn-primary', type: 'button', html: icon('play') + 'Слушать',
      onclick: async () => { await setShuffleQuiet(false); if (await playOn({ context_uri: uri })) closeSheet(); } }),
    el('button', { class: 'btn-ghost', type: 'button', html: icon('shuffle') + 'Вперемешку',
      onclick: async () => { await setShuffleQuiet(true); if (await playOn({ context_uri: uri })) closeSheet(); } }));
}

function heroTitle(title, sub, onSubClick) {
  return el('div', {},
    el('div', { class: 'row-title', style: 'font-size:21px;white-space:normal' }, title),
    sub ? el(onSubClick ? 'button' : 'div', { class: 'row-sub', type: onSubClick ? 'button' : null, onclick: onSubClick, style: 'white-space:normal' }, sub) : null);
}

function artistView(a) {
  return {
    title: a.name || 'Артист',
    render(body) {
      const hero = el('div', { class: 'hero' });
      body.append(hero);
      const fill = (full) => {
        const img = pickImage(full.images, 300);
        hero.replaceChildren(
          img ? el('img', { src: img, alt: '', style: 'border-radius:50%' })
            : el('div', { class: 'thumb-icon', html: icon('user'), style: 'width:180px;height:180px;border-radius:50%' }),
          heroTitle(full.name, (full.genres || []).slice(0, 3).join(', ')),
          playButtons(full.uri || 'spotify:artist:' + full.id));
      };
      fill(a);
      api.artist(a.id).then((full) => { if (full && hero.isConnected) fill(full); }).catch(() => {});
      body.append(el('div', { class: 'section-label' }, 'Альбомы и синглы'));
      pagedList(body, (o) => api.artistAlbums(a.id, o), (al) => al && row({
        img: pickImage(al.images, 64), iconName: 'disc', title: al.name,
        sub: [{ album: 'Альбом', single: 'Сингл', compilation: 'Сборник' }[al.album_type] || 'Альбом', (al.release_date || '').slice(0, 4)].filter(Boolean).join(' · '),
        onClick: () => pushView(albumView(al)),
      }), 'Альбомов не нашлось');
    },
  };
}

function albumView(al) {
  return {
    title: al.name,
    render(body) {
      const img = pickImage(al.images, 300);
      const sub = [itemArtists(al), (al.release_date || '').slice(0, 4)].filter(Boolean).join(' · ');
      body.append(el('div', { class: 'hero' },
        img ? el('img', { src: img, alt: '' }) : el('div', { class: 'thumb-icon', html: icon('disc'), style: 'width:180px;height:180px;border-radius:18px' }),
        heroTitle(al.name, sub, al.artists?.[0]?.id ? () => pushView(artistView(al.artists[0])) : null),
        playButtons(al.uri || 'spotify:album:' + al.id)));
      pagedList(body, (o) => api.albumTracks(al.id, o), (t, i) => {
        if (!t) return null;
        if (!t.album) t.album = al;
        return trackRow(t, async () => { if (await playOn({ context_uri: al.uri || 'spotify:album:' + al.id, offset: { position: i } })) closeSheet(); });
      }, 'Треков нет');
    },
  };
}

let meCache = null;
async function getMe() {
  if (!meCache) meCache = await api.me();
  return meCache;
}

function addToPlaylistView(track) {
  return {
    title: 'Добавить в плейлист',
    render(body) {
      if (!track) { body.append(note('Сейчас ничего не играет')); return; }
      body.append(el('div', { class: 'list' }, trackRow(track, null, false)));
      const input = el('input', { class: 'search-input', type: 'text', placeholder: 'Название плейлиста', maxlength: '100', enterkeyhint: 'done' });
      const create = el('button', { class: 'btn-primary', type: 'button' }, 'Создать');
      const form = el('div', { class: 'inline-form', hidden: true }, input, create);
      const newRow = row({
        iconName: 'plus', title: 'Новый плейлист', sub: 'Создать и сразу добавить трек',
        onClick: () => { newRow.hidden = true; form.hidden = false; input.focus(); },
      });
      body.append(el('div', { class: 'list' }, newRow), form, el('div', { class: 'section-label' }, 'Мои плейлисты'));

      const add = async (p) => {
        try {
          await api.addToPlaylist(p.id, [track.uri]);
          toast(`Добавлено в «${p.name}»`);
          closeSheet();
        } catch (e) { handleError(e, false); }
      };
      create.addEventListener('click', async () => {
        const name = input.value.trim();
        if (!name) { input.focus(); return; }
        create.disabled = true;
        try { await add(await api.createPlaylist(name)); }
        catch (e) { handleError(e, false); }
        finally { create.disabled = false; }
      });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') create.click(); });

      // Добавлять можно только в свои и совместные плейлисты
      getMe().catch(() => null).then((me) => {
        if (!body.isConnected) return;
        pagedList(body, (o) => api.playlists(o), (p) => {
          if (!p || !(p.collaborative || !me || p.owner?.id === me.id)) return null;
          const total = (p.items || p.tracks)?.total;
          return row({
            img: pickImage(p.images, 64), iconName: 'music', title: p.name,
            sub: total != null ? `${total} ${plural(total, 'трек', 'трека', 'треков')}` : null,
            onClick: () => add(p),
          });
        }, 'Своих плейлистов пока нет');
      });
    },
  };
}

function trackMenuView() {
  const item = ui.pb?.item;
  return {
    title: 'Трек',
    render(body) {
      if (!item) { body.append(note('Сейчас ничего не играет')); return; }
      const img = pickImage(itemImages(item), 300);
      body.append(el('div', { class: 'hero' }, img ? el('img', { src: img, alt: '' }) : null, heroTitle(item.name, itemArtists(item))));
      const act = (iconName, title, onClick) => row({ iconName, title, onClick });
      body.append(el('div', { class: 'list' },
        act('lyrics', 'Текст песни', () => { closeSheet(); toggleLyrics(true); }),
        act('plus', 'Добавить в плейлист', () => pushView(addToPlaylistView(item))),
        act(ui.liked ? 'heartFill' : 'heart', ui.liked ? 'Убрать из любимых' : 'Добавить в любимые', () => { closeSheet(); toggleLike(false); }),
        (item.artists || []).filter((a) => a.id).map((a) => act('user', 'Артист: ' + a.name, () => pushView(artistView(a)))),
        item.album?.id ? act('disc', 'Альбом: ' + item.album.name, () => pushView(albumView(item.album))) : null,
        act('share', 'Поделиться карточкой', () => { closeSheet(); shareCard(); }),
      ));
    },
  };
}

async function openContext() {
  const s = ui.pb, item = s?.item;
  if (!item) return;
  const ctx = s.context;
  if (ctx?.type === 'playlist') {
    try {
      const p = await api.playlist(ctx.uri.split(':').pop());
      openSheet(playlistView({ ...p, id: ctx.uri.split(':').pop(), uri: ctx.uri }));
    } catch (e) { handleError(e, false); }
  } else if (ctx?.type === 'artist' && item.artists?.[0]) {
    openSheet(artistView(item.artists[0]));
  } else if (ctx?.type === 'collection') {
    openSheet(likedView());
  } else if (item.album?.id) {
    openSheet(albumView(item.album));
  }
}

function bindSheetDrag() {
  const s = $('#sheet');
  let y0 = null, dy = 0;
  const start = (e) => {
    if (e.target.closest('button')) return;
    y0 = e.clientY; dy = 0;
    s.classList.add('dragging');
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const move = (e) => {
    if (y0 == null) return;
    dy = Math.max(0, e.clientY - y0);
    s.style.transform = `translateY(${dy}px)`;
  };
  const end = () => {
    if (y0 == null) return;
    y0 = null;
    s.classList.remove('dragging');
    if (dy > 110) closeSheet(); else s.style.transform = '';
  };
  for (const h of [$('#sheetHandle'), $('.sheet-head')]) {
    h.addEventListener('pointerdown', start);
    h.addEventListener('pointermove', move);
    h.addEventListener('pointerup', end);
    h.addEventListener('pointercancel', end);
  }
}

/* =====================================================================
   Установка как приложение (PWA)
   ===================================================================== */
let deferredPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
  $('#btnInstallLogin').hidden = false;
});
window.addEventListener('appinstalled', () => {
  deferredPrompt = null;
  $('#btnInstallLogin').hidden = true;
  toast('Aura установлена — ищи её среди приложений');
});
async function promptInstall() {
  if (!deferredPrompt) return;
  deferredPrompt.prompt();
  await deferredPrompt.userChoice.catch(() => {});
  deferredPrompt = null;
  $('#btnInstallLogin').hidden = true;
}
function isStandalone() { return matchMedia('(display-mode: standalone)').matches; }

/* =====================================================================
   Запуск
   ===================================================================== */
function startApp(a) {
  api = a;
  ui.pb = null;
  ui.trackUri = null;
  ui.coverUrl = null;
  ui.contextNames.clear();
  meCache = null;
  lyr.cache.clear();
  $('#login').hidden = true;
  $('#player').hidden = false;
  cancelAnimationFrame(ui.raf);
  progressLoop();
  poll();
  updateViz();
  updateWakeLock();
  if (NATIVE) { try { window.auraHeadphones(JSON.parse(NATIVE.headphones())); } catch {} }
}

function showLogin(message) {
  api = null;
  clearTimeout(ui.timer);
  cancelAnimationFrame(ui.raf);
  closeSheet();
  $('#player').hidden = true;
  $('#login').hidden = false;
  $('#clientIdBox').hidden = !!CONFIG.clientId;
  $('#clientIdInput').value = store.get('clientId', '');
  ui.coverUrl = null;
  clearBackground();
  applyTheme(DEFAULT_THEME);
  document.title = 'Aura Player';
  updateViz();
  updateWakeLock();
  if (message) toast(message);
}

function logout() {
  Auth.logout();
  store.del('demo');
  showLogin();
}

function bindUI() {
  document.querySelectorAll('[data-icon]').forEach((n) => setIcon(n, n.dataset.icon));
  $('#redirectUri').textContent = REDIRECT_URI;

  $('#redirectCopy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(REDIRECT_URI); toast('Скопировано'); } catch { toast(REDIRECT_URI); }
  });
  $('#btnLogin').addEventListener('click', () => {
    if (!window.isSecureContext || !crypto.subtle) { toast('Открой Aura по адресу https:// (или http://127.0.0.1 на компьютере)'); return; }
    if (!CONFIG.clientId) {
      const id = $('#clientIdInput').value.trim();
      if (!/^[0-9a-f]{32}$/i.test(id)) { toast('Вставь Client ID — 32 символа из Spotify Dashboard'); $('#clientIdInput').focus(); return; }
      store.set('clientId', id);
    }
    store.del('demo');
    Auth.login();
  });
  $('#btnDemo').addEventListener('click', () => { store.set('demo', true); startApp(new DemoApi()); });
  $('#btnInstallLogin').addEventListener('click', promptInstall);

  $('#btnPlay').addEventListener('click', togglePlay);
  $('#btnNext').addEventListener('click', () => skip(1));
  $('#btnPrev').addEventListener('click', () => skip(-1));
  $('#btnShuffle').addEventListener('click', toggleShuffle);
  $('#btnRepeat').addEventListener('click', cycleRepeat);
  $('#btnLike').addEventListener('click', () => toggleLike(false));

  const seek = $('#seek');
  seek.addEventListener('input', () => {
    ui.seeking = true;
    const dur = ui.pb?.item?.duration_ms || 0;
    seek.style.setProperty('--p', seek.value / 10 + '%');
    $('#tCur').textContent = fmtTime((seek.value / 1000) * dur);
  });
  seek.addEventListener('change', () => {
    const dur = ui.pb?.item?.duration_ms || 0;
    const ms = (seek.value / 1000) * dur;
    ui.seeking = false;
    command(() => api.seek(ms), (s) => { s.progress_ms = ms; });
  });

  $('#btnLibrary').addEventListener('click', () => openSheet(libraryView()));
  $('#btnOpenLibrary').addEventListener('click', () => openSheet(libraryView()));
  $('#btnRetry').addEventListener('click', retryWake);
  $('#btnSearch').addEventListener('click', () => openSheet(searchView()));
  $('#btnQueue').addEventListener('click', () => openSheet(queueView()));
  $('#btnDevices').addEventListener('click', () => openDevices());
  $('#btnPickDevice').addEventListener('click', () => openDevices());
  $('#btnMenu').addEventListener('click', () => openSheet(settingsView()));

  $('#sheetClose').addEventListener('click', () => closeSheet());
  $('#sheetBack').addEventListener('click', popView);
  $('#sheetBackdrop').addEventListener('click', () => closeSheet());

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && sheet.open) closeSheet();
    if (e.code === 'Space' && api && !sheet.open && e.target === document.body) { e.preventDefault(); togglePlay(); }
  });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && api) poll();
    updateWakeLock();
    if (viz.mode === 'mic') updateViz(); // в фоне микрофон выключаем, при возврате — снова включаем
  });
  window.addEventListener('resize', updateMarquee);

  $('#btnLyrics').addEventListener('click', () => toggleLyrics());
  $('#btnAdd').addEventListener('click', () => openSheet(addToPlaylistView(ui.pb?.item)));
  $('#trackNameWrap').addEventListener('click', () => openSheet(trackMenuView()));
  $('#trackNameWrap').addEventListener('keydown', (e) => { if (e.key === 'Enter') openSheet(trackMenuView()); });
  $('#artistName').addEventListener('click', () => {
    const a = ui.pb?.item?.artists?.find((x) => x.id);
    if (a) openSheet(artistView(a));
  });
  $('#contextName').addEventListener('click', openContext);
  for (const ev of ['touchstart', 'wheel']) {
    $('#lyrics').addEventListener(ev, () => { lyr.userScrollAt = Date.now(); }, { passive: true });
  }
  WIDE_MQ.addEventListener('change', () => {
    updateMarquee();
    if (lyricsVisible()) { loadLyrics(); requestAnimationFrame(() => syncLyrics(true)); }
  });

  bindArtGestures();
  bindPlayerSwipes();
  bindSheetDrag();
}

// Вход начат в приложении, а Spotify вернул нас в обычный браузер — отдаём код приложению
function handOffToApp() {
  const link = 'aura://auth' + location.search;
  document.body.append(el('div', { class: 'handoff' },
    el('div', { class: 'login-inner' },
      el('h2', {}, 'Почти готово'),
      el('p', { class: 'lead' }, 'Возвращаемся в приложение Aura…'),
      el('a', { class: 'btn-primary', href: link }, 'Открыть Aura'))));
  location.href = link;
}

async function boot() {
  bindUI();
  document.body.dataset.theme = settings.theme;
  if (!NATIVE && new URLSearchParams(location.search).get('state')?.startsWith('app-')) { handOffToApp(); return; }
  if (NATIVE) {
    NATIVE.setClientId(Auth.clientId());
    // Первая версия приложения хранила вход в странице — переносим
    try {
      const old = localStorage.getItem('aura.token');
      if (old && !NATIVE.getToken()) NATIVE.saveToken(old);
      localStorage.removeItem('aura.token');
    } catch {}
  }
  try {
    if (await Auth.handleRedirect()) { startApp(new SpotifyApi()); return; }
  } catch (e) {
    showLogin(e.message);
    return;
  }
  const token = store.get('token');
  if (store.get('demo')) startApp(new DemoApi());
  else if (token && token.v !== CONFIG.scopesVersion) showLogin('Aura обновилась! Войди ещё раз, чтобы включить статистику и плейлисты');
  else if (token) startApp(new SpotifyApi());
  else showLogin();
}

if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}

boot();
