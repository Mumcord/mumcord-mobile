// SPDX-License-Identifier: AGPL-3.0-only

//! Android start-up hook for certificate checking against the system store (see the crate docs).

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::JObject;

/// `app.mumcord.mobile.platform.securestore.SystemCertificates.init(context)`: hands the app
/// context to `rustls-platform-verifier`. Calling it again is harmless. A failure is thrown as a
/// `RuntimeException`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mumcord_mobile_platform_securestore_SystemCertificates_init<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _this: JObject<'caller>,
    context: JObject<'caller>,
) {
    env.with_env(|env| rustls_platform_verifier::android::init_with_env(env, context))
        .resolve::<ThrowRuntimeExAndDefault>()
}
