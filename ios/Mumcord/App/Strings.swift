// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Typed access to the texts in `Localizable.strings`. The keys equal the string names of the
/// Android app, so both apps are translated from the same list.
///
/// Plural forms come from `Localizable.stringsdict`, which `scripts/translations.py` writes with
/// the plural rules of each language; the number that picks the form is passed after the text's
/// own arguments, as Android passes its quantity apart from them.
enum Strings {
    static var appName: String { text("app_name") }
    static var actionCancel: String { text("action_cancel") }
    static var actionBack: String { text("action_back") }
    static var actionClose: String { text("action_close") }
    static var talkModeTitle: String { text("talk_mode_title") }
    static var talkModeSubtitle: String { text("talk_mode_subtitle") }
    static var talkModeVoiceActivity: String { text("talk_mode_voice_activity") }
    static var talkModeVoiceActivityText: String { text("talk_mode_voice_activity_text") }
    static var talkModePerkNoiseSuppression: String { text("talk_mode_perk_noise_suppression") }
    static var talkModePerkHandsFree: String { text("talk_mode_perk_hands_free") }
    static var talkModePushToTalk: String { text("talk_mode_push_to_talk") }
    static var talkModePushToTalkText: String { text("talk_mode_push_to_talk_text") }
    static var talkModePerkHold: String { text("talk_mode_perk_hold") }
    static var talkModeContinue: String { text("talk_mode_continue") }
    static var talkModeChangeLater: String { text("talk_mode_change_later") }
    static var serversTitle: String { text("servers_title") }
    static var serversFavourites: String { text("servers_favourites") }
    static var serversEdit: String { text("servers_edit") }
    static var serversDone: String { text("servers_done") }
    static var serversConnect: String { text("servers_connect") }
    static func serversConnectDescription(_ p1: String) -> String { format("servers_connect_description", p1) }
    static func serversCancelDescription(_ p1: String) -> String { format("servers_cancel_description", p1) }
    static func serversEditDescription(_ p1: String) -> String { format("servers_edit_description", p1) }
    static func serversRemoveDescription(_ p1: String) -> String { format("servers_remove_description", p1) }
    static var serversConnecting: String { text("servers_connecting") }
    static func serversOnline(_ p1: Int, _ p2: Int) -> String {
        plural("servers_online", count: p1, p1, p2)
    }
    static var serversOffline: String { text("servers_offline") }
    static var serversAdd: String { text("servers_add") }
    static var serversEmptyTitle: String { text("servers_empty_title") }
    static var serversEmptyText: String { text("servers_empty_text") }
    static var serversFindMore: String { text("servers_find_more") }
    static var serversBrowsePublic: String { text("servers_browse_public") }
    static var serversSettings: String { text("servers_settings") }
    static var serversMoreBack: String { text("servers_more_back") }
    static var publicTitle: String { text("public_title") }
    static var publicBack: String { text("public_back") }
    static var publicLoading: String { text("public_loading") }
    static var publicLoadFailedTitle: String { text("public_load_failed_title") }
    static var publicLoadFailed: String { text("public_load_failed") }
    static var publicRetry: String { text("public_retry") }
    static var publicRefresh: String { text("public_refresh") }
    static var publicRefreshing: String { text("public_refreshing") }
    static var publicRefreshFailed: String { text("public_refresh_failed") }
    static var publicSearch: String { text("public_search") }
    static var publicAnyCountry: String { text("public_any_country") }
    static func publicCountAll(_ p1: Int) -> String {
        plural("public_count_all", count: p1, p1)
    }
    /// The plural follows the total, as on Android.
    static func publicCountShown(_ p1: Int, _ p2: Int) -> String {
        plural("public_count_shown", count: p2, p1, p2)
    }
    static var publicEmpty: String { text("public_empty") }
    static var publicNoMatch: String { text("public_no_match") }
    static func publicAddressCountry(_ p1: String, _ p2: String) -> String { format("public_address_country", p1, p2) }
    static func publicPing(_ p1: Int, _ p2: Int, _ p3: Int) -> String { format("public_ping", p1, p2, p3) }
    /// The plural follows the user limit, as on Android.
    static func publicPingDescription(_ p1: Int, _ p2: Int, _ p3: Int) -> String {
        plural("public_ping_description", count: p2, p1, p2, p3)
    }
    static var publicNoAnswer: String { text("public_no_answer") }
    static var publicAdd: String { text("public_add") }
    static func publicAddDescription(_ p1: String) -> String { format("public_add_description", p1) }
    static var publicAddFailed: String { text("public_add_failed") }
    static var publicNote: String { text("public_note") }
    static var formAddTitle: String { text("form_add_title") }
    static var formEditTitle: String { text("form_edit_title") }
    static var formAddressLabel: String { text("form_address_label") }
    static var formAddressPlaceholder: String { text("form_address_placeholder") }
    static var formAddressMissing: String { text("form_address_missing") }
    static var formPortLabel: String { text("form_port_label") }
    static var formPortInvalid: String { text("form_port_invalid") }
    static var formNameLabel: String { text("form_name_label") }
    static var formNameHint: String { text("form_name_hint") }
    static var formUsernameLabel: String { text("form_username_label") }
    static var formUsernameHint: String { text("form_username_hint") }
    static var formUsernameMissing: String { text("form_username_missing") }
    static var formPasswordLabel: String { text("form_password_label") }
    static var formPasswordHint: String { text("form_password_hint") }
    static var formPasswordSaved: String { text("form_password_saved") }
    static var formPasswordWillBeRemoved: String { text("form_password_will_be_removed") }
    static var formPasswordRemove: String { text("form_password_remove") }
    static var formPasswordKeep: String { text("form_password_keep") }
    static var formSave: String { text("form_save") }
    static var formSaveFailed: String { text("form_save_failed") }
    static var passwordTitle: String { text("password_title") }
    static func passwordText(_ p1: String) -> String { format("password_text", p1) }
    static var passwordLabel: String { text("password_label") }
    static var switchTitle: String { text("switch_title") }
    static func switchText(_ p1: String, _ p2: String) -> String { format("switch_text", p1, p2) }
    static var switchConfirm: String { text("switch_confirm") }
    static var removeTitle: String { text("remove_title") }
    static func removeText(_ p1: String) -> String { format("remove_text", p1) }
    static var removeConfirm: String { text("remove_confirm") }
    static var linkTitle: String { text("link_title") }
    static var linkText: String { text("link_text") }
    static var linkServer: String { text("link_server") }
    static var linkChannel: String { text("link_channel") }
    static var linkUsername: String { text("link_username") }
    static var linkWillAdd: String { text("link_will_add") }
    static var linkInvalidTitle: String { text("link_invalid_title") }
    static var linkInvalidText: String { text("link_invalid_text") }
    static var errorTitle: String { text("error_title") }
    static func errorTitleServer(_ p1: String) -> String { format("error_title_server", p1) }
    static var errorRetry: String { text("error_retry") }
    static var errorDismiss: String { text("error_dismiss") }
    static var errorEnterPassword: String { text("error_enter_password") }
    static var errorNotConnected: String { text("error_not_connected") }
    static var errorServerUnreachable: String { text("error_server_unreachable") }
    static var errorWrongPassword: String { text("error_wrong_password") }
    static var errorNameInUse: String { text("error_name_in_use") }
    static var errorCertificateRejected: String { text("error_certificate_rejected") }
    static var errorCertificateChanged: String { text("error_certificate_changed") }
    static var errorServerTooOld: String { text("error_server_too_old") }
    static var errorBanned: String { text("error_banned") }
    static var errorNotFound: String { text("error_not_found") }
    static var errorPermissionDenied: String { text("error_permission_denied") }
    static var errorInvalidInput: String { text("error_invalid_input") }
    static var errorSecureStorageUnavailable: String { text("error_secure_storage_unavailable") }
    static var errorIo: String { text("error_io") }
    static var errorDuplicateIdentity: String { text("error_duplicate_identity") }
    static var errorLastIdentity: String { text("error_last_identity") }
    static var errorNoIdentity: String { text("error_no_identity") }
    static var errorInternal: String { text("error_internal") }
    static var certificateUnknownTitle: String { text("certificate_unknown_title") }
    static var certificateUnknownMessage: String { text("certificate_unknown_message") }
    static var certificateFingerprintLabel: String { text("certificate_fingerprint_label") }
    static var certificateUnknownTrust: String { text("certificate_unknown_trust") }
    static var certificateReject: String { text("certificate_reject") }
    static var certificateChangedTitle: String { text("certificate_changed_title") }
    static var certificateChangedMessage: String { text("certificate_changed_message") }
    static var certificateChangedPreviousLabel: String { text("certificate_changed_previous_label") }
    static var certificateChangedNewLabel: String { text("certificate_changed_new_label") }
    static var certificateChangedTrust: String { text("certificate_changed_trust") }
    static func channelsReconnecting(_ p1: Int, _ p2: Int) -> String { format("channels_reconnecting", p1, p2) }
    static var channelsSelfMarker: String { text("channels_self_marker") }
    static var channelsHere: String { text("channels_here") }
    static var channelsSearch: String { text("channels_search") }
    static var channelsSearchClose: String { text("channels_search_close") }
    static var channelsSearchEmpty: String { text("channels_search_empty") }
    static func channelsChannelDescription(_ p1: String, _ p2: String) -> String { format("channels_channel_description", p1, p2) }
    static var channelsChannelHereSuffix: String { text("channels_channel_here_suffix") }
    static var channelsChannelLockedSuffix: String { text("channels_channel_locked_suffix") }
    static var channelsLocked: String { text("channels_locked") }
    static func channelsCollapse(_ p1: String) -> String { format("channels_collapse", p1) }
    static func channelsExpand(_ p1: String) -> String { format("channels_expand", p1) }
    static var channelsMenu: String { text("channels_menu") }
    static func channelsOnline(_ p1: Int) -> String {
        plural("channels_online", count: p1, p1)
    }
    static var personTalking: String { text("person_talking") }
    static var personMuted: String { text("person_muted") }
    static var personDeafened: String { text("person_deafened") }
    static var personServerMuted: String { text("person_server_muted") }
    static var personStreaming: String { text("person_streaming") }
    static var personCamera: String { text("person_camera") }
    static var personVolume: String { text("person_volume") }
    static func personVolumeValue(_ p1: Int) -> String { format("person_volume_value", p1) }
    static var personMute: String { text("person_mute") }
    static var channelPasswordTitle: String { text("channel_password_title") }
    static func channelPasswordText(_ p1: String) -> String { format("channel_password_text", p1) }
    static var channelPasswordWrong: String { text("channel_password_wrong") }
    static var channelJoin: String { text("channel_join") }
    static var channelCollapse: String { text("channel_collapse") }
    static var channelExpand: String { text("channel_expand") }
    static var deniedTitle: String { text("denied_title") }
    static func deniedTitleChannel(_ p1: String) -> String { format("denied_title_channel", p1) }
    static var deniedText: String { text("denied_text") }
    static var deniedDismiss: String { text("denied_dismiss") }
    static func hintFileTransfer(_ p1: String) -> String { format("hint_file_transfer", p1) }
    static func hintWatchTogether(_ p1: String) -> String { format("hint_watch_together", p1) }
    static func hintPluginContent(_ p1: String) -> String { format("hint_plugin_content", p1) }
    static var tabChannels: String { text("tab_channels") }
    static var tabChat: String { text("tab_chat") }
    static var tabMessages: String { text("tab_messages") }
    static var tabMore: String { text("tab_more") }
    static func moreDisconnect(_ p1: String) -> String { format("more_disconnect", p1) }
    static func channelsPeopleCount(_ p1: Int) -> String {
        plural("channels_people_count", count: p1, p1)
    }

    static var localNetworkTitle: String { text("local_network_title") }
    static func localNetworkText(_ p1: String) -> String { format("local_network_text", p1) }
    static var localNetworkContinue: String { text("local_network_continue") }
    static var localNetworkNotNow: String { text("local_network_not_now") }
    static var localNetworkDenied: String { text("local_network_denied") }
    static var localNetworkSuspected: String { text("local_network_suspected") }
    static var localNetworkOpenSettings: String { text("local_network_open_settings") }
    static var microphoneTitle: String { text("microphone_title") }
    static var microphoneText: String { text("microphone_text") }
    static var microphoneContinue: String { text("microphone_continue") }
    static var microphoneNotNow: String { text("microphone_not_now") }
    static var microphoneDeniedTitle: String { text("microphone_denied_title") }
    static var microphoneDeniedAsk: String { text("microphone_denied_ask") }
    static var microphoneDeniedSettings: String { text("microphone_denied_settings") }
    static var microphoneAllow: String { text("microphone_allow") }
    static var microphoneOpenSettings: String { text("microphone_open_settings") }
    static func voiceStatusConnected(_ p1: String) -> String { format("voice_status_connected", p1) }
    static func voiceStatusReconnecting(_ p1: String) -> String { format("voice_status_reconnecting", p1) }
    static var voiceOpen: String { text("voice_open") }
    static var voiceCollapse: String { text("voice_collapse") }
    static var voiceView: String { text("voice_view") }
    static var voiceTalking: String { text("voice_talking") }
    static var voiceNotTalking: String { text("voice_not_talking") }
    static func talkingAnnouncementOne(_ p1: String) -> String { format("talking_announcement_one", p1) }
    static func talkingAnnouncementTwo(_ p1: String, _ p2: String) -> String { format("talking_announcement_two", p1, p2) }
    /// The plural follows the number of the others, as on Android.
    static func talkingAnnouncementMore(_ p1: String, _ p2: Int) -> String {
        plural("talking_announcement_more", count: p2, p1, p2)
    }
    static var voiceMute: String { text("voice_mute") }
    static var voiceUnmute: String { text("voice_unmute") }
    static var voiceMuteMicrophone: String { text("voice_mute_microphone") }
    static var voiceUnmuteMicrophone: String { text("voice_unmute_microphone") }
    static var voiceMutedByServer: String { text("voice_muted_by_server") }
    static var voiceNoMicrophone: String { text("voice_no_microphone") }
    static var voiceNoMicrophoneAction: String { text("voice_no_microphone_action") }
    static var voiceDeafen: String { text("voice_deafen") }
    static var voiceUndeafen: String { text("voice_undeafen") }
    static var voiceLeave: String { text("voice_leave") }
    static var voiceLeaveServer: String { text("voice_leave_server") }
    static var voiceTalkMode: String { text("voice_talk_mode") }
    static var voiceHoldToTalk: String { text("voice_hold_to_talk") }
    static func voicePersonYou(_ p1: String) -> String { format("voice_person_you", p1) }
    static var voiceLevel: String { text("voice_level") }
    static var voiceLevelHint: String { text("voice_level_hint") }
    static func personWhisper(_ p1: String) -> String { format("person_whisper", p1) }
    static var channelShout: String { text("channel_shout") }
    static func whisperToPerson(_ p1: String) -> String { format("whisper_to_person", p1) }
    static func whisperToChannel(_ p1: String) -> String { format("whisper_to_channel", p1) }
    static var whisperActive: String { text("whisper_active") }
    static var whisperInactive: String { text("whisper_inactive") }
    static var whisperClose: String { text("whisper_close") }

    static func chatTitle(_ p1: String) -> String { format("chat_title", p1) }
    static func chatSubtitle(_ p1: Int, _ p2: String) -> String {
        plural("chat_subtitle", count: p1, p1, p2)
    }
    static func chatPlaceholder(_ p1: String) -> String { format("chat_placeholder", p1) }
    static var chatAddImage: String { text("chat_add_image") }
    static var chatAddGif: String { text("chat_add_gif") }
    static var chatSend: String { text("chat_send") }
    static var chatEmpty: String { text("chat_empty") }
    static var chatLoading: String { text("chat_loading") }
    static var chatLoadFailed: String { text("chat_load_failed") }
    static var chatRetry: String { text("chat_retry") }
    static var chatLoadingOlder: String { text("chat_loading_older") }
    static var chatHistoryStart: String { text("chat_history_start") }
    static var chatDayToday: String { text("chat_day_today") }
    static var chatDayYesterday: String { text("chat_day_yesterday") }
    static func chatNewMessages(_ p1: Int) -> String {
        plural("chat_new_messages", count: p1, p1)
    }
    static func chatTabUnread(_ p1: Int) -> String {
        plural("chat_tab_unread", count: p1, p1)
    }
    static func chatAnnounce(_ p1: String, _ p2: String) -> String { format("chat_announce", p1, p2) }
    static var chatMentionsYou: String { text("chat_mentions_you") }
    static var chatReplyAction: String { text("chat_reply_action") }
    static func chatReplyingTo(_ p1: String) -> String { format("chat_replying_to", p1) }
    static var chatReplyCancel: String { text("chat_reply_cancel") }
    static func chatMentionItem(_ p1: String) -> String { format("chat_mention_item", p1) }
    static var chatImageWord: String { text("chat_image_word") }
    static var chatGifWord: String { text("chat_gif_word") }
    static var chatImageOpenAction: String { text("chat_image_open_action") }
    static var chatGifAddAction: String { text("chat_gif_add_action") }
    static var chatTruncated: String { text("chat_truncated") }
    static var chatSending: String { text("chat_sending") }
    static var chatNotSent: String { text("chat_not_sent") }
    static var chatRefused: String { text("chat_refused") }
    static var chatImageRefused: String { text("chat_image_refused") }
    static var chatDiscard: String { text("chat_discard") }
    static func chatCharactersLeft(_ p1: Int) -> String {
        plural("chat_characters_left", count: p1, p1)
    }
    static func chatCharactersOver(_ p1: Int) -> String {
        plural("chat_characters_over", count: p1, p1)
    }
    static var chatImageUnreadable: String { text("chat_image_unreadable") }
    static var chatNoticeDismiss: String { text("chat_notice_dismiss") }
    static var gifTitle: String { text("gif_title") }
    static var gifHint: String { text("gif_hint") }
    static func gifItem(_ p1: Int) -> String { format("gif_item", p1) }
    static var gifRemove: String { text("gif_remove") }
    static var gifSaved: String { text("gif_saved") }
    static var gifNotSaved: String { text("gif_not_saved") }
    static var imageSourceTitle: String { text("image_source_title") }
    static var imageSourceLibrary: String { text("image_source_library") }
    static var imageSourceCamera: String { text("image_source_camera") }
    static var linkWarningTitle: String { text("link_warning_title") }
    static var linkWarningText: String { text("link_warning_text") }
    static var linkWarningOpen: String { text("link_warning_open") }
    static var viewerTitle: String { text("viewer_title") }
    static var viewerClose: String { text("viewer_close") }
    static var sessionEndedTitle: String { text("session_ended_title") }
    static func sessionEndedText(_ p1: String) -> String { format("session_ended_text", p1) }
    static var sessionEndedReconnect: String { text("session_ended_reconnect") }
    static var sessionEndedClose: String { text("session_ended_close") }

    static var messagesEmpty: String { text("messages_empty") }
    static var messagesSearch: String { text("messages_search") }
    static var messagesSearchClose: String { text("messages_search_close") }
    static var messagesSearchEmpty: String { text("messages_search_empty") }
    static var messagesSection: String { text("messages_section") }
    static var messagesNew: String { text("messages_new") }
    static func messagesRequests(_ p1: Int) -> String { format("messages_requests", p1) }
    static var messagesRequestsSub: String { text("messages_requests_sub") }
    static var messagesUnknownPerson: String { text("messages_unknown_person") }
    static var messagesPreviewPicture: String { text("messages_preview_picture") }
    static var messagesOnline: String { text("messages_online") }
    static func messagesRowUnread(_ p1: Int) -> String {
        plural("messages_row_unread", count: p1, p1)
    }
    static func messagesTabUnread(_ p1: Int) -> String {
        plural("messages_tab_unread", count: p1, p1)
    }
    static var messagesNotificationsOffTitle: String { text("messages_notifications_off_title") }
    static var messagesNotificationsOffText: String { text("messages_notifications_off_text") }
    static var messagesNotificationsOffAction: String { text("messages_notifications_off_action") }
    static var conversationBack: String { text("conversation_back") }
    static func conversationOnline(_ p1: String) -> String { format("conversation_online", p1) }
    static func conversationOffline(_ p1: String) -> String { format("conversation_offline", p1) }
    static var requestsTitle: String { text("requests_title") }
    static var requestsBack: String { text("requests_back") }
    static var requestsHint: String { text("requests_hint") }
    static var requestsChatRequest: String { text("requests_chat_request") }
    static var requestsChatRequestEmpty: String { text("requests_chat_request_empty") }
    static func requestsMoreMessages(_ p1: Int) -> String {
        plural("requests_more_messages", count: p1, p1)
    }
    static var requestsAccept: String { text("requests_accept") }
    static var requestsDecline: String { text("requests_decline") }
    static func requestsAcceptDescription(_ p1: String) -> String { format("requests_accept_description", p1) }
    static func requestsDeclineDescription(_ p1: String) -> String { format("requests_decline_description", p1) }
    static var pickerTitle: String { text("picker_title") }
    static var pickerSearch: String { text("picker_search") }
    static var pickerEmpty: String { text("picker_empty") }
    static var pickerNoMatch: String { text("picker_no_match") }
    static var personMessage: String { text("person_message") }
    static var personChatRequest: String { text("person_chat_request") }
    static var personChatRequestPending: String { text("person_chat_request_pending") }
    static var personBlock: String { text("person_block") }
    static var personUnblock: String { text("person_unblock") }
    static var moderationTitle: String { text("moderation_title") }
    static var moderationMove: String { text("moderation_move") }
    static var moderationServerMute: String { text("moderation_server_mute") }
    static var moderationServerUnmute: String { text("moderation_server_unmute") }
    static var moderationKick: String { text("moderation_kick") }
    static var moderationBan: String { text("moderation_ban") }
    static func moderationPersonLine(_ p1: String, _ p2: String) -> String { format("moderation_person_line", p1, p2) }
    static var moderationReason: String { text("moderation_reason") }
    static var moderationRefusedTitle: String { text("moderation_refused_title") }
    static var moderationRefused: String { text("moderation_refused") }
    static func moderationGone(_ p1: String) -> String { format("moderation_gone", p1) }
    static var moderationDisconnected: String { text("moderation_disconnected") }
    static var moderationFailed: String { text("moderation_failed") }
    static var moveTitle: String { text("move_title") }
    static func moveText(_ p1: String) -> String { format("move_text", p1) }
    static func kickTitle(_ p1: String) -> String { format("kick_title", p1) }
    static var kickReconnect: String { text("kick_reconnect") }
    static func kickReasonHint(_ p1: String) -> String { format("kick_reason_hint", p1) }
    static var kickConfirm: String { text("kick_confirm") }
    static func banTitle(_ p1: String) -> String { format("ban_title", p1) }
    static var banLoading: String { text("ban_loading") }
    static func banReasonHint(_ p1: String) -> String { format("ban_reason_hint", p1) }
    static var banDuration: String { text("ban_duration") }
    static var banDurationHour: String { text("ban_duration_hour") }
    static var banDurationDay: String { text("ban_duration_day") }
    static var banDurationWeek: String { text("ban_duration_week") }
    static var banDurationMonth: String { text("ban_duration_month") }
    static var banDurationPermanent: String { text("ban_duration_permanent") }
    static func banEndsOn(_ p1: String) -> String { format("ban_ends_on", p1) }
    static var banQuick: String { text("ban_quick") }
    static var banQuickTargets: String { text("ban_quick_targets") }
    static var banByCertificate: String { text("ban_by_certificate") }
    static var banByCertificateHint: String { text("ban_by_certificate_hint") }
    static var banNoCertificate: String { text("ban_no_certificate") }
    static var banAddress: String { text("ban_address") }
    static var banRemoveNow: String { text("ban_remove_now") }
    static var banRemoveNowHint: String { text("ban_remove_now_hint") }
    static var banConfirm: String { text("ban_confirm") }
    static func banConfirmFor(_ p1: String) -> String { format("ban_confirm_for", p1) }
    static var banConfirmPermanent: String { text("ban_confirm_permanent") }
    static var banKickInstead: String { text("ban_kick_instead") }
    static var banConflictTitle: String { text("ban_conflict_title") }
    static var banConflictText: String { text("ban_conflict_text") }
    static var banSaveAnyway: String { text("ban_save_anyway") }
    static func chatRequestTitle(_ p1: String) -> String { format("chat_request_title", p1) }
    static func chatRequestText(_ p1: String) -> String { format("chat_request_text", p1) }
    static var chatRequestNote: String { text("chat_request_note") }
    static func chatRequestCounter(_ p1: Int, _ p2: Int) -> String { format("chat_request_counter", p1, p2) }
    static var chatRequestSend: String { text("chat_request_send") }
    static func chatRequestRefused(_ p1: String) -> String { format("chat_request_refused", p1) }
    static var chatRequestFailed: String { text("chat_request_failed") }
    static func blockTitle(_ p1: String) -> String { format("block_title", p1) }
    static func blockPrivate(_ p1: String) -> String { format("block_private", p1) }
    static func blockChannel(_ p1: String) -> String { format("block_channel", p1) }
    static func blockVoice(_ p1: String) -> String { format("block_voice", p1) }
    static var blockSilent: String { text("block_silent") }
    static var blockFailed: String { text("block_failed") }
    static var moreBlocked: String { text("more_blocked") }
    static var moreActivity: String { text("more_activity") }
    static func moreActivityNew(_ p1: Int) -> String {
        plural("more_activity_new", count: p1, p1)
    }
    static var activityTitle: String { text("activity_title") }
    static var activityBack: String { text("activity_back") }
    static var activityFilterAll: String { text("activity_filter_all") }
    static var activityFilterJoins: String { text("activity_filter_joins") }
    static var activityFilterStreams: String { text("activity_filter_streams") }
    static var activityEmpty: String { text("activity_empty") }
    static var activityEmptyText: String { text("activity_empty_text") }
    static var activityEmptyFilter: String { text("activity_empty_filter") }
    static var activityYou: String { text("activity_you") }
    // The sentences of the activity log are templates: `activitySentence` puts the name and
    // the place in, so that the name can be bold.
    static var activityConnected: String { text("activity_connected") }
    static var activityJoined: String { text("activity_joined") }
    static var activityJoinedServer: String { text("activity_joined_server") }
    static var activityMoved: String { text("activity_moved") }
    static var activityMovedElsewhere: String { text("activity_moved_elsewhere") }
    static var activityLeft: String { text("activity_left") }
    static var activityStreamStarted: String { text("activity_stream_started") }
    static var activityStreamStartedElsewhere: String { text("activity_stream_started_elsewhere") }
    static var activityStreamStopped: String { text("activity_stream_stopped") }
    static var blockedTitle: String { text("blocked_title") }
    static var blockedBack: String { text("blocked_back") }
    static var blockedEmpty: String { text("blocked_empty") }
    static func blockedUnblockDescription(_ p1: String) -> String { format("blocked_unblock_description", p1) }
    static var notificationsTitle: String { text("notifications_title") }
    static var notificationsText: String { text("notifications_text") }
    static var notificationsContinue: String { text("notifications_continue") }
    static var notificationsNotNow: String { text("notifications_not_now") }
    static var messageNew: String { text("message_new") }
    static func outputChip(_ p1: String) -> String { format("output_chip", p1) }
    static var outputTitle: String { text("output_title") }
    static var outputSpeaker: String { text("output_speaker") }
    static var outputEarpiece: String { text("output_earpiece") }
    static var outputWired: String { text("output_wired") }
    static var outputBluetooth: String { text("output_bluetooth") }
    static var outputNotConnected: String { text("output_not_connected") }
    static var outputNote: String { text("output_note") }
    static var outputQuestionTitle: String { text("output_question_title") }
    static var outputQuestionText: String { text("output_question_text") }
    static var outputQuestionSpeaker: String { text("output_question_speaker") }
    static var outputQuestionEarpiece: String { text("output_question_earpiece") }
    static var sessionEndedAloneTitle: String { text("session_ended_alone_title") }
    static func sessionEndedAloneText(_ p1: String) -> String { format("session_ended_alone_text", p1) }
    static var settingsTitle: String { text("settings_title") }
    static var settingsBack: String { text("settings_back") }
    static var settingsSectionVoice: String { text("settings_section_voice") }
    static var settingsNoiseSuppression: String { text("settings_noise_suppression") }
    static var settingsDefaultOutput: String { text("settings_default_output") }
    static var settingsOutputAsk: String { text("settings_output_ask") }
    static var settingsOutputNote: String { text("settings_output_note") }
    static var settingsSectionNotifications: String { text("settings_section_notifications") }
    static var settingsNotifyPrivate: String { text("settings_notify_private") }
    static var settingsNotifyMentions: String { text("settings_notify_mentions") }
    static var settingsSenderOnly: String { text("settings_sender_only") }
    static var settingsNotifyNote: String { text("settings_notify_note") }
    static var settingsSectionChat: String { text("settings_section_chat") }
    static var settingsHistory: String { text("settings_history") }
    static var settingsHistoryNever: String { text("settings_history_never") }
    static func settingsHistoryDays(_ p1: Int) -> String {
        plural("settings_history_days", count: p1, p1)
    }
    static var settingsHistoryNote: String { text("settings_history_note") }
    static var settingsSectionMobileData: String { text("settings_section_mobile_data") }
    static var settingsAutoplayStreams: String { text("settings_autoplay_streams") }
    static var settingsLoadMedia: String { text("settings_load_media") }
    static var settingsReducedVoice: String { text("settings_reduced_voice") }
    static var settingsSectionBattery: String { text("settings_section_battery") }
    static var settingsIdleDisconnect: String { text("settings_idle_disconnect") }
    static var settingsIdleDisconnectSub: String { text("settings_idle_disconnect_sub") }
    static var idleNotificationTexts: IdleNotificationTexts {
        IdleNotificationTexts(
            warningTitle: text("idle_warning_title"),
            warningText: text("idle_warning_text"),
            stayConnected: text("idle_stay_connected"),
            disconnectedTitle: text("idle_disconnected_title"),
            disconnectedText: text("idle_disconnected_text")
        )
    }

    static var cameraTitle: String { text("camera_title") }
    static var cameraBlockedText: String { text("camera_blocked_text") }
    static var cameraContinue: String { text("camera_continue") }
    static var cameraNotNow: String { text("camera_not_now") }
    static var cameraOpenSettings: String { text("camera_open_settings") }
    static var cameraAllow: String { text("camera_allow") }
    static var cameraReasonPhoto: String { text("camera_reason_photo") }
    static var cameraReasonScan: String { text("camera_reason_scan") }
    static var identitiesTitle: String { text("identities_title") }
    static var identitiesIntro: String { text("identities_intro") }
    static var identitiesSectionYours: String { text("identities_section_yours") }
    static var identitiesSectionAdd: String { text("identities_section_add") }
    static func identitiesDefault(_ p1: String) -> String { format("identities_default", p1) }
    static func identitiesUsedOn(_ p1: Int) -> String {
        plural("identities_used_on", count: p1, p1)
    }
    static var identitiesUnused: String { text("identities_unused") }
    static func identitiesFingerprint(_ p1: String) -> String { format("identities_fingerprint", p1) }
    static func identitiesCardDescription(_ p1: String, _ p2: String) -> String { format("identities_card_description", p1, p2) }
    static var identitiesScan: String { text("identities_scan") }
    static var identitiesScanTag: String { text("identities_scan_tag") }
    static var identitiesScanSub: String { text("identities_scan_sub") }
    static var identitiesImport: String { text("identities_import") }
    static var identitiesImportSub: String { text("identities_import_sub") }
    static var identitiesCreate: String { text("identities_create") }
    static var identitiesCreateSub: String { text("identities_create_sub") }
    static var identitiesFileUnreadable: String { text("identities_file_unreadable") }
    static var identitySetupTitle: String { text("identity_setup_title") }
    static var identitySetupText: String { text("identity_setup_text") }
    static var identityDetailsName: String { text("identity_details_name") }
    static var identityDetailsFingerprint: String { text("identity_details_fingerprint") }
    static var identityDetailsAdded: String { text("identity_details_added") }
    static var identityDetailsDefault: String { text("identity_details_default") }
    static var identitySetDefault: String { text("identity_set_default") }
    static var identityExport: String { text("identity_export") }
    static var identityDelete: String { text("identity_delete") }
    static var identityDeleteLast: String { text("identity_delete_last") }
    static var identityCreateTitle: String { text("identity_create_title") }
    static var identityCreateText: String { text("identity_create_text") }
    static var identityCreateName: String { text("identity_create_name") }
    static var identityCreateNameInvalid: String { text("identity_create_name_invalid") }
    static var identityCreateConfirm: String { text("identity_create_confirm") }
    static var identityImportTitle: String { text("identity_import_title") }
    static var identityImportText: String { text("identity_import_text") }
    static var identityImportPassword: String { text("identity_import_password") }
    static var identityImportConfirm: String { text("identity_import_confirm") }
    static var identityImportWrong: String { text("identity_import_wrong") }
    static var identityImportFailed: String { text("identity_import_failed") }
    static func identityExportTitle(_ p1: String) -> String { format("identity_export_title", p1) }
    static var identityExportText: String { text("identity_export_text") }
    static var identityExportPassword: String { text("identity_export_password") }
    static var identityExportRepeat: String { text("identity_export_repeat") }
    static var identityExportMismatch: String { text("identity_export_mismatch") }
    static var identityExportFailed: String { text("identity_export_failed") }
    static var identityExportConfirm: String { text("identity_export_confirm") }
    static var identityExportShare: String { text("identity_export_share") }
    static func identityDeleteTitle(_ p1: String) -> String { format("identity_delete_title", p1) }
    static var identityDeleteWarning: String { text("identity_delete_warning") }
    static func identityDeleteServers(_ p1: Int, _ p2: String) -> String {
        plural("identity_delete_servers", count: p1, p1, p2)
    }
    static func identityDeleteNextDefault(_ p1: String) -> String { format("identity_delete_next_default", p1) }
    static var identityDeleteBackup: String { text("identity_delete_backup") }
    static var identityDeleteType: String { text("identity_delete_type") }
    static var scanTitle: String { text("scan_title") }
    static func scanProgress(_ p1: Int, _ p2: Int) -> String { format("scan_progress", p1, p2) }
    static var scanProgressNone: String { text("scan_progress_none") }
    static func scanPercent(_ p1: Int) -> String { format("scan_percent", p1) }
    static var scanHint: String { text("scan_hint") }
    static var scanHintWhere: String { text("scan_hint_where") }
    static var scanAllScanned: String { text("scan_all_scanned") }
    static var scanViewfinder: String { text("scan_viewfinder") }
    static var scanCodeLabel: String { text("scan_code_label") }
    static func scanCodeDescription(_ p1: Int) -> String {
        plural("scan_code_description", count: p1, p1)
    }
    static var scanChecking: String { text("scan_checking") }
    static func scanWrongCode(_ p1: Int) -> String {
        plural("scan_wrong_code", count: p1, p1)
    }
    static var scanCameraDenied: String { text("scan_camera_denied") }
    static var scanCameraBlocked: String { text("scan_camera_blocked") }
    static var scanNoteNotATransfer: String { text("scan_note_not_a_transfer") }
    static var scanNoteUnreadable: String { text("scan_note_unreadable") }
    static var scanNoteNewerVersion: String { text("scan_note_newer_version") }
    static var scanNoteExpired: String { text("scan_note_expired") }
    static var scanNoteClockSkew: String { text("scan_note_clock_skew") }
    static var scanNoteRescan: String { text("scan_note_rescan") }
    static var scanNoteDamaged: String { text("scan_note_damaged") }
    static var scanNoteOutOfMemory: String { text("scan_note_out_of_memory") }
    static var scanNoteFailed: String { text("scan_note_failed") }
    static var scanNoteNoCamera: String { text("scan_note_no_camera") }
    static var scanReceiving: String { text("scan_receiving") }
    static func scanReceivingIdentities(_ p1: Int) -> String {
        plural("scan_receiving_identities", count: p1, p1)
    }
    static func scanReceivingServers(_ p1: Int) -> String {
        plural("scan_receiving_servers", count: p1, p1)
    }
    static func scanReceivingBoth(_ p1: String, _ p2: String) -> String { format("scan_receiving_both", p1, p2) }
    static var scanSectionIdentities: String { text("scan_section_identities") }
    static var scanSectionServers: String { text("scan_section_servers") }
    static var scanNoPasswords: String { text("scan_no_passwords") }
    static var scanImport: String { text("scan_import") }
    static var scanImporting: String { text("scan_importing") }
    static var scanDoneTitle: String { text("scan_done_title") }
    static var scanDoneNothing: String { text("scan_done_nothing") }
    static var scanDoneAdded: String { text("scan_done_added") }
    static var scanSectionSkipped: String { text("scan_section_skipped") }
    static var scanSkippedDuplicate: String { text("scan_skipped_duplicate") }
    static var scanSkippedInvalid: String { text("scan_skipped_invalid") }
    static var scanDone: String { text("scan_done") }

    // MARK: Streams

    static func streamTitleScreen(_ p1: String) -> String { format("stream_title_screen", p1) }
    static func streamTitleCamera(_ p1: String) -> String { format("stream_title_camera", p1) }
    static var streamLive: String { text("stream_live") }
    static func streamWatching(_ p1: Int) -> String {
        plural("stream_watching", count: p1, p1)
    }
    static func streamViewers(_ p1: Int) -> String {
        plural("stream_viewers", count: p1, p1)
    }
    static func streamTileDescription(_ p1: String) -> String { format("stream_tile_description", p1) }
    static func streamDescriptionPart(_ p1: String, _ p2: String) -> String { format("stream_description_part", p1, p2) }
    static var streamTapToWatch: String { text("stream_tap_to_watch") }
    static var streamConnecting: String { text("stream_connecting") }
    static var streamPaused: String { text("stream_paused") }
    static var streamOpenElsewhere: String { text("stream_open_elsewhere") }
    static var streamRotateHint: String { text("stream_rotate_hint") }
    static var streamCollapse: String { text("stream_collapse") }
    static var streamRotateLandscape: String { text("stream_rotate_landscape") }
    static var streamRotatePortrait: String { text("stream_rotate_portrait") }
    static var streamStop: String { text("stream_stop") }
    static var streamExpand: String { text("stream_expand") }
    static var streamLeaveFullScreen: String { text("stream_leave_full_screen") }
    static func streamChatStrip(_ p1: String) -> String { format("stream_chat_strip", p1) }
    static var streamChatStripOpen: String { text("stream_chat_strip_open") }
    static var streamVolume: String { text("stream_volume") }
    static var streamTryAgain: String { text("stream_try_again") }
    static var streamUnreachableNoPath: String { text("stream_unreachable_no_path") }
    static var streamUnreachablePeerToPeerOff: String { text("stream_unreachable_peer_to_peer_off") }
    static var streamUnreachableDirectDeclined: String { text("stream_unreachable_direct_declined") }
    static var streamUnreachableRelayUnavailable: String { text("stream_unreachable_relay_unavailable") }
    static func streamUnreachableSharerUnsupported(_ p1: String) -> String { format("stream_unreachable_sharer_unsupported", p1) }
    static func streamUnreachableNoAnswer(_ p1: String) -> String { format("stream_unreachable_no_answer", p1) }
    static func streamUnreachableFull(_ p1: String) -> String { format("stream_unreachable_full", p1) }
    static func streamUnreachableNeedsUpdate(_ p1: String) -> String { format("stream_unreachable_needs_update", p1) }
    static func streamUnreachableJoinFailed(_ p1: String) -> String { format("stream_unreachable_join_failed", p1) }
    static var directConnectionsTitle: String { text("direct_connections_title") }
    static var directConnectionsBody: String { text("direct_connections_body") }
    static var directConnectionsNo: String { text("direct_connections_no") }
    static var directConnectionsYes: String { text("direct_connections_yes") }

    // MARK: Sharing the own camera

    static var cameraReasonShare: String { text("camera_reason_share") }
    static var cameraShareButton: String { text("camera_share_button") }
    static var cameraShareTitle: String { text("camera_share_title") }
    static var cameraSharePreview: String { text("camera_share_preview") }
    static var cameraShareFront: String { text("camera_share_front") }
    static var cameraShareBack: String { text("camera_share_back") }
    static var cameraShareSwitch: String { text("camera_share_switch") }
    static var cameraSharePauses: String { text("camera_share_pauses") }
    static var cameraShareStart: String { text("camera_share_start") }
    static var cameraShareStarting: String { text("camera_share_starting") }
    static var cameraShareCancel: String { text("camera_share_cancel") }
    static func cameraOnWatching(_ p1: Int) -> String {
        plural("camera_on_watching", count: p1, p1)
    }
    static var cameraStop: String { text("camera_stop") }
    static var cameraStopSharing: String { text("camera_stop_sharing") }
    static var cameraStopped: String { text("camera_stopped") }
    static var cameraNoticeDismiss: String { text("camera_notice_dismiss") }
    static var cameraProblemNotConnected: String { text("camera_problem_not_connected") }
    static var cameraProblemBusy: String { text("camera_problem_busy") }
    static var cameraProblemPermission: String { text("camera_problem_permission") }
    static var cameraProblemFailed: String { text("camera_problem_failed") }
    static var cameraProblemDirectOff: String { text("camera_problem_direct_off") }
    static var cameraProblemNoCertificate: String { text("camera_problem_no_certificate") }
    static var cameraProblemFull: String { text("camera_problem_full") }
    static var cameraProblemRelayLost: String { text("camera_problem_relay_lost") }
    static var cameraProblemRefused: String { text("camera_problem_refused") }

    // MARK: Profile and themes

    static var moreEditProfile: String { text("more_edit_profile") }
    static var moreThemes: String { text("more_themes") }
    static var profileTitle: String { text("profile_title") }
    static var profileSave: String { text("profile_save") }
    static var profileChangePhoto: String { text("profile_change_photo") }
    static var profileName: String { text("profile_name") }
    static var profileNameHint: String { text("profile_name_hint") }
    static var profileComment: String { text("profile_comment") }
    /// The counter's numbers are written without a thousands separator, as on Android.
    static func profileCommentCount(_ p1: Int, _ p2: Int) -> String { ungrouped("profile_comment_count", p1, p2) }
    /// The plural form follows the limit, as on Android. The numbers are passed as text, so that
    /// they are written without a thousands separator while the plural follows the language.
    static func profileCommentCountDescription(_ p1: Int, _ p2: Int) -> String {
        plural("profile_comment_count_description", count: p2, String(p1), String(p2))
    }
    static var profileTooLong: String { text("profile_too_long") }
    static var profilePreview: String { text("profile_preview") }
    static var profileYou: String { text("profile_you") }
    static var profileAvatarTitle: String { text("profile_avatar_title") }
    static var profileAvatarRemove: String { text("profile_avatar_remove") }
    static var profileAvatarUnreadable: String { text("profile_avatar_unreadable") }
    static var profileSaveFailed: String { text("profile_save_failed") }
    static var cameraReasonAvatar: String { text("camera_reason_avatar") }
    static var themesTitle: String { text("themes_title") }
    static var themesAppearance: String { text("themes_appearance") }
    static var themesModeSystem: String { text("themes_mode_system") }
    static var themesModeDark: String { text("themes_mode_dark") }
    static var themesModeLight: String { text("themes_mode_light") }
    static var themesTheme: String { text("themes_theme") }
    static var themesYours: String { text("themes_yours") }
    static func themesItem(_ p1: String) -> String { format("themes_item", p1) }
    static func themesItemSelected(_ p1: String) -> String { format("themes_item_selected", p1) }
    static var themesShare: String { text("themes_share") }
    static var themesDelete: String { text("themes_delete") }
    static func themesDeleteTitle(_ p1: String) -> String { format("themes_delete_title", p1) }
    static var themesDeleteMessage: String { text("themes_delete_message") }
    static var themesDeleteConfirm: String { text("themes_delete_confirm") }
    static func themesServerNote(_ p1: String, _ p2: String) -> String { format("themes_server_note", p1, p2) }
    static var themesPaste: String { text("themes_paste") }
    static var themesImportTitle: String { text("themes_import_title") }
    static var themesImportLabel: String { text("themes_import_label") }
    static var themesImportHint: String { text("themes_import_hint") }
    static var themesImportInvalid: String { text("themes_import_invalid") }
    static var themesImportUnreadable: String { text("themes_import_unreadable") }
    static var themesImported: String { text("themes_imported") }
    static func themesMadeWithNewer(_ p1: String) -> String { format("themes_made_with_newer", p1) }
    static var themesApply: String { text("themes_apply") }
    static var serverTheme: String { text("server_theme") }
    static func serverThemeTitle(_ p1: String) -> String { format("server_theme_title", p1) }
    static var serverThemeUseApp: String { text("server_theme_use_app") }
    static var serverThemeHint: String { text("server_theme_hint") }
    static var settingsSectionLanguage: String { text("settings_section_language") }
    static var settingsLanguage: String { text("settings_language") }
    static var settingsLanguageSystem: String { text("settings_language_system") }

    private static func text(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }

    private static func plural(_ key: String, count: Int, _ arguments: CVarArg...) -> String {
        pluralText(key, count: count, arguments: arguments)
    }

    /// A text of `Localizable.stringsdict` in the form for `count`. Foundation picks the form by
    /// the plural rules of the locale's language, so the locale must be the one of the texts.
    static func pluralText(
        _ key: String,
        count: Int,
        arguments: [CVarArg],
        bundle: Bundle = .main,
        locale: Locale = locale
    ) -> String {
        String(format: bundle.localizedString(forKey: key, value: nil, table: nil), locale: locale, arguments: arguments + [count])
    }

    /// The language the app shows its texts in (the system's choice among the app's languages)
    /// with the phone's region, whose number formats stay.
    static var locale: Locale {
        textLocale(language: Bundle.main.preferredLocalizations.first ?? "en", region: Locale.current.region)
    }

    static func textLocale(language: String, region: Locale.Region?) -> Locale {
        Locale(identifier: region.map { "\(language)_\($0.identifier)" } ?? language)
    }

    private static func ungrouped(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: text(key), arguments: arguments)
    }
}
