// SPDX-License-Identifier: AGPL-3.0-only

//! Images of the chat: checking what arrives before an app decodes it, fitting what is sent
//! into the server's limit, and keeping the bytes of shown messages for a while.
//!
//! Fitting an image for sending is a port of the desktop UI's rules
//! (`ui/src/features/chat/images.ts`); the engine does not have them yet.

use std::collections::VecDeque;
use std::io::Cursor;

use base64::Engine as _;
use image::codecs::jpeg::JpegEncoder;
use image::codecs::png::PngEncoder;
use image::codecs::webp::WebPDecoder;
use image::imageops::FilterType;
use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};

use super::types::ImageData;
use crate::error::BridgeError;

/// Largest encoded image the bridge passes on; the engine's limit for fetched images.
pub(crate) const MAX_IMAGE_BYTES: usize = 16 * 1024 * 1024;
/// Largest image by its header, so a small file cannot unpack into a huge bitmap.
pub(crate) const MAX_IMAGE_SIDE: u32 = 8192;
pub(crate) const MAX_IMAGE_PIXELS: u64 = 40_000_000;
/// Larger files are refused for sending before they are read.
pub(crate) const MAX_SOURCE_BYTES: usize = 25 * 1024 * 1024;
/// Largest edge of a sent image; bigger pictures are scaled down to at most this.
const MAX_SENT_EDGE: u32 = 1920;
const MIN_SENT_EDGE: u32 = 64;
const JPEG_QUALITY: u8 = 85;
/// Memory the decoder may take for an image that is sent.
const DECODE_MEMORY: u64 = 512 * 1024 * 1024;
/// The bytes of embedded images kept for `chat_image`.
const CACHE_BYTES: usize = 32 * 1024 * 1024;

fn mime_of(format: ImageFormat) -> Option<&'static str> {
    match format {
        ImageFormat::Png => Some("image/png"),
        ImageFormat::Jpeg => Some("image/jpeg"),
        ImageFormat::Gif => Some("image/gif"),
        ImageFormat::WebP => Some("image/webp"),
        _ => None,
    }
}

fn unreadable() -> BridgeError {
    BridgeError::invalid("not an image that can be shown")
}

/// Checks image bytes by their header only: one of the four formats, within the size limits.
/// Nothing is decoded, so a decompression bomb costs nothing.
pub(crate) fn inspect(bytes: Vec<u8>) -> Result<ImageData, BridgeError> {
    if bytes.len() > MAX_IMAGE_BYTES {
        return Err(BridgeError::invalid("the image is too large"));
    }
    let reader = ImageReader::new(Cursor::new(&bytes))
        .with_guessed_format()
        .map_err(|_| unreadable())?;
    let format = reader.format().ok_or_else(unreadable)?;
    let mime = mime_of(format).ok_or_else(unreadable)?;
    let (width, height) = reader.into_dimensions().map_err(|_| unreadable())?;
    if width == 0
        || height == 0
        || width > MAX_IMAGE_SIDE
        || height > MAX_IMAGE_SIDE
        || u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS
    {
        return Err(BridgeError::invalid("the image is too large"));
    }
    let animated = match format {
        // Telling a still GIF apart needs its frames; the apps' players handle both.
        ImageFormat::Gif => true,
        ImageFormat::WebP => {
            WebPDecoder::new(Cursor::new(&bytes)).is_ok_and(|decoder| decoder.has_animation())
        }
        _ => false,
    };
    Ok(ImageData {
        bytes,
        mime: mime.to_owned(),
        width,
        height,
        animated,
    })
}

/// The image inside a `data:` address the engine made from a fetched image.
pub(crate) fn from_data_url(data_url: &str) -> Result<ImageData, BridgeError> {
    let (_, payload) = data_url.split_once(";base64,").ok_or_else(unreadable)?;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(payload)
        .map_err(|_| unreadable())?;
    inspect(bytes)
}

fn data_url(mime: &str, bytes: &[u8]) -> String {
    format!(
        "data:{mime};base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    )
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Encoding {
    Png,
    Jpeg,
}

fn encode(image: &DynamicImage, width: u32, height: u32, encoding: Encoding) -> Option<String> {
    let scaled = if (width, height) == (image.width(), image.height()) {
        image.clone()
    } else {
        image.resize_exact(width, height, FilterType::Triangle)
    };
    let mut bytes = Vec::new();
    match encoding {
        Encoding::Png => {
            scaled
                .write_with_encoder(PngEncoder::new(&mut bytes))
                .ok()?;
            Some(data_url("image/png", &bytes))
        }
        Encoding::Jpeg => {
            // JPEG has no transparency; a white background matches how most clients show PNGs.
            let mut flat = image::RgbImage::from_pixel(width, height, image::Rgb([255, 255, 255]));
            for (x, y, pixel) in scaled.to_rgba8().enumerate_pixels() {
                let alpha = u32::from(pixel[3]);
                let blend = |c: u8| ((u32::from(c) * alpha + 255 * (255 - alpha)) / 255) as u8;
                flat.put_pixel(
                    x,
                    y,
                    image::Rgb([blend(pixel[0]), blend(pixel[1]), blend(pixel[2])]),
                );
            }
            flat.write_with_encoder(JpegEncoder::new_with_quality(&mut bytes, JPEG_QUALITY))
                .ok()?;
            Some(data_url("image/jpeg", &bytes))
        }
    }
}

/// The largest edge at which the image fits `budget` characters in `encoding`, by a binary
/// search: about six encodes find it within a few percent.
fn largest_fit(
    image: &DynamicImage,
    max_edge: u32,
    encoding: Encoding,
    budget: usize,
) -> Option<(u32, String)> {
    let longest = image.width().max(image.height());
    let attempt = |edge: u32| {
        let scale = f64::from(edge) / f64::from(longest);
        let side = |length: u32| ((f64::from(length) * scale).round() as u32).max(1);
        encode(image, side(image.width()), side(image.height()), encoding)
            .filter(|url| url.len() <= budget)
    };
    if let Some(url) = attempt(max_edge) {
        return Some((max_edge, url));
    }
    let mut best = None;
    let (mut low, mut high) = (MIN_SENT_EDGE.min(max_edge), max_edge);
    while high - low > 8.max(high / 25) {
        let edge = (low + high).div_ceil(2);
        match attempt(edge) {
            Some(url) => {
                best = Some((edge, url));
                low = edge;
            }
            None => high = edge,
        }
    }
    best.or_else(|| attempt(low).map(|url| (low, url)))
}

/// Prepares an image for sending as a `data:` address of at most `budget` characters. An image
/// that already fits is kept as it is (keeps GIF animation and PNG transparency); anything else
/// is scaled down and written as PNG (best for screenshots) or JPEG (best for photos),
/// whichever fits larger. Fails when even the smallest version is too big.
pub(crate) fn prepare_outgoing(bytes: Vec<u8>, budget: usize) -> Result<String, BridgeError> {
    if bytes.len() > MAX_SOURCE_BYTES {
        return Err(BridgeError::invalid("the image file is too large"));
    }
    let mut reader = ImageReader::new(Cursor::new(&bytes))
        .with_guessed_format()
        .map_err(|_| unreadable())?;
    let format = reader.format().ok_or_else(unreadable)?;
    let mime = mime_of(format).ok_or_else(unreadable)?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_IMAGE_SIDE);
    limits.max_image_height = Some(MAX_IMAGE_SIDE);
    limits.max_alloc = Some(DECODE_MEMORY);
    reader.limits(limits);
    let mut decoder = reader.into_decoder().map_err(|_| unreadable())?;
    let (width, height) = decoder.dimensions();
    if u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS {
        return Err(BridgeError::invalid("the image is too large"));
    }
    // Phone cameras store photos turned and say so in the file.
    let orientation = decoder.orientation().map_err(|_| unreadable())?;
    let upright = orientation == image::metadata::Orientation::NoTransforms;
    if upright && width.max(height) <= MAX_SENT_EDGE {
        let original = data_url(mime, &bytes);
        if original.len() <= budget {
            return Ok(original);
        }
    }
    let mut image = DynamicImage::from_decoder(decoder).map_err(|_| unreadable())?;
    image.apply_orientation(orientation);
    let longest = image.width().max(image.height());
    let cap = MAX_SENT_EDGE.min(longest);
    if longest > cap {
        // Every attempt below starts from this copy instead of the full photo.
        image = image.resize(cap, cap, FilterType::Triangle);
    }
    let png = largest_fit(&image, cap, Encoding::Png, budget);
    // A full-size PNG that fits cannot be beaten, so the JPEG search is skipped.
    let jpeg = match &png {
        Some((edge, _)) if *edge == cap => None,
        _ => largest_fit(&image, cap, Encoding::Jpeg, budget),
    };
    let winner = match (png, jpeg) {
        (Some(png), Some(jpeg)) if png.0 < jpeg.0 => Some(jpeg),
        (Some(png), _) => Some(png),
        (None, jpeg) => jpeg,
    };
    winner
        .map(|(_, url)| url)
        .ok_or_else(|| BridgeError::invalid("the image does not fit the server's limit"))
}

/// The embedded images of the messages that were handed to the app, newest last, within a
/// memory limit. An image that fell out is read again from the stored message.
#[derive(Default)]
pub(crate) struct ImageCache {
    entries: VecDeque<((String, String), ImageData)>,
    bytes: usize,
}

impl ImageCache {
    pub(crate) fn put(&mut self, message_id: &str, images: Vec<(String, ImageData)>) {
        for (image_id, image) in images {
            let key = (message_id.to_owned(), image_id);
            if self.entries.iter().any(|(known, _)| *known == key) {
                continue;
            }
            self.bytes += image.bytes.len();
            self.entries.push_back((key, image));
        }
        while self.bytes > CACHE_BYTES {
            let Some((_, image)) = self.entries.pop_front() else {
                break;
            };
            self.bytes -= image.bytes.len();
        }
    }

    pub(crate) fn get(&self, message_id: &str, image_id: &str) -> Option<ImageData> {
        self.entries
            .iter()
            .find(|((message, image), _)| message == message_id && image == image_id)
            .map(|(_, image)| image.clone())
    }

    pub(crate) fn clear(&mut self) {
        self.entries.clear();
        self.bytes = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(width: u32, height: u32) -> Vec<u8> {
        // Noise, so the file does not compress to nothing.
        let mut seed = 1u32;
        let image = image::RgbImage::from_fn(width, height, |_, _| {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12_345);
            image::Rgb([(seed >> 16) as u8, (seed >> 8) as u8, seed as u8])
        });
        let mut bytes = Vec::new();
        image
            .write_with_encoder(PngEncoder::new(&mut bytes))
            .expect("png");
        bytes
    }

    /// A PNG file that claims a size and holds no pixels: what a decompression bomb looks like
    /// from the outside.
    fn png_header(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = b"\x89PNG\r\n\x1a\n".to_vec();
        let mut chunk = b"IHDR".to_vec();
        chunk.extend(width.to_be_bytes());
        chunk.extend(height.to_be_bytes());
        chunk.extend([8, 2, 0, 0, 0]);
        bytes.extend(13u32.to_be_bytes());
        bytes.extend(&chunk);
        bytes.extend(crc32(&chunk).to_be_bytes());
        bytes
    }

    fn crc32(data: &[u8]) -> u32 {
        let mut crc = !0u32;
        for byte in data {
            crc ^= u32::from(*byte);
            for _ in 0..8 {
                crc = (crc >> 1) ^ (0xEDB8_8320 & (!(crc & 1)).wrapping_add(1));
            }
        }
        !crc
    }

    #[test]
    fn reads_format_and_size_from_the_header() {
        let image = inspect(png(20, 10)).expect("png");
        assert_eq!(
            (image.mime.as_str(), image.width, image.height),
            ("image/png", 20, 10)
        );
        assert!(!image.animated);
    }

    #[test]
    fn refuses_what_is_no_image_or_another_format() {
        assert!(inspect(b"<svg xmlns='http://www.w3.org/2000/svg'/>".to_vec()).is_err());
        assert!(inspect(b"BM000000000000000000000000".to_vec()).is_err());
        assert!(inspect(Vec::new()).is_err());
    }

    #[test]
    fn refuses_a_decompression_bomb_by_its_header() {
        assert!(inspect(png_header(60_000, 60_000)).is_err());
        assert!(inspect(png_header(MAX_IMAGE_SIDE + 1, 10)).is_err());
        assert!(inspect(png_header(8000, 8000)).is_err(), "64 megapixels");
        // A photo of a phone camera passes.
        let photo = image::RgbImage::from_pixel(4000, 3000, image::Rgb([1, 2, 3]));
        let mut bytes = Vec::new();
        photo
            .write_with_encoder(PngEncoder::new(&mut bytes))
            .expect("png");
        assert!(inspect(bytes).is_ok());
        assert!(prepare_outgoing(png_header(60_000, 60_000), 1 << 20).is_err());
    }

    #[test]
    fn refuses_more_bytes_than_the_limit() {
        assert!(inspect(vec![0; MAX_IMAGE_BYTES + 1]).is_err());
        assert!(prepare_outgoing(vec![0; MAX_SOURCE_BYTES + 1], 1 << 20).is_err());
    }

    #[test]
    fn an_image_that_fits_is_sent_as_it_is() {
        let bytes = png(40, 30);
        let url = prepare_outgoing(bytes.clone(), 1 << 20).expect("fits");
        assert_eq!(from_data_url(&url).expect("image").bytes, bytes);
    }

    #[test]
    fn a_large_image_is_scaled_into_the_limit() {
        let budget = 128 * 1024;
        let url = prepare_outgoing(png(900, 600), budget).expect("scaled");
        assert!(url.len() <= budget, "{}", url.len());
        let sent = from_data_url(&url).expect("image");
        assert!(sent.width < 900 && sent.width >= MIN_SENT_EDGE);
        // The shape is kept.
        assert!((f64::from(sent.width) / f64::from(sent.height) - 1.5).abs() < 0.05);
    }

    #[test]
    fn an_image_is_never_sent_larger_than_the_edge_limit() {
        let image = image::RgbImage::from_pixel(2400, 1200, image::Rgb([10, 20, 30]));
        let mut bytes = Vec::new();
        image
            .write_with_encoder(PngEncoder::new(&mut bytes))
            .expect("png");
        let sent =
            from_data_url(&prepare_outgoing(bytes, 1 << 20).expect("scaled")).expect("image");
        assert_eq!((sent.width, sent.height), (MAX_SENT_EDGE, 960));
    }

    #[test]
    fn an_image_that_cannot_fit_is_refused() {
        assert!(prepare_outgoing(png(900, 600), 300).is_err());
    }

    #[test]
    fn the_cache_forgets_the_oldest_images_first() {
        let image = |size: usize| ImageData {
            bytes: vec![0; size],
            mime: "image/png".into(),
            width: 1,
            height: 1,
            animated: false,
        };
        let mut cache = ImageCache::default();
        cache.put("1", vec![("0".into(), image(CACHE_BYTES / 2))]);
        cache.put("2", vec![("0".into(), image(CACHE_BYTES / 2))]);
        assert!(cache.get("1", "0").is_some());
        cache.put("3", vec![("0".into(), image(CACHE_BYTES / 2))]);
        assert!(cache.get("1", "0").is_none());
        assert!(cache.get("2", "0").is_some() && cache.get("3", "0").is_some());
        assert!(cache.get("3", "1").is_none());
    }
}
