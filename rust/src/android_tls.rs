use std::{error::Error, mem::MaybeUninit};

use jni::errors::{Error as JniError, JniError as RawJniError};
use tracing::{debug, error};

use crate::FfiError;

static ANDROID_JVM: once_cell::sync::OnceCell<jni::JavaVM> = once_cell::sync::OnceCell::new();

static TLS_INIT_ERROR: once_cell::sync::OnceCell<String> = once_cell::sync::OnceCell::new();

pub(crate) fn init() {
    debug!("Initializing Android platform support");

    ANDROID_JVM.get_or_init(|| match get_java_vm() {
        Ok(jvm) => {
            if let Err(e) = jvm.attach_current_thread(init_rustls_platform_verifier) {
                let msg = format!("Failed to initialize rustls platform verifier: {e}");
                error!("{msg}");
                TLS_INIT_ERROR.get_or_init(|| msg);
            } else {
                debug!("Android platform support initialized successfully");
            }

            jvm
        }
        Err(e) => {
            let msg = format!("Failed to initialize Android platform support: {e}");
            error!("{msg}");
            TLS_INIT_ERROR.get_or_init(|| msg.clone());
            panic!("{msg}");
        }
    });
}

pub(crate) fn check_tls() -> Result<(), FfiError> {
    if let Some(err) = TLS_INIT_ERROR.get() {
        return Err(FfiError::TlsUnavailable(err.clone()));
    }
    if ANDROID_JVM.get().is_some() {
        return Ok(());
    }
    init();
    check_tls()
}

fn get_java_vm() -> Result<jni::JavaVM, Box<dyn Error>> {
    debug!("Getting a JVM pointer");
    #[allow(non_snake_case)]
    let JNI_GetCreatedJavaVMs = unsafe {
        jvm_getter::find_jni_get_created_java_vms().expect("Failed to find JNI_GetCreatedJavaVMs")
    };

    let mut vm: MaybeUninit<*mut jni::sys::JavaVM> = MaybeUninit::uninit();
    let status = unsafe { JNI_GetCreatedJavaVMs(vm.as_mut_ptr() as *mut _, 1, &mut 0) };
    if status != 0 {
        panic!("no JavaVM was found by JNI_GetCreatedJavaVMs");
    }

    Ok(unsafe { jni::JavaVM::from_raw(vm.assume_init()) })
}

fn init_rustls_platform_verifier(env: &mut jni::Env<'_>) -> jni::errors::Result<()> {
    let activity_thread = env
        .call_static_method(
            jni::jni_str!("android/app/ActivityThread"),
            jni::jni_str!("currentActivityThread"),
            jni::jni_sig!(() -> android.app.ActivityThread),
            &[],
        )?
        .l()?;

    let context = env
        .call_method(
            &activity_thread,
            jni::jni_str!("getApplication"),
            jni::jni_sig!(() -> android.app.Application),
            &[],
        )?
        .l()?;

    rustls_platform_verifier::android::init_with_env(env, context)?;
    Ok(())
}

#[allow(dead_code)]
pub(crate) fn attach_current_thread<T>(
    callback: impl FnOnce(&mut jni::Env<'_>) -> jni::errors::Result<T>,
) -> jni::errors::Result<T> {
    let jvm = ANDROID_JVM
        .get()
        .ok_or_else(|| JniError::JniCall(RawJniError::Unknown))?;
    jvm.attach_current_thread(callback)
}
