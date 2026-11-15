// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// The settings (mockup `16-settings`), as far as they exist: the sections "Mobile data"
/// (FR-5.41) and "Battery" (FR-5.42). Every switch is saved at once.
struct SettingsScreen: View {
    let settings: MobileSettings
    let onChange: (@escaping @Sendable (inout MobileSettings) -> Void) -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.settingsTitle, onBack: onBack, backLabel: Strings.settingsBack)
            ScrollView {
                VStack(spacing: 0) {
                    MumcordSectionHeader(title: Strings.settingsSectionMobileData)
                    MumcordList {
                        SettingsSwitchRow(
                            title: Strings.settingsAutoplayStreams,
                            icon: MumcordIcons.monitor,
                            checked: settings.mobileDataAutoplayStreams,
                            id: "settings-autoplay-streams"
                        ) { on in onChange { $0.mobileDataAutoplayStreams = on } }
                        SettingsSwitchRow(
                            title: Strings.settingsLoadMedia,
                            icon: MumcordIcons.image,
                            checked: settings.mobileDataLoadMedia,
                            id: "settings-load-media",
                            divider: true
                        ) { on in onChange { $0.mobileDataLoadMedia = on } }
                        SettingsSwitchRow(
                            title: Strings.settingsReducedVoice,
                            icon: MumcordIcons.wifi,
                            checked: settings.mobileDataReducedVoice,
                            id: "settings-reduced-voice",
                            divider: true
                        ) { on in onChange { $0.mobileDataReducedVoice = on } }
                    }
                    MumcordSectionHeader(title: Strings.settingsSectionBattery)
                    MumcordList {
                        SettingsSwitchRow(
                            title: Strings.settingsIdleDisconnect,
                            subtitle: Strings.settingsIdleDisconnectSub,
                            icon: MumcordIcons.phoneOff,
                            checked: settings.idleDisconnect,
                            id: "settings-idle-disconnect"
                        ) { on in onChange { $0.idleDisconnect = on } }
                    }
                }
                .padding(.bottom, MumcordSpace.s4)
            }
        }
        .accessibilityIdentifier("settings")
    }
}

private struct SettingsSwitchRow: View {
    let title: String
    var subtitle: String?
    let icon: MumcordIconData
    let checked: Bool
    let id: String
    var divider = false
    let onCheckedChange: (Bool) -> Void

    var body: some View {
        MumcordListRow(
            title: title,
            subtitle: subtitle,
            icon: icon,
            divider: divider,
            trailing: {
                MumcordToggle(checked: checked, label: title, onCheckedChange: onCheckedChange)
                    .accessibilityIdentifier(id)
            }
        )
        // Nothing is cut off, also with a large font.
        .wrapping()
    }
}
