// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// What the connected screens can ask for, as closures, so the views stay free of the model.
struct ConnectedActions {
    let selectTab: (ConnectedTab) -> Void
    let toggleCollapsed: (UInt32) -> Void
    let join: (UInt32) -> Void
    let openChannelMenu: (UInt32) -> Void
    let openSearch: () -> Void
    let closeSearch: () -> Void
    let changeQuery: (String) -> Void
    let openPerson: (UInt32) -> Void
    let changeVolume: (Int) -> Void
    let setLocalMute: (Bool) -> Void
    let changePassword: (String) -> Void
    let submitPassword: () -> Void
    let dismissSheet: () -> Void
    let dismissDenied: () -> Void
    let openVoice: () -> Void
    let closeVoice: () -> Void
    let toggleMute: () -> Void
    let toggleDeafen: () -> Void
    let selectTalkMode: (TalkMode) -> Void
    let setTalkHeld: (Bool) -> Void
    let openWhisper: (WhisperTarget) -> Void
    let setWhisperHeld: (Bool) -> Void
    let closeWhisper: () -> Void
    let openOutput: () -> Void
    let selectOutput: (String) -> Void
    let answerOutputQuestion: (DefaultOutput) -> Void
    let dismissOutputQuestion: () -> Void
    /// Lets the system ask for the microphone, or opens the system settings once it no longer asks.
    let requestMicrophone: () -> Void
    let microphoneNotNow: () -> Void
    let askNotifications: () -> Void
    let notificationsNotNow: () -> Void
    /// Move, server mute, kick and ban of the person whose sheet is open.
    let moderation: ModerationActions
    /// The microphone level; changes often, so only the level meter reads it.
    let inputLevel: InputLevel
}

/// What the moderation section of the person sheet and its steps can ask for.
struct ModerationActions {
    let openMove: () -> Void
    let move: (UInt32) -> Void
    let toggleServerMute: () -> Void
    let openKick: () -> Void
    let changeKickReason: (String) -> Void
    let confirmKick: () -> Void
    let openBan: () -> Void
    let changeBanReason: (String) -> Void
    let selectBanDuration: (UInt32) -> Void
    let setBanByCertificate: (Bool) -> Void
    let setBanRemoveNow: (Bool) -> Void
    let kickInstead: () -> Void
    let confirmBan: () -> Void
    let banAnyway: () -> Void
}

extension ConnectedActions {
    @MainActor
    init(model: AppModel) {
        self.init(
            selectTab: model.selectTab,
            toggleCollapsed: model.toggleCollapsed,
            join: model.join,
            openChannelMenu: model.openChannelMenu,
            openSearch: model.openSearch,
            closeSearch: model.closeSearch,
            changeQuery: model.changeQuery,
            openPerson: model.openPerson,
            changeVolume: model.changeVolume,
            setLocalMute: model.setLocalMute,
            changePassword: model.changeChannelPassword,
            submitPassword: model.submitChannelPassword,
            dismissSheet: model.dismissSheet,
            dismissDenied: model.dismissDenied,
            openVoice: model.openVoice,
            closeVoice: model.closeVoice,
            toggleMute: model.toggleMute,
            toggleDeafen: model.toggleDeafen,
            selectTalkMode: model.switchTalkMode,
            setTalkHeld: model.setTalkHeld,
            openWhisper: model.openWhisper,
            setWhisperHeld: model.setWhisperHeld,
            closeWhisper: model.closeWhisper,
            openOutput: model.openOutput,
            selectOutput: model.selectOutput,
            answerOutputQuestion: model.answerOutputQuestion,
            dismissOutputQuestion: model.dismissOutputQuestion,
            requestMicrophone: model.requestMicrophone,
            microphoneNotNow: model.microphoneNotNow,
            askNotifications: model.askNotifications,
            notificationsNotNow: model.notificationsNotNow,
            moderation: ModerationActions(
                openMove: model.openMove,
                move: model.movePerson,
                toggleServerMute: model.toggleServerMute,
                openKick: model.openKick,
                changeKickReason: model.changeKickReason,
                confirmKick: model.confirmKick,
                openBan: model.openBan,
                changeBanReason: model.changeBanReason,
                selectBanDuration: model.selectBanDuration,
                setBanByCertificate: model.setBanByCertificate,
                setBanRemoveNow: model.setBanRemoveNow,
                kickInstead: model.kickInstead,
                confirmBan: model.confirmBan,
                banAnyway: model.banAnyway
            ),
            inputLevel: model.inputLevel
        )
    }
}
