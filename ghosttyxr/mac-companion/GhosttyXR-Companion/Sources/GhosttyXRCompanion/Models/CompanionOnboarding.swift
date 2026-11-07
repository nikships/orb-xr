import Foundation

enum CompanionOnboardingStep: Int, CaseIterable {
    case welcome
    case macReady
    case credentials
    case pair

    var number: Int {
        rawValue + 1
    }
}

enum CompanionOnboardingReadiness {
    static func macReady(remoteLogin: RemoteLoginState, hasConnection: Bool, tmuxInstalled: Bool) -> Bool {
        hasConnection && remoteLogin == .on && tmuxInstalled
    }

    static func credentialsReady(password: String, confirmation: String) -> Bool {
        !password.isEmpty && password == confirmation
    }
}
