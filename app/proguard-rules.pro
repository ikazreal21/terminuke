# SSHJ reflects over cryptographic providers and key material types.
-keep class net.schmizz.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Optional SSHJ Kerberos/GSSAPI auth is not used by terminuke and Android has no JGSS APIs.
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**

# EdDSA includes a JVM-only compatibility check for this Sun implementation; Android uses BC.
-dontwarn sun.security.x509.X509Key
