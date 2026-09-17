use std::{error::Error, mem::MaybeUninit};

use jni::errors::{Error as JniError, JniError as RawJniError};
use tracing::debug;

static ANDROID_JVM: once_cell::sync::OnceCell<jni::JavaVM> = once_cell::sync::OnceCell::new();

pub(crate) fn init() {
    debug!("Initializing Android platform support");

    ANDROID_JVM.get_or_init(|| match get_java_vm() {
        Ok(jvm) => {
            jvm.attach_current_thread(|env| {
                init_rustls_platform_verifier(env)?;
                debug!("Android platform support initialized successfully");
                Ok::<_, JniError>(())
            })
            .expect("Failed to initialize rustls platform verifier");

            jvm
        }
        Err(e) => {
            panic!("Failed to initialize Android platform support: {e}");
        }
    });
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
