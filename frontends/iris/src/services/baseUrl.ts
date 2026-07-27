// Resolving a configured backend origin to something fetchable.
//
// `config.bff.baseUrl` is `/bff` in every deployed cluster (Stage 2.2 re-point: the SPA calls
// same-origin and the FE's nginx proxies /bff/ → the in-cluster iris-bff). Call sites that
// still date from the Golem-direct era assumed the value was always a HOST — `erp-agent.
// dfpartner.cz` — and so prefixed a protocol whenever it did not already start with `http`:
//
//     `${window.location.protocol}//${baseUrl}`      // 'https:' + '//' + '/bff'
//
// For a root-relative path that yields `https:///bff/ready`. Browsers do not reject it: the
// WHATWG URL parser tolerates any number of slashes after a special scheme, so it normalises
// to `https://bff/ready` — host `bff`, which resolves nowhere. The console shows
// ERR_NAME_NOT_RESOLVED against a plausible-looking URL, and the failure reads as DNS rather
// than as a bad join.
//
// A root-relative path is already fetchable. Only a bare host needs a protocol.
export function toAbsoluteOrigin(baseUrl: string): string {
  if (!baseUrl) return ''
  // Absolute (http://, https://) or protocol-relative (//host) — usable as-is.
  if (/^https?:\/\//i.test(baseUrl) || baseUrl.startsWith('//')) return baseUrl
  // Root-relative ('/bff') — same-origin; prefixing a protocol would corrupt it.
  if (baseUrl.startsWith('/')) return baseUrl
  // A bare host ('erp-agent.dfpartner.cz') — inherit the page's protocol.
  return `${window.location.protocol}//${baseUrl}`
}
