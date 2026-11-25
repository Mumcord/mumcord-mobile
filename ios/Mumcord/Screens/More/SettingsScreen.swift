// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

private let levelMeterWidth: CGFloat = 120
private let settingsFigureSize: CGFloat = 30
/// The talk modes and default outputs in the order of their sheets.
private let talkModes: [TalkMode] = [.voiceActivity, .pushToTalk]
private let defaultOutputs: [DefaultOutput] = [.ask, .speaker, .earpiece]

/// The settings (mockup `16-settings`, FR-5.100). Every switch is saved at once; the rows with a
/// value open a sheet with the choices. Left out until their features exist: echo cancellation
/// (the phone's own voice processing does it, there is nothing to switch), link previews (the
/// chat shows none yet) and about. Dark, light or system is chosen on the themes screen (mockup
/// `19-themes`). The level meter, the "Chat" and "Language" sections and the note under
/// "Notifications" have no mockup; the language is worded as in the desktop app. iOS lets the
/// user pick an app's language only in the system settings, so the language row opens them there
/// instead of a sheet of languages as on Android. Opened from the server list, without a
/// connection, the level meter and "Default audio output" are left out: both need a running
/// voice session.
struct SettingsScreen: View {
    let state: SettingsUiState
    let actions: MoreController
    let inputLevel: InputLevel

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.settingsTitle, onBack: actions.back, backLabel: Strings.settingsBack)
            ScrollView {
                // At least as tall as the list's own space, so the figure below never shows at rest.
                ZStack(alignment: .top) {
                    Color.clear.containerRelativeFrame(.vertical)
                    VStack(spacing: 0) {
                        voice
                        mobileData
                        MumcordSectionHeader(title: Strings.settingsSectionBattery)
                        MumcordList {
                            SettingsSwitchRow(
                                title: Strings.settingsIdleDisconnect,
                                subtitle: Strings.settingsIdleDisconnectSub,
                                icon: MumcordIcons.phoneOff,
                                checked: state.mobile.idleDisconnect,
                                id: "settings-idle-disconnect"
                            ) { on in actions.changeMobile { $0.idleDisconnect = on } }
                        }
                        notifications
                        MumcordSectionHeader(title: Strings.settingsSectionChat)
                        MumcordList {
                            SettingsValueRow(
                                title: Strings.settingsHistory,
                                value: historyAgeText(state.app.historyMaxAgeDays),
                                icon: MumcordIcons.trash,
                                id: "settings-history"
                            ) { actions.openSheet(.historyAge) }
                        }
                        MumcordSectionHeader(title: Strings.settingsSectionLanguage)
                        MumcordList {
                            SettingsValueRow(
                                title: Strings.settingsLanguage,
                                value: languageText(state.app.language),
                                icon: MumcordIcons.globe,
                                id: "settings-language"
                            ) { actions.openLanguageSettings() }
                        }
                    }
                    .padding(.bottom, MumcordSpace.s4)
                }
                // A small hidden figure below the end of the settings: it shows only while the
                // list is pulled past its end. Decorative, it has no function.
                .overlay(alignment: .bottom) {
                    Image("SettingsFigure")
                        .resizable()
                        .interpolation(.none)
                        .frame(width: settingsFigureSize, height: settingsFigureSize)
                        .offset(y: settingsFigureSize + MumcordSpace.s2)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                }
            }
        }
        .accessibilityIdentifier("settings")
    }

    @ViewBuilder private var voice: some View {
        MumcordSectionHeader(title: Strings.settingsSectionVoice)
        MumcordList {
            SettingsValueRow(
                title: Strings.voiceTalkMode,
                value: talkModeText(state.talkMode),
                icon: MumcordIcons.mic,
                id: "settings-talk-mode"
            ) { actions.openSheet(.talkMode) }
            if let noiseSuppression = state.noiseSuppression {
                SettingsSwitchRow(
                    title: Strings.settingsNoiseSuppression,
                    icon: MumcordIcons.activity,
                    checked: noiseSuppression,
                    id: "settings-noise-suppression",
                    divider: true,
                    onCheckedChange: actions.setNoiseSuppression
                )
            }
            // The level only moves while the voice runs: not on the server list's More screen.
            if state.connected {
                MumcordListRow(
                    title: Strings.voiceLevel,
                    icon: MumcordIcons.volume,
                    divider: true,
                    trailing: { SettingsLevelMeter(level: inputLevel) }
                )
            }
            if state.hasEarpiece {
                SettingsValueRow(
                    title: Strings.settingsDefaultOutput,
                    value: defaultOutputText(state.mobile.defaultOutput),
                    icon: MumcordIcons.speaker,
                    id: "settings-default-output",
                    divider: true
                ) { actions.openSheet(.defaultOutput) }
            }
        }
    }

    @ViewBuilder private var mobileData: some View {
        MumcordSectionHeader(title: Strings.settingsSectionMobileData)
        MumcordList {
            SettingsSwitchRow(
                title: Strings.settingsAutoplayStreams,
                icon: MumcordIcons.monitor,
                checked: state.mobile.mobileDataAutoplayStreams,
                id: "settings-autoplay-streams"
            ) { on in actions.changeMobile { $0.mobileDataAutoplayStreams = on } }
            SettingsSwitchRow(
                title: Strings.settingsLoadMedia,
                icon: MumcordIcons.image,
                checked: state.mobile.mobileDataLoadMedia,
                id: "settings-load-media",
                divider: true
            ) { on in actions.changeMobile { $0.mobileDataLoadMedia = on } }
            SettingsSwitchRow(
                title: Strings.settingsReducedVoice,
                icon: MumcordIcons.wifi,
                checked: state.mobile.mobileDataReducedVoice,
                id: "settings-reduced-voice",
                divider: true
            ) { on in actions.changeMobile { $0.mobileDataReducedVoice = on } }
        }
    }

    @ViewBuilder private var notifications: some View {
        MumcordSectionHeader(title: Strings.settingsSectionNotifications)
        MumcordList {
            SettingsSwitchRow(
                title: Strings.settingsNotifyPrivate,
                icon: MumcordIcons.message,
                checked: state.app.notifyPrivateMessages,
                id: "settings-notify-private"
            ) { on in actions.changeApp { $0.notifyPrivateMessages = on } }
            SettingsSwitchRow(
                title: Strings.settingsNotifyMentions,
                icon: MumcordIcons.bell,
                checked: state.app.notifyMentions,
                id: "settings-notify-mentions",
                divider: true
            ) { on in actions.changeApp { $0.notifyMentions = on } }
            SettingsSwitchRow(
                title: Strings.settingsSenderOnly,
                icon: MumcordIcons.lock,
                checked: state.senderOnly,
                id: "settings-sender-only",
                divider: true,
                onCheckedChange: actions.setSenderOnly
            )
        }
        // FR-5.54: there is no push server, so the settings say when notifications come.
        MumcordNote(text: Strings.settingsNotifyNote)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: MumcordSpace.s2, leading: MumcordSpace.s4, bottom: 0, trailing: MumcordSpace.s4))
    }
}

/// The sheet of a setting with choices; picking one saves it and closes the sheet.
struct SettingsSheetHost: View {
    let state: SettingsUiState
    let actions: MoreController

    var body: some View {
        switch state.sheet {
        case .talkMode:
            SettingsChoiceSheet(
                title: Strings.voiceTalkMode,
                options: talkModes.map {
                    SettingsChoice(title: talkModeText($0), icon: MumcordIcons.mic, selected: $0 == state.talkMode)
                },
                onSelect: { actions.selectTalkMode(talkModes[$0]) },
                onDismiss: actions.closeSheet
            )
        case .defaultOutput:
            SettingsChoiceSheet(
                title: Strings.settingsDefaultOutput,
                options: defaultOutputs.map {
                    SettingsChoice(
                        title: defaultOutputText($0),
                        icon: defaultOutputIcon($0),
                        selected: $0 == state.mobile.defaultOutput
                    )
                },
                note: Strings.settingsOutputNote,
                onSelect: { actions.selectDefaultOutput(defaultOutputs[$0]) },
                onDismiss: actions.closeSheet
            )
        case .historyAge:
            SettingsChoiceSheet(
                title: Strings.settingsHistory,
                options: historyAgeDays.map {
                    SettingsChoice(
                        title: historyAgeText($0),
                        icon: MumcordIcons.trash,
                        selected: $0 == state.app.historyMaxAgeDays
                    )
                },
                note: Strings.settingsHistoryNote,
                onSelect: { actions.selectHistoryAge(historyAgeDays[$0]) },
                onDismiss: actions.closeSheet
            )
        case nil:
            EmptyView()
        }
    }
}

private struct SettingsChoice {
    let title: String
    let icon: MumcordIconData
    let selected: Bool
}

private struct SettingsChoiceSheet: View {
    let title: String
    let options: [SettingsChoice]
    var note: String?
    let onSelect: (Int) -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: title, onDismiss: onDismiss) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                ForEach(options.indices, id: \.self) { index in
                    let option = options[index]
                    MumcordOptionRow(
                        title: option.title,
                        icon: option.icon,
                        selected: option.selected,
                        divider: index > 0,
                        action: { onSelect(index) }
                    )
                    .accessibilityIdentifier("settings-option-\(index)")
                }
            }
            if let note {
                MumcordNote(text: note)
            }
        }
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

/// A setting with its current value at the end; tapping it opens the choices.
private struct SettingsValueRow: View {
    let title: String
    let value: String
    let icon: MumcordIconData
    let id: String
    var divider = false
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        MumcordListRow(
            title: title,
            icon: icon,
            divider: divider,
            action: action,
            trailing: {
                MumcordListRowValue(text: value)
                MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
            }
        )
        .wrapping()
        .accessibilityIdentifier(id)
    }
}

/// The microphone level, live while the voice runs. Only this view reads the level, so its up
/// to 20 changes a second redraw the meter and nothing else.
private struct SettingsLevelMeter: View {
    let level: InputLevel

    var body: some View {
        MumcordLevelMeter(level: Double(level.percent) / 100, label: Strings.voiceLevel)
            .frame(width: levelMeterWidth)
            // The row's title already names it, and its moving level is not announced.
            .accessibilityHidden(true)
            .accessibilityIdentifier("settings-input-level")
    }
}

private func talkModeText(_ mode: TalkMode) -> String {
    switch mode {
    case .voiceActivity: Strings.talkModeVoiceActivity
    case .pushToTalk: Strings.talkModePushToTalk
    }
}

private func defaultOutputText(_ output: DefaultOutput) -> String {
    switch output {
    case .ask: Strings.settingsOutputAsk
    case .speaker: Strings.outputQuestionSpeaker
    case .earpiece: Strings.outputQuestionEarpiece
    }
}

private func defaultOutputIcon(_ output: DefaultOutput) -> MumcordIconData {
    switch output {
    case .ask: MumcordIcons.info
    case .speaker: MumcordIcons.speaker
    case .earpiece: MumcordIcons.volume
    }
}

private func historyAgeText(_ days: UInt32?) -> String {
    days.map { Strings.settingsHistoryDays(Int($0)) } ?? Strings.settingsHistoryNever
}

/// A language under its own name, so that everybody finds theirs; nil follows the phone.
private func languageText(_ language: String?) -> String {
    language.map(languageEndonym) ?? Strings.settingsLanguageSystem
}
