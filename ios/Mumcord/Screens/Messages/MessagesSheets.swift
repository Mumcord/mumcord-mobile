// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Draws the question about blocking or the chat request that is open.
struct MessagesSheetHost: View {
    let sheet: MessagesSheet
    let actions: MessagesController

    var body: some View {
        switch sheet {
        case .blockConfirm(_, let name, let failed):
            BlockSheet(name: name, failed: failed, actions: actions)
        case .chatRequest(_, let name, let note, let sending, let error):
            ChatRequestSheet(name: name, note: note, sending: sending, error: error, actions: actions)
        }
    }
}

/// Says what a block does, as the desktop app does, before anything is blocked.
private struct BlockSheet: View {
    let name: String
    let failed: Bool
    let actions: MessagesController

    var body: some View {
        SheetOverlay(title: Strings.blockTitle(name), onDismiss: actions.dismissSheet) {
            Paragraph(Strings.blockPrivate(name)).accessibilityIdentifier("block-sheet")
            Paragraph(Strings.blockChannel(name))
            Paragraph(Strings.blockVoice(name))
            Paragraph(Strings.blockSilent)
            if failed {
                MessagesErrorBanner(text: Strings.blockFailed)
            }
            MumcordButton(text: Strings.personBlock, style: .danger, fullWidth: true, action: actions.confirmBlock)
                .accessibilityIdentifier("block-confirm")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: actions.dismissSheet)
        }
    }
}

/// A chat request with an optional short note, as on the desktop.
private struct ChatRequestSheet: View {
    let name: String
    let note: String
    let sending: Bool
    let error: ChatRequestError?
    let actions: MessagesController

    var body: some View {
        SheetOverlay(title: Strings.chatRequestTitle(name), onDismiss: actions.dismissSheet) {
            Paragraph(Strings.chatRequestText(name))
            MumcordTextField(
                value: note,
                onValueChange: actions.changeRequestNote,
                label: Strings.chatRequestNote,
                hint: Strings.chatRequestCounter(note.count, chatRequestNoteMax),
                submitLabel: .send,
                onSubmit: actions.sendChatRequest
            )
            .accessibilityIdentifier("chat-request-note")
            switch error {
            case .refused: MessagesErrorBanner(text: Strings.chatRequestRefused(name))
            case .failed: MessagesErrorBanner(text: Strings.chatRequestFailed)
            case nil: EmptyView()
            }
            MumcordButton(text: Strings.chatRequestSend, fullWidth: true, action: actions.sendChatRequest)
                .disabled(sending)
                .accessibilityIdentifier("chat-request-send")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: actions.dismissSheet)
        }
    }
}

private struct MessagesErrorBanner: View {
    let text: String

    var body: some View {
        MumcordBanner(text: text, icon: MumcordIcons.alert, tone: .warning, horizontalMargin: 0)
            .accessibilityIdentifier("chat-request-error")
    }
}

/// Why notifications are wanted, in one sentence, before the system asks. "Not now" changes
/// nothing about the session.
struct NotificationSheet: View {
    let onContinue: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.notificationsTitle, onDismiss: onDismiss) {
            MumcordBanner(text: Strings.notificationsText, horizontalMargin: 0)
            MumcordButton(text: Strings.notificationsContinue, fullWidth: true, action: onContinue)
                .accessibilityIdentifier("notifications-continue")
            MumcordButton(text: Strings.notificationsNotNow, style: .secondary, fullWidth: true, action: onDismiss)
                .accessibilityIdentifier("notifications-not-now")
        }
    }
}
