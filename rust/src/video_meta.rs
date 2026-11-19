use std::io::Cursor;

use image::RgbImage;
use symphonia::core::codecs::video::well_known::extra_data::{
    VIDEO_EXTRA_DATA_ID_AVC_DECODER_CONFIG, VIDEO_EXTRA_DATA_ID_HEVC_DECODER_CONFIG,
    VIDEO_EXTRA_DATA_ID_VP9_DECODER_CONFIG,
};
use symphonia::core::codecs::video::well_known::{
    CODEC_ID_H264, CODEC_ID_HEVC, CODEC_ID_VP8, CODEC_ID_VP9,
};
use symphonia::core::codecs::video::{VideoCodecId, VideoCodecParameters, VideoExtraDataId};
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo, Track};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::units::{Time, TimeBase};
use videoson::{
    CodecType, NalFormat, Packet, PixelFormat, PlaneData, VideoCodecParams, VideoDecoder,
    VideoDecoderOptions, VideoFrame, VideoFramePlanes, VideoOutputFormat,
};

const POSTER_MAX_EDGE: u32 = 512;
const POSTER_QUALITY: u8 = 80;
const DECODE_FRAME_BUDGET: usize = 150;
const POSTER_TARGET_US: i64 = 1_000_000;
const MAX_SOURCE_PIXELS: u64 = 4096 * 2304;

pub(crate) struct VideoPoster {
    pub width: u32,
    pub height: u32,
    pub duration_ms: Option<u64>,
    pub thumbnail: Vec<u8>,
    pub thumbnail_width: u32,
    pub thumbnail_height: u32,
    pub blurhash: Option<String>,
}

struct VideoSource {
    track_id: u32,
    codec: CodecType,
    coded_width: u32,
    coded_height: u32,
    extradata: Vec<u8>,
    nal_format: Option<NalFormat>,
    time_base: Option<TimeBase>,
    duration_ms: Option<u64>,
}

fn video_source(tracks: &[Track]) -> Option<VideoSource> {
    let track = tracks
        .iter()
        .find(|t| t.codec_params.as_ref().and_then(|p| p.video()).is_some())?;
    let params = track.codec_params.as_ref()?.video()?;
    let codec = codec_type(params.codec)?;
    let extradata = match codec {
        CodecType::H264 => decoder_config(VIDEO_EXTRA_DATA_ID_AVC_DECODER_CONFIG, params)?,
        CodecType::H265 => decoder_config(VIDEO_EXTRA_DATA_ID_HEVC_DECODER_CONFIG, params)?,
        CodecType::VP9 => {
            decoder_config(VIDEO_EXTRA_DATA_ID_VP9_DECODER_CONFIG, params).unwrap_or_default()
        }
        _ => Vec::new(),
    };
    Some(VideoSource {
        track_id: track.id,
        codec,
        coded_width: u32::from(params.width?),
        coded_height: u32::from(params.height?),
        nal_format: nal_format_for(codec, &extradata),
        extradata,
        time_base: track.time_base,
        duration_ms: track
            .duration
            .zip(track.time_base)
            .and_then(|(dur, tb)| tb.calc_duration(dur))
            .map(|t| (t.as_micros().max(0) as u64) / 1000)
            .filter(|ms| *ms > 0),
    })
}

pub(crate) fn poster_from_bytes(bytes: &[u8], ext: Option<&str>) -> Option<VideoPoster> {
    let (source, frame) =
        decode_source(bytes, ext, true).or_else(|| decode_source(bytes, ext, false))?;

    let (rotation, display_width, display_height) = iso_bmff_orientation(bytes)
        .unwrap_or((0, source.coded_width, source.coded_height));
    let rotated = rotate(frame, rotation);
    let (thumbnail, thumbnail_width, thumbnail_height) = resized_jpeg(&rotated)?;

    Some(VideoPoster {
        width: display_width,
        height: display_height,
        duration_ms: source.duration_ms,
        blurhash: blurhash_of(&rotated),
        thumbnail,
        thumbnail_width,
        thumbnail_height,
    })
}

fn decode_source(
    bytes: &[u8],
    ext: Option<&str>,
    seek: bool,
) -> Option<(VideoSource, RgbImage)> {
    let mss = MediaSourceStream::new(Box::new(Cursor::new(bytes)), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = ext.filter(|e| !e.is_empty()) {
        hint.with_extension(ext);
    }
    let reader = symphonia::default::get_probe()
        .probe(
            &hint,
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        )
        .ok()?;

    let source = video_source(reader.tracks())?;
    let target_us = source
        .duration_ms
        .map(|ms| (ms.saturating_mul(1000) / 10) as i64)
        .unwrap_or(0)
        .clamp(0, POSTER_TARGET_US);

    let frame = decode_poster_frame(reader, &source, target_us, seek)?;
    Some((source, frame))
}

fn codec_type(codec: VideoCodecId) -> Option<CodecType> {
    match codec {
        CODEC_ID_H264 => Some(CodecType::H264),
        CODEC_ID_HEVC => Some(CodecType::H265),
        CODEC_ID_VP8 => Some(CodecType::VP8),
        CODEC_ID_VP9 => Some(CodecType::VP9),
        _ => None,
    }
}

fn decoder_config(id: VideoExtraDataId, params: &VideoCodecParameters) -> Option<Vec<u8>> {
    params
        .extra_data
        .iter()
        .find(|d| d.id == id)
        .map(|d| d.data.to_vec())
}

fn nal_format_for(codec: CodecType, extradata: &[u8]) -> Option<NalFormat> {
    match codec {
        CodecType::H264 => Some(NalFormat::Avcc {
            nal_len_size: avc_nal_len_size(extradata).unwrap_or(4),
        }),
        CodecType::H265 => Some(NalFormat::Hvcc {
            nal_len_size: hvcc_nal_len_size(extradata).unwrap_or(4),
        }),
        _ => None,
    }
}

fn avc_nal_len_size(record: &[u8]) -> Option<u8> {
    let record = strip_box(record, b"avcC");
    Some((record.get(4)? & 0x03) + 1)
}

fn hvcc_nal_len_size(record: &[u8]) -> Option<u8> {
    let record = strip_box(record, b"hvcC");
    Some((record.get(21)? & 0x03) + 1)
}

fn strip_box<'a>(record: &'a [u8], fourcc: &[u8; 4]) -> &'a [u8] {
    if record.len() >= 8 && &record[4..8] == fourcc.as_slice() {
        &record[8..]
    } else {
        record
    }
}

fn new_decoder(
    codec: CodecType,
    width: u32,
    height: u32,
    extradata: Vec<u8>,
    nal_format: Option<NalFormat>,
) -> Option<Box<dyn VideoDecoder>> {
    let params = VideoCodecParams {
        codec,
        coded_width: width,
        coded_height: height,
        extradata,
        nal_format,
    };
    let opts = VideoDecoderOptions {
        output_format: VideoOutputFormat::Yuv420,
        max_pixels: Some(MAX_SOURCE_PIXELS),
        ..Default::default()
    };
    let decoder: Box<dyn VideoDecoder> = match codec {
        CodecType::H264 => Box::new(videoson::codec_h264::H264Decoder::try_new(&params, &opts).ok()?),
        CodecType::H265 => Box::new(videoson::codec_h265::H265Decoder::try_new(&params, &opts).ok()?),
        CodecType::VP8 => Box::new(videoson::codec_vp8::Vp8Decoder::try_new(&params, &opts).ok()?),
        CodecType::VP9 => Box::new(videoson::codec_vp9::Vp9Decoder::try_new(&params, &opts).ok()?),
        _ => return None,
    };
    Some(decoder)
}

fn decode_poster_frame(
    mut reader: Box<dyn FormatReader + '_>,
    source: &VideoSource,
    target_us: i64,
    seek: bool,
) -> Option<RgbImage> {
    if seek && target_us > 0 {
        let time = Time::try_new(
            (target_us / 1_000_000) as i64,
            (target_us % 1_000_000) as u32 * 1000,
        )?;
        reader
            .seek(
                SeekMode::Coarse,
                SeekTo::Time {
                    time,
                    track_id: Some(source.track_id),
                },
            )
            .ok()?;
    }
    let mut decoder = new_decoder(
        source.codec,
        source.coded_width,
        source.coded_height,
        source.extradata.clone(),
        source.nal_format,
    )?;
    let mut best: Option<RgbImage> = None;

    for _ in 0..DECODE_FRAME_BUDGET {
        let packet = match reader.next_packet() {
            Ok(Some(packet)) => packet,
            _ => break,
        };
        if packet.track_id != source.track_id {
            continue;
        }
        let pts_us = source
            .time_base
            .and_then(|tb| tb.calc_time(packet.pts).map(|t| t.as_micros() as i64))
            .unwrap_or(0);
        let input = Packet {
            track_id: 0,
            pts: Some(pts_us),
            dts: None,
            duration: None,
            is_sync: false,
            data: packet.data.to_vec(),
        };
        if decoder.send_packet(&input).is_err() {
            break;
        }
        while let Ok(Some(frame)) = decoder.receive_frame() {
            let reached = frame.pts.unwrap_or(pts_us) >= target_us;
            if let Some(image) = frame_to_rgb(&frame) {
                best = Some(image);
                if reached {
                    return best;
                }
            }
        }
    }
    best
}

fn frame_to_rgb(frame: &VideoFrame) -> Option<RgbImage> {
    let (width, height) = (frame.width, frame.height);
    if width == 0 || height == 0 {
        return None;
    }
    if u64::from(width) * u64::from(height) > MAX_SOURCE_PIXELS {
        return None;
    }
    let mut out = vec![0u8; width as usize * height as usize * 3];
    let (kr, kb) = luma_weights(frame.color_info.matrix, height);
    let full_range = frame.color_info.full_range;

    let plane = |index: usize| match frame.plane_data.get(index).map(|p| &p.data) {
        Some(PlaneData::U8(data)) => Some((&**data, frame.plane_data[index].stride)),
        _ => None,
    };

    match frame.planes {
        VideoFramePlanes::Yuv420 => {
            let (y, y_stride) = plane(0)?;
            let (u, u_stride) = plane(1)?;
            let (v, v_stride) = plane(2)?;
            for row in 0..height as usize {
                for col in 0..width as usize {
                    let yv = y[row * y_stride + col] as f32;
                    let uv_row = row / 2;
                    let uv_col = col / 2;
                    let uu = u[uv_row * u_stride + uv_col] as f32;
                    let vv = v[uv_row * v_stride + uv_col] as f32;
                    write_pixel(&mut out, row * width as usize + col, yv, uu, vv, full_range, kr, kb);
                }
            }
        }
        VideoFramePlanes::Nv12 => {
            let (y, y_stride) = plane(0)?;
            let (uv, uv_stride) = plane(1)?;
            for row in 0..height as usize {
                for col in 0..width as usize {
                    let yv = y[row * y_stride + col] as f32;
                    let uv_row = row / 2;
                    let uv_col = (col / 2) * 2;
                    let uu = uv[uv_row * uv_stride + uv_col] as f32;
                    let vv = uv[uv_row * uv_stride + uv_col + 1] as f32;
                    write_pixel(&mut out, row * width as usize + col, yv, uu, vv, full_range, kr, kb);
                }
            }
        }
        VideoFramePlanes::Mono if frame.pixfmt == PixelFormat::Gray => {
            let (y, y_stride) = plane(0)?;
            for row in 0..height as usize {
                for col in 0..width as usize {
                    let yv = y[row * y_stride + col];
                    let index = (row * width as usize + col) * 3;
                    out[index] = yv;
                    out[index + 1] = yv;
                    out[index + 2] = yv;
                }
            }
        }
        _ => return None,
    }

    RgbImage::from_raw(width, height, out)
}

fn luma_weights(matrix: u8, height: u32) -> (f32, f32) {
    match matrix {
        1 => (0.2126, 0.0722),
        4 => (0.30, 0.11),
        5 | 6 => (0.299, 0.114),
        9 => (0.2627, 0.0593),
        _ if height >= 720 => (0.2126, 0.0722),
        _ => (0.299, 0.114),
    }
}

#[allow(clippy::too_many_arguments)]
fn write_pixel(
    out: &mut [u8],
    pixel: usize,
    y: f32,
    u: f32,
    v: f32,
    full_range: bool,
    kr: f32,
    kb: f32,
) {
    let (y, u, v) = if full_range {
        (y, u - 128.0, v - 128.0)
    } else {
        ((y - 16.0) * 1.164, u - 128.0, v - 128.0)
    };
    let kg = 1.0 - kr - kb;
    let r = y + 2.0 * (1.0 - kr) * v;
    let b = y + 2.0 * (1.0 - kb) * u;
    let g = y - (2.0 * (1.0 - kb) * kb / kg) * u - (2.0 * (1.0 - kr) * kr / kg) * v;
    let index = pixel * 3;
    out[index] = clamp_u8(r);
    out[index + 1] = clamp_u8(g);
    out[index + 2] = clamp_u8(b);
}

fn clamp_u8(value: f32) -> u8 {
    value.clamp(0.0, 255.0) as u8
}

fn rotate(image: RgbImage, degrees: i32) -> RgbImage {
    match ((degrees % 360) + 360) % 360 {
        90 => image::imageops::rotate90(&image),
        180 => image::imageops::rotate180(&image),
        270 => image::imageops::rotate270(&image),
        _ => image,
    }
}

fn resized_jpeg(image: &RgbImage) -> Option<(Vec<u8>, u32, u32)> {
    let (width, height) = image.dimensions();
    let scale = (POSTER_MAX_EDGE as f32 / width.max(height) as f32).min(1.0);
    let target_width = ((width as f32 * scale).round() as u32).max(1);
    let target_height = ((height as f32 * scale).round() as u32).max(1);
    let resized = if (target_width, target_height) == (width, height) {
        image.clone()
    } else {
        image::imageops::resize(
            image,
            target_width,
            target_height,
            image::imageops::FilterType::Triangle,
        )
    };
    let mut bytes = Vec::new();
    let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, POSTER_QUALITY);
    encoder.encode_image(&resized).ok()?;
    Some((bytes, target_width, target_height))
}

fn blurhash_of(image: &RgbImage) -> Option<String> {
    let sample = image::imageops::resize(image, 32, 32, image::imageops::FilterType::Triangle);
    blurhash::encode_image(4, 3, &image::DynamicImage::ImageRgb8(sample).to_rgba8()).ok()
}

fn iso_bmff_orientation(bytes: &[u8]) -> Option<(i32, u32, u32)> {
    let moov = child_boxes(bytes, 0, bytes.len())
        .into_iter()
        .find(|b| &b.0 == b"moov")?;
    for trak in child_boxes(bytes, moov.1, moov.2)
        .into_iter()
        .filter(|b| &b.0 == b"trak")
    {
        let children = child_boxes(bytes, trak.1, trak.2);
        let Some(mdia) = children.iter().find(|b| &b.0 == b"mdia") else {
            continue;
        };
        let mdia_children = child_boxes(bytes, mdia.1, mdia.2);
        let Some(hdlr) = mdia_children.iter().find(|b| &b.0 == b"hdlr") else {
            continue;
        };
        if bytes.get(hdlr.1 + 8..hdlr.1 + 12) != Some(b"vide".as_slice()) {
            continue;
        }
        let Some(tkhd) = children.iter().find(|b| &b.0 == b"tkhd") else {
            continue;
        };
        return tkhd_orientation(bytes, tkhd.1, tkhd.2);
    }
    None
}

fn tkhd_orientation(bytes: &[u8], start: usize, end: usize) -> Option<(i32, u32, u32)> {
    let version = *bytes.get(start)?;
    let matrix = start + if version == 1 { 52 } else { 40 };
    if matrix + 44 > end {
        return None;
    }
    let read = |offset: usize| -> i32 {
        i32::from_be_bytes([
            bytes[matrix + offset],
            bytes[matrix + offset + 1],
            bytes[matrix + offset + 2],
            bytes[matrix + offset + 3],
        ])
    };
    let a = read(0) as f64 / 65536.0;
    let b = read(4) as f64 / 65536.0;
    let width = u32::from_be_bytes([
        bytes[matrix + 36],
        bytes[matrix + 37],
        bytes[matrix + 38],
        bytes[matrix + 39],
    ]) >> 16;
    let height = u32::from_be_bytes([
        bytes[matrix + 40],
        bytes[matrix + 41],
        bytes[matrix + 42],
        bytes[matrix + 43],
    ]) >> 16;
    if width == 0 || height == 0 {
        return None;
    }
    let angle = b.atan2(a).to_degrees().round() as i32;
    let angle = ((angle % 360) + 360) % 360;
    let (width, height) = if angle == 90 || angle == 270 {
        (height, width)
    } else {
        (width, height)
    };
    Some((angle, width, height))
}

fn child_boxes(bytes: &[u8], start: usize, end: usize) -> Vec<([u8; 4], usize, usize)> {
    let mut boxes = Vec::new();
    let mut position = start;
    while position + 8 <= end {
        let size = u32::from_be_bytes([
            bytes[position],
            bytes[position + 1],
            bytes[position + 2],
            bytes[position + 3],
        ]) as usize;
        let kind = [
            bytes[position + 4],
            bytes[position + 5],
            bytes[position + 6],
            bytes[position + 7],
        ];
        let (header, size) = match size {
            0 => (8, end - position),
            1 if position + 16 <= end => {
                let large = u64::from_be_bytes([
                    bytes[position + 8],
                    bytes[position + 9],
                    bytes[position + 10],
                    bytes[position + 11],
                    bytes[position + 12],
                    bytes[position + 13],
                    bytes[position + 14],
                    bytes[position + 15],
                ]) as usize;
                (16, large)
            }
            _ => (8, size),
        };
        if size < header || position + size > end {
            break;
        }
        boxes.push((kind, position + header, position + size));
        position += size;
    }
    boxes
}
