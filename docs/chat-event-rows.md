# Chat event rows

Xtra v2 presents prominent chat events as one flat, inline component. The row
keeps the existing `ChatMessageTextView` span renderer, but the compiler first
normalizes protocol notices into a small semantic family and three content
sections.

## Shared grammar

```text
accent rail | icon  title
             metadata
             actor: message
```

- The rail is 4dp wide.
- Content starts 12dp after the rail, with 8dp at the end.
- Event rows use 6dp vertical padding and 1dp extra line spacing.
- The title is strong. Metadata is muted. The actor keeps the normal chat
  color. A message is emitted only when Twitch supplied meaningful input.
- The surface is a low-alpha tint of the channel surface. The rail carries the
  semantic accent. Event rows do not use cards, gradients, shadows, or animated
  backgrounds.

## Semantic families

`SUBSCRIPTION` covers paid, Prime, resub, gifts, community gifts, upgrades,
pay-it-forward, and shared-chat equivalents. `CHANNEL_POINTS` covers custom
redemptions. `HIGHLIGHT` is the stronger Channel Points variant. Watch Streak,
First Time Chatter, announcements, raids, and generic notices are explicit
families. Unknown notices fall back to `NOTICE` instead of inheriting another
event's style.

The compiler prefers structured subscription and reward fields. Legacy
`systemText` remains a fallback for notice variants whose transport does not
provide enough structured metadata.

## Review fixture

The debug build includes `ChatEventFixtureActivity`, a deterministic sequence
of ordinary chat, Prime and paid subscriptions, gifts, a community gift,
Channel Points, Highlight My Message, Watch Streak, and First Time Chatter.
Launch it with:

```text
adb shell am start -n com.github.andreyasadchy.xtra.debug/com.github.andreyasadchy.xtra.ui.chat.v2.ChatEventFixtureActivity \
  --es event_theme dark --ef event_scale 1.0
```

Captured review renders:

- [Before redesign, Dark](chat-event-rows/before-dark-normal.png)
- [Dark, normal size](chat-event-rows/dark-normal.png)
- [Light, normal size](chat-event-rows/light-normal.png)
- [AMOLED, normal size](chat-event-rows/amoled-normal.png)
- [Modern, normal size](chat-event-rows/modern-normal.png)
- [Modern AMOLED, normal size](chat-event-rows/modern_amoled-normal.png)
- [Blue, normal size](chat-event-rows/blue-normal.png)
- [Dark, larger chat size](chat-event-rows/dark-large.png)

The grammar was checked against Twitch's current [chat basics](https://help.twitch.tv/s/article/chat-basics),
[gift subscriptions](https://help.twitch.tv/s/article/gift-subscriptions),
[Channel Points guide](https://help.twitch.tv/s/article/channel-points-guide), and
[chat highlights](https://help.twitch.tv/s/article/chat-highlights) documentation.
