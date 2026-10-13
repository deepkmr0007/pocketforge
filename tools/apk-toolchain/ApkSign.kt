// Minimal `apksigner`-compatible CLI on top of Google's apksig library (shipped inside bundletool).
// Used by tools/build-apk.sh and the lite-build E2E where the Android SDK's own apksigner is not
// installable. Supports exactly the subset PocketForge uses:
//   sign   --ks FILE [--ks-type PKCS12|JKS] --ks-pass pass:X|env:VAR|file:F [--key-pass ...] [--ks-key-alias A] --out OUT IN
//   verify [--print-certs] [-v] APK
package dev.pocketforge.tools

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.system.exitProcess

private fun die(msg: String): Nothing { System.err.println("apksign: $msg"); exitProcess(2) }

private fun password(spec: String): CharArray = when {
    spec.startsWith("pass:") -> spec.removePrefix("pass:").toCharArray()
    spec.startsWith("env:") -> (System.getenv(spec.removePrefix("env:")) ?: die("environment variable ${spec.removePrefix("env:")} is not set")).toCharArray()
    spec.startsWith("file:") -> File(spec.removePrefix("file:")).readText().trimEnd('\n', '\r').toCharArray()
    else -> die("password must be pass:..., env:... or file:...")
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    if (args.isEmpty()) die("usage: sign|verify ...")
    val opts = HashMap<String, String>()
    val flags = HashSet<String>()
    val positional = ArrayList<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        when {
            a in setOf("--print-certs", "-v", "--verbose") -> flags += a
            a.startsWith("--") -> { opts[a] = args.getOrNull(i + 1) ?: die("missing value for $a"); i++ }
            else -> positional += a
        }
        i++
    }
    when (args[0]) {
        "sign" -> sign(opts, positional)
        "verify" -> verify(flags, positional)
        else -> die("unknown command ${args[0]}")
    }
}

private fun sign(o: Map<String, String>, pos: List<String>) {
    val input = File(pos.singleOrNull() ?: die("exactly one input APK expected"))
    val out = File(o["--out"] ?: die("--out is required"))
    val ksFile = File(o["--ks"] ?: die("--ks is required"))
    val storePass = password(o["--ks-pass"] ?: die("--ks-pass is required"))
    val keyPass = o["--key-pass"]?.let(::password) ?: storePass
    val types = o["--ks-type"]?.let { listOf(it) } ?: listOf("PKCS12", "JKS")
    val ks = types.firstNotNullOfOrNull { t ->
        runCatching { KeyStore.getInstance(t).apply { ksFile.inputStream().use { load(it, storePass) } } }.getOrNull()
    } ?: die("cannot open keystore ${ksFile.name} (wrong password or type)")
    val alias = o["--ks-key-alias"] ?: ks.aliases().toList().singleOrNull { ks.isKeyEntry(it) } ?: die("keystore has several keys; pass --ks-key-alias")
    val key = ks.getKey(alias, keyPass) as? PrivateKey ?: die("alias $alias has no private key")
    val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate } ?: die("alias $alias has no certificate")
    val signer = ApkSigner.SignerConfig.Builder("CERT", key, chain).build()
    ApkSigner.Builder(listOf(signer))
        .setInputApk(input)
        .setOutputApk(out)
        .setV1SigningEnabled(true)
        .setV2SigningEnabled(true)
        .setV3SigningEnabled(true)
        .build()
        .sign()
    storePass.fill('\u0000'); keyPass.fill('\u0000')
}

private fun verify(flags: Set<String>, pos: List<String>) {
    val apk = File(pos.singleOrNull() ?: die("exactly one APK expected"))
    val r = ApkVerifier.Builder(apk).build().verify()
    if (!r.isVerified) {
        r.errors.forEach { System.err.println("ERROR: $it") }
        r.v2SchemeSigners.flatMap { it.errors }.forEach { System.err.println("ERROR (v2): $it") }
        r.v3SchemeSigners.flatMap { it.errors }.forEach { System.err.println("ERROR (v3): $it") }
        die("DOES NOT VERIFY")
    }
    if ("-v" in flags || "--verbose" in flags) {
        println("Verifies")
        println("Verified using v1 scheme (JAR signing): ${r.isVerifiedUsingV1Scheme}")
        println("Verified using v2 scheme (APK Signature Scheme v2): ${r.isVerifiedUsingV2Scheme}")
        println("Verified using v3 scheme (APK Signature Scheme v3): ${r.isVerifiedUsingV3Scheme}")
        println("Number of signers: ${r.signerCertificates.size}")
    }
    if ("--print-certs" in flags) r.signerCertificates.forEachIndexed { n, c ->
        println("Signer #${n + 1} certificate DN: ${c.subjectX500Principal.name}")
        println("Signer #${n + 1} certificate SHA-256 digest: ${hex(MessageDigest.getInstance("SHA-256").digest(c.encoded))}")
    }
}
