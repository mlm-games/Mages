use crate::FfiError;
use robius_location::{Access, Accuracy, Handler, Location, Manager};
use std::sync::{Arc, Mutex, MutexGuard};
use uniffi::{Object, Record, export};

#[derive(Clone, Debug, Record)]
pub struct DesktopLocationUpdate {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude: Option<f64>,
    pub speed: Option<f64>,
    pub bearing: Option<f64>,
}

#[export(callback_interface)]
pub trait DesktopLocationObserver: Send + Sync {
    fn on_location(&self, location: DesktopLocationUpdate);
    fn on_error(&self, message: String);
}

struct ObserverSlot {
    observer: Mutex<Option<Arc<dyn DesktopLocationObserver>>>,
}

impl ObserverSlot {
    fn set(&self, observer: Arc<dyn DesktopLocationObserver>) {
        *lock(&self.observer) = Some(observer);
    }

    fn clear(&self) {
        *lock(&self.observer) = None;
    }

    fn location(&self, update: DesktopLocationUpdate) {
        let observer = lock(&self.observer).clone();
        if let Some(observer) = observer {
            observer.on_location(update);
        }
    }

    fn error(&self, message: String) {
        let observer = lock(&self.observer).clone();
        if let Some(observer) = observer {
            observer.on_error(message);
        }
    }
}

struct DesktopLocationHandler {
    slot: Arc<ObserverSlot>,
}

impl Handler for DesktopLocationHandler {
    fn handle(&self, location: Location<'_>) {
        let coordinates = match location.coordinates() {
            Ok(coordinates) => coordinates,
            Err(error) => {
                self.slot
                    .error(format!("Could not read location coordinates: {error:?}"));
                return;
            }
        };
        self.slot.location(DesktopLocationUpdate {
            latitude: coordinates.latitude,
            longitude: coordinates.longitude,
            altitude: location.altitude().ok(),
            speed: location.speed().ok(),
            bearing: location.bearing().ok(),
        });
    }

    fn error(&self, error: robius_location::Error) {
        self.slot.error(location_error_message(error));
    }
}

fn location_error(error: robius_location::Error) -> FfiError {
    match error {
        robius_location::Error::AuthorizationDenied => FfiError::LocationPermissionDenied,
        error => FfiError::Msg(format!("{error:?}")),
    }
}

fn location_error_message(error: robius_location::Error) -> String {
    match error {
        robius_location::Error::AuthorizationDenied => "Location permission denied".to_string(),
        robius_location::Error::PermanentlyUnavailable => {
            "Location services are unavailable".to_string()
        }
        robius_location::Error::TemporarilyUnavailable => {
            "Location is temporarily unavailable".to_string()
        }
        error => format!("Location provider error: {error:?}"),
    }
}

#[derive(Object)]
pub struct DesktopLocationSource {
    manager: Mutex<Option<Manager>>,
    slot: Arc<ObserverSlot>,
}

#[uniffi::export]
impl DesktopLocationSource {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            manager: Mutex::new(None),
            slot: Arc::new(ObserverSlot {
                observer: Mutex::new(None),
            }),
        }
    }

    pub fn start(&self, observer: Box<dyn DesktopLocationObserver>) -> Result<(), FfiError> {
        self.slot.set(Arc::from(observer));
        if let Err(error) = self.ensure_manager() {
            self.slot.clear();
            return Err(error);
        }

        let result = with_location_thread(|| {
            let mut manager = lock(&self.manager);
            let manager = manager
                .as_mut()
                .ok_or(robius_location::Error::PermanentlyUnavailable)?;
            manager.request_authorization(Access::Background, Accuracy::Precise)?;
            manager.start_updates()
        });
        if result.is_err() {
            self.slot.clear();
        }
        result.map_err(location_error)
    }

    pub fn stop(&self) {
        let _ = with_location_thread(|| {
            let mut manager = lock(&self.manager);
            if let Some(mut manager) = manager.take() {
                manager.stop_updates()?;
            }
            Ok(())
        });
        self.slot.clear();
    }
}

impl Drop for DesktopLocationSource {
    fn drop(&mut self) {
        let _ = with_location_thread(|| {
            let mut manager = lock(&self.manager);
            if let Some(mut manager) = manager.take() {
                let _ = manager.stop_updates();
            }
            Ok(())
        });
        self.slot.clear();
    }
}

impl DesktopLocationSource {
    fn ensure_manager(&self) -> Result<(), FfiError> {
        let mut manager = lock(&self.manager);
        if manager.is_some() {
            return Ok(());
        }
        let slot = self.slot.clone();
        let created = with_location_thread(move || Manager::new(DesktopLocationHandler { slot }))
            .map_err(location_error)?;
        *manager = Some(created);
        Ok(())
    }
}

#[cfg(target_os = "macos")]
fn with_location_thread<T, F>(f: F) -> Result<T, robius_location::Error>
where
    F: FnOnce() -> robius_location::Result<T> + Send,
    T: Send,
{
    dispatch2::run_on_main(|_| f())
}

#[cfg(not(target_os = "macos"))]
fn with_location_thread<T, F>(f: F) -> Result<T, robius_location::Error>
where
    F: FnOnce() -> robius_location::Result<T>,
{
    f()
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}
