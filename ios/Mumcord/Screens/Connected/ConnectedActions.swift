// SPDX-License-Identifier: AGPL-3.0-only

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
            dismissDenied: model.dismissDenied
        )
    }
}
