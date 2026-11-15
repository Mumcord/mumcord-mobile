// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// One icon of design/icons.json: unfilled paths on the 24 x 24 grid, drawn with round caps and
/// joins. The colour comes from where the icon is drawn (`MumcordIcon`).
struct MumcordIconData {
    let id: String
    let path: Path

    init(id: String, build: (inout Path) -> Void) {
        var path = Path()
        build(&path)
        self.id = id
        self.path = path
    }
}
