import { z } from 'zod';

/** A CSS selector (validated for length only; the page engine ignores selectors the browser rejects). */
const selector = z.string().min(1).max(400);
const selectors = z.array(selector).max(40);

const serversSchema = z
  .object({
    toggle: selector.optional().describe('Button that opens the site server picker.'),
    list: selector.optional().describe('Container of the server items.'),
    item: selector.optional().describe('One server entry (clickable).'),
    active: selector.optional().describe('The selected server entry.'),
    failed: selector.optional().describe('Entry or banner shown when a server is dead.'),
    frame: selector.optional().describe('Iframe holding the picker when it is not in the page itself.'),
  })
  .strict();

const episodesSchema = z
  .object({
    list: selector.optional(),
    item: selector.optional(),
    next: selector.optional().describe('Next-episode button.'),
  })
  .strict();

const playerSchema = z
  .object({
    frame: selector.optional().describe('Player iframe or container.'),
    video: selector.optional().describe('The <video> element (same-origin).'),
    root: selector.optional().describe('Player root element.'),
    fullscreenTarget: selector.optional().describe('Element that receives requestFullscreen().'),
    fullscreenButton: selectors.optional().describe("The site's own fullscreen toggles, hidden with CSS on TV."),
    controls: selectors.optional().describe('Player control bars hidden on idle.'),
    overlayKill: selectors.optional().describe('Intro layers and ad overlays in front of the player, hidden.'),
    depth: z.number().int().min(0).max(4).optional().describe('Iframe nesting depth of the real <video> (0 = same document).'),
  })
  .strict();

export const tvProfileSchema = z
  .object({
    notes: z.string().max(2000).optional().describe('Free text for humans; ignored by the app.'),
    header: selectors.optional().describe('Hidden after 5 s idle, shown on input.'),
    bottomBar: selectors.optional().describe('Bottom bars hidden on idle.'),
    rows: selectors.optional().describe('Horizontal carousels (LEFT/RIGHT inside, UP/DOWN between rows).'),
    cards: selectors.optional().describe('Cards inside rows.'),
    focusables: selectors.optional().describe('Extra elements made focusable.'),
    skip: selectors.optional().describe('Never focused (rail arrows, dots, floating buttons).'),
    firstFocus: selectors.optional().describe('First focus on page load (hero Play), in priority order.'),
    playButtons: selectors.optional().describe('Elements that start playback: trigger auto-fullscreen.'),
    servers: serversSchema.optional(),
    episodes: episodesSchema.optional(),
    player: playerSchema.optional(),
    killOnTv: selectors.optional().describe('Heavy decorative media removed on TV (autoplaying trailer iframes).'),
    viewportWidth: z.number().int().min(320).max(3840).optional().describe('CSS px layout width on TV; default 1280, a site may opt out with 960.'),
  })
  .strict();

const sourceId = z.string().regex(/^[a-z0-9-]+$/);

/** tvProfiles: record of source id -> profile. */
export const tvProfilesSchema = z.record(sourceId, tvProfileSchema);

/** Merges a remote override over base profiles per source: the remote wins per key. Pure; returns new objects. */
export function mergeTvProfiles(base = {}, remote = {}) {
  const out = { ...base };
  for (const [id, prof] of Object.entries(remote)) out[id] = { ...(base[id] ?? {}), ...prof };
  return out;
}

export const cacheSchema = z
  .object({
    enabled: z.boolean().optional().describe('Default: on for the tv target, off elsewhere.'),
    maxMb: z.number().int().min(10).max(2000).default(300),
    maxAgeDays: z.number().int().min(1).max(365).default(30),
  })
  .strict();
