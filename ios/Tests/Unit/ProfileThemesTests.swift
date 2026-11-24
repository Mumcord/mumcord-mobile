// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Edit profile, the themes screen and the theme the app is drawn in, through the model, against
/// the fake engine (FR-5.80, FR-5.81).
@MainActor
final class ProfileThemesTests: XCTestCase {
    private let box = EngineBox()

    private var engine: FakeEngine { box.engine }

    /// A model connected to Nordlicht, with the More tab open.
    private func connected(configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }) async -> AppModel {
        let store = makeStore(box, server: mockupVoiceServer(), servers: [savedServer(1, "Nordlicht")], configure: configure)
        let model = PhoneFakes().makeModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.moreUiState != nil }
        model.selectTab(.more)
        return model
    }

    private func ui(_ model: AppModel) -> MoreUiState? { model.moreUiState }

    private func pasteSheet(_ model: AppModel) -> (text: String, read: ThemeCodeRead?, applying: Bool)? {
        guard case .paste(let text, let read, let applying) = model.moreUiState?.themes?.sheet else { return nil }
        return (text, read, applying)
    }

    // MARK: Profile

    func testTheMoreTabShowsTheOwnProfileAndTheAppTheme() async throws {
        let model = await connected { $0.profileThemes.profile.commentText = "Playing late\nAsk me" }
        await assertEventually { self.ui(model)?.profile != nil }

        let profile = try XCTUnwrap(ui(model)?.profile)
        XCTAssertEqual(profile.name, "Lea")
        XCTAssertEqual(profile.commentLine, "Playing late")
        XCTAssertEqual(ui(model)?.themeName, "Mumcord")
    }

    func testAChangedCommentIsReadAgainWhenTheServerSaysSo() async {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        engine.profileThemes.profile.commentText = "Changed on the desktop"
        engine.emit(.profileChanged(session: Mockup.lea))
        await assertEventually { self.ui(model)?.profile?.comment == "Changed on the desktop" }
    }

    func testAChangedCommentAndANewPictureAreSavedAndTheEditorCloses() async throws {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        await assertEventually { self.ui(model)?.profileEdit?.edit.limit != nil }
        let edit = try XCTUnwrap(ui(model)?.profileEdit)
        XCTAssertEqual(edit.edit.limit, 5000, "the server's limit")
        XCTAssertFalse(edit.edit.canSave, "nothing to save yet")

        model.more.profile.changeComment("Up for a chat")
        model.more.profile.pickAvatar(Data([7, 8]))
        model.more.profile.save()
        await assertEventually { self.ui(model)?.page == .main }

        XCTAssertEqual(engine.profileThemes.profile.commentText, "Up for a chat")
        XCTAssertEqual(engine.profileThemes.ownAvatar, Data([7, 8]))
        await assertEventually { self.ui(model)?.profile?.comment == "Up for a chat" }
        XCTAssertEqual(ui(model)?.profile?.avatar, Data([7, 8]))
    }

    func testAnUnchangedCommentIsNotSentAgainWhenOnlyThePictureIsRemoved() async throws {
        let model = await connected { engine in
            engine.profileThemes.profile.commentText = "Kept"
            engine.profileThemes.profile.avatar = ImageData(bytes: Data([1]), mime: "image/png", width: 1, height: 1, animated: false)
        }
        await assertEventually { self.ui(model)?.profile?.avatar != nil }
        model.more.openProfile()
        model.more.profile.openAvatarSheet()
        XCTAssertEqual(ui(model)?.profileEdit?.edit.sheet, .avatar)

        model.more.profile.removeAvatar()
        XCTAssertNil(ui(model)?.profileEdit?.avatar)
        XCTAssertNil(ui(model)?.profileEdit?.edit.sheet, "the sheet closes")
        engine.profileThemes.profile.commentText = "Changed elsewhere"
        model.more.profile.save()
        await assertEventually { self.ui(model)?.page == .main }

        XCTAssertNil(engine.profileThemes.profile.avatar)
        XCTAssertEqual(engine.profileThemes.profile.commentText, "Changed elsewhere", "the comment was not sent")
    }

    func testRemovingANewPictureWithNoneOnTheServerOnlyDropsIt() async {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        model.more.profile.pickAvatar(Data([3]))
        model.more.profile.removeAvatar()
        XCTAssertEqual(ui(model)?.profileEdit?.edit.avatar, .keep)
        XCTAssertEqual(ui(model)?.profileEdit?.edit.canSave, false)
    }

    func testACommentOverTheLimitCannotBeSaved() async {
        let model = await connected { $0.limits = ChatLimits(text: 5, image: 131_072, allowHtml: true) }
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        await assertEventually { self.ui(model)?.profileEdit?.edit.limit == 5 }
        model.more.profile.changeComment("too long")
        model.more.profile.save()

        XCTAssertEqual(ui(model)?.profileEdit?.edit.tooLong, true)
        XCTAssertEqual(ui(model)?.page, .editProfile)
        XCTAssertEqual(engine.profileThemes.profile.commentText, "")
    }

    func testAFailedSaveStaysOpenWithItsErrorAndASavedPictureIsNotSentAgain() async {
        let model = await connected { $0.profileThemes.saveFailure = .Io(detail: "gone") }
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        model.more.profile.changeComment("Hello")
        model.more.profile.pickAvatar(Data([9]))
        model.more.profile.save()

        await assertEventually { self.ui(model)?.profileEdit?.edit.error == .saveFailed }
        XCTAssertEqual(ui(model)?.page, .editProfile)
        let edit = ui(model)?.profileEdit?.edit
        XCTAssertEqual(edit?.saving, false)
        XCTAssertEqual(edit?.comment, "Hello", "what was typed stays")
        XCTAssertEqual(edit?.avatar, .keep, "the picture is on the server already")
        XCTAssertEqual(engine.profileThemes.ownAvatar, Data([9]))

        model.more.profile.save()
        await assertEventually { self.ui(model)?.page == .main }
        XCTAssertEqual(engine.profileThemes.profile.commentText, "Hello")
    }

    /// The counter counts what was typed; the engine checks the comment as Mumble HTML, where a
    /// `<` takes four characters, so it can refuse a comment the counter let through.
    func testACommentTheServerFindsTooLongSaysSo() async {
        let model = await connected { $0.profileThemes.saveFailure = .InvalidInput(detail: "message length 12 exceeds the limit of 10") }
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        model.more.profile.changeComment("<<<")
        model.more.profile.save()

        await assertEventually { self.ui(model)?.profileEdit?.edit.error == .commentTooLong }
        XCTAssertEqual(ui(model)?.page, .editProfile)
    }

    func testARefusedPictureIsNoTooLongComment() async {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        engine.profileThemes.avatarFailure = .InvalidInput(detail: "no image")
        model.more.openProfile()
        model.more.profile.changeComment("Hello")
        model.more.profile.pickAvatar(Data([9]))
        model.more.profile.save()

        await assertEventually { self.ui(model)?.profileEdit?.edit.error == .saveFailed }
    }

    /// The camera explanation hides the editor from VoiceOver like every other sheet.
    func testTheCameraExplanationCountsAsAnOpenSheet() async {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        XCTAssertFalse(model.more.sheetOpen)

        model.more.profile.explainCamera(.undetermined)
        XCTAssertEqual(ui(model)?.profileEdit?.edit.sheet, .camera(.undetermined))
        XCTAssertTrue(model.more.sheetOpen)
        model.more.profile.closeSheet()
        XCTAssertFalse(model.more.sheetOpen)
    }

    func testLeavingTheEditorDropsWhatWasNotSaved() async {
        let model = await connected()
        await assertEventually { self.ui(model)?.profile != nil }
        model.more.openProfile()
        model.more.profile.changeComment("Not saved")
        model.more.back()
        XCTAssertNil(ui(model)?.profileEdit)
        XCTAssertNil(model.more.profile.edit)
        XCTAssertEqual(engine.profileThemes.profile.commentText, "")
    }

    // MARK: Themes

    func testTheAppearanceAndTheAppThemeAreSavedAndDrawn() async throws {
        let model = await connected()
        model.more.openThemes()
        await assertEventually { self.ui(model)?.themes != nil }
        XCTAssertEqual(ui(model)?.themes?.mode, .system)
        XCTAssertEqual(ui(model)?.themes?.builtIn.map(\.name), ["Mumcord", "Deep Ocean", "Forest", "Paper"])
        await assertEventually { model.appTheme.shown != nil }

        model.more.themes.setMode(.light)
        await assertEventually { self.ui(model)?.themes?.mode == .light }
        await assertEventually { model.appTheme.shown?.mode == .light }
        XCTAssertEqual(model.appTheme.shown?.light.themeId, MockupThemes.mumcordId)

        model.more.themes.apply(MockupThemes.oceanId)
        await assertEventually { model.appTheme.shown?.dark.themeId == MockupThemes.oceanId }
        XCTAssertEqual(ui(model)?.themeName, "Deep Ocean")
        XCTAssertEqual(ui(model)?.themes?.appThemeId, MockupThemes.oceanId)
        XCTAssertFalse(try XCTUnwrap(model.appTheme.shown).theme(systemDark: true).isDark, "Light is drawn whatever the phone says")
    }

    func testAPastedCodeIsPreviewedThenImportedAndApplied() async throws {
        let model = await connected()
        model.more.openThemes()
        let themes = model.more.themes
        themes.openPaste()
        themes.changePasteText("hello")
        await assertEventually { self.pasteSheet(model)?.read == .invalid }

        themes.changePasteText("🎨 Mumcord theme \"Imported\" – \(fakeThemeCode)")
        XCTAssertNil(pasteSheet(model)?.read, "the old answer is gone while the new text is read")
        await assertEventually {
            if case .found = self.pasteSheet(model)?.read { return true }
            return false
        }
        guard case .found(let preview) = pasteSheet(model)?.read else { return XCTFail("no preview") }
        XCTAssertEqual(preview.name, "Imported")
        XCTAssertNil(preview.importedId)

        themes.applyPasted()
        await assertEventually { self.ui(model)?.themes?.sheet == nil }
        let state = try XCTUnwrap(ui(model)?.themes)
        XCTAssertEqual(state.custom.map(\.id), ["custom:1"])
        XCTAssertEqual(state.appThemeId, "custom:1")
        await assertEventually { model.appTheme.shown?.dark.themeId == "custom:1" }
    }

    func testAPastedCodeAlreadyImportedSaysSo() async {
        let model = await connected { engine in _ = engine.profileThemes.addCustom(MockupThemes.fromTheDesktop) }
        _ = try? await engine.importThemeCode(text: fakeThemeCode)
        model.more.openThemes()
        model.more.themes.openPaste()
        model.more.themes.changePasteText(fakeThemeCode)
        await assertEventually {
            if case .found(let preview) = self.pasteSheet(model)?.read { return preview.importedId == "custom:2" }
            return false
        }
    }

    func testClosingThePasteSheetForgetsTheText() async {
        let model = await connected()
        model.more.openThemes()
        model.more.themes.openPaste()
        model.more.themes.changePasteText(fakeThemeCode)
        model.more.themes.closeSheet()
        model.more.themes.openPaste()
        XCTAssertEqual(pasteSheet(model)?.text, "")
    }

    func testAThemeIsSharedAndAnOwnOneDeleted() async throws {
        let model = await connected { engine in _ = engine.profileThemes.addCustom(MockupThemes.fromTheDesktop) }
        model.more.openThemes()
        await assertEventually { self.ui(model)?.themes?.custom.count == 1 }
        let themes = model.more.themes

        themes.openActions("custom:1")
        themes.share("custom:1")
        XCTAssertNil(ui(model)?.themes?.sheet, "the sheet closes for the share sheet")
        await assertEventually { themes.local.shareText != nil }
        XCTAssertTrue(try XCTUnwrap(themes.local.shareText).hasSuffix(fakeThemeCode))
        XCTAssertTrue(try XCTUnwrap(themes.local.shareText).contains("From the desktop"))
        themes.shared()
        XCTAssertNil(themes.local.shareText)

        themes.apply("custom:1")
        await assertEventually { self.ui(model)?.themes?.appThemeId == "custom:1" }
        themes.requestDelete("custom:1")
        XCTAssertEqual(ui(model)?.themes?.sheet, .confirmDelete(themeId: "custom:1"))
        themes.confirmDelete("custom:1")
        await assertEventually { self.ui(model)?.themes?.custom.isEmpty == true }
        XCTAssertEqual(ui(model)?.themes?.appThemeId, MockupThemes.mumcordId, "back to Mumcord")
    }

    func testARefusedThemeCommandIsShownLikeARefusal() async {
        let model = await connected()
        model.more.openThemes()
        model.more.themes.apply("preset:gone")
        await assertEventually { model.uiState.session.denied != nil }
    }

    func testAServersOwnThemeIsShownOnlyWhileThatServerIsShown() async throws {
        let model = await connected()
        try await engine.setServerTheme(serverId: 1, themeId: MockupThemes.oceanId)
        await assertEventually { model.appTheme.shown?.dark.themeId == MockupThemes.oceanId }
        model.more.openThemes()
        await assertEventually { self.ui(model)?.themes?.serverTheme?.id == MockupThemes.oceanId }
        XCTAssertEqual(ui(model)?.themes?.serverName, "Nordlicht")
        XCTAssertEqual(ui(model)?.themeName, "Mumcord", "the row names the app theme")

        model.disconnect()
        await assertEventually { model.appTheme.shown?.dark.themeId == MockupThemes.mumcordId }
    }

    func testLeavingTheThemesScreenClosesItsSheet() async {
        let model = await connected()
        model.more.openThemes()
        model.more.themes.openPaste()
        model.more.back()
        XCTAssertNil(model.more.themes.local.sheet)
        XCTAssertEqual(ui(model)?.page, .main)
    }

    func testTheEditServerFormSavesTheServersTheme() async throws {
        let model = await connected()
        model.disconnect()
        await assertEventually { model.moreUiState == nil }
        model.openEditServer(1)
        XCTAssertNil(model.uiState.form?.themeId)

        model.changeForm { $0.themeSheet = true }
        model.changeForm {
            $0.themeId = MockupThemes.oceanId
            $0.themeSheet = false
        }
        model.saveForm()
        await assertEventually { model.uiState.form == nil }
        XCTAssertEqual(engine.themeSettings().serverThemes, [1: MockupThemes.oceanId])

        model.openEditServer(1)
        XCTAssertEqual(model.uiState.form?.themeId, MockupThemes.oceanId, "the form opens with it")
        model.changeForm { $0.themeId = nil }
        model.saveForm()
        await assertEventually { model.uiState.form == nil }
        XCTAssertEqual(engine.themeSettings().serverThemes, [:], "back to the app theme")
    }
}
