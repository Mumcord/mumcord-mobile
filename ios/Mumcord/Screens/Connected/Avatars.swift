// SPDX-License-Identifier: AGPL-3.0-only

private let avatarColours: Int32 = 8

extension String {
    /// The first `count` letters or digits of a name in upper case, "?" when it has none.
    func initials(_ count: Int) -> String {
        let letters = String(filter { $0.isLetter || $0.isNumber }.prefix(count)).uppercased()
        return letters.isEmpty ? "?" : letters
    }
}

/// One of the eight avatar colours, always the same for the same name. The same rule as in the
/// Android app (the 31-based hash of the UTF-16 text), so a person has one colour on both.
func avatarSlot(_ name: String) -> Int {
    var hash: Int32 = 0
    for unit in name.utf16 {
        hash = hash &* 31 &+ Int32(unit)
    }
    return Int(((hash % avatarColours) + avatarColours) % avatarColours) + 1
}
