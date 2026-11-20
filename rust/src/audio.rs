use std::{
    fs::File,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use crossbeam_channel::{Receiver, Sender, bounded};
use rubato::audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Fft, FixedSync, Resampler};
use symphonia::core::{
    codecs::{
        CodecParameters,
        audio::{AudioCodecParameters, AudioDecoder, AudioDecoderOptions},
        registry::CodecRegistry,
    },
    errors::Error as SymphoniaError,
    formats::{FormatOptions, FormatReader, SeekMode, SeekTo, Track, probe::Hint},
    io::MediaSourceStream,
    meta::MetadataOptions,
    units::Time,
};

use crate::types::FfiError;

const NO_SEEK: u64 = u64::MAX;
const QUEUE_SECONDS: usize = 1;
const MIN_SPEED: f32 = 0.25;
const MAX_SPEED: f32 = 4.0;
const LOAD_TIMEOUT: Duration = Duration::from_secs(5);
const BACKOFF: Duration = Duration::from_millis(2);
const DRAIN_POLL: Duration = Duration::from_millis(20);

struct Shared {
    playing: AtomicBool,
    stop: AtomicBool,
    seek_ms: AtomicU64,
    speed_bits: AtomicU32,
    requested_speed_bits: AtomicU32,
    resume_ms: AtomicU64,
    speed_serial: AtomicU64,
    played_frames: AtomicU64,
    pending_frames: AtomicU64,
    base_ms: AtomicU64,
    sample_rate: AtomicU32,
    duration_ms: AtomicU64,
    finished: AtomicBool,
    error: Mutex<Option<String>>,
}

impl Shared {
    fn new() -> Self {
        Self {
            playing: AtomicBool::new(false),
            stop: AtomicBool::new(false),
            seek_ms: AtomicU64::new(NO_SEEK),
            speed_bits: AtomicU32::new(1.0f32.to_bits()),
            requested_speed_bits: AtomicU32::new(1.0f32.to_bits()),
            resume_ms: AtomicU64::new(0),
            speed_serial: AtomicU64::new(0),
            played_frames: AtomicU64::new(0),
            pending_frames: AtomicU64::new(0),
            base_ms: AtomicU64::new(0),
            sample_rate: AtomicU32::new(0),
            duration_ms: AtomicU64::new(0),
            finished: AtomicBool::new(false),
            error: Mutex::new(None),
        }
    }

    fn reset(&self) {
        self.playing.store(false, Ordering::Release);
        self.stop.store(false, Ordering::Release);
        self.seek_ms.store(NO_SEEK, Ordering::Release);
        self.played_frames.store(0, Ordering::Release);
        self.pending_frames.store(0, Ordering::Release);
        self.base_ms.store(0, Ordering::Release);
        self.resume_ms.store(0, Ordering::Release);
        self.sample_rate.store(0, Ordering::Release);
        self.duration_ms.store(0, Ordering::Release);
        self.finished.store(false, Ordering::Release);
        self.error
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
    }

    fn speed(&self) -> f32 {
        f32::from_bits(self.speed_bits.load(Ordering::Acquire)).clamp(MIN_SPEED, MAX_SPEED)
    }

    fn requested_speed(&self) -> f32 {
        f32::from_bits(self.requested_speed_bits.load(Ordering::Acquire))
            .clamp(MIN_SPEED, MAX_SPEED)
    }

    fn set_error(&self, message: impl Into<String>) {
        self.playing.store(false, Ordering::Release);
        self.finished.store(true, Ordering::Release);
        *self.error.lock().unwrap_or_else(|error| error.into_inner()) = Some(message.into());
    }
}

enum Command {
    Shutdown,
}

struct Session {
    commands: Sender<Command>,
    thread: Option<thread::JoinHandle<()>>,
}

#[derive(uniffi::Object)]
pub struct AudioEngine {
    shared: Arc<Shared>,
    session: Mutex<Option<Session>>,
}

#[uniffi::export]
impl AudioEngine {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            shared: Arc::new(Shared::new()),
            session: Mutex::new(None),
        }
    }

    pub fn load(&self, path: String) -> Result<i64, FfiError> {
        self.stop_session();
        self.shared.reset();
        let (commands, receiver) = bounded(4);
        let (reply, reply_rx) = bounded(1);
        let shared = self.shared.clone();
        let source = PathBuf::from(path);
        let handle = thread::Builder::new()
            .name("mages-audio".into())
            .spawn(move || run_session(source, receiver, reply, shared))
            .map_err(|error| FfiError::Msg(format!("audio thread: {error}")))?;
        *self.session.lock().unwrap_or_else(|error| error.into_inner()) = Some(Session {
            commands,
            thread: Some(handle),
        });
        match reply_rx.recv_timeout(LOAD_TIMEOUT) {
            Ok(Ok(duration_ms)) => Ok(duration_ms),
            Ok(Err(message)) => {
                self.stop_session();
                Err(FfiError::Msg(message))
            }
            Err(_) => {
                self.stop_session();
                Err(FfiError::Msg("audio load timed out".into()))
            }
        }
    }

    pub fn play(&self) {
        if self.has_session() {
            self.shared.playing.store(true, Ordering::Release);
        }
    }

    pub fn pause(&self) {
        self.shared.playing.store(false, Ordering::Release);
    }

    pub fn stop(&self) {
        self.stop_session();
    }

    pub fn shutdown(&self) {
        self.stop_session();
    }

    pub fn seek(&self, position_ms: i64) {
        if self.has_session() {
            self.shared.finished.store(false, Ordering::Release);
            self.shared
                .seek_ms
                .store(position_ms.max(0) as u64, Ordering::Release);
        }
    }

    pub fn set_speed(&self, speed: f32) {
        let speed = speed.clamp(MIN_SPEED, MAX_SPEED);
        let position = self.position_ms().max(0) as u64;
        self.shared.resume_ms.store(position, Ordering::Release);
        self.shared
            .requested_speed_bits
            .store(speed.to_bits(), Ordering::Release);
        self.shared.speed_serial.fetch_add(1, Ordering::AcqRel);
    }

    pub fn position_ms(&self) -> i64 {
        let rate = self.shared.sample_rate.load(Ordering::Acquire);
        if rate == 0 {
            return 0;
        }
        let played = self.shared.played_frames.load(Ordering::Acquire) as f64;
        let speed = self.shared.speed() as f64;
        let base = self.shared.base_ms.load(Ordering::Acquire) as f64;
        let position = (base + played * 1000.0 * speed / rate as f64).max(0.0) as i64;
        let duration = self.shared.duration_ms.load(Ordering::Acquire) as i64;
        if duration > 0 {
            position.min(duration)
        } else {
            position
        }
    }

    pub fn duration_ms(&self) -> i64 {
        self.shared.duration_ms.load(Ordering::Acquire) as i64
    }

    pub fn is_finished(&self) -> bool {
        self.shared.finished.load(Ordering::Acquire)
            && self.shared.pending_frames.load(Ordering::Acquire) == 0
    }

    pub fn error(&self) -> Option<String> {
        self.shared
            .error
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone()
    }
}

impl AudioEngine {
    fn has_session(&self) -> bool {
        self.session
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .is_some()
    }

    fn stop_session(&self) {
        let mut guard = self.session.lock().unwrap_or_else(|error| error.into_inner());
        let Some(mut session) = guard.take() else {
            return;
        };
        self.shared.stop.store(true, Ordering::Release);
        self.shared.playing.store(false, Ordering::Release);
        let _ = session.commands.send(Command::Shutdown);
        if let Some(handle) = session.thread.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for AudioEngine {
    fn drop(&mut self) {
        self.stop_session();
    }
}

struct Prepared {
    reader: Box<dyn FormatReader>,
    track_id: u32,
    decoder: Box<dyn AudioDecoder>,
    sample_rate: u32,
    duration_ms: i64,
}

struct Output {
    _stream: cpal::Stream,
    samples: Sender<f32>,
    flush: Receiver<f32>,
    capacity: usize,
    channels: usize,
    rate: u32,
}

fn codecs() -> &'static CodecRegistry {
    static REGISTRY: OnceLock<CodecRegistry> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut registry = CodecRegistry::new();
        symphonia::default::register_enabled_codecs(&mut registry);
        registry.register_audio_decoder::<symphonia_adapter_oporus::OpusDecoder>();
        registry
    })
}

fn open_reader(path: &Path) -> Result<Box<dyn FormatReader>, String> {
    let file = File::open(path).map_err(|error| format!("open {}: {error}", path.display()))?;
    let source = MediaSourceStream::new(Box::new(file), Default::default());
    let mut hint = Hint::new();
    if let Some(extension) = path.extension().and_then(|value| value.to_str()) {
        hint.with_extension(extension);
    }
    symphonia::default::get_probe()
        .probe(
            &hint,
            source,
            FormatOptions::default(),
            MetadataOptions::default(),
        )
        .map_err(|error| format!("unsupported audio: {error}"))
}

fn select_track(
    reader: &dyn FormatReader,
) -> Result<(u32, AudioCodecParameters, i64), String> {
    let track = reader
        .tracks()
        .iter()
        .find(|track| matches!(&track.codec_params, Some(CodecParameters::Audio(_))))
        .ok_or_else(|| "no audio track".to_string())?;
    let params = match &track.codec_params {
        Some(CodecParameters::Audio(params)) => params.clone(),
        _ => return Err("no audio track".into()),
    };
    let duration_ms = track_duration_ms(track, params.sample_rate.unwrap_or(48_000));
    Ok((track.id, params, duration_ms))
}

fn track_duration_ms(track: &Track, sample_rate: u32) -> i64 {
    track
        .num_frames
        .map(|frames| (frames as f64 * 1000.0 / sample_rate.max(1) as f64) as i64)
        .filter(|duration| *duration > 0)
        .or_else(|| {
            track
                .duration
                .zip(track.time_base)
                .and_then(|(duration, base)| base.calc_duration(duration))
                .map(|time| (time.as_micros().max(0) / 1000) as i64)
        })
        .unwrap_or(0)
}

fn prepare(path: &Path) -> Result<Prepared, String> {
    let reader = open_reader(path)?;
    let (track_id, params, duration_ms) = select_track(reader.as_ref())?;
    let decoder = codecs()
        .make_audio_decoder(&params, &AudioDecoderOptions::default())
        .map_err(|error| format!("audio decoder: {error}"))?;
    Ok(Prepared {
        reader,
        track_id,
        decoder,
        sample_rate: params.sample_rate.unwrap_or(48_000),
        duration_ms,
    })
}

fn start_output(shared: Arc<Shared>) -> Result<Output, String> {
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or_else(|| "no audio output device".to_string())?;
    let supported = device
        .default_output_config()
        .map_err(|error| format!("audio output config: {error}"))?;
    let sample_format = supported.sample_format();
    let config: cpal::StreamConfig = supported.into();
    let channels = config.channels as usize;
    let rate = config.sample_rate;
    let capacity = rate as usize * channels.max(1) * QUEUE_SECONDS;
    let (samples, receiver) = bounded(capacity);
    let flush = receiver.clone();
    let error_state = shared.clone();
    let on_error = move |error: cpal::Error| {
        error_state.set_error(format!("audio output: {error}"));
    };

    let stream = match sample_format {
        cpal::SampleFormat::F32 => {
            let receiver = receiver.clone();
            let shared = shared.clone();
            device.build_output_stream(
                config,
                move |data: &mut [f32], _| fill_f32(data, &receiver, &shared, channels),
                on_error,
                None,
            )
        }
        cpal::SampleFormat::I16 => {
            let receiver = receiver.clone();
            let shared = shared.clone();
            device.build_output_stream(
                config,
                move |data: &mut [i16], _| fill_i16(data, &receiver, &shared, channels),
                on_error,
                None,
            )
        }
        cpal::SampleFormat::U16 => {
            let receiver = receiver.clone();
            let shared = shared.clone();
            device.build_output_stream(
                config,
                move |data: &mut [u16], _| fill_u16(data, &receiver, &shared, channels),
                on_error,
                None,
            )
        }
        other => return Err(format!("unsupported output sample format: {other:?}")),
    }
    .map_err(|error| format!("audio output stream: {error}"))?;

    stream
        .play()
        .map_err(|error| format!("audio output start: {error}"))?;

    Ok(Output {
        _stream: stream,
        samples,
        flush,
        capacity,
        channels,
        rate,
    })
}

fn account(shared: &Shared, frames: usize) {
    if frames == 0 {
        return;
    }
    shared
        .played_frames
        .fetch_add(frames as u64, Ordering::AcqRel);
    let _ = shared.pending_frames.fetch_update(
        Ordering::AcqRel,
        Ordering::Acquire,
        |pending| Some(pending.saturating_sub(frames as u64)),
    );
}

fn fill_f32(data: &mut [f32], receiver: &Receiver<f32>, shared: &Shared, channels: usize) {
    let channels = channels.max(1);
    let mut consumed = 0;
    if shared.playing.load(Ordering::Acquire) {
        while consumed + channels <= data.len() && receiver.len() >= channels {
            for slot in &mut data[consumed..consumed + channels] {
                *slot = receiver.recv().unwrap_or(0.0);
            }
            consumed += channels;
        }
    }
    data[consumed..].fill(0.0);
    account(shared, consumed / channels);
}

fn fill_i16(data: &mut [i16], receiver: &Receiver<f32>, shared: &Shared, channels: usize) {
    let channels = channels.max(1);
    let mut consumed = 0;
    if shared.playing.load(Ordering::Acquire) {
        while consumed + channels <= data.len() && receiver.len() >= channels {
            for slot in &mut data[consumed..consumed + channels] {
                let sample = receiver.recv().unwrap_or(0.0).clamp(-1.0, 1.0);
                *slot = (sample * i16::MAX as f32) as i16;
            }
            consumed += channels;
        }
    }
    data[consumed..].fill(0);
    account(shared, consumed / channels);
}

fn fill_u16(data: &mut [u16], receiver: &Receiver<f32>, shared: &Shared, channels: usize) {
    let channels = channels.max(1);
    let mut consumed = 0;
    if shared.playing.load(Ordering::Acquire) {
        while consumed + channels <= data.len() && receiver.len() >= channels {
            for slot in &mut data[consumed..consumed + channels] {
                let sample = receiver.recv().unwrap_or(0.0).clamp(-1.0, 1.0) * 0.5 + 0.5;
                *slot = (sample * u16::MAX as f32) as u16;
            }
            consumed += channels;
        }
    }
    data[consumed..].fill(u16::MAX / 2);
    account(shared, consumed / channels);
}

struct ResampledAudio {
    inner: Fft<f32>,
    channels: usize,
    input_frames_needed: usize,
    input_buf: Vec<f32>,
    input_pos: usize,
    output_buf: Vec<f32>,
    output_capacity: usize,
}

impl ResampledAudio {
    fn new(in_rate: u32, out_rate: u32, channels: usize) -> Result<Self, String> {
        let channels = channels.max(1);
        let chunk_size = 512.min(in_rate.max(out_rate) as usize / 10).max(64);
        let inner = Fft::<f32>::new(
            in_rate as usize,
            out_rate as usize,
            chunk_size,
            channels,
            FixedSync::Input,
        )
        .map_err(|error| format!("resampler: {error}"))?;
        let input_frames_needed = inner.input_frames_next();
        let output_capacity = inner.output_frames_max();
        Ok(Self {
            inner,
            channels,
            input_frames_needed,
            input_buf: vec![0.0; input_frames_needed * channels],
            input_pos: 0,
            output_buf: vec![0.0; output_capacity * channels],
            output_capacity,
        })
    }

    fn process(&mut self, input: &[f32], in_channels: usize) -> Result<Vec<f32>, String> {
        let in_channels = in_channels.max(1);
        let out_channels = self.channels;
        let in_frames = input.len() / in_channels;
        if in_frames == 0 {
            return Ok(Vec::new());
        }

        let new_pos = self.input_pos + in_frames;
        let needed = new_pos * out_channels;
        if needed > self.input_buf.len() {
            self.input_buf.resize(needed, 0.0);
        }
        for frame in 0..in_frames {
            let position = (self.input_pos + frame) * out_channels;
            for channel in 0..out_channels {
                self.input_buf[position + channel] =
                    map_sample(input, frame, channel, in_channels, out_channels);
            }
        }
        self.input_pos = new_pos;

        let mut output = Vec::new();
        while self.input_pos >= self.input_frames_needed {
            let total = self.input_frames_needed * out_channels;
            let input_adapter = InterleavedSlice::new(
                &self.input_buf[..total],
                out_channels,
                self.input_frames_needed,
            )
            .map_err(|error| format!("resampler input: {error}"))?;
            let capacity = self.output_capacity * out_channels;
            let mut output_adapter = InterleavedSlice::new_mut(
                &mut self.output_buf[..capacity],
                out_channels,
                self.output_capacity,
            )
            .map_err(|error| format!("resampler output: {error}"))?;
            let (_, written) = self
                .inner
                .process_into_buffer(&input_adapter, &mut output_adapter, None)
                .map_err(|error| format!("resample: {error}"))?;
            output.extend_from_slice(&self.output_buf[..written * out_channels]);

            let consumed = self.input_frames_needed;
            let remaining = self.input_pos - consumed;
            if remaining > 0 {
                self.input_buf.copy_within(consumed * out_channels.., 0);
            }
            self.input_pos = remaining;
        }

        Ok(output)
    }

    fn flush(&mut self) -> Result<Vec<f32>, String> {
        if self.input_pos == 0 {
            return Ok(Vec::new());
        }
        let out_channels = self.channels;
        let total = self.input_frames_needed * out_channels;
        let used = self.input_pos * out_channels;
        self.input_buf[used..total].fill(0.0);
        let input_adapter = InterleavedSlice::new(
            &self.input_buf[..total],
            out_channels,
            self.input_frames_needed,
        )
        .map_err(|error| format!("resampler flush input: {error}"))?;
        let capacity = self.output_capacity * out_channels;
        let mut output_adapter = InterleavedSlice::new_mut(
            &mut self.output_buf[..capacity],
            out_channels,
            self.output_capacity,
        )
        .map_err(|error| format!("resampler flush output: {error}"))?;
        let indexing = rubato::Indexing::new().partial_len(self.input_pos);
        let (_, written) = self
            .inner
            .process_into_buffer(&input_adapter, &mut output_adapter, Some(&indexing))
            .map_err(|error| format!("resample flush: {error}"))?;
        self.input_pos = 0;
        Ok(self.output_buf[..written * out_channels].to_vec())
    }

    fn reset(&mut self) {
        self.input_pos = 0;
        self.inner.reset();
    }
}

fn map_sample(
    input: &[f32],
    frame: usize,
    out_channel: usize,
    in_channels: usize,
    out_channels: usize,
) -> f32 {
    let base = frame * in_channels;
    if out_channels == 1 && in_channels > 1 {
        let sum: f32 = (0..in_channels)
            .map(|channel| input.get(base + channel).copied().unwrap_or(0.0))
            .sum();
        sum / in_channels as f32
    } else if in_channels == 1 {
        input.get(base).copied().unwrap_or(0.0)
    } else {
        input
            .get(base + out_channel.min(in_channels - 1))
            .copied()
            .unwrap_or(0.0)
    }
}

fn rebuild_resampler(
    in_rate: u32,
    speed: f32,
    out_rate: u32,
    channels: usize,
) -> Result<ResampledAudio, String> {
    let target = (out_rate as f64 / speed.clamp(MIN_SPEED, MAX_SPEED) as f64).round() as u32;
    ResampledAudio::new(in_rate.max(1), target.max(8_000), channels)
}

fn push_samples(samples: &[f32], output: &Output, shared: &Shared, serial: u64) -> bool {
    let channels = output.channels.max(1);
    let mut index = 0;
    let mut frames = 0u64;
    'push: while index + channels <= samples.len() {
        if shared.stop.load(Ordering::Acquire) {
            break;
        }
        if shared.seek_ms.load(Ordering::Acquire) != NO_SEEK {
            break;
        }
        if shared.speed_serial.load(Ordering::Acquire) != serial {
            break;
        }
        if output.samples.len() + channels > output.capacity {
            thread::sleep(BACKOFF);
            continue;
        }
        for sample in &samples[index..index + channels] {
            if output.samples.try_send(*sample).is_err() {
                break 'push;
            }
        }
        index += channels;
        frames += 1;
    }
    if frames > 0 {
        shared.pending_frames.fetch_add(frames, Ordering::AcqRel);
    }
    !shared.stop.load(Ordering::Acquire)
}

fn reset_position(
    prepared: &mut Prepared,
    output: &Output,
    shared: &Shared,
    target: u64,
) -> Result<(), String> {
    let time = Time::try_from_secs_f64(target as f64 / 1000.0).unwrap_or(Time::ZERO);
    prepared
        .reader
        .seek(
            SeekMode::Accurate,
            SeekTo::Time {
                time,
                track_id: Some(prepared.track_id),
            },
        )
        .map_err(|error| format!("seek failed: {error}"))?;
    prepared.decoder.reset();
    while output.flush.try_recv().is_ok() {}
    shared.pending_frames.store(0, Ordering::Release);
    shared.played_frames.store(0, Ordering::Release);
    shared.base_ms.store(target, Ordering::Release);
    shared.finished.store(false, Ordering::Release);
    Ok(())
}

fn apply_seek(
    prepared: &mut Prepared,
    resampler: &mut ResampledAudio,
    output: &Output,
    shared: &Shared,
) -> bool {
    let Some(target) = take_seek(shared) else {
        return false;
    };
    match reset_position(prepared, output, shared, target) {
        Ok(()) => {
            resampler.reset();
            shared
                .error
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .take();
            true
        }
        Err(message) => {
            shared.set_error(message);
            false
        }
    }
}

fn take_seek(shared: &Shared) -> Option<u64> {
    let target = shared.seek_ms.swap(NO_SEEK, Ordering::AcqRel);
    (target != NO_SEEK).then_some(target)
}

fn run_session(
    path: PathBuf,
    commands: Receiver<Command>,
    reply: Sender<Result<i64, String>>,
    shared: Arc<Shared>,
) {
    let mut prepared = match prepare(&path) {
        Ok(prepared) => prepared,
        Err(message) => {
            shared.set_error(message.clone());
            let _ = reply.send(Err(message));
            return;
        }
    };
    let output = match start_output(shared.clone()) {
        Ok(output) => output,
        Err(message) => {
            shared.set_error(message.clone());
            let _ = reply.send(Err(message));
            return;
        }
    };
    let mut resampler = match rebuild_resampler(
        prepared.sample_rate,
        shared.requested_speed(),
        output.rate,
        output.channels,
    ) {
        Ok(resampler) => resampler,
        Err(message) => {
            shared.set_error(message.clone());
            let _ = reply.send(Err(message));
            return;
        }
    };

    shared.sample_rate.store(output.rate, Ordering::Release);
    shared
        .speed_bits
        .store(shared.requested_speed().to_bits(), Ordering::Release);
    shared
        .duration_ms
        .store(prepared.duration_ms.max(0) as u64, Ordering::Release);
    shared.playing.store(true, Ordering::Release);
    let _ = reply.send(Ok(prepared.duration_ms));

    let mut speed_serial = shared.speed_serial.load(Ordering::Acquire);
    let mut eof = false;

    loop {
        if shared.stop.load(Ordering::Acquire) {
            break;
        }
        match commands.try_recv() {
            Ok(Command::Shutdown) | Err(crossbeam_channel::TryRecvError::Disconnected) => break,
            Err(crossbeam_channel::TryRecvError::Empty) => {}
        }

        if shared.seek_ms.load(Ordering::Acquire) != NO_SEEK
            && apply_seek(&mut prepared, &mut resampler, &output, &shared)
        {
            eof = false;
        }

        let serial = shared.speed_serial.load(Ordering::Acquire);
        if serial != speed_serial {
            speed_serial = serial;
            let requested = shared.requested_speed();
            match rebuild_resampler(
                prepared.sample_rate,
                requested,
                output.rate,
                output.channels,
            ) {
                Ok(rebuilt) => {
                    let position = shared.resume_ms.load(Ordering::Acquire);
                    match reset_position(&mut prepared, &output, &shared, position) {
                        Ok(()) => {
                            resampler = rebuilt;
                            shared
                                .speed_bits
                                .store(requested.to_bits(), Ordering::Release);
                            eof = false;
                        }
                        Err(message) => {
                            shared.set_error(message);
                            break;
                        }
                    }
                }
                Err(message) => {
                    shared.set_error(message);
                    break;
                }
            }
        }

        if eof {
            if shared.pending_frames.load(Ordering::Acquire) == 0 {
                break;
            }
            match commands.recv_timeout(DRAIN_POLL) {
                Ok(Command::Shutdown) | Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                    break;
                }
                _ => continue,
            }
        }

        let packet = match prepared.reader.next_packet() {
            Ok(Some(packet)) => packet,
            Ok(None) => {
                finish_stream(&mut resampler, &output, &shared);
                eof = true;
                continue;
            }
            Err(SymphoniaError::IoError(error))
                if error.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                finish_stream(&mut resampler, &output, &shared);
                eof = true;
                continue;
            }
            Err(SymphoniaError::ResetRequired) => {
                match reset_track(&mut prepared, &mut resampler, &output, &shared) {
                    Ok(()) => continue,
                    Err(message) => {
                        shared.set_error(message);
                        break;
                    }
                }
            }
            Err(error) => {
                shared.set_error(format!("audio read: {error}"));
                break;
            }
        };

        if packet.track_id != prepared.track_id {
            continue;
        }

        let decoded = match prepared.decoder.decode(&packet) {
            Ok(decoded) => decoded,
            Err(SymphoniaError::DecodeError(_)) | Err(SymphoniaError::IoError(_)) => continue,
            Err(SymphoniaError::ResetRequired) => {
                prepared.decoder.reset();
                continue;
            }
            Err(error) => {
                shared.set_error(format!("audio decode: {error}"));
                break;
            }
        };

        let in_channels = decoded.spec().channels().count().max(1);
        let mut samples = vec![0.0f32; decoded.samples_interleaved()];
        decoded.copy_to_slice_interleaved(&mut samples);
        let converted = match resampler.process(&samples, in_channels) {
            Ok(converted) => converted,
            Err(message) => {
                shared.set_error(message);
                break;
            }
        };
        if !push_samples(&converted, &output, &shared, speed_serial) {
            break;
        }
    }

    shared.playing.store(false, Ordering::Release);
}

fn finish_stream(resampler: &mut ResampledAudio, output: &Output, shared: &Shared) {
    if let Ok(tail) = resampler.flush() {
        push_samples(
            &tail,
            output,
            shared,
            shared.speed_serial.load(Ordering::Acquire),
        );
    }
    shared.finished.store(true, Ordering::Release);
}

fn reset_track(
    prepared: &mut Prepared,
    resampler: &mut ResampledAudio,
    output: &Output,
    shared: &Shared,
) -> Result<(), String> {
    let (track_id, params, duration_ms) = select_track(prepared.reader.as_ref())?;
    let decoder = codecs()
        .make_audio_decoder(&params, &AudioDecoderOptions::default())
        .map_err(|error| format!("audio decoder: {error}"))?;
    let sample_rate = params.sample_rate.unwrap_or(48_000);
    let rebuilt = rebuild_resampler(
        sample_rate,
        shared.requested_speed(),
        output.rate,
        output.channels,
    )?;
    prepared.track_id = track_id;
    prepared.decoder = decoder;
    prepared.sample_rate = sample_rate;
    prepared.duration_ms = duration_ms;
    *resampler = rebuilt;
    shared
        .duration_ms
        .store(duration_ms.max(0) as u64, Ordering::Release);
    shared.finished.store(false, Ordering::Release);
    Ok(())
}
