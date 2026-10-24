# durio: only for -Pdurio.minifiedTests (plan AN-34). The instrumented tests call our E2EE
# code directly, which R8 would otherwise inline or drop; JNA and UniFFI are kept by the rules
# inside the durio-crypt AAR alone, as in the published APK.
-keep class chat.stoat.api.fork.** { *; }
-keep class chat.stoat.core.crypto.** { *; }
-keep class chat.stoat.fork.e2ee.** { *; }
-keep class chat.stoat.fork.keystore.** { *; }
-keep class chat.stoat.api.internals.ULID { *; }
-keep class chat.stoat.core.model.schemas.Message { *; }
