import { z } from 'zod';
import { isValidAppId, isValidHostPattern } from './hosts.mjs';
import { cacheSchema, tvProfilesSchema } from './tvprofile.mjs';

const httpUrl = z
  .string()
  .url('must be a valid URL')
  .refine((u) => /^https?:\/\//i.test(u), 'must start with http:// or https://');

const color = z.string().regex(/^#[0-9a-fA-F]{6}$/, 'must be a #RRGGBB color');

const hostPattern = z
  .string()
  .refine(isValidHostPattern, 'must be a host like "example.com" or "*.example.com"');

const sourceSchema = z
  .object({
    id: z.string().regex(/^[a-z0-9-]+$/, 'must be lowercase letters, digits and dashes').describe('Stable source id.'),
    name: z.string().min(1).max(40).describe('Display name (the only thing shown in the UI).'),
    url: httpUrl.describe('Start URL (never shown in the UI).'),
    allowedHosts: z.array(hostPattern).optional().describe('Default: URL host and its subdomains.'),
    accent: color.optional().describe('Accent colour; a palette colour is picked by index when missing.'),
    enabled: z.boolean().default(true),
  })
  .strict();

const remoteSchema = z
  .object({
    provider: z.literal('firebase-remote-config'),
    projectId: z.string().min(1),
    parameter: z.string().min(1).default('movy_config'),
    googleServices: z.string().min(1).describe('Path to google-services.json, relative to the repo root.'),
    template: z.string().min(1).optional().describe('Path to the Remote Config JSON source.'),
  })
  .strict();

const islandSchema = z
  .object({ visibility: z.enum(['home', 'always']).default('home') })
  .strict();

const adblockFields = {
  enabled: z.boolean().default(true).describe('Master switch for ad blocking.'),
  lists: z
    .union([z.literal('default'), z.array(httpUrl).min(1)])
    .default('default')
    .describe('"default" or an array of filter list URLs.'),
  extraLists: z.array(httpUrl).default([]).describe('Filter list URLs appended to `lists`.'),
  customRules: z.array(z.string()).default([]).describe('Inline ABP/uBO filter rules.'),
  cosmetic: z.boolean().default(true).describe('Element hiding + scriptlets.'),
  blockPopups: z.boolean().default(true).describe('Block window.open / target=_blank to non-allowed hosts.'),
};

export const adblockSchema = z.object(adblockFields).strict();

const strip = (v) => (v instanceof z.ZodDefault ? v.removeDefault() : v).optional();

const baseFields = {
  name: z.string().regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'must be lowercase letters, digits and dashes').describe('Config key; must equal the file name.'),
  appName: z.string().min(1).max(60).describe('Launcher label / window title.'),
  appId: z.string().refine(isValidAppId, 'must be a valid Java package id such as "com.example.shell"').describe('Android applicationId / Electron appId.'),
  url: httpUrl.optional().describe('Start URL of a single-site app (legacy; normalized to one source). Use `sources` for several.'),
  sources: z.array(sourceSchema).min(1).optional().describe('Sources, in display order.'),
  recommendedSource: z.string().optional().describe('Id of the recommended source.'),
  tagline: z.string().max(120).default('').describe('Shown on the splash.'),
  credit: z.string().max(60).default('').describe('Author credit shown on the splash.'),
  island: islandSchema.default(() => islandSchema.parse({})),
  remote: remoteSchema.optional().describe('Firebase Remote Config live config; omit to build without Firebase.'),
  version: z.string().regex(/^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$/, 'must look like 1.0.0').default('1.0.0'),
  versionCode: z.number().int().min(1).max(2100000000).default(1),
  allowedHosts: z.array(hostPattern).optional().describe('Hosts allowed for in-app top-level navigation. Default: URL host and its subdomains.'),
  authHosts: z.array(hostPattern).optional().describe('Extra sign-in hosts (added to the built-in list) that a user tap may open as a top-level navigation.'),
  dohHosts: z.array(hostPattern).optional().describe('Hosts whose requests are answered over DNS-over-HTTPS, for networks whose resolver poisons a name the site depends on.'),
  externalLinks: z.enum(['block', 'in-app', 'open-external']).default('block').describe('Non-allowed hosts: "block" (default, silently ignored) or "in-app". "open-external" is deprecated and treated as "block"; the app never launches other apps.'),
  userAgent: z.string().min(1).nullable().default(null).describe('null = platform default with WebView markers removed.'),
  backgroundColor: color.default('#000000'),
  orientation: z.enum(['any', 'portrait', 'landscape']).default('any'),
  adblock: adblockSchema.default(() => adblockSchema.parse({})),
  tvProfiles: tvProfilesSchema.optional().describe('Per-source TV navigation profiles (selectors only); overridable from Remote Config.'),
  cache: cacheSchema.optional().describe('Disk asset cache (images, fonts, versioned static files); on by default for tv only.'),
};

// Per-target overrides: any key except identity ones, all optional, adblock partial.
const overrideSchema = z
  .object({
    ...Object.fromEntries(
      Object.entries(baseFields)
        .filter(([k]) => k !== 'name' && k !== 'adblock')
        .map(([k, v]) => [k, strip(v)]),
    ),
    adblock: z.object(Object.fromEntries(Object.entries(adblockFields).map(([k, v]) => [k, strip(v)]))).strict().optional(),
  })
  .partial()
  .strict();

function checkSources(c, ctx) {
  if (!c.url && !c.sources) {
    ctx.addIssue({ code: 'custom', path: ['sources'], message: 'either `url` or `sources` is required' });
  }
  const ids = new Set();
  (c.sources ?? []).forEach((s, i) => {
    if (ids.has(s.id)) ctx.addIssue({ code: 'custom', path: ['sources', i, 'id'], message: `duplicate source id "${s.id}"` });
    ids.add(s.id);
  });
  if (c.recommendedSource && c.sources && !ids.has(c.recommendedSource)) {
    ctx.addIssue({ code: 'custom', path: ['recommendedSource'], message: `"${c.recommendedSource}" is not a source id` });
  }
}

export const appConfigSchema = z
  .object({
    ...baseFields,
    targets: z
      .object({ phone: overrideSchema, tv: overrideSchema, desktop: overrideSchema })
      .partial()
      .strict()
      .default({}),
    $schema: z.string().optional(),
  })
  .strict()
  .superRefine(checkSources);

export { overrideSchema, sourceSchema };
