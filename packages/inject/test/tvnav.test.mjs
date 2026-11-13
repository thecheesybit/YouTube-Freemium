import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { score, pick, pickInitial, seekTarget, fmt, renderable, buildLines, pickVertical, pickHorizontal, nextColumn, rowScrollDelta, pickEntry, nearestInColumn } = createRequire(import.meta.url)('../tvnav-pure.js');
const R = (l, t, w, h) => ({ l, t, r: l + w, b: t + h });
const C = (id, rect) => ({ id, rect });

test('picks the nearest element in each direction', () => {
  const from = R(500, 500, 100, 60);
  const cands = [C('L', R(300, 500, 100, 60)), C('R', R(700, 500, 100, 60)), C('U', R(500, 380, 100, 60)), C('D', R(500, 620, 100, 60)), C('far', R(1500, 500, 100, 60))];
  assert.equal(pick(from, cands, 'left').id, 'L');
  assert.equal(pick(from, cands, 'right').id, 'R');
  assert.equal(pick(from, cands, 'up').id, 'U');
  assert.equal(pick(from, cands, 'down').id, 'D');
});

test('prefers aligned over nearer but offset candidates', () => {
  const from = R(500, 500, 100, 60);
  const aligned = C('aligned', R(760, 500, 100, 60));
  const offset = C('offset', R(640, 300, 100, 60));
  assert.equal(pick(from, [offset, aligned], 'right').id, 'aligned');
});

test('rejects candidates behind or outside the cone', () => {
  const from = R(500, 500, 100, 60);
  assert.equal(score(from, R(300, 500, 100, 60), 'right'), -1);
  assert.equal(score(from, R(600, 100, 20, 20), 'right'), -1); // almost straight up
  assert.equal(pick(from, [], 'up'), null);
});

test('rows of cards: down goes to the card below, not a diagonal one', () => {
  const grid = [];
  for (let r = 0; r < 3; r++) for (let c = 0; c < 4; c++) grid.push(C(`${r}${c}`, R(100 + c * 220, 100 + r * 300, 200, 280)));
  const from = grid.find((g) => g.id === '01').rect;
  assert.equal(pick(from, grid.filter((g) => g.id !== '01'), 'down').id, '11');
  assert.equal(pick(from, grid.filter((g) => g.id !== '01'), 'right').id, '02');
  assert.equal(pick(from, grid.filter((g) => g.id !== '01'), 'up'), null);
});

test('initial pick is the first element in reading order inside the viewport', () => {
  const cands = [C('below', R(10, 2000, 50, 50)), C('b', R(400, 100, 50, 50)), C('a', R(20, 100, 50, 50))];
  assert.equal(pickInitial(cands, 1920, 1080).id, 'a');
  assert.equal(pickInitial([C('x', R(0, 5000, 10, 10))], 1920, 1080), null);
});

test('seek target clamps to the media range; time formatting', () => {
  assert.equal(seekTarget(5, 100, -10), 0);
  assert.equal(seekTarget(95, 100, 10), 100);
  assert.equal(seekTarget(50, NaN, 10), 60);
  assert.equal(fmt(65), '1:05');
  assert.equal(fmt(3725), '1:02:05');
  assert.equal(fmt(NaN), '0:00');
});

// ---------- row model ----------

/** A page: header line (free), hero line (free), then `rows` carousels of 8 cards each, 200 wide with 20 gap. */
function page() {
  const items = [];
  ['Home', 'Movies', 'Shows'].forEach((n, i) => items.push({ id: `h${i}`, row: null, rect: R(40 + i * 120, 10, 100, 40) }));
  items.push({ id: 'play', row: null, rect: R(60, 200, 160, 50) });
  items.push({ id: 'more', row: null, rect: R(240, 205, 160, 44) });
  for (let r = 0; r < 3; r++) for (let c = 0; c < 8; c++) items.push({ id: `r${r}c${c}`, row: `row${r}`, rect: R(40 + c * 220 - (r === 1 ? 70 : 0), 300 + r * 260 + (c % 2), 200, 240) });
  return items;
}

test('buildLines: rows are single lines, free items cluster by vertical overlap, sorted top to bottom', () => {
  const lines = buildLines(page());
  assert.equal(lines.length, 5); // header, hero, 3 rows
  assert.deepEqual(lines.map((l) => l.row), [null, null, 'row0', 'row1', 'row2']);
  assert.equal(lines[1].items.length, 2);
});

test('UP/DOWN keep the column across rows of different offset (Netflix behaviour)', () => {
  const items = page();
  const lines = buildLines(items);
  const vw = 1280;
  const from = items.find((i) => i.id === 'r0c3'); // centre x = 40+660+100 = 800
  const col = nextColumn(null, from.rect, 'down');
  const d1 = pickVertical(lines, 'r0c3', 'down', col, vw);
  assert.equal(d1.row, 'row1');
  assert.ok(Math.abs((d1.rect.l + d1.rect.r) / 2 - col) <= 110);
  // second vertical move keeps the ORIGINAL column even though row1's card is offset
  const col2 = nextColumn(col, d1.rect, 'down');
  assert.equal(col2, col);
  const d2 = pickVertical(lines, d1.id, 'down', col2, vw);
  assert.equal(d2.id, 'r2c3');
  // and back up returns to the same card we started from
  assert.equal(pickVertical(lines, d2.id, 'up', col2, vw).id, 'r1c3');
  assert.equal(pickVertical(lines, 'r1c3', 'up', col2, vw).id, 'r0c3');
});

test('a horizontal move resets the column to the new card', () => {
  const rect = R(500, 100, 200, 240);
  assert.equal(nextColumn(123, rect, 'right'), 600);
  assert.equal(nextColumn(123, rect, 'left'), 600);
  assert.equal(nextColumn(123, rect, 'up'), 123);
});

test('UP from the first row reaches the header; DOWN from the header reaches the hero, then rows', () => {
  const lines = buildLines(page());
  assert.equal(pickVertical(lines, 'play', 'up', 100, 1280).id, 'h0');
  assert.equal(pickVertical(lines, 'h0', 'down', 80, 1280).id, 'play');
  assert.equal(pickVertical(lines, 'play', 'down', 140, 1280).row, 'row0');
  assert.equal(pickVertical(lines, 'r2c0', 'down', 140, 1280), null);
});

test('LEFT/RIGHT stay inside a row: neighbours in order, null at the ends, never a rail arrow or another row', () => {
  const items = page();
  const lines = buildLines(items);
  const at = (id) => items.find((i) => i.id === id);
  assert.equal(pickHorizontal(lines, 'r0c0', 'right', at('r0c0').rect, items).id, 'r0c1');
  assert.equal(pickHorizontal(lines, 'r0c1', 'left', at('r0c1').rect, items).id, 'r0c0');
  assert.equal(pickHorizontal(lines, 'r0c0', 'left', at('r0c0').rect, items), null);
  assert.equal(pickHorizontal(lines, 'r0c7', 'right', at('r0c7').rect, items), null);
});

test('free line LEFT/RIGHT: same line first, then spatial fallback', () => {
  const items = page();
  const lines = buildLines(items);
  const at = (id) => items.find((i) => i.id === id);
  assert.equal(pickHorizontal(lines, 'h0', 'right', at('h0').rect, items).id, 'h1');
  assert.equal(pickHorizontal(lines, 'play', 'right', at('play').rect, items).id, 'more');
  assert.equal(pickHorizontal(lines, 'h0', 'left', at('h0').rect, items), null);
});

test('nearestInColumn prefers the card containing the column, ignores cards centred outside the viewport', () => {
  const line = { items: [{ id: 'a', rect: R(0, 0, 100, 50) }, { id: 'b', rect: R(120, 0, 100, 50) }, { id: 'off', rect: R(1400, 0, 100, 50) }] };
  assert.equal(nearestInColumn(line, 150, 1280).id, 'b');
  assert.equal(nearestInColumn(line, 1450, 1280).id, 'b'); // the off-screen one is excluded
});

test('renderable: closed details, aria-hidden layers, zero size and hidden styles are never focusable', () => {
  const ok = { w: 100, h: 50, display: 'block', visibility: 'visible', pointerEvents: 'auto', opacity: 1 };
  assert.equal(renderable(ok), true);
  assert.equal(renderable({ ...ok, closedDetails: true }), false);
  assert.equal(renderable({ ...ok, hiddenAncestor: true }), false);
  assert.equal(renderable({ ...ok, offscreenLayer: true }), false);
  assert.equal(renderable({ ...ok, w: 9 }), false);
  assert.equal(renderable({ ...ok, display: 'none' }), false);
  assert.equal(renderable({ ...ok, visibility: 'hidden' }), false);
  assert.equal(renderable({ ...ok, opacity: 0 }), false);
  assert.equal(renderable({ ...ok, disabled: true }), false);
  assert.equal(renderable(null), false);
});

test('rowScrollDelta: card fully visible with a peek of the next one', () => {
  const view = { l: 0, r: 1000 };
  assert.equal(rowScrollDelta({ l: 100, r: 300 }, view, 50), 0);
  assert.equal(rowScrollDelta({ l: 850, r: 1050 }, view, 50), 100);
  assert.equal(rowScrollDelta({ l: -20, r: 180 }, view, 50), -70);
});

test('pickEntry chooses the nearest side for the entry direction', () => {
  const c = [{ id: 'tl', rect: R(0, 0, 50, 50) }, { id: 'br', rect: R(900, 500, 50, 50) }, { id: 'tr', rect: R(900, 0, 50, 50) }];
  assert.equal(pickEntry(c, 'down').id, 'tl');
  assert.equal(pickEntry(c, 'up').id, 'br');
  assert.equal(pickEntry(c, 'right').id, 'tl');
  assert.equal(pickEntry(c, 'left').id, 'tr');
});
