import Foundation

struct PairingToken: Equatable {
    let value: String
    let issuedAt: Date
    let lifetime: TimeInterval

    var expiresAt: Date { issuedAt.addingTimeInterval(lifetime) }

    func isExpired(at now: Date = Date()) -> Bool {
        now >= expiresAt
    }

    static func generate(lifetime: TimeInterval = 30, byteCount: Int = 24) -> PairingToken {
        precondition(byteCount > 0, "token byteCount must be positive")
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        guard status == errSecSuccess else {
            fatalError("SecRandomCopyBytes failed with status \(status)")
        }
        let base64 = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return PairingToken(value: base64, issuedAt: Date(), lifetime: lifetime)
    }
}
