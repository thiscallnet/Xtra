const NATIVE_APP = "com.github.andreyasadchy.xtra.session";
const AUTH_TOKEN_COOKIE = "auth-token";
const COOKIE_REPORT_DELAY_MS = 150;
const GQL_HEADER_NAMES = [
  "Accept",
  "Accept-Encoding",
  "Accept-Language",
  "Authorization",
  "Client-Id",
  "Client-Integrity",
  "Client-Session-Id",
  "Client-Version",
  "Content-Length",
  "Content-Type",
  "Priority",
  "Sec-Ch-Ua",
  "Sec-Ch-Ua-Mobile",
  "Sec-Ch-Ua-Platform",
  "Sec-Fetch-Dest",
  "Sec-Fetch-Mode",
  "Sec-Fetch-Site",
  "X-Device-Id",
  "Origin",
  "Referer",
  "User-Agent"
];

let lastAuthToken = undefined;
let lastCookieSignature = undefined;
let lastGqlHeaderSignature = undefined;
let pendingReportTimer = null;
let pendingAuthTokenChange = null;
let reportQueue = Promise.resolve();

function serializeChangeInfo(changeInfo) {
  if (!changeInfo) return null;
  const cookie = changeInfo.cookie || null;
  return {
    removed: Boolean(changeInfo.removed),
    cause: changeInfo.cause || null,
    cookie: cookie ? {
      name: cookie.name || null,
      domain: cookie.domain || null,
      path: cookie.path || null
    } : null
  };
}

async function reportSession(reason, changeInfo = null) {
  const cookies = await browser.cookies.getAll({ domain: "twitch.tv" });
  const authCandidates = cookies.filter(cookie =>
    cookie.name === AUTH_TOKEN_COOKIE && cookie.value
  );
  const authCookie = authCandidates[0] || null;
  const authToken = authCookie ? authCookie.value : null;
  const cookieSignature = JSON.stringify(cookies.map(cookie => [
    cookie.name,
    cookie.value,
    cookie.domain,
    cookie.path,
    cookie.secure,
    cookie.hostOnly,
    cookie.expirationDate
  ]));

  const forceReport = reason === "initial" || reason === "page_request";
  if (forceReport) lastGqlHeaderSignature = undefined;
  if (!forceReport && authToken === lastAuthToken && cookieSignature === lastCookieSignature) return;
  lastAuthToken = authToken;
  lastCookieSignature = cookieSignature;

  const metadata = cookies.map(cookie => ({
    name: cookie.name,
    value: cookie.value,
    domain: cookie.domain,
    path: cookie.path,
    secure: cookie.secure,
    hostOnly: cookie.hostOnly,
    httpOnly: cookie.httpOnly,
    session: cookie.session,
    expirationDate: cookie.expirationDate,
    storeId: cookie.storeId,
    firstPartyDomain: cookie.firstPartyDomain,
    partitionKey: cookie.partitionKey
  }));

  await browser.runtime.sendNativeMessage(NATIVE_APP, {
    type: "twitch_session",
    reason,
    authToken,
    cookieCount: metadata.length,
    cookies: metadata,
    change: serializeChangeInfo(changeInfo)
  });
}

function queueSessionReport(reason, changeInfo = null) {
  reportQueue = reportQueue
    .then(() => reportSession(reason, changeInfo))
    .catch(() => {});
  return reportQueue;
}

function scheduleCookieReport(changeInfo) {
  const cookie = changeInfo && changeInfo.cookie;
  if (cookie && cookie.name === AUTH_TOKEN_COOKIE) {
    // Keep the auth-token transition attached to the debounced full-jar read.
    // An unrelated cookie event must not hide a real auth-token removal.
    pendingAuthTokenChange = changeInfo;
  }
  if (pendingReportTimer !== null) clearTimeout(pendingReportTimer);
  pendingReportTimer = setTimeout(() => {
    pendingReportTimer = null;
    const authTokenChange = pendingAuthTokenChange;
    pendingAuthTokenChange = null;
    queueSessionReport("cookie_changed", authTokenChange);
  }, COOKIE_REPORT_DELAY_MS);
}

browser.cookies.onChanged.addListener(changeInfo => {
  const cookie = changeInfo && changeInfo.cookie;
  if (cookie && cookie.name === AUTH_TOKEN_COOKIE &&
      changeInfo.removed && changeInfo.cause === "overwrite") {
    // Firefox emits removal+insertion for cookie replacement. The insertion
    // event will produce the full, current cookie jar after the debounce.
    return;
  }
  scheduleCookieReport(changeInfo);
});

function captureGqlRequestHeaders(details) {
  const observed = new Map();
  for (const header of details.requestHeaders || []) {
    const canonicalName = GQL_HEADER_NAMES.find(name =>
      name.toLowerCase() === String(header.name || "").toLowerCase()
    );
    if (canonicalName && header.value) observed.set(canonicalName, header.value);
  }
  const headers = {};
  for (const name of GQL_HEADER_NAMES) {
    if (observed.has(name)) headers[name] = observed.get(name);
  }
  browser.runtime.sendNativeMessage(NATIVE_APP, {
    type: "twitch_gql_browser_request",
    requestId: details.requestId || null,
    method: details.method || null,
    headerNames: [...new Set((details.requestHeaders || [])
      .map(header => String(header.name || ""))
      .filter(Boolean))]
  }).catch(() => {});
  if (observed.size === 0) return;

  const signature = JSON.stringify(GQL_HEADER_NAMES.map(name => headers[name] || null));
  if (signature === lastGqlHeaderSignature) return;
  lastGqlHeaderSignature = signature;
  browser.runtime.sendNativeMessage(NATIVE_APP, {
    type: "twitch_gql_request_headers",
    headers
  }).catch(() => {});
}

browser.webRequest.onBeforeSendHeaders.addListener(
  captureGqlRequestHeaders,
  { urls: ["*://gql.twitch.tv/*"] },
  ["requestHeaders"]
);

browser.webRequest.onCompleted.addListener(
  details => {
    browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: "twitch_gql_browser_response",
      requestId: details.requestId || null,
      statusCode: details.statusCode || 0,
      headerNames: (details.responseHeaders || []).map(header => header.name).filter(Boolean)
    }).catch(() => {});
  },
  { urls: ["*://gql.twitch.tv/*"] },
  ["responseHeaders"]
);

browser.webRequest.onErrorOccurred.addListener(
  details => {
    browser.runtime.sendNativeMessage(NATIVE_APP, {
      type: "twitch_gql_browser_error",
      requestId: details.requestId || null,
      error: details.error || null
    }).catch(() => {});
  },
  { urls: ["*://gql.twitch.tv/*"] }
);

browser.runtime.onMessage.addListener(message => {
  if (message && message.type === "request_session") {
    return queueSessionReport("page_request");
  }
  return undefined;
});

queueSessionReport("initial");
