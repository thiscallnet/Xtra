const NATIVE_APP = "com.github.andreyasadchy.xtra.session";
const AUTH_TOKEN_COOKIE = "auth-token";
const COOKIE_REPORT_DELAY_MS = 150;

let lastAuthToken = undefined;
let lastCookieSignature = undefined;
let pendingReportTimer = null;
let pendingAuthTokenChange = null;
let reportQueue = Promise.resolve();
let lastGqlIdentitySignature = undefined;

function requestHeader(requestHeaders, name) {
  const header = requestHeaders.find(item =>
    item && item.name && item.name.toLowerCase() === name.toLowerCase()
  );
  return header && header.value ? header.value : null;
}

function reportGqlIdentity(details) {
  const authorization = requestHeader(details.requestHeaders || [], "Authorization");
  const clientId = requestHeader(details.requestHeaders || [], "Client-Id");
  const clientIntegrity = requestHeader(details.requestHeaders || [], "Client-Integrity");
  const xDeviceId = requestHeader(details.requestHeaders || [], "X-Device-Id");
  if (!authorization || !clientId || !clientIntegrity || !xDeviceId) return;

  const identity = {
    authorization,
    clientId,
    clientIntegrity,
    xDeviceId,
    clientSessionId: requestHeader(details.requestHeaders || [], "Client-Session-Id"),
    clientVersion: requestHeader(details.requestHeaders || [], "Client-Version"),
    capturedAt: Date.now()
  };
  const signature = JSON.stringify([
    identity.authorization,
    identity.clientId,
    identity.clientIntegrity,
    identity.xDeviceId,
    identity.clientSessionId,
    identity.clientVersion
  ]);
  if (signature === lastGqlIdentitySignature) return;
  lastGqlIdentitySignature = signature;
  browser.runtime.sendNativeMessage(NATIVE_APP, {
    type: "twitch_integrity",
    ...identity
  }).catch(() => {});
}

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

browser.webRequest.onBeforeSendHeaders.addListener(
  reportGqlIdentity,
  { urls: ["https://gql.twitch.tv/*"] },
  ["requestHeaders"]
);

browser.runtime.onMessage.addListener(message => {
  if (message && message.type === "request_session") {
    // A new Gecko page may be the native side's request to reacquire the same
    // browser identity after it invalidated its stored snapshot.
    lastGqlIdentitySignature = undefined;
    return queueSessionReport("page_request");
  }
  return undefined;
});

queueSessionReport("initial");
