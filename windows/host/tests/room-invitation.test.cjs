const test = require('node:test');
const assert = require('node:assert/strict');
const invitation = require('../src/main/resources/web/room-invitation.js');

test('read-only paths preserve the intended room but grant no automatic admission', () => {
  assert.deepEqual(invitation.parseEntry('/listen/room-one', ''), { roomId: 'room-one', invalid: false, listener: true });
  assert.deepEqual(invitation.parseEntry('/', '?room=room-one'), { roomId: 'room-one', invalid: false });
});
test('invalid encoded extra-segment and oversized read-only paths fail closed', () => {
  for (const pathname of ['/listen/ab', '/listen/', '/listen/room-one/extra', '/listen/%2Fabc', '/listen/%E0%A4%A', '/listen/' + 'a'.repeat(257)])
    assert.deepEqual(invitation.parseEntry(pathname, ''), { roomId: null, invalid: true });
});
test('read-only query contradictions cannot override the path room', () => {
  for (const search of ['?room=other', '?room=room-one', '?room=', '?x=' + 'a'.repeat(1024)])
    assert.deepEqual(invitation.parseEntry('/listen/room-one', search), { roomId: null, invalid: true });
});
test('read-only invitation retains guest room scope enforcement', () => {
  assert.deepEqual(invitation.resolve(invitation.parseEntry('/listen/room-one', ''), { role: 'GUEST', guestRoomId: 'room-two' }), { roomId: null, blocked: true });
});

test('room identifier boundaries match the server contract', () => {
  for (const value of ['abc', 'Room_101', 'a'.repeat(64)]) assert.equal(invitation.validRoom(value), true);
  for (const value of ['', 'ab', 'a'.repeat(65), '_room', ' room', 'room/other', '<img>', '회의실', null]) assert.equal(invitation.validRoom(value), false);
});
test('empty and valid invitation queries parse without redirecting', () => {
  assert.deepEqual(invitation.parse(''), { roomId: null, invalid: false });
  assert.deepEqual(invitation.parse('?room=Room_101&next=https://example.invalid'), { roomId: 'Room_101', invalid: false });
});
test('malformed duplicate overlong and encoded unsafe rooms are rejected', () => {
  for (const query of ['?room=', '?room=one&room=two', '?room=%3Cimg%3E', '?room=abc%2Fdef', '?room=abc%00', '?room=' + 'x'.repeat(1024)]) {
    assert.deepEqual(invitation.parse(query), { roomId: null, invalid: true });
  }
});
test('generated links contain only origin and room, never other query data', () => {
  const result = invitation.build('http://127.0.0.1:8787/private?token=secret#fragment', 'meeting');
  assert.equal(result, 'http://127.0.0.1:8787/?room=meeting');
  assert.equal(invitation.build('http://[::1]:8787', 'meeting'), 'http://[::1]:8787/?room=meeting');
});
test('unsupported schemes public hosts embedded credentials and unsafe rooms fail closed', () => {
  for (const origin of ['javascript:alert(1)', 'https://example.invalid', 'http://user:secret@localhost:8787']) {
    assert.throws(() => invitation.build(origin, 'meeting'));
  }
  assert.throws(() => invitation.build('http://localhost:8787', '../meeting'));
});
test('room links cannot widen a guest account room scope', () => {
  const link = invitation.parse('?room=meeting');
  assert.deepEqual(invitation.resolve(link, { role: 'GUEST', guestRoomId: 'other' }), { roomId: null, blocked: true });
  assert.deepEqual(invitation.resolve(link, { role: 'GUEST', guestRoomId: 'meeting' }), { roomId: 'meeting', blocked: false });
  for (const user of [null, { role: 'USER' }, { role: 'ADMIN' }]) assert.equal(invitation.resolve(link, user).roomId, 'meeting');
});
