package org.transline.geoworker.tracker

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS [SecureConfigStore] using Keychain SecItem (generic password).
 * Accessibility: AfterFirstUnlockThisDeviceOnly for later background reads.
 * Save uses update-or-add.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureConfigStore : SecureConfigStore {

    override fun load(): SecureConfig? {
        val raw = readKeychain() ?: return null
        return SecureConfigJson.decode(raw)
    }

    override fun save(config: SecureConfig) {
        val encoded = SecureConfigJson.encode(config)
        val data = (encoded as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val updateStatus = SecItemUpdate(baseQuery(), attributesDictionary(data))
        if (updateStatus == errSecSuccess) return
        if (updateStatus == errSecItemNotFound) {
            SecItemAdd(addQuery(data), null)
        } else {
            // Corrupt / duplicate — replace
            SecItemDelete(baseQuery())
            SecItemAdd(addQuery(data), null)
        }
    }

    override fun clear() {
        SecItemDelete(baseQuery())
    }

    private fun baseQuery(): CFDictionaryRef =
        mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
        ) as CFDictionaryRef

    private fun attributesDictionary(data: NSData): CFDictionaryRef =
        mapOf(kSecValueData to data) as CFDictionaryRef

    private fun addQuery(data: NSData): CFDictionaryRef =
        mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ) as CFDictionaryRef

    private fun readKeychain(): String? = memScoped {
        val query = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne,
        ) as CFDictionaryRef
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        if (status != errSecSuccess) return null
        val data = result.value as? NSData ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    companion object {
        const val SERVICE = "org.transline.geoworker.secure"
        const val ACCOUNT = "secure_config"
    }
}
