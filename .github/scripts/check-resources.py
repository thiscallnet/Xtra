#!/usr/bin/env python3
"""Check preference XML for hardcoded text and report translation coverage."""

from pathlib import Path
import json
import re
import sys
from collections import Counter
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
TRANSLATION_BASELINE = Path(__file__).with_name("translation-baseline.json")
ANDROID_ATTRIBUTE = re.compile(r'android:(title|summary)="([^"]*)"')
FORMAT_TOKEN = re.compile(
    r"\\n|%%|%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])"
)
TRANSLATION_MARKER = re.compile(r"XTRAP|XTRANL|XTRAL|⟦X|␞")
CONTROL_PICTURE = re.compile(r"[\u2400-\u243F]")
INVALID_ANDROID_ESCAPE = re.compile(r"(?<!\\)\\(?:[^nrt\\'\"u:]|u(?![0-9a-fA-F]{4}))|\\{2,}['\"]")
LOCALE_DIRECTORY = re.compile(r"^values-[a-z]{2}(?:-r[A-Z]{2})?$")
PLURAL_QUANTITIES = {
    "values-ar": {"zero", "one", "two", "few", "many", "other"},
    "values-cs": {"one", "few", "many", "other"},
    "values-de": {"one", "other"},
    "values-es": {"one", "many", "other"},
    "values-fr": {"one", "many", "other"},
    "values-gl": {"one", "other"},
    "values-in": {"other"},
    "values-it": {"one", "many", "other"},
    "values-ja": {"other"},
    "values-pt-rBR": {"one", "many", "other"},
    "values-pl": {"one", "few", "many", "other"},
    "values-ru": {"one", "few", "many", "other"},
    "values-sk": {"one", "few", "many", "other"},
    "values-tr": {"one", "other"},
    "values-zh-rCN": {"other"},
    "values-zh-rTW": {"other"},
}
ARRAY_REFERENCE = re.compile(r"^@string/([A-Za-z0-9_]+)$")

# The second-generation updater is intentionally shipped with the default
# English resources until its translations are reviewed by native speakers.
# Android falls back to values/ for these keys; keeping the allowlist explicit
# prevents this policy from hiding missing translations elsewhere in the app.
INTENTIONAL_FALLBACK_RESOURCES = {
    # Watch streak protection is shipped with the default English wording until
    # the new notification settings are translated.
    "watch_streak_protection_category",
    "watch_streak_protection",
    "watch_streak_protection_summary",
    "watch_streak_protection_permission_required",
    "watch_streak_protection_blocked",
    "watch_streak_minimum_title",
    "watch_streak_minimum_summary",
    "watch_streak_notification_title",
    "notification_watch_streak_channel_title",
    "notification_watch_streak_channel_description",
    # Emoji picker labels currently use the default English wording until
    # the picker is translated consistently across locales.
    "emoji",
    "emoji_category_all",
    "emoji_category_smileys",
    "emoji_category_people",
    "emoji_category_animals",
    "emoji_category_food",
    "emoji_category_travel",
    "emoji_category_activities",
    "emoji_category_objects",
    "emoji_category_symbols",
    "emoji_category_flags",
    "select_emoji_category",
    "use_emoji",
    "settings_twemoji",
    "settings_twemoji_summary",
    # Chat user-card details use the default English wording until translated.
    "settings_user_card_category",
    "settings_user_card_account_created",
    "settings_user_card_account_created_summary",
    "settings_user_card_followed_since",
    "settings_user_card_followed_since_summary",
    "user_card_following_since_unavailable",
    "settings_user_card_subscription",
    "settings_user_card_subscription_summary",
    "settings_user_card_roles",
    "settings_user_card_roles_summary",
    "settings_user_card_last_broadcast",
    "settings_user_card_last_broadcast_summary",
    "add_emoji_to_favorites",
    "remove_emoji_from_favorites",
    "added_emoji_to_favorites",
    "removed_emoji_from_favorites",
    "reorder_favorite_item",
    "move_favorite_item_before",
    "move_favorite_item_after",
    # Drops progress settings ship with the default English wording until their
    # translations are reviewed by native speakers.
    "drops_show_summary",
    # Drops browsing controls use the default English wording until translated.
    "drops_progress_accessibility",
    "drops_minimize_progress",
    "drops_expand_progress",
    "drops_view_image",
    "drops_close_image",
    "drops_zoom_hint",
    "drops_find_streams",
    "drops_live_update_expired_title",
    "drops_live_update_expired_result",
    # Diagnostics are intentionally available in English first; entries are
    # technical support output and Android falls back to the default wording.
    "settings_diagnostics_live",
    "settings_diagnostics_live_summary",
    "diagnostics_enable",
    "diagnostics_enable_summary",
    "diagnostics_account_context",
    "diagnostics_account_context_summary",
    "diagnostics_category_filter",
    "diagnostics_severity_filter",
    "diagnostics_filter_apply",
    "diagnostics_clear",
    "diagnostics_copy_all",
    "diagnostics_share",
    "diagnostics_no_entries",
    "diagnostics_entry_copy",
    "diagnostics_entry_expand",
    "diagnostics_entry_collapse",
    "diagnostics_copied",
    "search_drops_filter",
    "search_drops_filter_button",
    "search_drops_filter_title",
    "search_drops_filter_summary",
    "search_drops_filter_hint",
    "search_drops_filter_empty",
    "search_drops_filter_select",
    "search_drops_filter_apply",
    "drops_search_hint",
    "drops_search_no_matches",
    "stream_drops_available_summary",
    "stream_drops_campaign_image",
    "stream_drops_filter",
    "stream_drops_image_hint",
    "stream_drops_view_in_drops",
    "stream_drops_drop_image",
    "automatic_updates",
    "channel_points_prediction_outcome_description",
    "check_automatically",
    "copy_diagnostics",
    "diagnostics_copied",
    "none",
    "update_available_banner",
    "update_checked_recently",
    "update_count_fixed",
    "update_count_improved",
    "update_count_new",
    "update_count_other",
    "update_count_security",
    "update_diagnostics",
    "update_diagnostics_asset",
    "update_diagnostics_error",
    "update_diagnostics_installed",
    "update_diagnostics_last_attempt",
    "update_diagnostics_last_check",
    "update_diagnostics_progress",
    "update_diagnostics_reason",
    "update_diagnostics_speed",
    "update_diagnostics_stage",
    "update_diagnostics_state",
    "update_diagnostics_status",
    "update_diagnostics_target",
    "update_diagnostics_timestamp",
    "update_download_failed_connection",
    "update_download_failed_connection_message",
    "update_download_failed_generic_message",
    "update_download_failed_server",
    "update_download_failed_server_message",
    "update_download_failed_storage",
    "update_download_failed_storage_message",
    "update_download_finished",
    "update_download_paused",
    "update_download_starting",
    "update_download_storage_unavailable",
    "update_download_storage_unavailable_message",
    "update_download_waiting_network",
    "update_download_waiting_retry",
    "update_download_waiting_wifi",
    "update_downloaded_ready",
    "update_downloaded_verified",
    "update_error_message",
    "update_eta_minutes",
    "update_eta_seconds",
    "update_full_release_notes",
    "update_install_cancelled_message",
    "update_install_failed_message",
    "update_install_permission_message",
    "update_install_permission_title",
    "update_meta_separator",
    "update_more_actions",
    "update_preparing_download",
    "update_ready_title",
    "update_release_note_item",
    "update_release_notes_earlier",
    "update_release_notes_earlier_expanded",
    "update_section_fixed",
    "update_section_improved",
    "update_section_new",
    "update_section_other",
    "update_section_security",
    "update_transfer_calculating_speed",
    "update_transfer_downloaded",
    "update_transfer_progress",
    "update_transfer_speed",
    "update_transfer_speed_eta",
    "update_transfer_waiting",
    "update_verification_failed_message",
    "update_verification_failed_title",
    "update_verifying",
    "update_view",
    "settings_tv_chat",
    "settings_tv_chat_layout",
    "settings_tv_chat_configure",
    "settings_tv_chat_hidden",
    "settings_tv_chat_side_panel",
    "settings_tv_chat_overlay",
    "settings_tv_chat_preset",
    "settings_tv_chat_position",
    "settings_tv_chat_side_width",
    "settings_tv_chat_width",
    "settings_tv_chat_height",
    "settings_tv_chat_opacity",
    "settings_tv_chat_reset",
    "tv_browser_remote_hint",
    "settings_tv_chat_preset_auto",
    "settings_tv_chat_preset_compact",
    "settings_tv_chat_preset_standard",
    "settings_tv_chat_preset_large",
    "settings_tv_chat_preset_full_height",
    "settings_tv_chat_preset_custom",
    "settings_tv_chat_anchor_top_left",
    "settings_tv_chat_anchor_top_center",
    "settings_tv_chat_anchor_top_right",
    "settings_tv_chat_anchor_center_left",
    "settings_tv_chat_anchor_center",
    "settings_tv_chat_anchor_center_right",
    "settings_tv_chat_anchor_bottom_left",
    "settings_tv_chat_anchor_bottom_center",
    "settings_tv_chat_anchor_bottom_right",
    # Phone fullscreen chat controls currently use the default English
    # wording until their translations are reviewed by native speakers.
    "settings_phone_chat",
    "settings_phone_chat_overlay",
    "settings_phone_chat_overlay_summary",
    "settings_phone_chat_width",
    "settings_phone_chat_height",
    "settings_phone_chat_opacity",
    "settings_phone_chat_reset",
    # Player accessibility status is currently English-only until translated.
    "player_playing",
    "player_move_chat",
    # Live-rewind labels currently use the default English wording until
    # their translations are reviewed by native speakers.
    "player_live_tap_seek_back",
    "player_live_tap_seek_forward",
    "player_live_tap_seek_live",
    "player_return_to_live",
    "settings_live_rewind_time_position",
    "settings_live_rewind_time_left",
    "settings_live_rewind_time_right",
    # Player-control editor labels are currently English-only until translated.
    "settings_home_controls",
    "settings_home_controls_summary",
    "settings_layout_preview",
    "settings_customize_hud",
    "settings_customize_hud_summary",
    "settings_preview_empty",
    "settings_player_control_metadata",
    "settings_player_control_timeline",
    "settings_customize_controls_position",
    # HUD setup sharing labels currently use the default English wording until
    # the editor's new share flow is translated.
    "settings_hud_share_setup",
    "settings_hud_share_setup_summary",
    "settings_hud_use_for_both",
    "settings_hud_copy_setup",
    "settings_hud_paste_setup",
    "settings_hud_setup_copied",
    "settings_hud_setup_copied_to_both",
    "settings_hud_paste_setup_title",
    "settings_hud_paste_setup_message",
    "settings_hud_setup_invalid_title",
    "settings_hud_setup_invalid_message",
    "third_party_emotes_loading",
    "third_party_emotes_empty",
    "third_party_emotes_error",
    "user_card_subscribed",
    # Chat clip unfurls ship with the default English resources until their
    # translations are reviewed by native speakers.
    "chat_clip_playing",
    "chat_clip_clipped_by",
    # User-targeted moderation notices use the default English wording until
    # translations are reviewed by native speakers.
    "chat_clear_user",
    # The chat input emote preview setting ships with the default English
    # wording until its translations are reviewed by native speakers.
    "render_emotes_in_input",
    "render_emotes_in_input_summary",
    # Chat emote suggestion settings ship with the default English wording
    # until their translations are reviewed by native speakers.
    "chat_emote_autocomplete",
    "chat_emote_autocomplete_summary",
    "chat_emote_recommendations",
    "chat_emote_recommendations_summary",
    "chat_emote_suggestions",
    "chat_compact_twitch_emote_groups",
    "chat_compact_twitch_emote_groups_summary",
    "chat_compact_picker_item_size",
    "chat_compact_picker_item_size_small",
    "chat_compact_picker_item_size_medium",
    "chat_compact_picker_item_size_large",
    "twitch_emote_group_unlocked",
    "twitch_emote_group_hype_train",
    "twitch_emote_group_subscriber",
    "twitch_emote_group_global",
    # Emote interaction settings currently use the default English wording
    # until their translations are reviewed by native speakers.
    "chat_emote_interaction",
    "chat_emote_interaction_emote_details",
    "chat_emote_interaction_emote_tap_profile_hold",
    "chat_emote_interaction_profile_gesture",
    # Moderation display options and their preview ship with the default
    # English resources until translations are reviewed by native speakers.
    "chat_moderation_display",
    "chat_moderation_display_notice",
    "chat_moderation_display_strikethrough",
    "chat_moderation_display_hide",
    "chat_moderation_display_preview_title",
    "chat_moderation_preview_summary",
    "chat_moderation_timeout",
    "chat_moderation_ban",
    "chat_moderation_messages_cleared",
    "chat_moderation_preview_username",
    "chat_moderation_preview_message",
    "chat_moderation_preview_new_message",
    # Fast-chat batching settings use the default English wording until their
    # translations are reviewed by native speakers.
    "settings_chat_batch_interval",
    "settings_chat_batch_interval_summary",
    "settings_chat_batch_interval_off",
    # Chat event rows ship with the default English wording until their
    # translations are reviewed by native speakers. Android intentionally
    # falls back to values/ for these presentation labels.
    "chat_event_raid",
    "chat_event_notice",
    "chat_event_anonymous",
    "chat_event_viewer",
    "chat_event_channel_points_reward",
    "chat_subscription_prime",
    "chat_subscription_paid",
    "chat_subscription_upgrade",
    "chat_subscription_gift",
    "chat_subscription_community_gift",
    "chat_subscription_months",
    "chat_subscription_streak",
    "chat_subscription_accessibility_months",
    # Prediction result display settings ship with the default English resources
    # until their translations are reviewed by native speakers.
    "prediction_result_duration",
    "prediction_result_10s",
    "prediction_result_20s",
    "prediction_result_30s",
    "prediction_result_60s",
    # Happening Now activity cards ship with the default English wording until
    # their translations are reviewed by native speakers.
    "happening_now_title",
    "happening_now_new_events",
    "happening_now_predict_with_points",
    "happening_now_prediction",
    "happening_now_prediction_result",
    "happening_now_poll",
    "happening_now_predict",
    "happening_now_vote",
    "happening_now_see_details",
    "happening_now_gifted_subs",
    "happening_now_gift_count",
    "happening_now_go_to",
    "happening_now_votes",
    "happening_now_dismiss",
    "happening_now_winner",
    "happening_now_more",
    "happening_now_expand",
    "happening_now_collapse",
    # Message highlight settings ship with the default English resources until
    # their translations are reviewed by native speakers.
    "chat_message_highlights",
    "chat_highlight_replies",
    "chat_highlight_mentions",
    "chat_highlight_mentions_without_at",
    "chat_highlight_color",
    "chat_highlight_color_summary",
    "chat_highlight_color_hint",
    "chat_highlight_color_invalid",
    # Chat appearance settings currently ship with the default English wording
    # until their translations are reviewed by native speakers.
    "settings_chat_background",
    "settings_chat_background_summary",
    "settings_chat_background_enabled",
    "settings_chat_background_enabled_summary",
    "settings_chat_background_choose",
    "settings_chat_background_choose_summary",
    "settings_chat_background_change_summary",
    "settings_chat_background_unavailable",
    "settings_chat_background_persist_failed",
    "settings_chat_background_visibility",
    "settings_chat_background_reset",
    "settings_chat_text",
    "settings_chat_message_text",
    "settings_chat_metadata_text",
    "settings_chat_text_default_summary",
    "settings_chat_restore_text_defaults",
    "settings_chat_appearance_preview",
    "settings_chat_appearance_preview_summary",
    "settings_chat_preview_message",
    "settings_chat_preview_metadata",
    "settings_chat_preview_notice",
    # App-wide and player/chat background settings currently ship with the
    # default English wording until their translations are reviewed.
    "settings_app_background",
    "settings_app_background_preview",
    "settings_app_background_preview_summary",
    "settings_app_background_preview_sample",
    "settings_app_background_enabled",
    "settings_app_background_enabled_summary",
    "settings_app_background_choose",
    "settings_app_background_choose_summary",
    "settings_app_background_change_summary",
    "settings_app_background_unavailable",
    "settings_app_background_persist_failed",
    "settings_app_background_visibility",
    "settings_app_background_reset",
    "settings_player_background",
    "settings_player_background_mode",
    "settings_player_background_use_app",
    "settings_player_background_custom",
    "settings_player_background_off",
    "settings_player_background_choose",
    "settings_player_background_choose_summary",
    "settings_player_background_persist_failed",
    "settings_player_background_visibility",
    "settings_player_background_reset",
    "settings_color_picker_hex",
    "settings_color_picker_preview",
    "settings_color_picker_preview_value",
    "settings_color_picker_sample",
    "settings_color_picker_low_contrast",
    "settings_color_picker_saturation_brightness",
    "settings_color_picker_saturation_brightness_value",
    "settings_color_picker_hue",
    "settings_color_picker_hue_value",
    "settings_color_picker_alpha",
    "settings_color_picker_alpha_value",
    "settings_color_picker_default",
    "settings_color_picker_apply",
    "settings_color_picker_invalid",
    # Stream Drops catalog labels ship with the default English resources until
    # their translations are reviewed by native speakers.
    "stream_drops_badge",
    "stream_drops_badge_content_description",
    "stream_drops_title",
    "stream_drops_info",
    "stream_drops_empty",
    "stream_drops_ends",
    "stream_drops_watch_requirement",
    "stream_drops_progress_minutes",
    "stream_drops_event_requirement",
    "stream_drops_ready",
    "stream_drops_watching",
    "stream_drops_sub_requirement",
    "stream_drops_count",
    # Android system-integration settings and notifications ship with the
    # default English wording until their translations are reviewed.
    "live_notification_viewers",
    "live_notification_watch",
    "live_notification_chat",
    "settings_live_activities",
    "settings_rich_live_notifications",
    "settings_rich_live_notifications_summary",
    "settings_live_notification_preview",
    "settings_live_notification_avatar",
    "settings_live_notification_title",
    "settings_live_notification_category",
    "settings_live_notification_viewers",
    "settings_live_notification_preview_metered",
    "settings_live_notification_watch_action",
    "settings_live_notification_chat_action",
    "settings_system_media_controls",
    "settings_system_media_controls_summary",
    "settings_system_media_controls_enabled",
    "settings_system_media_status",
    "settings_system_media_status_available",
    "settings_system_media_show_title",
    "settings_system_media_show_category",
    "settings_system_media_seek_buttons",
    "settings_system_media_seek_back",
    "settings_system_media_seek_forward",
    "settings_system_media_go_live",
    "settings_system_media_artwork",
    "settings_system_media_artwork_streamer",
    "settings_system_media_artwork_preview",
    "settings_system_media_artwork_category",
    "settings_system_media_artwork_none",
    "prediction_live_updates",
    "prediction_live_updates_summary",
    "prediction_live_updates_enabled",
    "prediction_tracking_started",
    "prediction_tracking_stopped",
    "prediction_auto_track_after_vote",
    "prediction_request_promoted",
    "prediction_notify_resolved",
    "prediction_status_chip_text",
    "prediction_chip_time_remaining",
    "prediction_chip_my_pick",
    "prediction_chip_prediction",
    "prediction_live_update_time",
    "prediction_live_update_chip",
    "prediction_live_update_outcome",
    "prediction_live_update_my_pick",
    "prediction_live_update_title",
    "prediction_live_update_result_title",
    "prediction_live_update_canceled",
    "prediction_live_update_won",
    "prediction_live_update_lost",
    "prediction_live_update_resolved",
    "prediction_live_update_channel",
    "prediction_live_update_result_channel",
    "prediction_track",
    "prediction_untrack",
    "drops_live_updates",
    "drops_live_updates_summary",
    "drops_live_updates_enabled",
    "drops_auto_track_active",
    "drops_request_promoted",
    "drops_notify_completed",
    "drops_status_chip_text",
    "drops_chip_time_remaining",
    "drops_chip_percent",
    "drops_chip_drop",
    "drops_live_update_title",
    "drops_live_update_progress",
    "drops_live_update_paused",
    "drops_live_update_expired",
    "drops_live_update_remaining_chip",
    "drops_live_update_percent_chip",
    "drops_live_update_chip",
    "drops_live_update_completed",
    "drops_live_update_completed_text",
    "drops_live_update_channel",
    "drops_live_update_result_channel",
    "drops_track",
    "drops_untrack",
    "live_update_unpin",
    "chat_bubble",
    "chat_bubble_summary",
    "chat_bubble_enabled",
    "chat_bubble_auto_expand",
    "chat_bubble_show_unread",
    "chat_bubble_show_preview",
    "chat_bubble_keep_open",
    "chat_bubble_open",
    "chat_bubble_close",
    "chat_bubble_open_text",
    "chat_bubble_message_preview",
    "chat_bubble_shortcut_label",
    "chat_bubble_channel",
    "chat_bubble_system_disabled",
    "chat_bubble_open_settings",
    "live_update_status",
    "live_update_status_android",
    "live_update_status_available",
    "live_update_status_disabled",
    "live_update_tracking_notification_settings",
    "live_update_result_notification_settings",
    "live_update_notification_settings",
    "chat_bubble_status",
    "chat_bubble_status_android",
    "chat_bubble_status_available",
    "chat_bubble_status_disabled",
    # Settings IA labels and summaries currently use the default English
    # wording until the reorganized settings copy is reviewed for translation.
    "player_live_position",
    "settings_account_manage",
    "settings_account_manage_summary",
    "settings_app_summary",
    "settings_background_behavior",
    "settings_chat_appearance_layout",
    "settings_chat_appearance_layout_summary",
    "settings_chat_emotes_badges_summary",
    "settings_chat_features_summary",
    "settings_chat_messages_interactions",
    "settings_chat_messages_interactions_summary",
    "settings_chat_translation_summary",
    "settings_chat_username_colors",
    "settings_diagnostics_page",
    "settings_diagnostics_page_summary",
    "settings_help_about",
    "settings_help_about_summary",
    "settings_hud_playback_timeline",
    "settings_hud_playback_timeline_fixed",
    "settings_network_proxy",
    "settings_network_proxy_summary",
    "settings_section_advanced_tools",
    "settings_section_customize",
    "settings_section_watch",
    # Moderator tools and chat-command UI ship with the default English
    # wording until their translations are reviewed by native speakers.
    "user_card_moderator_tools",
    "moderator_tools_title",
    "moderator_action_timeout",
    "moderator_action_ban",
    "moderator_action_remove",
    "moderator_action_confirm_ban_title",
    "moderator_action_confirm_ban_message",
    "moderator_action_confirm_timeout_title",
    "moderator_action_confirm_timeout_message",
    "moderator_action_confirm_remove_title",
    "moderator_action_confirm_remove_message",
    "moderator_action_reason_hint",
    "moderator_action_timeout_title",
    "moderator_action_success",
    "moderator_action_rejected",
    "moderator_action_failed",
    "moderator_action_could_not_verify",
    "chat_command_help_title",
    "chat_command_help_available_heading",
    "chat_command_help_moderation_heading",
    "chat_command_help_suggestion",
    "chat_command_me_suggestion",
    "chat_command_ban_help",
    "chat_command_unban_help",
    "chat_command_timeout_help",
    "chat_command_untimeout_help",
    "chat_command_requires_username",
    "chat_command_timeout_duration_invalid",
    "chat_command_help_unavailable_note",
    "chat_command_moderator_required",
    "chat_command_moderator_confirmation_required",
    "chat_command_help_close",
    "chat_command_help_use_composer",
    "chat_api_commands_disabled",
}


def duplicate_resource_keys(directory: Path) -> list[str]:
    counts: Counter[str] = Counter()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            kind = element.tag.rsplit("}", 1)[-1]
            if kind not in {"string", "plurals"}:
                continue
            name = element.attrib.get("name")
            if not name:
                continue
            counts[f"{kind}:{name}"] += 1
            if kind == "plurals":
                for item in element:
                    counts[f"{kind}:{name}:{item.attrib.get('quantity')}"] += 1
    return sorted(key for key, count in counts.items() if count > 1)


def resource_keys(directory: Path) -> set[str]:
    keys: set[str] = set()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] not in {"string", "plurals"}:
                continue
            if element.attrib.get("translatable") == "false":
                continue
            name = element.attrib.get("name")
            if name:
                keys.add(name)
    return keys


def all_string_keys(directory: Path) -> set[str]:
    keys: set[str] = set()
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "string":
                continue
            name = element.attrib.get("name")
            if name:
                keys.add(name)
    return keys


def plural_quantities(directory: Path) -> dict[str, set[str]]:
    quantities: dict[str, set[str]] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "plurals":
                continue
            name = element.attrib.get("name")
            if name:
                quantities.setdefault(name, set()).update(
                    item.attrib.get("quantity", "") for item in element
                )
    return quantities


def resource_arrays(directory: Path) -> dict[str, tuple[bool, list[str]]]:
    arrays: dict[str, tuple[bool, list[str]]] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            if element.tag.rsplit("}", 1)[-1] != "string-array":
                continue
            name = element.attrib.get("name")
            if not name:
                continue
            arrays[name] = (
                element.attrib.get("translatable") != "false",
                [item.text or "" for item in element if item.tag.rsplit("}", 1)[-1] == "item"],
            )
    return arrays


def resource_values(directory: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for path in directory.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"Invalid XML in {path}: {error}") from error
        for element in root:
            kind = element.tag.rsplit("}", 1)[-1]
            if kind == "string" and element.attrib.get("translatable") != "false":
                name = element.attrib.get("name")
                if name:
                    values[name] = element.text or ""
            elif kind == "plurals":
                name = element.attrib.get("name")
                if name:
                    for item in element:
                        values[f"{name}:{item.attrib.get('quantity')}"] = item.text or ""
    return values


def hardcoded_preference_text() -> list[str]:
    findings: list[str] = []
    for path in (RES / "xml").glob("*.xml"):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for attribute, value in ANDROID_ATTRIBUTE.findall(line):
                if value.startswith(("@", "?")) or value == "%s":
                    continue
                findings.append(f"{path.relative_to(ROOT)}:{line_number}: android:{attribute}=\"{value}\"")
    return findings


def main() -> int:
    findings = hardcoded_preference_text()
    if findings:
        print("Hardcoded preference text found:", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)

    default_keys = resource_keys(RES / "values")
    default_string_keys = all_string_keys(RES / "values")
    default_values = resource_values(RES / "values")
    default_plurals = plural_quantities(RES / "values")
    default_arrays = resource_arrays(RES / "values")
    baseline = json.loads(TRANSLATION_BASELINE.read_text(encoding="utf-8"))
    coverage_regressions: list[str] = []
    format_regressions: list[str] = []
    duplicate_regressions: list[str] = []
    escape_regressions: list[str] = []
    empty_regressions: list[str] = []
    marker_regressions: list[str] = []
    plural_regressions: list[str] = []
    array_regressions: list[str] = []
    directories = [RES / "values"] + [
        directory
        for directory in sorted(RES.glob("values-*"))
        if LOCALE_DIRECTORY.fullmatch(directory.name)
    ]
    for directory in directories:
        for key in duplicate_resource_keys(directory):
            duplicate_regressions.append(f"{directory.name}:{key}")
        for key, value in resource_values(directory).items():
            if INVALID_ANDROID_ESCAPE.search(value):
                escape_regressions.append(f"{directory.name}:{key}: invalid Android escape")
    print(f"Default translatable string/plural resources: {len(default_keys)}")
    translatable_arrays = {
        name: items
        for name, (translatable, items) in default_arrays.items()
        if translatable
    }
    print(f"Default translatable string arrays: {len(translatable_arrays)}")
    for name, items in translatable_arrays.items():
        references = [ARRAY_REFERENCE.fullmatch(item) for item in items]
        if not all(references):
            array_regressions.append(
                f"values:{name}: translatable array contains inline text; use @string references"
            )
            continue
        for reference in references:
            assert reference is not None
            key = reference.group(1)
            if key not in default_string_keys:
                array_regressions.append(f"values:{name}: missing string reference {key}")
            elif key not in default_keys:
                array_regressions.append(
                    f"values:{name}: reference {key} is not translatable"
                )

    for directory in directories[1:]:
        translated = resource_keys(directory)
        translated_values = resource_values(directory)
        locale_plurals = plural_quantities(directory)
        locale_arrays = resource_arrays(directory)
        missing = default_keys - translated
        print(
            f"{directory.name}: {len(translated)}/{len(default_keys)} present; "
            f"{len(missing)} fallback resources"
        )
        unexpected_missing = missing - INTENTIONAL_FALLBACK_RESOURCES
        allowed_missing = baseline.get(directory.name)
        if allowed_missing is None or len(unexpected_missing) > allowed_missing:
            coverage_regressions.append(
                f"{directory.name}: {len(unexpected_missing)} resources missing; "
                f"baseline allows {allowed_missing if allowed_missing is not None else 'no value'}"
            )
        required_quantities = PLURAL_QUANTITIES[directory.name]
        for name in default_plurals.keys() | locale_plurals.keys():
            if name in INTENTIONAL_FALLBACK_RESOURCES and name not in locale_plurals:
                continue
            missing_quantities = required_quantities - locale_plurals.get(name, set())
            if missing_quantities:
                plural_regressions.append(
                    f"{directory.name}:{name}: missing plural quantities "
                    f"{', '.join(sorted(missing_quantities))}"
                )
        for name, items in translatable_arrays.items():
            references = [ARRAY_REFERENCE.fullmatch(item) for item in items]
            if not all(references):
                continue
            locale_array = locale_arrays.get(name)
            if locale_array is not None and len(locale_array[1]) != len(items):
                array_regressions.append(
                    f"{directory.name}:{name}: item count changed from "
                    f"{len(items)} to {len(locale_array[1])}"
                )
            for reference in references:
                assert reference is not None
                key = reference.group(1)
                if key not in translated:
                    array_regressions.append(
                        f"{directory.name}:{name}: referenced string {key} is not translated"
                    )
        for key in default_values.keys() & translated_values.keys():
            if default_values[key].strip() and not translated_values[key].strip():
                empty_regressions.append(
                    f"{directory.name}:{key}: translation is empty while the default is not"
                )
            expected_tokens = Counter(FORMAT_TOKEN.findall(default_values[key]))
            actual_tokens = Counter(FORMAT_TOKEN.findall(translated_values.get(key, "")))
            if expected_tokens != actual_tokens:
                format_regressions.append(
                    f"{directory.name}:{key}: format tokens changed from "
                    f"{sorted(expected_tokens.elements())} to {sorted(actual_tokens.elements())}"
                )
            translated_value = translated_values.get(key, "")
            if TRANSLATION_MARKER.search(translated_value) or CONTROL_PICTURE.search(translated_value):
                marker_regressions.append(f"{directory.name}:{key}: translation marker leaked into resources")

    if coverage_regressions:
        print("Translation coverage regressed:", file=sys.stderr)
        print("\n".join(coverage_regressions), file=sys.stderr)

    if format_regressions:
        print("Translation format validation failed:", file=sys.stderr)
        print("\n".join(format_regressions), file=sys.stderr)

    if empty_regressions:
        print("Empty translations found:", file=sys.stderr)
        print("\n".join(empty_regressions), file=sys.stderr)

    if marker_regressions:
        print("Translation markers found:", file=sys.stderr)
        print("\n".join(marker_regressions), file=sys.stderr)

    if plural_regressions:
        print("Plural coverage validation failed:", file=sys.stderr)
        print("\n".join(plural_regressions), file=sys.stderr)

    if array_regressions:
        print("String-array validation failed:", file=sys.stderr)
        print("\n".join(array_regressions), file=sys.stderr)

    if duplicate_regressions:
        print("Duplicate resource definitions found:", file=sys.stderr)
        print("\n".join(duplicate_regressions), file=sys.stderr)

    if escape_regressions:
        print("Invalid Android string escapes found:", file=sys.stderr)
        print("\n".join(escape_regressions), file=sys.stderr)

    return 1 if (
        findings
        or coverage_regressions
        or format_regressions
        or empty_regressions
        or marker_regressions
        or plural_regressions
        or array_regressions
        or duplicate_regressions
        or escape_regressions
    ) else 0


if __name__ == "__main__":
    raise SystemExit(main())
