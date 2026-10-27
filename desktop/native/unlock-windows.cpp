// Windows Hello is an application verification gate. DPAPI remains the storage boundary,
// matching Electron safeStorage; successful Hello verification does not create a TPM-bound key.
#include <windows.h>
#include <UserConsentVerifierInterop.h>
#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Security.Credentials.UI.h>
#include <charconv>
#include <cstdint>
#include <iostream>
#include <string>

using namespace winrt;
using namespace Windows::Data::Json;
using namespace Windows::Foundation;
using namespace Windows::Security::Credentials::UI;

static void reply(const char* result) {
    std::cout << result << '\n';
}

static void failed(const char* error) {
    std::cout << "{\"ok\":false,\"error\":\"" << error << "\"}\n";
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
            case UserConsentVerifierAvailability::DeviceNotPresent:
            case UserConsentVerifierAvailability::NotConfiguredForUser:
            case UserConsentVerifierAvailability::DisabledByPolicy:
                reply("{\"ok\":true,\"available\":false,\"touchId\":false}"); break;
            default:
                // A busy device or unknown result must not silently disable configured authentication.
                failed("unavailable"); break;
            }
            return 0;
        }
        if (operation != L"verify") { failed("invalid-request"); return 0; }
        const std::string handle = to_string(request.GetNamedString(L"windowHandle", L""));
        std::uintptr_t number = 0;
        const auto parsed = std::from_chars(handle.data(), handle.data() + handle.size(), number);
        if (handle.empty() || parsed.ec != std::errc{} || parsed.ptr != handle.data() + handle.size() || number == 0) {
            failed("invalid-request"); return 0;
        }
        const auto window = reinterpret_cast<HWND>(number);
        if (!IsWindow(window)) { failed("invalid-request"); return 0; }
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
    } catch (const hresult_error& error) {
        // Do not expose native exception descriptions to logs or the renderer.
        if (checking && (error.code() == E_NOINTERFACE || error.code() == REGDB_E_CLASSNOTREG)) {
            reply("{\"ok\":true,\"available\":false,\"touchId\":false}");
        } else {
            failed("unavailable");
        }
    } catch (...) {
        failed("failed");
    }
    return 0;
}
