package app.folio.core.editing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.util.UUID

/**
 * PDFs that open without a password but carry an owner password and permissions (common for
 * statements, invoices and forms) can't be saved by PDFBox unless their protection is set up
 * again ("PDF contains an encryption dictionary…"). This re-applies equivalent protection: same
 * permissions, still openable without a password. The original owner password isn't known, so a
 * random one is used; nobody gains rights the original didn't grant.
 */
object Encryption {

    fun keepProtectionForSave(doc: PDDocument) {
        if (!doc.isEncrypted || doc.isAllSecurityToBeRemoved) return
        val permissions = AccessPermission(doc.currentAccessPermission.permissionBytes)
        val keyLength = doc.encryption?.length?.takeIf { it == 40 || it == 128 || it == 256 } ?: 128
        val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), "", permissions)
        policy.encryptionKeyLength = keyLength
        doc.protect(policy)
    }
}
