import Darwin
import Foundation

enum NetworkInterfaceError: LocalizedError {
    case getifaddrsFailed(Int32)

    var errorDescription: String? {
        switch self {
        case .getifaddrsFailed(let code):
            return "getifaddrs failed (errno \(code))"
        }
    }
}

struct NetworkInterface: Equatable {
    let name: String
    let ipv4: String
}

enum NetworkInterfaceService {
    /// Preferred ordering: WiFi is usually en0 on Apple Silicon laptops; en1 on some older Intel Macs.
    /// We surface every up/running IPv4 interface and let the caller pick.
    static func activeIPv4Interfaces() throws -> [NetworkInterface] {
        var head: UnsafeMutablePointer<ifaddrs>?
        let rc = getifaddrs(&head)
        guard rc == 0, let first = head else {
            throw NetworkInterfaceError.getifaddrsFailed(errno)
        }
        defer { freeifaddrs(head) }

        var results: [NetworkInterface] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP,
                  (flags & IFF_RUNNING) == IFF_RUNNING,
                  (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET) else { continue }

            let name = String(cString: ptr.pointee.ifa_name)
            var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let nameInfo = getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &hostBuf,
                socklen_t(hostBuf.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard nameInfo == 0 else { continue }
            let ip = String(cString: hostBuf)
            if ip == "0.0.0.0" || ip.hasPrefix("169.254.") { continue }
            results.append(NetworkInterface(name: name, ipv4: ip))
        }

        return results.sorted { rank($0.name) < rank($1.name) }
    }

    private static func rank(_ name: String) -> Int {
        switch name {
        case "en0": return 0
        case "en1": return 1
        case "en2": return 2
        default:
            if name.hasPrefix("en") { return 10 }
            if name.hasPrefix("bridge") { return 50 }
            if name.hasPrefix("utun") || name.hasPrefix("awdl") || name.hasPrefix("llw") { return 90 }
            return 100
        }
    }
}
