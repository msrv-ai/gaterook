// Native startup verification for GateRook. The OS account remains the storage boundary, as with
// Electron safeStorage: LocalAuthentication is an application gate, not a Secure Enclave key policy.
// The legacy Keychain item trusts this signed helper and remains compatible with local ad-hoc builds.
// Key access uses one authentication path: the native Keychain access dialog when it is needed,
// otherwise LocalAuthentication. A key is never returned before that path succeeds.
import Foundation
import LocalAuthentication
import Security

private let service = "GateRook Vault Unlock"
private let reason = "unlock your saved GateRook credentials"
private let maximumRequestBytes = 4096

private struct Failure: Error {
    let code: String
    let keychainStatus: OSStatus?
    init(_ code: String, status: OSStatus? = nil) {
        self.code = code
        self.keychainStatus = status
    }
}

private func reply(_ value: [String: Any]) {
    guard var data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return }
    data.append(0x0a)
    FileHandle.standardOutput.write(data)
}

private func request() throws -> [String: Any] {
    var bytes = Data()
    while let part = try FileHandle.standardInput.read(upToCount: maximumRequestBytes + 1 - bytes.count), !part.isEmpty {
        bytes.append(part)
        if bytes.count > maximumRequestBytes { throw Failure("invalid-request") }
    }
    guard let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
        throw Failure("invalid-request")
    }
    return value
}

private func account(_ input: [String: Any]) throws -> String {
    guard let value = input["account"] as? String, !value.isEmpty, value.utf8.count <= 128,
          value.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil else {
        throw Failure("invalid-request")
    }
    return value
}

private func query(_ account: String) -> [String: Any] {
    // Explicitly use the file-based Keychain. Data Protection Keychain requires provisioned
    // keychain-access-group entitlements, which an ad-hoc development build cannot supply.
    return [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: false]
}

private func keychainError(_ status: OSStatus) -> Failure {
    let code: String
    switch status {
    case errSecItemNotFound: code = "missing"
    case errSecDuplicateItem: code = "exists"
    case errSecUserCanceled: code = "cancelled"
    case errSecAuthFailed, errSecInteractionNotAllowed, errSecInteractionRequired: code = "denied"
    case errSecNotAvailable: code = "unavailable"
    default: code = "failed"
    }
    return Failure(code, status: status)
}

private func requiresKeychainInteraction(_ failure: Failure) -> Bool {
    switch failure.keychainStatus {
    case errSecAuthFailed, errSecInteractionNotAllowed, errSecInteractionRequired: return true
    default: return false
    }
}

private func withoutKeychainInteraction<T>(_ operation: () throws -> T) throws -> T {
    var wasAllowed = DarwinBoolean(true)
    guard SecKeychainGetUserInteractionAllowed(&wasAllowed) == errSecSuccess,
          SecKeychainSetUserInteractionAllowed(false) == errSecSuccess else {
        throw Failure("unavailable")
    }
    defer { SecKeychainSetUserInteractionAllowed(wasAllowed.boolValue) }
    return try operation()
}

private func authenticate() throws {
    let context = LAContext()
    defer { context.invalidate() }
    context.localizedFallbackTitle = "Use password"
    var availabilityError: NSError?
    guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &availabilityError) else {
        throw Failure("unavailable")
    }
    let completed = DispatchSemaphore(value: 0)
    var success = false
    var failure: Error?
    context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { verified, error in
        success = verified
        failure = error
        completed.signal()
    }
    completed.wait()
    guard success else {
        let code = (failure as? LAError)?.code
        switch code {
        case .userCancel, .systemCancel, .appCancel: throw Failure("cancelled")
        case .biometryNotAvailable, .biometryNotEnrolled, .passcodeNotSet: throw Failure("unavailable")
        default: throw Failure("denied")
        }
    }
}

private func readKey(_ account: String) throws -> Data {
    var lookup = query(account)
    lookup[kSecReturnData as String] = true
    lookup[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(lookup as CFDictionary, &result)
    guard status == errSecSuccess else { throw keychainError(status) }
    guard let key = result as? Data, key.count == 32 else { throw Failure("failed") }
    return key
}

private func storeKey(_ name: String, key: Data) throws {
    var item = query(name)
    item[kSecAttrLabel as String] = "GateRook encryption key"
    item[kSecValueData as String] = key
    let status = SecItemAdd(item as CFDictionary, nil)
    if status == errSecDuplicateItem {
        // A failed local reference write can retry storage. This comparison never releases
        // the stored key and never replaces another key.
        var previous = try readKey(name)
        defer { previous.resetBytes(in: 0..<previous.count) }
        let difference = zip(previous, key).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) }
        guard difference == 0 else { throw Failure("exists") }
    } else if status != errSecSuccess {
        throw keychainError(status)
    }
}

private func run(_ input: [String: Any]) throws -> [String: Any] {
    switch input["operation"] as? String {
    case "check":
        let context = LAContext()
        defer { context.invalidate() }
        var error: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
        let biometrics = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        return ["ok": true, "available": available, "touchId": biometrics && context.biometryType == .touchID]
    case "verify":
        try authenticate()
        return ["ok": true]
    case "delete":
        // The per-home reference supplies one account in our fixed service. Never read its key
        // or delete Electron's shared Safe Storage entry when resetting this vault.
        let name = try account(input)
        let status = SecItemDelete(query(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw keychainError(status) }
        return ["ok": true]
    case "get":
        let name = try account(input)
        var key: Data
        var keychainAuthorization = false
        do {
            key = try withoutKeychainInteraction { try readKey(name) }
        } catch let failure as Failure where requiresKeychainInteraction(failure) {
            // A locked Keychain or changed helper signature may need its password/access dialog.
            // Let that native access grant unlock the app, without also asking for Touch ID.
            key = try readKey(name)
            keychainAuthorization = true
        }
        defer { key.resetBytes(in: 0..<key.count) }
        if !keychainAuthorization { try authenticate() }
        return ["ok": true, "key": key.base64EncodedString()]
    case "store", "create":
        let name = try account(input)
        guard let encoded = input["key"] as? String,
              var key = Data(base64Encoded: encoded), key.count == 32,
              key.base64EncodedString() == encoded else { throw Failure("invalid-request") }
        defer { key.resetBytes(in: 0..<key.count) }
        if input["operation"] as? String == "create" {
            var keychainAuthorization = false
            do {
                try withoutKeychainInteraction { try storeKey(name, key: key) }
            } catch let failure as Failure where requiresKeychainInteraction(failure) {
                try storeKey(name, key: key)
                keychainAuthorization = true
            }
            if !keychainAuthorization { try authenticate() }
        } else {
            // Migration already obtained this key through the legacy provider's authentication.
            // A second permission prompt must not follow it; the caller retains its legacy key on failure.
            try withoutKeychainInteraction { try storeKey(name, key: key) }
        }
        return ["ok": true]
    default:
        throw Failure("invalid-request")
    }
}

do {
    reply(try run(request()))
} catch let failure as Failure {
    reply(["ok": false, "error": failure.code])
} catch {
    // Never print native errors: descriptions can include account names or Keychain data.
    reply(["ok": false, "error": "invalid-request"])
}
