# BouncyCastle lightweight API: we reference classes directly, so R8 keeps what we use.
# These providers are reflective entry points we never register, so they can go.
-dontwarn org.bouncycastle.jsse.**
-dontwarn javax.naming.**
