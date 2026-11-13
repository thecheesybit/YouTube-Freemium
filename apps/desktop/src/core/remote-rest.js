'use strict';
// Firebase Remote Config over the REST fetch API (the same call the Android RestRemoteFallback makes). Pure request
// building / response parsing plus a poller that takes an injected `post` function (so it is unit tested).
const HOST = 'firebaseremoteconfig.googleapis.com';

/** @param {{projectNumber:string, apiKey:string, appId:string}} creds */
function buildRequest(creds, instanceId) {
  return {
    url: `https://${HOST}/v1/projects/${encodeURIComponent(creds.projectNumber)}/namespaces/firebase:fetch`,
    headers: { 'x-goog-api-key': creds.apiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ appId: creds.appId, appInstanceId: instanceId }),
  };
}

/** The parameter's JSON text from a fetch response, or null when absent / not changed. */
function extractParameter(response, parameter) {
  const v = response && response.entries ? response.entries[parameter] : null;
  return typeof v === 'string' && v.length > 0 ? v : null;
}

/**
 * Polls the parameter; calls `apply(json)` when it changed and `apply` did not throw (a rejected config is
 * remembered so it is not re-applied every cycle). Never throws.
 */
class RemotePoller {
  constructor({ creds, parameter, instanceId, post, apply, log, periodMs = 60_000 }) {
    Object.assign(this, { creds, parameter, instanceId, post, apply, log, periodMs });
    this.last = null;
    this.timer = null;
  }

  start(firstDelayMs = 3000) {
    this.stop();
    const loop = async () => {
      await this.tick();
      this.timer = setTimeout(loop, this.periodMs);
    };
    this.timer = setTimeout(loop, firstDelayMs);
  }

  stop() {
    clearTimeout(this.timer);
    this.timer = null;
  }

  async tick() {
    try {
      const req = buildRequest(this.creds, this.instanceId);
      const res = await this.post(req);
      if (res.status !== 200) {
        this.log(`[remote] fetch status ${res.status}`);
        return false;
      }
      const json = extractParameter(JSON.parse(res.body), this.parameter);
      if (json === null || json === this.last) return false;
      this.last = json;
      this.apply(json);
      return true;
    } catch (e) {
      this.log(`[remote] fetch failed: ${e.name}`);
      return false;
    }
  }
}

module.exports = { buildRequest, extractParameter, RemotePoller, HOST };
