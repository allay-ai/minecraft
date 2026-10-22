package chat.allay.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.logging.Logger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A file in the plugin folder that holds something about players: AES-256-GCM under a key derived with HMAC-SHA256
 * from the server's license or `server.token` and [label], owner-only, written atomically. Without the key it's
 * noise, so a copied folder or a file API that serves the plugin folder learns nothing.
 */
class SealedFile(private val file: File, private val secret: () -> String?, private val label: String, private val log: Logger) {
  /** Whether there's a key to seal with: none before the server has a license or a token. */
  val usable get() = key() != null

  /** False when there's no key yet. */
  fun write(text: String): Boolean {
    val key = key() ?: return false
    val iv = ByteArray(IV).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG, iv)) }
    val sealed = iv + cipher.doFinal(text.toByteArray())
    val folder = file.absoluteFile.parentFile.apply { mkdirs() }
    val temp = File(folder, "${file.name}.tmp")
    temp.delete()
    temp.createNewFile()
    ownerOnly(temp)
    temp.writeBytes(sealed)
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return true
  }

  /** The text, or null when there's no file or no key; a file the key no longer opens is dropped with a line in the log. */
  fun read(): String? {
    if (!file.isFile) return null
    val key = key() ?: return null
    return try {
      val bytes = file.readBytes()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG, bytes, 0, IV)) }
      cipher.doFinal(bytes, IV, bytes.size - IV).decodeToString()
    } catch (_: Exception) {
      log.warning("Couldn't open ${file.name} (the license or server.token changed since it was written); dropping it")
      file.delete()
      null
    }
  }

  fun delete() {
    file.delete()
  }

  private fun key(): SecretKeySpec? {
    val secret = secret()?.takeIf { it.isNotBlank() } ?: return null
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.trim().toByteArray(), "HmacSHA256")) }
    return SecretKeySpec(mac.doFinal(label.toByteArray()), "AES")
  }

  private companion object {
    const val IV = 12
    const val TAG = 128
  }
}
