import Foundation

struct ConnectionInfo: Equatable {
    let username: String
    let host: String
    let port: Int
    let token: PairingToken
    let hostFingerprint: String?

    func pairingURL() -> URL {
        var components = URLComponents()
        components.scheme = "ssh"
        components.user = username
        components.host = host
        components.port = port
        var items: [URLQueryItem] = [URLQueryItem(name: "token", value: token.value)]
        if let fp = hostFingerprint, !fp.isEmpty {
            items.append(URLQueryItem(name: "fp", value: fp))
        }
        components.queryItems = items
        guard let url = components.url else {
            fatalError("ConnectionInfo failed to produce a URL: host=\(host) port=\(port)")
        }
        return url
    }
}
