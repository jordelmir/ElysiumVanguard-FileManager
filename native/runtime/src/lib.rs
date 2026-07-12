#![deny(unsafe_op_in_unsafe_fn)]

use std::collections::HashMap;
use std::ffi::{CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong};

const MAX_IO_BYTES: usize = 64 * 1024;
const STATUS_RUNNING: jint = jint::MIN;
const STATUS_CLOSED: jint = jint::MIN + 1;
const STATUS_UNKNOWN: jint = jint::MIN + 2;
const DEFAULT_CLOSE_GRACE_MS: u64 = 750;

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static SESSIONS: LazyLock<Mutex<HashMap<i64, Arc<NativeSession>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

#[derive(Debug)]
struct NativeError(String);

impl NativeError {
    fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }

    fn last_os_error(context: &str) -> Self {
        Self(format!("{context}: {}", std::io::Error::last_os_error()))
    }
}

impl std::fmt::Display for NativeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

type NativeResult<T> = Result<T, NativeError>;

struct NativeSession {
    master_fd: AtomicI32,
    pid: libc::pid_t,
    exited_status: Mutex<Option<libc::c_int>>,
    wait_lock: Mutex<()>,
    close_lock: Mutex<()>,
    closed: AtomicBool,
}

impl NativeSession {
    #[cfg(any(target_os = "android", test))]
    fn new(master_fd: libc::c_int, pid: libc::pid_t) -> Self {
        Self {
            master_fd: AtomicI32::new(master_fd),
            pid,
            exited_status: Mutex::new(None),
            wait_lock: Mutex::new(()),
            close_lock: Mutex::new(()),
            closed: AtomicBool::new(false),
        }
    }

    fn master_fd(&self) -> NativeResult<libc::c_int> {
        let fd = self.master_fd.load(Ordering::Acquire);
        if fd < 0 || self.closed.load(Ordering::Acquire) {
            Err(NativeError::new("PTY session is closed"))
        } else {
            Ok(fd)
        }
    }

    fn read(&self, destination: &mut [i8], timeout_ms: jint) -> NativeResult<jint> {
        let fd = self.master_fd()?;
        if destination.is_empty() {
            return Ok(0);
        }
        let ready = poll(fd, libc::POLLIN, timeout_ms.max(0))?;
        if !ready {
            return Ok(0);
        }
        let count = unsafe {
            libc::read(
                fd,
                destination.as_mut_ptr().cast::<libc::c_void>(),
                destination.len(),
            )
        };
        if count > 0 {
            return Ok(count as jint);
        }
        if count == 0 {
            return Ok(-1);
        }
        let error = std::io::Error::last_os_error();
        match error.raw_os_error() {
            Some(libc::EAGAIN) => Ok(0),
            // Linux PTYs return EIO after the slave closes. Treat it as EOF.
            Some(libc::EIO) => Ok(-1),
            _ => Err(NativeError::new(format!("PTY read failed: {error}"))),
        }
    }

    fn write(&self, source: &[i8]) -> NativeResult<jint> {
        let fd = self.master_fd()?;
        let mut offset = 0usize;
        while offset < source.len() {
            if !poll(fd, libc::POLLOUT, 1_000)? {
                return Err(NativeError::new("PTY write timed out"));
            }
            let count = unsafe {
                libc::write(
                    fd,
                    source[offset..].as_ptr().cast::<libc::c_void>(),
                    source.len() - offset,
                )
            };
            if count > 0 {
                offset += count as usize;
                continue;
            }
            if count == 0 {
                return Err(NativeError::new("PTY write made no progress"));
            }
            let error = std::io::Error::last_os_error();
            if matches!(error.raw_os_error(), Some(libc::EAGAIN)) {
                continue;
            }
            return Err(NativeError::new(format!("PTY write failed: {error}")));
        }
        Ok(offset as jint)
    }

    fn resize(&self, rows: jint, columns: jint) -> NativeResult<()> {
        if rows <= 0 || columns <= 0 || rows > u16::MAX as jint || columns > u16::MAX as jint {
            return Err(NativeError::new("PTY dimensions are out of range"));
        }
        let fd = self.master_fd()?;
        let size = libc::winsize {
            ws_row: rows as u16,
            ws_col: columns as u16,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let result = unsafe { libc::ioctl(fd, libc::TIOCSWINSZ, &size) };
        if result != 0 {
            return Err(NativeError::last_os_error("TIOCSWINSZ failed"));
        }
        self.signal(libc::SIGWINCH)
    }

    fn signal(&self, signal: jint) -> NativeResult<()> {
        if !matches!(
            signal,
            libc::SIGINT | libc::SIGHUP | libc::SIGTERM | libc::SIGKILL | libc::SIGWINCH
        ) {
            return Err(NativeError::new(
                "signal is not permitted for a terminal session",
            ));
        }
        let result = unsafe { libc::kill(-self.pid, signal) };
        if result == 0 {
            return Ok(());
        }
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() == Some(libc::ESRCH) {
            return Ok(());
        }
        Err(NativeError::new(format!(
            "signal process group failed: {error}"
        )))
    }

    fn wait(&self, timeout: Duration) -> NativeResult<Option<libc::c_int>> {
        let _guard = self
            .wait_lock
            .lock()
            .map_err(|_| NativeError::new("PTY wait lock is poisoned"))?;
        if let Some(status) = *self
            .exited_status
            .lock()
            .map_err(|_| NativeError::new("PTY exit status lock is poisoned"))?
        {
            return Ok(Some(status));
        }

        let deadline = Instant::now() + timeout;
        loop {
            let mut status: libc::c_int = 0;
            let result = unsafe { libc::waitpid(self.pid, &mut status, libc::WNOHANG) };
            if result == self.pid {
                *self
                    .exited_status
                    .lock()
                    .map_err(|_| NativeError::new("PTY exit status lock is poisoned"))? =
                    Some(status);
                return Ok(Some(status));
            }
            if result < 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::ECHILD) {
                    return Ok(*self
                        .exited_status
                        .lock()
                        .map_err(|_| NativeError::new("PTY exit status lock is poisoned"))?);
                }
                return Err(NativeError::new(format!("waitpid failed: {error}")));
            }
            if Instant::now() >= deadline {
                return Ok(None);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn close(&self, grace: Duration) -> NativeResult<libc::c_int> {
        let _guard = self
            .close_lock
            .lock()
            .map_err(|_| NativeError::new("PTY close lock is poisoned"))?;
        if self.closed.swap(true, Ordering::AcqRel) {
            return Ok(self
                .exited_status
                .lock()
                .map_err(|_| NativeError::new("PTY exit status lock is poisoned"))?
                .unwrap_or(STATUS_CLOSED));
        }

        let _ = self.signal(libc::SIGTERM);
        let mut status = self.wait(grace)?;
        if status.is_none() {
            let _ = self.signal(libc::SIGKILL);
            status = self.wait(Duration::from_millis(500))?;
        }
        // The shell can exit before a child in its process group. Kill the group
        // once more and check for ESRCH before releasing the PTY master FD.
        let _ = self.signal(libc::SIGKILL);

        let fd = self.master_fd.swap(-1, Ordering::AcqRel);
        if fd >= 0 {
            let _ = unsafe { libc::close(fd) };
        }
        Ok(status.unwrap_or(STATUS_UNKNOWN))
    }
}

fn poll(fd: libc::c_int, events: i16, timeout_ms: jint) -> NativeResult<bool> {
    let mut descriptor = libc::pollfd {
        fd,
        events,
        revents: 0,
    };
    loop {
        let result = unsafe { libc::poll(&mut descriptor, 1, timeout_ms) };
        if result > 0 {
            return Ok(true);
        }
        if result == 0 {
            return Ok(false);
        }
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        return Err(NativeError::new(format!("poll failed: {error}")));
    }
}

fn status_to_exit_code(status: libc::c_int) -> jint {
    if status == STATUS_CLOSED || status == STATUS_UNKNOWN {
        return status;
    }
    if libc::WIFEXITED(status) {
        return libc::WEXITSTATUS(status) as jint;
    }
    if libc::WIFSIGNALED(status) {
        return -(libc::WTERMSIG(status) as jint);
    }
    STATUS_UNKNOWN
}

fn session_for(handle: jlong) -> NativeResult<Arc<NativeSession>> {
    if handle <= 0 {
        return Err(NativeError::new("invalid PTY handle"));
    }
    SESSIONS
        .lock()
        .map_err(|_| NativeError::new("PTY registry lock is poisoned"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| NativeError::new("unknown PTY handle"))
}

fn throw_io(env: &mut JNIEnv<'_>, error: impl std::fmt::Display) {
    let _ = env.throw_new("java/io/IOException", error.to_string());
}

fn jni_guard<T: Copy>(
    env: &mut JNIEnv<'_>,
    fallback: T,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> NativeResult<T>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            throw_io(env, error);
            fallback
        }
        Err(_) => {
            throw_io(env, "native PTY panic contained at JNI boundary");
            fallback
        }
    }
}

fn java_strings(env: &mut JNIEnv<'_>, source: JObjectArray<'_>) -> NativeResult<Vec<String>> {
    let length = env
        .get_array_length(&source)
        .map_err(|error| NativeError::new(format!("array length failed: {error}")))?;
    let mut result = Vec::with_capacity(length as usize);
    for index in 0..length {
        let value = env
            .get_object_array_element(&source, index)
            .map_err(|error| NativeError::new(format!("array access failed: {error}")))?;
        if value.is_null() {
            return Err(NativeError::new(
                "argv/environment cannot contain null elements",
            ));
        }
        let string = JString::from(value);
        let value: String = env
            .get_string(&string)
            .map_err(|error| NativeError::new(format!("string conversion failed: {error}")))?
            .into();
        result.push(value);
    }
    Ok(result)
}

fn validate_environment(entries: &[String]) -> NativeResult<()> {
    for entry in entries {
        let Some((name, _)) = entry.split_once('=') else {
            return Err(NativeError::new("environment entries must be NAME=VALUE"));
        };
        if name.is_empty() || name.contains('\0') {
            return Err(NativeError::new("environment name is invalid"));
        }
    }
    Ok(())
}

fn c_strings(values: &[String], label: &str) -> NativeResult<Vec<CString>> {
    values
        .iter()
        .map(|value| {
            CString::new(value.as_str())
                .map_err(|_| NativeError::new(format!("{label} contains NUL")))
        })
        .collect()
}

fn spawn(
    argv: Vec<String>,
    environment: Vec<String>,
    working_directory: Option<String>,
    rows: jint,
    columns: jint,
) -> NativeResult<NativeSession> {
    if argv.is_empty() || argv[0].is_empty() {
        return Err(NativeError::new("argv must contain an executable"));
    }
    if rows <= 0 || columns <= 0 || rows > u16::MAX as jint || columns > u16::MAX as jint {
        return Err(NativeError::new("PTY dimensions are out of range"));
    }
    validate_environment(&environment)?;
    let argv = c_strings(&argv, "argv")?;
    let environment = c_strings(&environment, "environment")?;
    let working_directory = working_directory
        .as_deref()
        .map(|value| {
            CString::new(value).map_err(|_| NativeError::new("working directory contains NUL"))
        })
        .transpose()?;
    let mut argv_pointers: Vec<*const c_char> = argv.iter().map(|value| value.as_ptr()).collect();
    argv_pointers.push(std::ptr::null());
    let mut environment_pointers: Vec<*const c_char> =
        environment.iter().map(|value| value.as_ptr()).collect();
    environment_pointers.push(std::ptr::null());

    spawn_platform(
        argv_pointers.as_ptr(),
        environment_pointers.as_ptr(),
        working_directory
            .as_ref()
            .map_or(std::ptr::null(), |value| value.as_ptr()),
        rows as u16,
        columns as u16,
    )
}

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn elysium_pty_spawn(
        argv: *const *const c_char,
        envp: *const *const c_char,
        working_directory: *const c_char,
        rows: u16,
        columns: u16,
        master_fd_out: *mut libc::c_int,
        pid_out: *mut libc::pid_t,
        child_errno_out: *mut libc::c_int,
    ) -> libc::c_int;
}

#[cfg(target_os = "android")]
fn spawn_platform(
    argv: *const *const c_char,
    environment: *const *const c_char,
    working_directory: *const c_char,
    rows: u16,
    columns: u16,
) -> NativeResult<NativeSession> {
    let mut master_fd = -1;
    let mut pid = -1;
    let mut child_errno = 0;
    let result = unsafe {
        elysium_pty_spawn(
            argv,
            environment,
            working_directory,
            rows,
            columns,
            &mut master_fd,
            &mut pid,
            &mut child_errno,
        )
    };
    if result != 0 {
        let detail = if child_errno != 0 {
            std::io::Error::from_raw_os_error(child_errno).to_string()
        } else {
            std::io::Error::last_os_error().to_string()
        };
        return Err(NativeError::new(format!("PTY spawn failed: {detail}")));
    }
    Ok(NativeSession::new(master_fd, pid))
}

#[cfg(not(target_os = "android"))]
fn spawn_platform(
    _argv: *const *const c_char,
    _environment: *const *const c_char,
    _working_directory: *const c_char,
    _rows: u16,
    _columns: u16,
) -> NativeResult<NativeSession> {
    Err(NativeError::new(
        "native PTY spawning is only available on Android",
    ))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeIsSupported(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    #[cfg(target_os = "android")]
    {
        1
    }
    #[cfg(not(target_os = "android"))]
    {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeSpawn(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    argv: JObjectArray<'_>,
    environment: JObjectArray<'_>,
    working_directory: JString<'_>,
    rows: jint,
    columns: jint,
) -> jlong {
    jni_guard(&mut env, 0, |env| {
        let argv = java_strings(env, argv)?;
        let environment = java_strings(env, environment)?;
        let working_directory = if working_directory.is_null() {
            None
        } else {
            Some(
                env.get_string(&working_directory)
                    .map_err(|error| {
                        NativeError::new(format!("working directory conversion failed: {error}"))
                    })?
                    .into(),
            )
        };
        let session = Arc::new(spawn(argv, environment, working_directory, rows, columns)?);
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        SESSIONS
            .lock()
            .map_err(|_| NativeError::new("PTY registry lock is poisoned"))?
            .insert(handle, session);
        Ok(handle as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativePid(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    jni_guard(&mut env, -1, |_| Ok(session_for(handle)?.pid as jlong))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeRead(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    destination: JByteArray<'_>,
    offset: jint,
    length: jint,
    timeout_ms: jint,
) -> jint {
    jni_guard(&mut env, -2, |env| {
        let array_length = env
            .get_array_length(&destination)
            .map_err(|error| NativeError::new(format!("byte array length failed: {error}")))?;
        if offset < 0 || length < 0 || offset > array_length || length > array_length - offset {
            return Err(NativeError::new("read range is outside byte array"));
        }
        let length = (length as usize).min(MAX_IO_BYTES);
        let mut output = vec![0_i8; length];
        let count = session_for(handle)?.read(&mut output, timeout_ms)?;
        if count > 0 {
            env.set_byte_array_region(&destination, offset, &output[..count as usize])
                .map_err(|error| NativeError::new(format!("byte array write failed: {error}")))?;
        }
        Ok(count)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeWrite(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    source: JByteArray<'_>,
    offset: jint,
    length: jint,
) -> jint {
    jni_guard(&mut env, -1, |env| {
        let array_length = env
            .get_array_length(&source)
            .map_err(|error| NativeError::new(format!("byte array length failed: {error}")))?;
        if offset < 0 || length < 0 || offset > array_length || length > array_length - offset {
            return Err(NativeError::new("write range is outside byte array"));
        }
        if length as usize > MAX_IO_BYTES {
            return Err(NativeError::new("write exceeds maximum PTY chunk"));
        }
        let mut input = vec![0_i8; length as usize];
        env.get_byte_array_region(&source, offset, &mut input)
            .map_err(|error| NativeError::new(format!("byte array read failed: {error}")))?;
        session_for(handle)?.write(&input)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeResize(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    rows: jint,
    columns: jint,
) -> jboolean {
    jni_guard(&mut env, 0, |_| {
        session_for(handle)?.resize(rows, columns)?;
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeSignal(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    signal: jint,
) -> jboolean {
    jni_guard(&mut env, 0, |_| {
        session_for(handle)?.signal(signal)?;
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeWait(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    timeout_ms: jint,
) -> jint {
    jni_guard(&mut env, STATUS_UNKNOWN, |_| {
        let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
        let status = session_for(handle)?.wait(timeout)?;
        Ok(status.map_or(STATUS_RUNNING, status_to_exit_code))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_elysium_vanguard_core_runtime_terminal_pty_NativePtyBridge_nativeClose(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    grace_ms: jint,
) -> jint {
    jni_guard(&mut env, STATUS_UNKNOWN, |_| {
        let session = SESSIONS
            .lock()
            .map_err(|_| NativeError::new("PTY registry lock is poisoned"))?
            .remove(&handle)
            .ok_or_else(|| NativeError::new("unknown PTY handle"))?;
        let grace = Duration::from_millis(if grace_ms <= 0 {
            DEFAULT_CLOSE_GRACE_MS
        } else {
            grace_ms as u64
        });
        Ok(status_to_exit_code(session.close(grace)?))
    })
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{
        NativeSession, STATUS_CLOSED, STATUS_RUNNING, STATUS_UNKNOWN, status_to_exit_code,
    };

    #[test]
    fn status_sentinels_are_distinct() {
        assert_ne!(STATUS_RUNNING, STATUS_CLOSED);
        assert_ne!(STATUS_CLOSED, STATUS_UNKNOWN);
    }

    #[test]
    fn unknown_status_is_preserved() {
        assert_eq!(STATUS_UNKNOWN, status_to_exit_code(STATUS_UNKNOWN));
    }

    #[cfg(unix)]
    #[test]
    fn close_signals_process_group_before_invalidating_the_session() {
        let mut ready_pipe = [-1, -1];
        assert_eq!(
            0,
            unsafe { libc::pipe(ready_pipe.as_mut_ptr()) },
            "pipe failed"
        );
        let child = unsafe { libc::fork() };
        assert!(child >= 0, "fork failed");
        if child == 0 {
            unsafe {
                libc::close(ready_pipe[0]);
                if libc::setsid() < 0 {
                    libc::_exit(127);
                }
                let ready = [1_u8];
                if libc::write(ready_pipe[1], ready.as_ptr().cast(), ready.len()) != 1 {
                    libc::_exit(127);
                }
                libc::close(ready_pipe[1]);
                libc::pause();
                libc::_exit(0);
            }
        }

        unsafe { libc::close(ready_pipe[1]) };
        let mut ready = [0_u8];
        let count = unsafe { libc::read(ready_pipe[0], ready.as_mut_ptr().cast(), ready.len()) };
        unsafe { libc::close(ready_pipe[0]) };
        assert_eq!(1, count, "child did not establish its process group");

        let fd = unsafe { libc::dup(libc::STDOUT_FILENO) };
        assert!(fd >= 0, "dup failed");
        let session = NativeSession::new(fd, child);
        let status = session
            .close(Duration::from_millis(250))
            .expect("close failed");

        assert_ne!(STATUS_UNKNOWN, status);
        assert!(libc::WIFSIGNALED(status));
        assert_eq!(libc::SIGTERM, libc::WTERMSIG(status));
    }
}
