'use strict';
// DNS-over-HTTPS rescue. Some ISP resolvers poison a name a site depends on (e.g. its metadata API), so the page hangs
// on skeletons. Chromium's built-in secure DNS (Cloudflare first, dns.google as fallback) avoids the system resolver.
// `automatic` mode falls back to the system resolver if neither DoH server is reachable, so it can never break
// browsing on a network that blocks DoH.
const SERVERS = Object.freeze(['https://cloudflare-dns.com/dns-query', 'https://dns.google/dns-query']);

/** Options for app.configureHostResolver, or null when no host needs the rescue. */
function resolverOptions(dohHosts) {
  if (!Array.isArray(dohHosts) || dohHosts.length === 0) return null;
  return { enableBuiltInResolver: true, secureDnsMode: 'automatic', secureDnsServers: [...SERVERS] };
}

module.exports = { resolverOptions, SERVERS };
