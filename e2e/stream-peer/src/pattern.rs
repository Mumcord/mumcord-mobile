// SPDX-License-Identifier: AGPL-3.0-only

//! What the phone's test camera shows (`TestPatternCamera` of the Android end-to-end tests): a
//! gradient from dark at one top corner to bright at the opposite bottom corner, from the top
//! left for the front camera and from the top right for the back camera, with a white bar
//! moving through the middle band. The corners stay clear of the bar.

/// Which camera a picture shows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pattern {
    Front,
    Back,
    /// Not the test pattern (or a picture still building up after a keyframe).
    Other,
}

impl Pattern {
    pub fn name(self) -> &'static str {
        match self {
            Self::Front => "front",
            Self::Back => "back",
            Self::Other => "other",
        }
    }
}

/// Difference of the gradient's ends that counts as the pattern (of 0 to 255; it spans 200).
const MIN_GRADIENT: i32 = 100;
/// The two other corners are both mid-grey; the encoder may move them this much apart.
const MAX_MIDDLE_GAP: i32 = 40;

/// The pattern of a `width` × `height` luma plane without row padding.
pub fn pattern(width: u32, height: u32, luma: &[u8]) -> Pattern {
    let (w, h) = (width as usize, height as usize);
    if w < 32 || h < 32 || luma.len() < w * h {
        return Pattern::Other;
    }
    // The mean of an 8 × 8 square around a point given in tenths of the picture.
    let corner = |fx: usize, fy: usize| -> i32 {
        let (cx, cy) = (w * fx / 10, h * fy / 10);
        let mut sum = 0i32;
        for y in cy - 4..cy + 4 {
            for x in cx - 4..cx + 4 {
                sum += i32::from(luma[y * w + x]);
            }
        }
        sum / 64
    };
    let (top_left, top_right) = (corner(1, 1), corner(9, 1));
    let (bottom_left, bottom_right) = (corner(1, 9), corner(9, 9));
    if bottom_right - top_left > MIN_GRADIENT && (top_right - bottom_left).abs() < MAX_MIDDLE_GAP {
        Pattern::Front
    } else if bottom_left - top_right > MIN_GRADIENT
        && (top_left - bottom_right).abs() < MAX_MIDDLE_GAP
    {
        Pattern::Back
    } else {
        Pattern::Other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The phone's test pattern, as `TestPatternCamera` draws it.
    fn draw(width: usize, height: usize, back: bool, bar: usize) -> Vec<u8> {
        let mut luma = vec![0; width * height];
        for y in 0..height {
            for x in 0..width {
                let fx = if back { width - 1 - x } else { x };
                let value = 16 + 100 * fx / width + 100 * y / height;
                let in_band = y >= height * 4 / 10 && y < height * 6 / 10;
                luma[y * width + x] = if in_band && x >= bar && x < bar + 16 {
                    235
                } else {
                    value as u8
                };
            }
        }
        luma
    }

    #[test]
    fn the_front_and_the_back_pattern_are_told_apart() {
        assert_eq!(
            pattern(640, 360, &draw(640, 360, false, 300)),
            Pattern::Front
        );
        assert_eq!(pattern(640, 360, &draw(640, 360, true, 40)), Pattern::Back);
        assert_eq!(pattern(320, 180, &draw(320, 180, false, 0)), Pattern::Front);
    }

    #[test]
    fn a_flat_or_tiny_picture_is_no_pattern() {
        assert_eq!(pattern(640, 360, &vec![128; 640 * 360]), Pattern::Other);
        assert_eq!(pattern(16, 16, &[0; 256]), Pattern::Other);
    }
}
