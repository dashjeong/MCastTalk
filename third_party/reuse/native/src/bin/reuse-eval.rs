use mcasttalk_reuse::Engine;
use std::{env, fs, path::Path, time::Instant};

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().collect();
    if args.len() != 4 {
        return Err("Usage: reuse-eval <local model.safetensors> <16k mono PCM16.wav> <new output directory>".into());
    }
    let out = Path::new(&args[3]);
    if out.exists() { return Err("Output directory already exists; refusing to overwrite recordings".into()); }
    let mut reader = hound::WavReader::open(&args[2])?;
    let spec = reader.spec();
    if spec.channels != 1 || spec.sample_rate != 16000 || spec.bits_per_sample != 16 || spec.sample_format != hound::SampleFormat::Int {
        return Err("Expected 16 kHz mono signed 16-bit WAV".into());
    }
    let samples: Vec<f32> = reader.samples::<i16>().map(|s| s.map(|s| s as f32 / 32768.0)).collect::<Result<_, _>>()?;
    if samples.is_empty() || samples.len() > 16000 * 300 { return Err("Use a nonempty recording up to five minutes".into()); }
    let started = Instant::now();
    let mut engine = Engine::load(&args[1]).map_err(|e| format!("Model load: {e}"))?;
    let load_ms = started.elapsed().as_secs_f64() * 1000.0;
    let mut enhanced = Vec::with_capacity(samples.len() + 640);
    let mut times = Vec::new();
    for chunk in samples.chunks(320) {
        let mut frame = [0f32; 320]; frame[..chunk.len()].copy_from_slice(chunk);
        let started = Instant::now();
        enhanced.extend(engine.process(&frame)?);
        times.push(started.elapsed().as_secs_f64() * 1000.0);
    }
    enhanced.extend(engine.flush()?);
    enhanced.truncate(samples.len());
    if enhanced.len() != samples.len() || enhanced.iter().any(|s| !s.is_finite()) {
        return Err("Invalid enhanced audio length/values".into());
    }
    let total_ms: f64 = times.iter().sum();
    let warmup = if times.len() > 50 { 50 } else { 0 };
    let mut steady = times[warmup..].to_vec(); steady.sort_by(f64::total_cmp);
    let percentile = |p: f64| steady[((steady.len() as f64 * p).ceil() as usize).saturating_sub(1)];
    let report = serde_json::json!({
        "schema":1, "model":"NVIDIA Real-time_RE-USE", "license":"NVIDIA noncommercial research/education only",
        "host_arch":env::consts::ARCH, "host_os":env::consts::OS,
        "sample_rate_hz":16000,"layers":3,"look_ahead_frames":0,"frame_samples":320,
        "input_samples":samples.len(),"output_samples":enhanced.len(),"load_ms":load_ms,
        "inference_ms":total_ms,"real_time_factor":total_ms / (samples.len() as f64 / 16.0),
        "warmup_frames_excluded":warmup,"frame_p50_ms":percentile(0.5),"frame_p95_ms":percentile(0.95),
        "frame_max_ms":steady.last(),"frames_over_12ms":steady.iter().filter(|t| **t > 12.0).count(),
        "quality_evaluation":"NOT_MEASURED: compare matched ASR transcripts and human listening",
        "android_certification":false
    });
    fs::create_dir_all(out)?;
    fs::copy(&args[2], out.join("original.wav"))?;
    let mut writer = hound::WavWriter::create(out.join("reuse.wav"), spec)?;
    for sample in enhanced { writer.write_sample((sample.clamp(-1.0, 1.0) * 32768.0).round().clamp(-32768.0,32767.0) as i16)?; }
    writer.finalize()?;
    fs::write(out.join("timing.json"), serde_json::to_vec_pretty(&report)?)?;
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
fn main() { if let Err(error) = run() { eprintln!("{error}"); std::process::exit(1); } }
