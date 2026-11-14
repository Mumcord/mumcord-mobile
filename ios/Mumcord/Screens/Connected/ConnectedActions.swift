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
    /// Lets the system ask for the microphone, or opens the system settings once it no longer asks.
    let requestMicrophone: () -> Void
    let microphoneNotNow: () -> Void
    /// The microphone level; changes often, so only the level meter reads it.
    let inputLevel: InputLevel
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
            requestMicrophone: model.requestMicrophone,
            microphoneNotNow: model.microphoneNotNow,
            inputLevel: model.inputLevel
        )
    }
}
