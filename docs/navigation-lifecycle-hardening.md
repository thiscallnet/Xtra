# Overview and Browse lifecycle hardening

Users reported crashes when opening Overview and Browse, including before build 2487. Ordinary opening on API 26 and API 35 did not reproduce that report. This patch closes demonstrated lifecycle defects and related stale-callback paths; it does not claim a matching stack trace from the affected users.

## Scope and behavior

Keep the custom navigator, retained/restored root stacks, selected Browse page, settings/defaults, scroll and Back behavior, account data and media/chat behavior. No request/parser, schema, version-code, resource, or product-layout changes. No unit tests or delegated agents.

- Browse's current-page lookup returns null without a current view. Previously it dereferenced a nullable binding through `!!`.
- Browse callbacks capture their creating view and reject obsolete view generations, including delayed layout/post callbacks. The pager callback and tab mediator are unregistered when that view is destroyed.
- Overview's current-page lookup follows the same inactive-view contract. Both root scroll actions ignore absent child views.
- Queued root selections wait for the resumed activity and are retried after resume. A selection deferred while state was saved previously had no resume-time replay.
- The navigator also rejects root selection and hidden-tab trimming after its FragmentManager is destroyed. Synchronous commit and stack restoration semantics remain intact.

## Before and after evidence

Temporary diagnostic builds used the real native Browse/Overview path and recorded matching local and installed APK hashes. Their explicit dirty-worktree verification records and source patches are retained outside the repository. The probes did not modify accounts or stored app data.

1. Open Browse's Channels page, capture the host, then open Overview so Browse's view is destroyed. In the baseline, calling the inactive host's current-page lookup and scroll action each threw `NullPointerException`. The fixed version returned null and safely ignored the scroll call.
2. Return to Browse. Channels remained selected. Calls from the old pager callback and the old view's configuration method did not affect the replacement view. Calling inactive Overview's scroll action was also safe.
3. Background Browse, queue an Overview selection, and resume. Both builds retained the pending selection while stopped. The baseline remained on Browse; the fixed build completed the selection and showed Overview.

The diagnostic receiver, activity reference and manifest hook are absent from the submitted source. Clean-build native navigation, root reselection, child-page Back, rotation and process restoration are checked separately. No authenticated chat, follow, notification, point or reward action is part of this verification.

## Risk and limits

Risk is 2/5: callback guards are narrow, while replaying a pending selection changes timing around resume. The last queued destination remains the one honored. Pager listener cleanup must preserve selected-page restoration; the Channels path is checked explicitly.

The prior audit's Overview section-status binding was reviewed without finding a demonstrated crash defect. It is retained. No broad exception handler, navigator replacement, or speculative rollback is added. A user crash trace is still needed to establish whether these defects explain the original reports. OEM-specific behavior and every Android version are not covered.
