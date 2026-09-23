/* A room locator, never an authentication token or an access grant. */
(function (root) {
  "use strict";
  const validRoom = value => typeof value === "string" && /^[A-Za-z0-9][A-Za-z0-9_\-]{2,63}$/.test(value);
  function parse(search) {
    if (typeof search !== "string" || search.length > 1024) return { roomId: null, invalid: true };
    const rooms = new URLSearchParams(search).getAll("room");
    if (!rooms.length) return { roomId: null, invalid: false };
    if (rooms.length !== 1 || !validRoom(rooms[0])) return { roomId: null, invalid: true };
    return { roomId: rooms[0], invalid: false };
  }
  function build(origin, roomId, allowedLanHosts = []) {
    if (!validRoom(roomId)) throw new Error("Invalid room identifier");
    const url = new URL(origin);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password ||
        (!["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) &&
          !(url.protocol === 'https:' && allowedLanHosts.includes(url.hostname)))) throw new Error("Approved host required");
    url.pathname = "/"; url.search = ""; url.hash = "";
    url.searchParams.set("room", roomId);
    return url.toString();
  }
  function resolve(invitation, user) {
    const blocked = Boolean(invitation.roomId && user?.role === "GUEST" && user.guestRoomId !== invitation.roomId);
    return { roomId: blocked ? null : invitation.roomId, blocked };
  }
  function parseEntry(pathname, search) {
    if (!pathname.startsWith('/listen/')) return parse(search);
    if (pathname.length > 256 || search.length > 1024 || new URLSearchParams(search).has('room')) return { roomId: null, invalid: true };
    const match = /^\/listen\/([^/]+)$/.exec(pathname);
    try {
      const roomId = match && decodeURIComponent(match[1]);
      return validRoom(roomId) ? { roomId, invalid: false, listener: true } : { roomId: null, invalid: true };
    } catch (_) { return { roomId: null, invalid: true }; }
  }
  const api = Object.freeze({ parse, parseEntry, build, resolve, validRoom });
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.MCastTalkInvitation = api;
})(globalThis);
