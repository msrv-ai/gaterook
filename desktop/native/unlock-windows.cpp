// Windows Hello, or the account password where Hello isn't set up, is an application verification gate where DPAPI
// remains the storage boundary, matching Electron safeStorage. A file key first made on a computer with Hello is bound
// to a Hello key instead (hello-key-*): main derives the key that wraps it from that key's signature of the vault's
// own challenge, which takes the person's Hello gesture.
#include <windows.h>
#include <wincred.h>
#include <objbase.h>
#include <UserConsentVerifierInterop.h>
#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Security.Credentials.h>
#include <winrt/Windows.Security.Credentials.UI.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>
#include <atomic>
#include <charconv>
#include <cstdint>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Data::Json;
using namespace Windows::Foundation;
using namespace Windows::Security::Credentials;
using namespace Windows::Security::Credentials::UI;
using namespace Windows::Security::Cryptography;
using namespace Windows::Storage::Streams;

static void reply(const char* result) {
    std::cout << result << '\n';
}

static void failed(const char* error) {
    std::cout << "{\"ok\":false,\"error\":\"" << error << "\"}\n";
}

// The window a system dialog belongs to: the handle main passes as decimal text, or null when it isn't a window.
static HWND ownerWindow(const JsonObject& request) {
    const std::string handle = to_string(request.GetNamedString(L"windowHandle", L""));
    std::uintptr_t number = 0;
    const auto parsed = std::from_chars(handle.data(), handle.data() + handle.size(), number);
    if (handle.empty() || parsed.ec != std::errc{} || parsed.ptr != handle.data() + handle.size() || number == 0) {
        return nullptr;
    }
    const auto window = reinterpret_cast<HWND>(number);
    return IsWindow(window) ? window : nullptr;
}

// ── The Hello key of a vault ─────────────────────────────────────────────────────────────────────────────────

// While a Hello key operation waits for the person, its dialog is raised above the app's window: KeyCredentialManager
// has no window interop for a desktop app, and its dialog would otherwise open behind. This helper, started by the app
// in the foreground, may set the foreground window.
static std::atomic<bool> helloWaiting{false};

static void raiseHelloDialog() {
    for (int i = 0; i < 300 && helloWaiting; ++i) {
        const HWND dialog = FindWindowW(L"Credential Dialog Xaml Host", nullptr);
        if (dialog) {
            SetForegroundWindow(dialog);
            return;
        }
        Sleep(100);
    }
}

static bool validAccount(const hstring& account) {
    if (account.empty() || account.size() > 128) return false;
    for (const wchar_t c : account) {
        if (!((c >= L'a' && c <= L'z') || (c >= L'A' && c <= L'Z') || (c >= L'0' && c <= L'9') || c == L'.' || c == L'_'
              || c == L'-')) {
            return false;
        }
    }
    return true;
}

static const char* statusCode(KeyCredentialStatus status) {
    switch (status) {
    case KeyCredentialStatus::UserCanceled: return "cancelled";
    case KeyCredentialStatus::NotFound: return "missing";
    case KeyCredentialStatus::UserPrefersPassword: return "denied";
    case KeyCredentialStatus::SecurityDeviceLocked: return "unavailable";
    default: return "failed";
    }
}

// hello-key-create makes the vault's Hello key (replacing one of that name) and signs the challenge with it;
// hello-key-sign signs it with the existing key. Either way the reply is the signature, which only the person's
// gesture releases.
static void helloSign(const JsonObject& request, bool create) {
    const hstring account = request.GetNamedString(L"account", L"");
    const hstring challenge = request.GetNamedString(L"challenge", L"");
    if (!validAccount(account) || challenge.empty() || challenge.size() > 64) { failed("invalid-request"); return; }
    const IBuffer data = CryptographicBuffer::DecodeFromBase64String(challenge);
    if (data.Length() != 32) { failed("invalid-request"); return; }
    if (!KeyCredentialManager::IsSupportedAsync().get()) { failed("unavailable"); return; }
    helloWaiting = true;
    std::thread raise(raiseHelloDialog);
    const char* error = nullptr;
    hstring signature;
    try {
        const KeyCredentialRetrievalResult key = create
            ? KeyCredentialManager::RequestCreateAsync(account, KeyCredentialCreationOption::ReplaceExisting).get()
            : KeyCredentialManager::OpenAsync(account).get();
        if (key.Status() != KeyCredentialStatus::Success) {
            error = statusCode(key.Status());
        } else {
            const KeyCredentialOperationResult signing = key.Credential().RequestSignAsync(data).get();
            if (signing.Status() != KeyCredentialStatus::Success) {
                error = statusCode(signing.Status());
            } else {
                signature = CryptographicBuffer::EncodeToBase64String(signing.Result());
            }
        }
    } catch (...) {
        error = "failed";
    }
    helloWaiting = false;
    raise.join();
    if (error) { failed(error); return; }
    std::cout << "{\"ok\":true,\"signature\":\"" << to_string(signature) << "\"}\n";
}

// ── The account password ─────────────────────────────────────────────────────────────────────────────────────

enum class Outcome { verified, cancelled, denied, unavailable, failed };

// A wrong password asks again in the same dialog, with Windows' own message, before the unlock counts as denied.
static constexpr int passwordAttempts = 3;

// The SID of the account a token belongs to; empty when it can't be read.
static std::vector<BYTE> userOf(HANDLE token) {
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    if (size == 0) return {};
    std::vector<BYTE> information(size);
    if (!GetTokenInformation(token, TokenUser, information.data(), size, &size)) return {};
    const PSID sid = reinterpret_cast<const TOKEN_USER*>(information.data())->User.Sid;
    if (!IsValidSid(sid)) return {};
    const auto* bytes = static_cast<const BYTE*>(sid);
    return std::vector<BYTE>(bytes, bytes + GetLengthSid(sid));
}

// The signed-in account this helper, like GateRook, runs as.
static std::vector<BYTE> signedInUser() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return {};
    auto user = userOf(token);
    CloseHandle(token);
    return user;
}

// The SID an account name resolves to; empty when it doesn't resolve.
static std::vector<BYTE> userNamed(const std::wstring& name) {
    DWORD sidSize = 0;
    DWORD domainSize = 0;
    SID_NAME_USE use{};
    LookupAccountNameW(nullptr, name.c_str(), nullptr, &sidSize, nullptr, &domainSize, &use);
    if (sidSize == 0) return {};
    std::vector<BYTE> sid(sidSize);
    std::wstring domain(static_cast<std::size_t>(domainSize) + 1, L'\0');
    DWORD domainLength = static_cast<DWORD>(domain.size());
    if (!LookupAccountNameW(nullptr, name.c_str(), sid.data(), &sidSize, domain.data(), &domainLength, &use)
        || !IsValidSid(sid.data())) {
        return {};
    }
    sid.resize(GetLengthSid(sid.data()));
    return sid;
}

static bool sameUser(const std::vector<BYTE>& entered, const std::vector<BYTE>& signedIn) {
    return !entered.empty() && entered.size() == signedIn.size()
        && EqualSid(const_cast<BYTE*>(entered.data()), const_cast<BYTE*>(signedIn.data()));
}

// Errors Windows reports only once the password matched: the account just can't start another logon now.
static bool matchedButRefused(DWORD error, bool blank) {
    switch (error) {
    case ERROR_PASSWORD_EXPIRED:
    case ERROR_PASSWORD_MUST_CHANGE:
    case ERROR_LOGON_TYPE_NOT_GRANTED:
        return true;
    case ERROR_ACCOUNT_RESTRICTION:
        // An account without a password: by default Windows refuses a blank one away from the console.
        return blank;
    default:
        return false;
    }
}

// The system credential dialog for the signed-in account, then LogonUser on what was entered. Only that account's
// password unlocks: another account's, even a valid one, is refused like a wrong password. The password lives only
// in this function's buffers, which are cleared before they go out of scope.
static Outcome checkPassword(HWND window) {
    const auto account = signedInUser();
    if (account.empty()) return Outcome::unavailable;
    CREDUI_INFOW info{};
    info.cbSize = sizeof(info);
    info.hwndParent = window;
    info.pszCaptionText = L"GateRook";
    info.pszMessageText = L"Enter your Windows password to unlock your saved GateRook credentials.";
    DWORD previous = ERROR_SUCCESS;
    for (int attempt = 0; attempt < passwordAttempts; ++attempt) {
        ULONG package = 0;
        LPVOID packed = nullptr;
        ULONG packedSize = 0;
        BOOL save = FALSE;
        const DWORD prompted = CredUIPromptForWindowsCredentialsW(&info, previous, &package, nullptr, 0, &packed,
            &packedSize, &save, CREDUIWIN_ENUMERATE_CURRENT_USER);
        if (prompted == ERROR_CANCELLED) return Outcome::cancelled;
        if (prompted != ERROR_SUCCESS || packed == nullptr) return Outcome::unavailable;
        wchar_t user[CREDUI_MAX_USERNAME_LENGTH + 1]{};
        wchar_t domain[CREDUI_MAX_DOMAIN_TARGET_LENGTH + 1]{};
        wchar_t password[CREDUI_MAX_PASSWORD_LENGTH + 1]{};
        DWORD userLength = CREDUI_MAX_USERNAME_LENGTH + 1;
        DWORD domainLength = CREDUI_MAX_DOMAIN_TARGET_LENGTH + 1;
        DWORD passwordLength = CREDUI_MAX_PASSWORD_LENGTH + 1;
        const BOOL unpacked = CredUnPackAuthenticationBufferW(CRED_PACK_PROTECTED_CREDENTIALS, packed, packedSize,
            user, &userLength, domain, &domainLength, password, &passwordLength);
        SecureZeroMemory(packed, packedSize);
        CoTaskMemFree(packed);
        if (!unpacked) {
            // A smart card or another credential without a password: nothing this check can verify.
            SecureZeroMemory(password, sizeof(password));
            return Outcome::unavailable;
        }
        // CredUI may return DOMAIN\user with no separate domain. LogonUser takes a UPN without one, and "." for
        // a bare local account name.
        std::wstring name = user;
        std::wstring realm = domain;
        if (realm.empty()) {
            const auto slash = name.find(L'\\');
            if (slash != std::wstring::npos) {
                realm = name.substr(0, slash);
                name = name.substr(slash + 1);
            } else if (name.find(L'@') == std::wstring::npos) {
                realm = L".";
            }
        }
        HANDLE token = nullptr;
        const BOOL loggedOn = LogonUserW(name.c_str(), realm.empty() ? nullptr : realm.c_str(), password,
            LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT, &token);
        const DWORD error = loggedOn ? ERROR_SUCCESS : GetLastError();
        const bool blank = password[0] == L'\0';
        SecureZeroMemory(password, sizeof(password));
        if (loggedOn) {
            const bool same = sameUser(userOf(token), account);
            CloseHandle(token);
            if (same) return Outcome::verified;
            previous = ERROR_LOGON_FAILURE;
            continue;
        }
        if (error == ERROR_LOGON_FAILURE) {
            previous = error;
            continue;
        }
        if (matchedButRefused(error, blank)) {
            const std::wstring qualified = realm.empty() || realm == L"." ? name : realm + L"\\" + name;
            return sameUser(userNamed(qualified), account) ? Outcome::verified : Outcome::denied;
        }
        // A locked or disabled account, or a policy that refuses the check: not a password to try again.
        return Outcome::denied;
    }
    return Outcome::denied;
}

// CredUI runs its dialog from a single-threaded apartment, apart from main's multithreaded WinRT calls.
static Outcome checkPasswordInDialogThread(HWND window) {
    Outcome outcome = Outcome::failed;
    std::thread dialog([&outcome, window] {
        if (FAILED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED))) {
            outcome = Outcome::unavailable;
            return;
        }
        try {
            outcome = checkPassword(window);
        } catch (...) {
            outcome = Outcome::failed;
        }
        CoUninitialize();
    });
    dialog.join();
    return outcome;
}

int main() {
    bool checking = false;
    try {
        init_apartment(apartment_type::multi_threaded);
        std::string input;
        char byte;
        while (std::cin.get(byte)) {
            input.push_back(byte);
            if (input.size() > 4096) { failed("invalid-request"); return 0; }
        }
        JsonObject request;
        if (!JsonObject::TryParse(to_hstring(input), request)) { failed("invalid-request"); return 0; }
        const auto operation = request.GetNamedString(L"operation", L"");
        checking = operation == L"check";
        if (operation == L"check") {
            // Checking the desktop interop as well as enrollment avoids advertising a UWP-only path.
            const auto interop = get_activation_factory<UserConsentVerifier, IUserConsentVerifierInterop>();
            const auto availability = UserConsentVerifier::CheckAvailabilityAsync().get();
            switch (availability) {
            case UserConsentVerifierAvailability::Available:
                reply("{\"ok\":true,\"available\":true,\"touchId\":false}"); break;
            default:
                // Not set up, off by policy, a busy device or an unknown result: main asks for the account
                // password instead, so no answer here leaves the key without a check.
                reply("{\"ok\":true,\"available\":false,\"touchId\":false}"); break;
            }
            return 0;
        }
        if (operation == L"hello-key-create" || operation == L"hello-key-sign") {
            helloSign(request, operation == L"hello-key-create");
            return 0;
        }
        if (operation == L"hello-key-delete") {
            const hstring account = request.GetNamedString(L"account", L"");
            if (!validAccount(account)) { failed("invalid-request"); return 0; }
            try {
                KeyCredentialManager::DeleteAsync(account).get();
            } catch (const hresult_error&) {
                // Gone already: nothing to delete.
            }
            reply("{\"ok\":true}");
            return 0;
        }
        if (operation == L"verify-password") {
            const HWND window = ownerWindow(request);
            if (!window) { failed("invalid-request"); return 0; }
            switch (checkPasswordInDialogThread(window)) {
            case Outcome::verified: reply("{\"ok\":true}"); break;
            case Outcome::cancelled: failed("cancelled"); break;
            case Outcome::denied: failed("denied"); break;
            case Outcome::unavailable: failed("unavailable"); break;
            default: failed("failed"); break;
            }
            return 0;
        }
        if (operation != L"verify") { failed("invalid-request"); return 0; }
        const HWND window = ownerWindow(request);
        if (!window) { failed("invalid-request"); return 0; }
        const auto interop = get_activation_factory<UserConsentVerifier, IUserConsentVerifierInterop>();
        const hstring message = L"Unlock your saved GateRook credentials";
        IAsyncOperation<UserConsentVerificationResult> verification{nullptr};
        check_hresult(interop->RequestVerificationForWindowAsync(window,
            static_cast<HSTRING>(get_abi(message)), guid_of<decltype(verification)>(), put_abi(verification)));
        switch (verification.get()) {
        case UserConsentVerificationResult::Verified: reply("{\"ok\":true}"); break;
        case UserConsentVerificationResult::Canceled: failed("cancelled"); break;
        case UserConsentVerificationResult::DeviceNotPresent:
        case UserConsentVerificationResult::NotConfiguredForUser:
        case UserConsentVerificationResult::DisabledByPolicy: failed("unavailable"); break;
        default: failed("denied"); break;
        }
    } catch (const hresult_error&) {
        // Do not expose native exception descriptions to logs or the renderer. Hello that can't be reached, its
        // interop missing included, leaves the account password: a failed check says so instead of stopping.
        if (checking) {
            reply("{\"ok\":true,\"available\":false,\"touchId\":false}");
        } else {
            failed("unavailable");
        }
    } catch (...) {
        failed("failed");
    }
    return 0;
}
