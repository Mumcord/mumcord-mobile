// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// Typed access to the texts in `Localizable.strings`. The keys equal the string names of the
/// Android app, so both apps are translated from the same list.
///
/// Plural forms are picked here between `.one` and `.other`, which is enough for English; the
/// language task replaces this with the plural rules of each language.
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
        format(p1 == 1 ? "servers_online.one" : "servers_online.other", p1, p2)
    }
    static var serversOffline: String { text("servers_offline") }
    static var serversAdd: String { text("servers_add") }
    static var serversEmptyTitle: String { text("servers_empty_title") }
    static var serversEmptyText: String { text("servers_empty_text") }
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
        format(p1 == 1 ? "channels_online.one" : "channels_online.other", p1)
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
    static var tabChatEmpty: String { text("tab_chat_empty") }
    static var tabMessagesEmpty: String { text("tab_messages_empty") }
    static func moreDisconnect(_ p1: String) -> String { format("more_disconnect", p1) }
    static func channelsPeopleCount(_ p1: Int) -> String {
        format(p1 == 1 ? "channels_people_count.one" : "channels_people_count.other", p1)
    }

    private static func text(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: Locale.current, arguments: arguments)
    }
}
