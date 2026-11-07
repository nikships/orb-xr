import AppKit
import Darwin
import Foundation
import Network

enum RemoteLoginState: Equatable {
    case on
    case off
    case unknown(reason: String)
}

enum RemoteLoginService {
    /// Two-tier detection:
    /// 1. `systemsetup -getremotelogin` — authoritative but requires admin since macOS Ventura.
    /// 2. TCP probe of 127.0.0.1:22 — reliable for any user; tells us sshd is actually accepting connections.
    /// We prefer (1) when it works, fall back to (2), and only return .unknown if both fail.
    static func currentState() -> RemoteLoginState {
        switch systemsetupProbe() {
        case .on: return .on
        case .off: return .off
        case .unknown:
            return tcpProbe()
        }
    }

    private static func systemsetupProbe() -> RemoteLoginState {
        let process = Process()
        process.launchPath = "/usr/sbin/systemsetup"
        process.arguments = ["-getremotelogin"]
        let stdout = Pipe()
        let stderr = Pipe()
        process.standardOutput = stdout
        process.standardError = stderr

        do {
            try process.run()
        } catch {
            return .unknown(reason: "Failed to invoke systemsetup: \(error.localizedDescription)")
        }
        process.waitUntilExit()

        let outData = stdout.fileHandleForReading.readDataToEndOfFile()
        let errData = stderr.fileHandleForReading.readDataToEndOfFile()
        let output = String(data: outData, encoding: .utf8) ?? ""
        let errOutput = String(data: errData, encoding: .utf8) ?? ""

        if output.range(of: "On", options: .caseInsensitive) != nil
            && output.range(of: "Remote Login", options: .caseInsensitive) != nil {
            return .on
        }
        if output.range(of: "Off", options: .caseInsensitive) != nil
            && output.range(of: "Remote Login", options: .caseInsensitive) != nil {
            return .off
        }

        let reason = errOutput.isEmpty ? "Unexpected output: \(output.trimmingCharacters(in: .whitespacesAndNewlines))"
                                       : errOutput.trimmingCharacters(in: .whitespacesAndNewlines)
        return .unknown(reason: reason)
    }

    /// Opens System Settings → Sharing → Remote Login so the user can flip it on themselves.
    /// We don't enable it programmatically: `systemsetup -setremotelogin on` requires admin and
    /// silently fails when invoked from a sandboxed/normal context. Honest UX over fake automation.
    static func openSharingSettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preferences.sharing?Service_RemoteLogin")!
        NSWorkspace.shared.open(url)
    }

    /// Attempts a TCP connect to 127.0.0.1:22 with a short timeout. If the kernel accepts the
    /// connection, sshd is listening. ECONNREFUSED → off. Any other error → unknown.
    private static func tcpProbe(timeout: TimeInterval = 0.4) -> RemoteLoginState {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        if fd < 0 {
            return .unknown(reason: "socket() failed (errno \(errno))")
        }
        defer { close(fd) }

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(22).bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let connectRC = withUnsafePointer(to: &addr) { ptr -> Int32 in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if connectRC == 0 {
            return .on
        }
        if errno == ECONNREFUSED {
            return .off
        }
        if errno != EINPROGRESS {
            return .unknown(reason: "connect() errno \(errno)")
        }

        var writeSet = fd_set()
        fdZero(&writeSet)
        fdSet(fd, set: &writeSet)
        var tv = timeval(tv_sec: Int(timeout), tv_usec: Int32((timeout - floor(timeout)) * 1_000_000))
        let selectRC = select(fd + 1, nil, &writeSet, nil, &tv)
        if selectRC <= 0 {
            return .unknown(reason: "select() timed out probing 127.0.0.1:22")
        }
        var soError: Int32 = 0
        var len = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &soError, &len)
        if soError == 0 { return .on }
        if soError == ECONNREFUSED { return .off }
        return .unknown(reason: "SO_ERROR \(soError) probing 127.0.0.1:22")
    }

    private static func fdZero(_ set: inout fd_set) {
        set = fd_set()
    }

    private static func fdSet(_ fd: Int32, set: inout fd_set) {
        let intOffset = Int(fd / 32)
        let bitOffset = Int(fd % 32)
        let mask: Int32 = 1 << bitOffset
        withUnsafeMutablePointer(to: &set.fds_bits) { tuplePtr in
            tuplePtr.withMemoryRebound(to: Int32.self, capacity: 32) { intPtr in
                intPtr[intOffset] |= mask
            }
        }
    }
}
