const std = @import("std");

const c = @cImport({
    // NDK r28's bionic headers annotate array parameters with `_Nullable`
    // and `_Nonnull`. zig 0.16's translate-c (a newer clang frontend than
    // 0.15.2 shipped) rejects nullability attributes on non-pointer types
    // — `int [2]`, `char *const[]`, `struct timespec [2]` etc. — as hard
    // errors, even though the underlying clang would treat them as
    // ignorable. Neutralize the macros before any system header expands
    // so the attribute simply disappears from the translation unit.
    @cDefine("_Nullable", "");
    @cDefine("_Nonnull", "");
    @cDefine("_Null_unspecified", "");
    // Bionic uses clang's `__overloadable` + `__enable_if` overloads to
    // pick the right `ioctl()` signature based on the type of __op. zig
    // 0.16's translate-c can't model those overloads — it sees them as
    // two conflicting `int ioctl(...)` declarations and aborts with
    // "redefinition of 'ioctl' with a different type". Bionic exposes
    // an opt-out macro (`BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD`,
    // documented in <bits/ioctl.h>) that turns the overload off and
    // leaves only the canonical `int ioctl(int, int, ...)`. We don't
    // call ioctl, so the single-signature decl is irrelevant — we just
    // need translate-c to stop choking on the overload pair.
    @cDefine("BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD", "1");
    @cInclude("android/log.h");
    @cInclude("jni.h");
    @cInclude("libssh2.h");
    @cInclude("libssh2_sftp.h");
    @cInclude("sys/socket.h");
    @cInclude("netdb.h");
    @cInclude("arpa/inet.h");
    @cInclude("unistd.h");
    @cInclude("fcntl.h");
    @cInclude("errno.h");
    @cInclude("poll.h");
    // 0.16: std.time no longer exposes a wall/monotonic clock fn. We
    // call posix clock_gettime directly for idle-timeout bookkeeping.
    @cInclude("time.h");
});
const ipc = @import("ipc.zig");

const allocator = std.heap.c_allocator;
const LOG_TAG = "GhosttyxrSSH";
const verbose_ssh_logs = false;

fn androidLog(prio: c_int, comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(line.len)), line.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    if (!verbose_ssh_logs) return;
    androidLog(4, fmt, args); // ANDROID_LOG_INFO = 4
}

fn logError(comptime fmt: []const u8, args: anytype) void {
    androidLog(6, fmt, args); // ANDROID_LOG_ERROR = 6
}
const setup_wait_timeout_ms = 10_000;
const io_wait_timeout_ms = 120;
const read_wait_timeout_ms = 8;

/// Monotonic millisecond clock for idle-timeout bookkeeping. Uses posix
/// clock_gettime directly — we only consume the value for elapsed-time
/// math against an earlier observation, so a non-wall-clock source is
/// preferable (immune to NTP jumps).
fn nowMillis() i64 {
    var ts: c.timespec = undefined;
    _ = c.clock_gettime(c.CLOCK_MONOTONIC, &ts);
    return @as(i64, ts.tv_sec) * std.time.ms_per_s +
        @divFloor(@as(i64, ts.tv_nsec), std.time.ns_per_ms);
}

const NativeSshSession = struct {
    socket_fd: c_int = -1,
    session: ?*c.LIBSSH2_SESSION = null,
    channel: ?*c.LIBSSH2_CHANNEL = null,
    sftp: ?*c.LIBSSH2_SFTP = null,
    upload_handle: ?*c.LIBSSH2_SFTP_HANDLE = null,
    username: ?[]u8 = null,
    hostkey_ptr: ?[*]const u8 = null,
    hostkey_len: usize = 0,
    hostkey_type: c_int = 0,
    hostkey_copy: ?[]u8 = null,
    read_buffer: ?[]u8 = null,
    last_error: std.ArrayListUnmanaged(u8) = .empty,
    empty_reads: u32 = 0,
};

fn sessionFromHandle(handle: c.jlong) ?*NativeSshSession {
    if (handle == 0) return null;
    const raw_handle: u64 = @bitCast(handle);
    return @ptrFromInt(@as(usize, @truncate(raw_handle)));
}

fn handleFromSession(session: *NativeSshSession) c.jlong {
    const raw_ptr: u64 = @intCast(@intFromPtr(session));
    return @bitCast(raw_ptr);
}

fn setError(session: *NativeSshSession, comptime fmt: []const u8, args: anytype) void {
    // zig 0.16: ArrayListUnmanaged no longer exposes a `writer(allocator)`
    // method matching the old std.io.Writer trait, and `std.fmt.format`
    // now writes into `*std.Io.Writer`. We don't need a streaming writer
    // here — every error string we emit is bounded — so format into a
    // stack buffer and copy into the list.
    session.last_error.clearRetainingCapacity();
    var buf: [1024]u8 = undefined;
    const formatted = std.fmt.bufPrint(&buf, fmt, args) catch return;
    session.last_error.appendSlice(allocator, formatted) catch return;
}

fn setLibssh2Error(session: *NativeSshSession, prefix: []const u8, rc: c_int) void {
    var errmsg_ptr: [*c]const u8 = null;
    var errmsg_len: c_int = 0;
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_last_error(ssh_session, @ptrCast(&errmsg_ptr), &errmsg_len, 0);
    }
    if (errmsg_ptr != null and errmsg_len > 0) {
        setError(session, "{s}: {s}", .{ prefix, errmsg_ptr[0..@intCast(errmsg_len)] });
    } else {
        setError(session, "{s}: rc={}", .{ prefix, rc });
    }
}

fn closeSocket(fd: c_int) void {
    if (fd >= 0) _ = c.close(fd);
}

fn setSocketNonBlocking(fd: c_int) void {
    const flags = c.fcntl(fd, c.F_GETFL, @as(c_int, 0));
    if (flags < 0) return;
    _ = c.fcntl(fd, c.F_SETFL, flags | c.O_NONBLOCK);
}

fn clearHostKeyCopy(session: *NativeSshSession) void {
    if (session.hostkey_copy) |copy| allocator.free(copy);
    session.hostkey_copy = null;
    session.hostkey_ptr = null;
    session.hostkey_len = 0;
    session.hostkey_type = 0;
}

fn waitSocket(session: *NativeSshSession, timeout_ms: c_int) bool {
    const ssh_session = session.session orelse return false;
    if (session.socket_fd < 0) return false;

    const directions = c.libssh2_session_block_directions(ssh_session);
    var events: c_short = 0;
    if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) {
        events |= c.POLLIN;
    }
    if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) {
        events |= c.POLLOUT;
    }
    if (events == 0) {
        events = c.POLLIN | c.POLLOUT;
    }

    var poll_fds: [1]c.struct_pollfd = .{.{
        .fd = session.socket_fd,
        .events = events,
        .revents = 0,
    }};
    const poll_rc = c.poll(&poll_fds, 1, timeout_ms);
    return poll_rc > 0;
}

fn trySetChannelEnv(session: *NativeSshSession, channel: *c.LIBSSH2_CHANNEL, name: []const u8, value: []const u8) void {
    while (true) {
        const rc = c.libssh2_channel_setenv_ex(
            channel,
            name.ptr,
            @intCast(name.len),
            value.ptr,
            @intCast(value.len),
        );
        if (rc == 0) return;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logInfo("Ignoring rejected channel-setenv {s} rc={}", .{ name, rc });
            return;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            logInfo("Ignoring timed out channel-setenv {s}", .{name});
            return;
        }
    }
}

fn destroyNativeSshSession(session: *NativeSshSession) void {
    if (session.upload_handle) |uh| {
        _ = c.libssh2_sftp_close_handle(uh);
        session.upload_handle = null;
    }
    if (session.sftp) |sftp| {
        _ = c.libssh2_sftp_shutdown(sftp);
    }
    if (session.channel) |channel| {
        _ = c.libssh2_channel_close(channel);
        _ = c.libssh2_channel_free(channel);
    }
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_disconnect_ex(ssh_session, c.SSH_DISCONNECT_BY_APPLICATION, "bye", "en");
        _ = c.libssh2_session_free(ssh_session);
    }
    closeSocket(session.socket_fd);
    if (session.username) |username| allocator.free(username);
    if (session.read_buffer) |buffer| allocator.free(buffer);
    clearHostKeyCopy(session);
    session.last_error.deinit(allocator);
    allocator.destroy(session);
}

fn ensureSftp(session: *NativeSshSession) ?*c.LIBSSH2_SFTP {
    if (session.sftp) |sftp| return sftp;
    const ssh_session = session.session orelse {
        setError(session, "SSH session not connected", .{});
        return null;
    };
    while (true) {
        const sftp = c.libssh2_sftp_init(ssh_session);
        if (sftp != null) {
            session.sftp = sftp;
            return sftp;
        }
        const rc = c.libssh2_session_last_errno(ssh_session);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "SFTP init failed", rc);
            return null;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "SFTP init timed out", .{});
            return null;
        }
    }
}

fn sftpOpenDir(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, path_z: [:0]u8) ?*c.LIBSSH2_SFTP_HANDLE {
    while (true) {
        const dir = c.libssh2_sftp_opendir(sftp, path_z.ptr);
        if (dir != null) return dir;
        const rc = c.libssh2_session_last_errno(session.session.?);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "SFTP opendir failed", rc);
            return null;
        }
        if (!waitSocket(session, io_wait_timeout_ms)) {
            setError(session, "SFTP opendir timed out", .{});
            return null;
        }
    }
}

fn connectSocket(host: [:0]const u8, port: u16) !c_int {
    var hints: c.struct_addrinfo = std.mem.zeroes(c.struct_addrinfo);
    hints.ai_family = c.AF_UNSPEC;
    hints.ai_socktype = c.SOCK_STREAM;

    var service_buf: [16]u8 = undefined;
    const service = try std.fmt.bufPrintZ(&service_buf, "{}", .{port});

    var addr_list: ?*c.struct_addrinfo = null;
    const rc = c.getaddrinfo(host.ptr, service.ptr, &hints, &addr_list);
    if (rc != 0) return error.AddressLookupFailed;
    defer if (addr_list != null) c.freeaddrinfo(addr_list);

    var cur = addr_list;
    while (cur) |info| : (cur = info.ai_next) {
        const fd = c.socket(info.ai_family, info.ai_socktype, info.ai_protocol);
        if (fd < 0) continue;
        if (c.connect(fd, info.ai_addr, info.ai_addrlen) == 0) return fd;
        closeSocket(fd);
    }
    return error.ConnectFailed;
}

fn jniDupString(env: *c.JNIEnv, s: c.jstring) ?[]u8 {
    if (s == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, s, null);
    if (chars == null) return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, s, chars);
    return allocator.dupe(u8, std.mem.span(chars)) catch null;
}

fn dupSentinel(bytes: []const u8) ?[:0]u8 {
    const out = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    @memcpy(out[0..bytes.len], bytes);
    return out;
}

fn jniNewStringOrNull(env: *c.JNIEnv, bytes: []const u8) c.jstring {
    if (bytes.len == 0) return null;
    var buf = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    defer allocator.free(buf);
    @memcpy(buf[0..bytes.len], bytes);
    return env.*.*.NewStringUTF.?(env, buf.ptr);
}

fn jniNewByteArrayOrNull(env: *c.JNIEnv, bytes: []const u8) c.jbyteArray {
    const array = env.*.*.NewByteArray.?(env, @intCast(bytes.len));
    if (array == null) return null;
    if (bytes.len > 0) {
        env.*.*.SetByteArrayRegion.?(env, array, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    }
    return array;
}

fn hostkeyAlgorithmName(kind: c_int) []const u8 {
    return switch (kind) {
        c.LIBSSH2_HOSTKEY_TYPE_RSA => "RSA",
        c.LIBSSH2_HOSTKEY_TYPE_DSS => "DSA",
        c.LIBSSH2_HOSTKEY_TYPE_ECDSA_256, c.LIBSSH2_HOSTKEY_TYPE_ECDSA_384, c.LIBSSH2_HOSTKEY_TYPE_ECDSA_521 => "ECDSA",
        c.LIBSSH2_HOSTKEY_TYPE_ED25519 => "ED25519",
        else => "UNKNOWN",
    };
}

fn appendErrorFrame(response: *std.ArrayList(u8), session: *NativeSshSession, fallback: []const u8) void {
    const message = if (session.last_error.items.len > 0) session.last_error.items else fallback;
    ipc.appendMessage(allocator, response, .Error, message) catch {};
}

fn writeChannel(session: *NativeSshSession, bytes: []const u8) c.jint {
    const channel = session.channel orelse {
        setError(session, "Shell not open", .{});
        return -1;
    };
    if (bytes.len == 0) return 0;
    var total_written: usize = 0;
    var stalled_loops: u32 = 0;
    while (total_written < bytes.len) {
        const chunk = bytes[total_written..];
        const rc = c.libssh2_channel_write_ex(channel, 0, @ptrCast(chunk.ptr), @intCast(chunk.len));
        if (rc == c.LIBSSH2_ERROR_EAGAIN or rc == 0) {
            stalled_loops +%= 1;
            if (stalled_loops > 64) {
                break;
            }
            if (!waitSocket(session, io_wait_timeout_ms)) {
                break;
            }
            continue;
        }
        if (rc < 0) {
            setLibssh2Error(session, "Write failed", @intCast(rc));
            return -1;
        }
        stalled_loops = 0;
        total_written += @intCast(rc);
    }
    return @intCast(total_written);
}

fn sendKeepAlive(session: *NativeSshSession) bool {
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return false;
    };
    // libssh2_keepalive_send sends an SSH_MSG_IGNORE keepalive and reports
    // seconds until the next one is due. A negative rc means the transport
    // layer failed to write — the channel is dead. EAGAIN just means the
    // non-blocking socket wasn't writable this instant; that's not a dead
    // channel, so report alive and let the next probe try again.
    var seconds_to_next: c_int = 0;
    const rc = c.libssh2_keepalive_send(ssh_session, &seconds_to_next);
    if (rc == 0 or rc == c.LIBSSH2_ERROR_EAGAIN) return true;
    setLibssh2Error(session, "Keepalive failed", rc);
    return false;
}

fn ensureReadBuffer(session: *NativeSshSession, min_capacity: usize) ?[]u8 {
    const capacity = @max(min_capacity, 1);
    if (session.read_buffer) |buffer| {
        if (buffer.len >= capacity) return buffer;
        const resized = allocator.realloc(buffer, capacity) catch return null;
        session.read_buffer = resized;
        return resized;
    }
    const buffer = allocator.alloc(u8, capacity) catch return null;
    session.read_buffer = buffer;
    return buffer;
}

fn readChannel(session: *NativeSshSession, max_bytes: usize) ?[]const u8 {
    const channel = session.channel orelse return null;
    const buf = ensureReadBuffer(session, max_bytes) orelse return null;
    const limit = @max(max_bytes, 1);
    var total_read: usize = 0;
    var retried_after_wait = false;
    while (true) {
        const rc = c.libssh2_channel_read_ex(channel, 0, @ptrCast(buf.ptr + total_read), @intCast(limit - total_read));
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            if (total_read == 0 and !retried_after_wait and waitSocket(session, read_wait_timeout_ms)) {
                retried_after_wait = true;
                continue;
            }
            break;
        }
        if (rc == 0) {
            break;
        }
        if (rc < 0) {
            setLibssh2Error(session, "Read failed", @intCast(rc));
            return null;
        }
        total_read += @intCast(rc);
        if (total_read >= limit) {
            break;
        }
    }
    if (total_read == 0) {
        session.empty_reads +%= 1;
        return buf[0..0];
    }
    session.empty_reads = 0;
    return buf[0..total_read];
}

fn readJByteArray(env: *c.JNIEnv, array: c.jbyteArray) ?[]u8 {
    if (array == null) return null;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len <= 0) return &.{};
    const out = allocator.alloc(u8, @intCast(len)) catch return null;
    env.*.*.GetByteArrayRegion.?(env, array, 0, len, @ptrCast(out.ptr));
    return out;
}

comptime {
    _ = c.libssh2_init;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeCreateSession(env: *c.JNIEnv, thiz: c.jobject) callconv(.c) c.jlong {
    _ = env;
    _ = thiz;
    _ = c.libssh2_init(0);
    const session = allocator.create(NativeSshSession) catch return 0;
    session.* = .{};
    return handleFromSession(session);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeDestroySession(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
    destroyNativeSshSession(session);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeConnect(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, host: c.jstring, port: c.jint, username: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const host_slice = jniDupString(env, host) orelse {
        setError(session, "Missing host", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_slice);
    const host_z = dupSentinel(host_slice) orelse {
        setError(session, "Host alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_z);
    const username_slice = jniDupString(env, username) orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(username_slice);

    logInfo("nativeConnect host={s} port={} user={s}", .{ host_slice, port, username_slice });

    session.last_error.clearRetainingCapacity();
    clearHostKeyCopy(session);
    if (session.username) |old| allocator.free(old);
    session.username = allocator.dupe(u8, username_slice) catch {
        setError(session, "Username alloc failed", .{});
        return c.JNI_FALSE;
    };

    const fd = connectSocket(host_z, @intCast(@max(port, 0))) catch {
        logError("Socket connect failed host={s} port={}", .{ host_slice, port });
        setError(session, "Socket connect failed host={s} port={}", .{ host_slice, port });
        return c.JNI_FALSE;
    };
    logInfo("Socket connected fd={}", .{fd});
    session.socket_fd = fd;

    const ssh_session = c.libssh2_session_init_ex(null, null, null, null) orelse {
        setError(session, "libssh2_session_init_ex failed", .{});
        closeSocket(fd);
        session.socket_fd = -1;
        return c.JNI_FALSE;
    };
    session.session = ssh_session;
    c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("Starting SSH handshake...", .{});
    while (true) {
        const handshake_rc = c.libssh2_session_handshake(ssh_session, fd);
        if (handshake_rc == 0) break;
        if (handshake_rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("SSH handshake failed rc={}", .{handshake_rc});
            setLibssh2Error(session, "SSH handshake failed", handshake_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            logError("SSH handshake timed out", .{});
            setError(session, "SSH handshake timed out", .{});
            return c.JNI_FALSE;
        }
    }
    logInfo("SSH handshake completed", .{});

    var hostkey_len: usize = 0;
    const hostkey_ptr = c.libssh2_session_hostkey(ssh_session, &hostkey_len, &session.hostkey_type);
    if (hostkey_ptr == null or hostkey_len == 0) {
        setError(session, "Missing server host key", .{});
        return c.JNI_FALSE;
    }
    const hostkey_copy = allocator.alloc(u8, hostkey_len) catch {
        setError(session, "Host key copy alloc failed", .{});
        return c.JNI_FALSE;
    };
    @memcpy(hostkey_copy, hostkey_ptr[0..hostkey_len]);
    session.hostkey_copy = hostkey_copy;
    session.hostkey_ptr = hostkey_copy.ptr;
    session.hostkey_len = hostkey_len;
    return c.JNI_TRUE;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeAuthenticateNone(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("nativeAuthenticateNone user={s}", .{username_slice});
    if (c.libssh2_userauth_authenticated(ssh_session) != 0) {
        logInfo("None auth already authenticated after handshake", .{});
        return c.JNI_TRUE;
    }

    const deadline_ms = nowMillis() + setup_wait_timeout_ms;
    var attempts: u32 = 0;
    while (true) {
        attempts += 1;
        logInfo("None auth probe methods attempt={}", .{attempts});
        const userauth_list = c.libssh2_userauth_list(ssh_session, username_slice.ptr, @intCast(username_slice.len));
        logInfo("None auth probe returned attempt={}", .{attempts});
        if (userauth_list == null) {
            const rc = c.libssh2_session_last_errno(ssh_session);
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                if ((attempts % 32) == 0) {
                    logInfo("None auth waiting (EAGAIN) attempts={}", .{attempts});
                }
                if (nowMillis() >= deadline_ms) {
                    setError(session, "None auth timed out after {} attempts", .{attempts});
                    return c.JNI_FALSE;
                }
                if (!waitSocket(session, setup_wait_timeout_ms)) {
                    setError(session, "None auth timed out", .{});
                    return c.JNI_FALSE;
                }
                continue;
            }
            if (c.libssh2_userauth_authenticated(ssh_session) != 0) {
                logInfo("None auth succeeded - session authenticated", .{});
                return c.JNI_TRUE;
            }
            logError("None auth failed: no methods and not authenticated rc={}", .{rc});
            setError(session, "None auth failed: server returned no methods and session not authenticated", .{});
            return c.JNI_FALSE;
        }
        logError("None auth rejected, server requires: {s}", .{std.mem.span(userauth_list)});
        setError(session, "None auth rejected, server requires: {s}", .{std.mem.span(userauth_list)});
        return c.JNI_FALSE;
    }
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeGetLastError(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    return jniNewStringOrNull(env, session.last_error.items);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeGetHostKey(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    if (session.hostkey_ptr == null or session.hostkey_len == 0) return null;
    return jniNewByteArrayOrNull(env, session.hostkey_ptr.?[0..session.hostkey_len]);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeGetHostKeyAlgorithm(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    return jniNewStringOrNull(env, hostkeyAlgorithmName(session.hostkey_type));
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeAuthenticatePassword(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, password: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const password_slice = jniDupString(env, password) orelse {
        setError(session, "Missing password", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(password_slice);
    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("Password auth attempt start username_len={} password_len={}", .{ username_slice.len, password_slice.len });
    const password_deadline_ms = nowMillis() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_password_ex(ssh_session, username_slice.ptr, @intCast(username_slice.len), password_slice.ptr, @intCast(password_slice.len), null);
        if (rc == 0) {
            logInfo("Password auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("Password auth failed rc={} (keyboard-interactive fallback disabled for diagnostics)", .{rc});
            setLibssh2Error(session, "Password auth failed", rc);
            return c.JNI_FALSE;
        }
        if (nowMillis() >= password_deadline_ms) {
            logError("Password auth timed out (keyboard-interactive fallback disabled for diagnostics)", .{});
            setError(session, "Password auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeAuthenticatePublicKey(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, key_path: c.jstring, passphrase: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const key_path_slice = jniDupString(env, key_path) orelse {
        setError(session, "Missing key path", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(key_path_slice);
    const key_path_z = dupSentinel(key_path_slice) orelse {
        setError(session, "Failed to allocate key path", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(key_path_z);

    const passphrase_slice = jniDupString(env, passphrase);
    defer if (passphrase_slice) |ps| allocator.free(ps);
    const passphrase_z = if (passphrase_slice) |ps| dupSentinel(ps) else null;
    defer if (passphrase_z) |pz| allocator.free(pz);
    const passphrase_ptr: ?[*:0]const u8 = if (passphrase_z) |pz| pz.ptr else null;

    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);

    logInfo("Public key auth attempt key={s}", .{key_path_slice});
    const deadline_ms = nowMillis() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_publickey_fromfile_ex(
            ssh_session,
            username_slice.ptr,
            @intCast(username_slice.len),
            null,
            key_path_z.ptr,
            passphrase_ptr,
        );
        if (rc == 0) {
            logInfo("Public key auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("Public key auth failed rc={} key={s}", .{ rc, key_path_slice });
            setLibssh2Error(session, "Public key auth failed", rc);
            return c.JNI_FALSE;
        }
        if (nowMillis() >= deadline_ms) {
            setError(session, "Public key auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeAuthenticatePublicKeyMemory(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, public_key_open_ssh: c.jstring, private_key_pem: c.jstring, passphrase: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const private_key_slice = jniDupString(env, private_key_pem) orelse {
        setError(session, "Missing private key", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(private_key_slice);

    const public_key_slice = jniDupString(env, public_key_open_ssh);
    defer if (public_key_slice) |pk| allocator.free(pk);
    const trimmed_public = if (public_key_slice) |pk| std.mem.trim(u8, pk, " \t\r\n") else "";
    const compact_public: ?[]u8 = blk: {
        if (trimmed_public.len == 0) break :blk null;
        var it = std.mem.tokenizeAny(u8, trimmed_public, " \t\r\n");
        const t0 = it.next() orelse break :blk null;
        const t1 = it.next() orelse break :blk null;
        break :blk std.fmt.allocPrint(allocator, "{s} {s}", .{ t0, t1 }) catch null;
    };
    defer if (compact_public) |pk| allocator.free(pk);

    const passphrase_slice = jniDupString(env, passphrase);
    defer if (passphrase_slice) |ps| allocator.free(ps);
    const passphrase_z = if (passphrase_slice) |ps| dupSentinel(ps) else null;
    defer if (passphrase_z) |pz| allocator.free(pz);
    const passphrase_ptr: ?[*:0]const u8 = if (passphrase_z) |pz| pz.ptr else null;

    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);

    const auth_list_ptr = c.libssh2_userauth_list(ssh_session, username_slice.ptr, @intCast(username_slice.len));
    if (auth_list_ptr != null) {
        logInfo("Public key memory auth methods user={s} methods={s}", .{ username_slice, std.mem.span(auth_list_ptr) });
    } else {
        logInfo("Public key memory auth methods user={s} methods=<none>", .{username_slice});
    }

    logInfo(
        "Public key memory auth attempt user={s} publicLen={} privateLen={} passphraseLen={}",
        .{ username_slice, if (compact_public) |pk| pk.len else @as(usize, 0), private_key_slice.len, if (passphrase_slice) |ps| ps.len else @as(usize, 0) },
    );

    const deadline_ms = nowMillis() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_publickey_frommemory(
            ssh_session,
            username_slice.ptr,
            username_slice.len,
            if (compact_public) |pk| pk.ptr else null,
            if (compact_public) |pk| pk.len else 0,
            private_key_slice.ptr,
            private_key_slice.len,
            passphrase_ptr,
        );
        if (rc == 0) {
            logInfo("Public key memory auth succeeded user={s}", .{username_slice});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            const last_errno = c.libssh2_session_last_errno(ssh_session);
            logError("Public key memory auth failed rc={} last_errno={}", .{ rc, last_errno });
            setLibssh2Error(session, "Public key memory auth failed", rc);
            return c.JNI_FALSE;
        }
        if (nowMillis() >= deadline_ms) {
            setError(session, "Public key memory auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeOpenShell(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint, term: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const term_slice = jniDupString(env, term) orelse allocator.dupe(u8, "xterm-ghostty") catch return c.JNI_FALSE;
    defer allocator.free(term_slice);
    var channel: ?*c.LIBSSH2_CHANNEL = null;
    while (channel == null) {
        channel = c.libssh2_channel_open_ex(ssh_session, "session", 7, c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
        if (channel != null) break;
        const open_rc = c.libssh2_session_last_errno(ssh_session);
        if (open_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Channel open failed", open_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Channel open timed out", .{});
            return c.JNI_FALSE;
        }
    }
    session.channel = channel;
    c.libssh2_channel_set_blocking(channel.?, 0);
    var term_buf = allocator.allocSentinel(u8, term_slice.len, 0) catch {
        setError(session, "TERM alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(term_buf);
    @memcpy(term_buf[0..term_slice.len], term_slice);

    while (true) {
        const pty_rc = c.libssh2_channel_request_pty_ex(channel.?, term_buf.ptr, @intCast(term_slice.len), null, 0, cols, rows, width_px, height_px);
        if (pty_rc == 0) break;
        if (pty_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "PTY request failed", pty_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "PTY request timed out", .{});
            return c.JNI_FALSE;
        }
    }
    // PTY negotiation already conveys TERM. Extra env vars are best-effort
    // because many OpenSSH servers reject channel-setenv unless AcceptEnv
    // explicitly allows them.
    trySetChannelEnv(session, channel.?, "COLORTERM", "truecolor");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM", "ghostty");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM_VERSION", "1");
    while (true) {
        const startup_rc = c.libssh2_channel_process_startup(channel.?, "shell", 5, null, 0);
        if (startup_rc == 0) break;
        if (startup_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Shell start failed", startup_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Shell start timed out", .{});
            return c.JNI_FALSE;
        }
    }
    setSocketNonBlocking(session.socket_fd);
    c.libssh2_session_set_blocking(ssh_session, 0);
    c.libssh2_channel_set_blocking(channel.?, 0);
    return c.JNI_TRUE;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeOpenDirectTcpip(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
    host: c.jstring,
    port: c.jint,
    source_host: c.jstring,
    source_port: c.jint,
) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };

    const host_slice = jniDupString(env, host) orelse {
        setError(session, "Missing direct-tcpip host", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_slice);
    const source_host_slice = jniDupString(env, source_host) orelse allocator.dupe(u8, "127.0.0.1") catch {
        setError(session, "source host alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(source_host_slice);

    const host_z = dupSentinel(host_slice) orelse {
        setError(session, "direct-tcpip host alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_z);
    const source_host_z = dupSentinel(source_host_slice) orelse {
        setError(session, "direct-tcpip source host alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(source_host_z);

    if (session.channel) |old_channel| {
        _ = c.libssh2_channel_close(old_channel);
        _ = c.libssh2_channel_free(old_channel);
        session.channel = null;
    }

    var channel: ?*c.LIBSSH2_CHANNEL = null;
    while (channel == null) {
        channel = c.libssh2_channel_direct_tcpip_ex(
            ssh_session,
            host_z.ptr,
            port,
            source_host_z.ptr,
            source_port,
        );
        if (channel != null) break;
        const open_rc = c.libssh2_session_last_errno(ssh_session);
        if (open_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "direct-tcpip open failed", open_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "direct-tcpip open timed out", .{});
            return c.JNI_FALSE;
        }
    }

    session.channel = channel;
    setSocketNonBlocking(session.socket_fd);
    c.libssh2_session_set_blocking(ssh_session, 0);
    c.libssh2_channel_set_blocking(channel.?, 0);
    return c.JNI_TRUE;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeResize(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const channel = session.channel orelse return c.JNI_FALSE;
    while (true) {
        const rc = c.libssh2_channel_request_pty_size_ex(channel, cols, rows, width_px, height_px);
        if (rc == 0) return c.JNI_TRUE;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "PTY resize failed", rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "PTY resize timed out", .{});
            return c.JNI_FALSE;
        }
    }
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeIpcExchange(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, request: c.jbyteArray) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const req_bytes = readJByteArray(env, request) orelse return null;
    defer if (req_bytes.len > 0) allocator.free(req_bytes);

    var response: std.ArrayList(u8) = .empty;
    defer response.deinit(allocator);

    const frame = ipc.parse(req_bytes) catch {
        setError(session, "Invalid IPC frame", .{});
        appendErrorFrame(&response, session, "Invalid IPC frame");
        return jniNewByteArrayOrNull(env, response.items);
    };

    switch (frame.header.tag) {
        .Write => {
            const written = writeChannel(session, frame.payload);
            if (written < 0) {
                appendErrorFrame(&response, session, "Write failed");
                return jniNewByteArrayOrNull(env, response.items);
            }
            const written_u32: u32 = @intCast(written);
            ipc.appendMessage(allocator, &response, .Ack, std.mem.asBytes(&written_u32)) catch {};
        },
        .Read => {
            if (frame.payload.len != @sizeOf(u32)) {
                setError(session, "Invalid read request payload", .{});
                appendErrorFrame(&response, session, "Invalid read request payload");
                return jniNewByteArrayOrNull(env, response.items);
            }
            const max_bytes = std.mem.bytesToValue(u32, frame.payload[0..@sizeOf(u32)]);
            const bytes = readChannel(session, @intCast(@max(max_bytes, 1))) orelse {
                appendErrorFrame(&response, session, "Read failed");
                return jniNewByteArrayOrNull(env, response.items);
            };
            ipc.appendMessage(allocator, &response, .Data, bytes) catch {};
        },
        .Keepalive => {
            if (sendKeepAlive(session)) {
                ipc.appendMessage(allocator, &response, .Ack, &.{}) catch {};
            } else {
                appendErrorFrame(&response, session, "Keepalive failed");
            }
        },
        else => {
            setError(session, "Unsupported IPC tag {}", .{@intFromEnum(frame.header.tag)});
            appendErrorFrame(&response, session, "Unsupported IPC tag");
        },
    }

    return jniNewByteArrayOrNull(env, response.items);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeClose(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
    if (session.channel) |channel| {
        _ = c.libssh2_channel_close(channel);
        _ = c.libssh2_channel_free(channel);
        session.channel = null;
    }
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_disconnect_ex(ssh_session, c.SSH_DISCONNECT_BY_APPLICATION, "bye", "en");
        _ = c.libssh2_session_free(ssh_session);
        session.session = null;
    }
    closeSocket(session.socket_fd);
    session.socket_fd = -1;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpInit(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    return if (ensureSftp(session) != null) c.JNI_TRUE else c.JNI_FALSE;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpListDirectory(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jobjectArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const sftp = ensureSftp(session) orelse return null;
    const path_bytes = jniDupString(env, path) orelse return null;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return null;
    defer allocator.free(path_z);

    const dir = sftpOpenDir(session, sftp, path_z) orelse return null;
    defer _ = c.libssh2_sftp_closedir(dir);

    var names: std.ArrayList([]u8) = .empty;
    defer {
        for (names.items) |n| allocator.free(n);
        names.deinit(allocator);
    }

    var entry_buf: [1024]u8 = undefined;
    var attrs: c.LIBSSH2_SFTP_ATTRIBUTES = undefined;
    while (true) {
        const rc = c.libssh2_sftp_readdir_ex(dir, &entry_buf, @intCast(entry_buf.len), null, 0, &attrs);
        if (rc > 0) {
            const name = entry_buf[0..@intCast(rc)];
            if (std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) continue;
            const kind: []const u8 = blk: {
                const has_perms = (attrs.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0;
                if (!has_perms) break :blk "other";
                const mode: c_ulong = attrs.permissions;
                const kind_bits: c_ulong = mode & 0o170000;
                if (kind_bits == 0o040000) break :blk "dir";
                if (kind_bits == 0o100000) break :blk "file";
                if (kind_bits == 0o120000) break :blk "link";
                break :blk "other";
            };
            const size: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_SIZE) != 0) @intCast(attrs.filesize) else 0;
            const mtime: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_ACMODTIME) != 0) @intCast(attrs.mtime) else 0;
            const perms: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0) @intCast(attrs.permissions) else 0;
            const line = std.fmt.allocPrint(allocator, "{s}\t{s}\t{}\t{}\t{}", .{ name, kind, size, mtime, perms }) catch break;
            const copy = line;
            names.append(allocator, copy) catch {
                allocator.free(copy);
                break;
            };
            continue;
        }
        if (rc == 0) break;
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            if (!waitSocket(session, io_wait_timeout_ms)) break;
            continue;
        }
        break;
    }

    const string_class = env.*.*.FindClass.?(env, "java/lang/String") orelse return null;
    const result = env.*.*.NewObjectArray.?(env, @intCast(names.items.len), string_class, null) orelse return null;
    for (names.items, 0..) |name, idx| {
        const j_name = jniNewStringOrNull(env, name) orelse continue;
        env.*.*.SetObjectArrayElement.?(env, result, @intCast(idx), j_name);
    }
    return result;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpRealpath(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const sftp = ensureSftp(session) orelse return null;
    const path_bytes = jniDupString(env, path) orelse return null;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return null;
    defer allocator.free(path_z);

    var out_buf: [2048]u8 = undefined;
    const rc = c.libssh2_sftp_realpath(sftp, path_z.ptr, &out_buf, @as(c_int, @intCast(out_buf.len)));
    if (rc <= 0) {
        setLibssh2Error(session, "SFTP realpath failed", c.libssh2_session_last_errno(session.session.?));
        return null;
    }
    return jniNewStringOrNull(env, out_buf[0..@intCast(rc)]);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpOpenWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp = ensureSftp(session) orelse return c.JNI_FALSE;
    const path_bytes = jniDupString(env, path) orelse return c.JNI_FALSE;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return c.JNI_FALSE;
    defer allocator.free(path_z);

    if (session.upload_handle) |stale| {
        while (true) {
            const rc = c.libssh2_sftp_close_handle(stale);
            if (rc != c.LIBSSH2_ERROR_EAGAIN) break;
            if (!waitSocket(session, io_wait_timeout_ms)) break;
        }
        session.upload_handle = null;
    }

    const flags: c_ulong = c.LIBSSH2_FXF_WRITE | c.LIBSSH2_FXF_CREAT | c.LIBSSH2_FXF_TRUNC;
    const mode: c_long = c.LIBSSH2_SFTP_S_IRUSR | c.LIBSSH2_SFTP_S_IWUSR | c.LIBSSH2_SFTP_S_IRGRP | c.LIBSSH2_SFTP_S_IROTH;
    const file = sftpOpenFile(session, sftp, path_z, flags, mode) orelse return c.JNI_FALSE;
    session.upload_handle = file;
    return c.JNI_TRUE;
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpWriteChunk(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, data: c.jbyteArray) callconv(.c) c.jint {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return -1;
    const sftp_handle = session.upload_handle orelse return -1;

    const data_len = env.*.*.GetArrayLength.?(env, data);
    if (data_len == 0) return 0;
    const data_ptr = env.*.*.GetByteArrayElements.?(env, data, null) orelse return -1;
    defer env.*.*.ReleaseByteArrayElements.?(env, data, data_ptr, c.JNI_ABORT);
    const ptr: [*]const u8 = @ptrCast(data_ptr);
    const sftp_write_idle_limit_ms: i64 = 30_000;
    var written: usize = 0;
    var idle_since_ms: i64 = nowMillis();
    while (written < @as(usize, @intCast(data_len))) {
        const remaining: usize = @as(usize, @intCast(data_len)) - written;
        const chunk_size: usize = @min(remaining, 32768);
        const rc = c.libssh2_sftp_write(sftp_handle, ptr + written, chunk_size);
        if (rc == c.LIBSSH2_ERROR_EAGAIN or rc == 0) {
            if (waitSocket(session, io_wait_timeout_ms)) {
                idle_since_ms = nowMillis();
            } else if (nowMillis() - idle_since_ms > sftp_write_idle_limit_ms) {
                setError(session, "SFTP write stalled (no socket activity for {d}ms)", .{sftp_write_idle_limit_ms});
                return -1;
            }
            continue;
        }
        if (rc < 0) {
            setLibssh2Error(session, "SFTP write failed", @intCast(rc));
            return -1;
        }
        written += @intCast(rc);
        idle_since_ms = nowMillis();
    }

    return @intCast(written);
}

export fn Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_nativeSftpCloseWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = thiz;
    _ = env;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp_handle = session.upload_handle orelse return c.JNI_FALSE;
    session.upload_handle = null;
    const close_idle_limit_ms: i64 = 10_000;
    var idle_since_ms: i64 = nowMillis();
    while (true) {
        const rc = c.libssh2_sftp_close_handle(sftp_handle);
        if (rc == 0) return c.JNI_TRUE;
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            if (waitSocket(session, io_wait_timeout_ms)) {
                idle_since_ms = nowMillis();
                continue;
            }
            if (nowMillis() - idle_since_ms < close_idle_limit_ms) continue;
            setError(session, "SFTP close stalled (no socket activity for {d}ms)", .{close_idle_limit_ms});
            return c.JNI_FALSE;
        }
        setLibssh2Error(session, "SFTP close failed", @intCast(rc));
        return c.JNI_FALSE;
    }
}

fn sftpOpenFile(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, path_z: [:0]u8, flags: c_ulong, mode: c_long) ?*c.LIBSSH2_SFTP_HANDLE {
    while (true) {
        const file = c.libssh2_sftp_open(sftp, path_z.ptr, flags, mode);
        if (file != null) return file;
        const rc = c.libssh2_session_last_errno(session.session.?);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "SFTP open failed", rc);
            return null;
        }
        if (!waitSocket(session, io_wait_timeout_ms)) {
            setError(session, "SFTP open timed out", .{});
            return null;
        }
    }
}
