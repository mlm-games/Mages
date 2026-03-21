#[cfg(target_family = "wasm")]
use crate::wasm_bridge::to_json;
#[cfg(target_family = "wasm")]
use wasm_bindgen::JsValue;

macro_rules! abort_all_subs {
    ($self:expr; $($field:ident),+ $(,)?) => {
        $(
            for (_, h) in $self.$field.lock().unwrap().drain() {
                h.abort();
            }
        )+
    };
}
pub(crate) use abort_all_subs;

macro_rules! delegate_unit_result {
    ($($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[uniffi::export]
        impl Client {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> Result<(), FfiError> {
                    RT.block_on(self.core.$name($($arg),*))
                }
            )+
        }
    };
}
pub(crate) use delegate_unit_result;

macro_rules! delegate_result {
    ($ret:ty; $($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[uniffi::export]
        impl Client {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> Result<$ret, FfiError> {
                    RT.block_on(self.core.$name($($arg),*))
                }
            )+
        }
    };
}
pub(crate) use delegate_result;

macro_rules! delegate_option {
    ($ret:ty; $($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[uniffi::export]
        impl Client {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> Result<Option<$ret>, FfiError> {
                    RT.block_on(self.core.$name($($arg),*))
                }
            )+
        }
    };
}
pub(crate) use delegate_option;

macro_rules! delegate_plain {
    ($ret:ty; $($name:ident($($arg:ident : $ty:ty),* $(,)?));+ $(;)?) => {
        #[uniffi::export]
        impl Client {
            $(
                pub fn $name(&self, $($arg: $ty),*) -> $ret {
                    RT.block_on(self.core.$name($($arg),*))
                }
            )+
        }
    };
}
pub(crate) use delegate_plain;

macro_rules! sub_manager {
    ($self:expr, $subs:ident, $spawn:expr) => {{
        let id = $self.next_sub_id();
        let h = spawn_task!($spawn);
        $self.$subs.lock().unwrap().insert(id, h);
        id
    }};
}
pub(crate) use sub_manager;

macro_rules! unsub {
    ($self:expr, $subs:ident, $id:expr) => {{
        if let Some(h) = $self.$subs.lock().unwrap().remove(&$id) {
            h.abort();
            true
        } else {
            false
        }
    }};
}
pub(crate) use unsub;

#[cfg(not(target_family = "wasm"))]
macro_rules! spawn_task {
    ($fut:expr) => {
        RT.spawn($fut)
    };
}
#[cfg(target_family = "wasm")]
macro_rules! spawn_task {
    ($fut:expr) => {
        tokio::task::spawn_local($fut)
    };
}
pub(crate) use spawn_task;

#[cfg(not(target_family = "wasm"))]
macro_rules! spawn_detached {
    ($fut:expr) => {{
        let _ = tokio::spawn($fut);
    }};
}
#[cfg(target_family = "wasm")]
macro_rules! spawn_detached {
    ($fut:expr) => {{
        wasm_bindgen_futures::spawn_local($fut);
    }};
}
pub(crate) use spawn_detached;

/// Delegates async methods that return bool directly from core.
#[cfg(target_family = "wasm")]
macro_rules! wasm_delegate_bool {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return webffi_not_init(); };
                    webffi_bool(s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_delegate_bool;

/// Delegates async methods returning Result<(), _> as bool.
#[cfg(target_family = "wasm")]
macro_rules! wasm_delegate_result_bool {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return webffi_not_init(); };
                    webffi_unit(s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_delegate_result_bool;

/// Delegates async methods returning a value, serialized to JsValue.
/// Requires a default expression for when state is None.
#[cfg(target_family = "wasm")]
macro_rules! wasm_delegate_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) or $default:expr );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return to_json(&$default); };
                    to_json(&s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_delegate_json;

/// Delegates async methods returning Result<T, _> → JsValue or NULL on error.
#[cfg(target_family = "wasm")]
macro_rules! wasm_delegate_result_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return webffi_not_init(); };
                    webffi_value(s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_delegate_result_json;

/// Delegates async methods returning Option<T> → JsValue or NULL.
#[cfg(target_family = "wasm")]
macro_rules! wasm_delegate_option_json {
    ($( $js_name:literal => $method:ident($($arg:ident : $ty:ty),*) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub async fn $method(&self, $($arg: $ty),*) -> JsValue {
                    let Some(s) = self.state() else { return webffi_not_init(); };
                    webffi_option(s.core.$method($($arg),*).await)
                }
            )+
        }
    };
}

#[cfg(target_family = "wasm")]
pub(crate) use wasm_delegate_option_json;

/// Generates unobserve methods that abort a subscription handle.
#[cfg(target_family = "wasm")]
macro_rules! wasm_unobserve {
    ($( $js_name:literal => $method:ident($field:ident) );+ $(;)?) => {
        #[wasm_bindgen]
        impl WasmClient {
            $(
                #[wasm_bindgen(js_name = $js_name)]
                pub fn $method(&self, sub_id: f64) -> bool {
                    self.state()
                        .map(|s| Self::abort_sub(&s.$field, sub_id as u64))
                        .unwrap_or(false)
                }
            )+
        }
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_unobserve;

/// Wraps the Abortable subscription boilerplate for wasm observers.
#[cfg(target_family = "wasm")]
macro_rules! wasm_subscribe {
    ($state:expr, $field:ident, $body:expr) => {{
        let id = $state.next_sub_id();
        let (ah, ar) = futures_util::future::AbortHandle::new_pair();
        $state.$field.borrow_mut().insert(id, ah);
        wasm_bindgen_futures::spawn_local(async move {
            let _ = futures_util::future::Abortable::new($body, ar).await;
        });
        id as f64
    }};
}
#[cfg(target_family = "wasm")]
pub(crate) use wasm_subscribe;

#[cfg(target_family = "wasm")]
macro_rules! js_observer_json {
    ($name:ident : $trait:ident :: $method:ident, $arg:ident : $ty:ty) => {
        struct $name(Function);
        impl $trait for $name {
            fn $method(&self, $arg: $ty) {
                call_js(&self.0, to_json(&$arg));
            }
        }
        unsafe impl Send for $name {}
        unsafe impl Sync for $name {}
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use js_observer_json;

#[cfg(target_family = "wasm")]
macro_rules! js_observer_noargs {
    ($name:ident : $trait:ident :: $method:ident) => {
        struct $name(Function);
        impl $trait for $name {
            fn $method(&self) {
                call_js0(&self.0);
            }
        }
        unsafe impl Send for $name {}
        unsafe impl Sync for $name {}
    };
}
#[cfg(target_family = "wasm")]
pub(crate) use js_observer_noargs;

#[cfg(target_family = "wasm")]
pub fn webffi_not_init() -> JsValue {
    to_json(&serde_json::json!({"ok":false,"error":"not initialized"}))
}

#[cfg(target_family = "wasm")]
pub fn webffi_err(msg: &str) -> JsValue {
    to_json(&serde_json::json!({"ok":false,"error":msg}))
}

#[cfg(target_family = "wasm")]
pub fn webffi_unit<E: std::fmt::Display>(r: Result<(), E>) -> JsValue {
    match r {
        Ok(()) => to_json(&serde_json::json!({"ok":true})),
        Err(e) => to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
    }
}

#[cfg(target_family = "wasm")]
pub fn webffi_value<T: serde::Serialize, E: std::fmt::Display>(r: Result<T, E>) -> JsValue {
    match r {
        Ok(v) => to_json(&serde_json::json!({"ok":true,"value":v})),
        Err(e) => to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
    }
}

#[cfg(target_family = "wasm")]
pub fn webffi_bool<E: std::fmt::Display>(r: Result<bool, E>) -> JsValue {
    match r {
        Ok(true) => to_json(&serde_json::json!({"ok":true})),
        Ok(false) => to_json(&serde_json::json!({"ok":false})),
        Err(e) => to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
    }
}

#[cfg(target_family = "wasm")]
pub fn webffi_option<T: serde::Serialize, E: std::fmt::Display>(
    r: Result<Option<T>, E>,
) -> JsValue {
    match r {
        Ok(Some(v)) => to_json(&serde_json::json!({"ok":true,"value":v})),
        Ok(None) => to_json(&serde_json::json!({"ok":true,"value":null})),
        Err(e) => to_json(&serde_json::json!({"ok":false,"error":e.to_string()})),
    }
}
