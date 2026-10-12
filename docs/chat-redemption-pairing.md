# Redemption event pairing

A custom reward with input can reach a chat session twice:

- IRC `PRIVMSG` or EventSub `channel.chat.message` carries the chat message ID,
  author color, badges, emotes, input, and custom reward ID.
- Hermes or EventSub `channel.channel_points_custom_reward_redemption.add`
  carries the redemption ID, input, and reward details.

These are two representations of one action. Twitch's
[EventSub reference](https://dev.twitch.tv/docs/eventsub/eventsub-reference/)
does not supply a shared identity between them. The chat notification timestamp
and redemption `redeemed_at` also describe different processing stages. Delayed
chat publication can put them more than three seconds apart even if the client
receives both promptly.

The previous matcher required timestamps within three seconds. A debug fixture
with two identical inputs and a five-second chat timestamp offset produces four
canonical rows with that implementation. This reproduces the duplicate at the
session store, before presentation. Earlier arrival-order and resume fixes did
not remove this timestamp assumption.

`ChatTimelineStore` now pairs the first unmatched opposite-source row with the
same channel, user ID, reward ID, and exact nonblank input in its retained tail.
Each pairing consumes one chat input and one redemption notice. Timestamp
proximity is not an identity requirement. Known redemption IDs still take
precedence, and distinct known IDs cannot match by input.

The chat row remains authoritative for message ID, color, badges, segments,
replies, and moderation. The notice supplies missing reward metadata. A paired
row cannot consume another input. Same-source events, repeated identical
redemptions, and no-input redemptions remain separate.

## Verification fixture

The debug-only `ChatRewardResumeFixtureActivity` uses production parsers,
`ChatSession`, the canonical timeline, and `ChatV2RendererController` without
opening sockets or redeeming anything. It supports these intent extras:

- `auto_mode`: `hidden`, `background`, or `pip`.
- `clock_skew_ms`: long offset applied to the chat timestamp only.
- `chat_eventsub`: use EventSub chat instead of IRC.
- `eventsub`: use EventSub redemption instead of Hermes.
- `notice_first`, `batch_sources`, `same_text`: vary arrival order and repeated input.
- `redelivery`, `reconcile`: replay deliveries and recent history.
- `no_input`: submit two distinct no-input redemption notices without chat rows.

Each regular scenario must publish two unique chat rows with their original
color and reward metadata. No-input scenarios must retain both notices.

## Limits

Pairing is bounded by the retained timeline and resets with the channel
generation. It does not reconstruct inputs already evicted from the tail.
Without a shared ID, independently missing counterparts with identical input
cannot be distinguished perfectly from a delayed pair. This matcher treats
unmatched opposite-source rows as counterparts in retained order. A live
incident capture would still be needed to confirm which feed was delayed in a
particular user report; the screenshot alone cannot establish that.
