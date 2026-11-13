use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jfloatArray, jlong};
use jni::JNIEnv;
use reuse_rs::{Config, Denoiser};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};

/// Owns a two-thread pool rather than occupying every CPU core needed by ASR/translation.
pub struct Engine {
    denoiser: Denoiser,
    pool: rayon::ThreadPool,
}
impl Engine {
    pub fn load(path: &str) -> Result<Self, String> {
        let mut file = std::fs::File::open(path).map_err(|e| e.to_string())?;
        if file.metadata().map_err(|e| e.to_string())?.len() != 54_055_764 {
            return Err("Wrong research model size".into());
        }
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 65536];
        loop {
            let count = file.read(&mut buffer).map_err(|e| e.to_string())?;
            if count == 0 { break; }
            digest.update(&buffer[..count]);
        }
        if format!("{:x}", digest.finalize()) != "6eac4e8cc721f99a22cde7eac3c9f8e2826ced5dc8a4234fd1977de1ec0d3ade" {
            return Err("Research model SHA-256 mismatch".into());
        }
        let pool = rayon::ThreadPoolBuilder::new().num_threads(2).build().map_err(|e| e.to_string())?;
        let denoiser = pool.install(|| Denoiser::with_config(path,
            Config { layers: 3, sample_rate: 16000, look_ahead: 0 })).map_err(|e| e.to_string())?;
        Ok(Self { denoiser, pool })
    }
    pub fn process(&mut self, input: &[f32]) -> Result<Vec<f32>, String> {
        if input.len() != 320 || input.iter().any(|v| !v.is_finite()) {
            return Err("Expected 320 finite mono samples".into());
        }
        let output = self.pool.install(|| self.denoiser.process(input)).map_err(|e| e.to_string())?;
        if output.iter().any(|v| !v.is_finite()) { return Err("Nonfinite model output".into()); }
        Ok(output)
    }
    pub fn flush(&mut self) -> Result<Vec<f32>, String> {
        self.pool.install(|| self.denoiser.flush()).map_err(|e| e.to_string())
    }
}

static ENGINES: OnceLock<Mutex<HashMap<i64, Engine>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
fn engines() -> &'static Mutex<HashMap<i64, Engine>> { ENGINES.get_or_init(|| Mutex::new(HashMap::new())) }
// A caught inference panic must not permanently prevent cleanup or a later research session.
fn engine_registry() -> MutexGuard<'static, HashMap<i64, Engine>> {
    engines().lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
fn discard_engine(handle: i64) { engine_registry().remove(&handle); }
fn protected<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| Err("RE-USE native inference failed".into()))
}

#[no_mangle]
pub extern "system" fn Java_app_guidecast_core_audio_NativeReuseBackend_create(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jlong {
    let result = protected(|| {
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        let engine = Engine::load(&path)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        engine_registry().insert(id, engine);
        Ok(id)
    });
    match result {
        Ok(id) => id,
        Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "RE-USE model initialization failed"); 0 }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_guidecast_core_audio_NativeReuseBackend_processFrame(
    mut env: JNIEnv, _class: JClass, handle: jlong, input: JFloatArray,
) -> jfloatArray {
    let result = protected(|| {
        if env.get_array_length(&input).map_err(|e| e.to_string())? != 320 { return Err("Wrong input shape".into()); }
        let mut samples = [0f32; 320];
        env.get_float_array_region(&input, 0, &mut samples).map_err(|e| e.to_string())?;
        let output = engine_registry()
            .get_mut(&handle).ok_or("Invalid engine handle")?.process(&samples)?;
        let array = env.new_float_array(output.len() as i32).map_err(|e| e.to_string())?;
        env.set_float_array_region(&array, 0, &output).map_err(|e| e.to_string())?;
        Ok(array.into_raw())
    });
    match result {
        Ok(array) => array,
        Err(_) => {
            // Drop the potentially inconsistent model even if the panic poisoned its lock.
            discard_engine(handle);
            let _ = env.throw_new("java/lang/IllegalStateException", "RE-USE processing failed");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_guidecast_core_audio_NativeReuseBackend_destroy(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    let _ = protected(|| {
        discard_engine(handle);
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn missing_model_is_an_error() { assert!(Engine::load("missing-model.safetensors").is_err()); }
    #[test] fn same_size_corrupt_model_is_rejected_by_hash_before_inference() {
        let suffix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("mcasttalk-reuse-corrupt-{}-{suffix}.safetensors", std::process::id()));
        let file = std::fs::OpenOptions::new().write(true).create_new(true).open(&path).unwrap();
        file.set_len(54_055_764).unwrap(); drop(file);
        let error = Engine::load(path.to_str().unwrap()).err().unwrap();
        std::fs::remove_file(&path).unwrap();
        assert!(error.contains("SHA-256"));
    }
    #[test] fn panic_does_not_unwind_through_jni() {
        assert!(protected::<()>(|| panic!("test")).is_err());
    }
    #[test] fn poisoned_registry_still_allows_cleanup() {
        assert!(protected::<()>(|| { let _guard = engine_registry(); panic!("worker failed"); }).is_err());
        assert!(engine_registry().remove(&-1).is_none());
    }
    #[test]
    #[ignore = "requires the pinned local research weights; synthetic PCM only"]
    fn actual_weights_preserve_sample_count_and_produce_finite_output() {
        let path = std::env::var("REUSE_TEST_MODEL").expect("REUSE_TEST_MODEL");
        let mut engine = Engine::load(&path).unwrap();
        let mut total = 0;
        for step in 0..50 {
            let input: Vec<f32> = (0..320).map(|i| ((step * 320 + i) as f32 * 0.0864).sin() * 0.1).collect();
            let out = engine.process(&input).unwrap();
            assert!(out.iter().all(|v| v.is_finite()));
            assert_eq!(out.len(), if step == 0 { 0 } else { 320 });
            total += out.len();
        }
        total += engine.flush().unwrap().len();
        assert_eq!(total, 16000);
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        engine_registry().insert(id, engine);
        assert!(protected::<()>(|| {
            let mut registry = engine_registry();
            let _owned = registry.get_mut(&id).unwrap();
            panic!("inference panic with registered model");
        }).is_err());
        discard_engine(id);
        assert!(!engine_registry().contains_key(&id));
    }
}
