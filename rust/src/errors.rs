use crate::FfiError;

/// Convert `Result<T, E: Display>` to `Result<T, FfiError>` via `.ffi()`
pub(crate) trait IntoFfi<T> {
    fn ffi(self) -> Result<T, FfiError>;
}

impl<T, E: std::fmt::Display> IntoFfi<T> for Result<T, E> {
    fn ffi(self) -> Result<T, FfiError> {
        self.map_err(|e| FfiError::Msg(e.to_string()))
    }
}

/// Convert `Option<T>` to `Result<T, FfiError>` via `.or_ffi("msg")`
pub(crate) trait OptionFfi<T> {
    fn or_ffi(self, msg: &str) -> Result<T, FfiError>;
}

impl<T> OptionFfi<T> for Option<T> {
    fn or_ffi(self, msg: &str) -> Result<T, FfiError> {
        self.ok_or_else(|| FfiError::Msg(msg.into()))
    }
}

macro_rules! ffi_err {
    ($msg:literal) => { FfiError::Msg($msg.into()) };
    ($fmt:literal, $($arg:expr),+ $(,)?) => {
        FfiError::Msg(format!($fmt, $($arg),+))
    };
}
pub(crate) use ffi_err;
