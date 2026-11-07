import CryptoKit
import Foundation

enum SSHKeyError: LocalizedError {
    case fingerprintReadFailed(String)

    var errorDescription: String? {
        switch self {
        case .fingerprintReadFailed(let path):
            return "Could not read host fingerprint at \(path) (file missing or unreadable)."
        }
    }
}

enum SSHKeyService {
    static let hostPubKeyPath = "/etc/ssh/ssh_host_ecdsa_key.pub"

    /// SHA-256 of the Mac host pubkey, encoded as standard SSH fingerprint (`SHA256:<base64>`).
    /// The headset's current libssh2 build negotiates ECDSA first, so the QR must advertise
    /// the ECDSA host key until the native bridge is rebuilt to prefer Ed25519.
    /// The headset can compare this to the fingerprint observed during handshake to detect MITM.
    static func hostFingerprint() throws -> String {
        guard FileManager.default.isReadableFile(atPath: hostPubKeyPath) else {
            throw SSHKeyError.fingerprintReadFailed(hostPubKeyPath)
        }
        let contents = try String(contentsOfFile: hostPubKeyPath, encoding: .utf8)
        // Format: "ssh-ed25519 AAAA... comment"
        let parts = contents.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true)
        guard parts.count >= 2, let keyData = Data(base64Encoded: String(parts[1])) else {
            throw SSHKeyError.fingerprintReadFailed(hostPubKeyPath)
        }
        let digest = SHA256.hash(data: keyData)
        let b64 = Data(digest).base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
        return "SHA256:\(b64)"
    }
}
