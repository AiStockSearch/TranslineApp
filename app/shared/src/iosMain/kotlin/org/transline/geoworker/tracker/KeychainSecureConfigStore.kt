package org.transline.geoworker.tracker

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
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
 *
 * Builds queries via [CFDictionaryCreateMutable] — never cast Kotlin Map to CFDictionaryRef
 * (that ClassCastException aborts the ObjC RN bridge via undeclared Kotlin exception).
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureConfigStore : SecureConfigStore {

    override fun load(): SecureConfig? {
        return try {
            val raw = readKeychain() ?: return null
            SecureConfigJson.decode(raw)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(config: SecureConfig) {
        val encoded = SecureConfigJson.encode(config)
        val data = (encoded as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val base = newBaseQuery()
        try {
            val attrs = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)
            CFDictionarySetValue(attrs, kSecValueData, CFBridgingRetain(data))
            val updateStatus = SecItemUpdate(base, attrs)
            CFRelease(attrs)
            if (updateStatus == errSecSuccess) return
            if (updateStatus == errSecItemNotFound) {
                val add = newAddQuery(data)
                SecItemAdd(add, null)
                CFRelease(add)
            } else {
                SecItemDelete(base)
                val add = newAddQuery(data)
                SecItemAdd(add, null)
                CFRelease(add)
            }
        } finally {
            CFRelease(base)
        }
    }

    override fun clear() {
        val base = newBaseQuery()
        try {
            SecItemDelete(base)
        } finally {
            CFRelease(base)
        }
    }

    private fun newBaseQuery(): CFDictionaryRef {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null)!!
        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dict, kSecAttrService, CFBridgingRetain(SERVICE))
        CFDictionarySetValue(dict, kSecAttrAccount, CFBridgingRetain(ACCOUNT))
        return dict
    }

    private fun newAddQuery(data: NSData): CFDictionaryRef {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)!!
        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dict, kSecAttrService, CFBridgingRetain(SERVICE))
        CFDictionarySetValue(dict, kSecAttrAccount, CFBridgingRetain(ACCOUNT))
        CFDictionarySetValue(dict, kSecValueData, CFBridgingRetain(data))
        CFDictionarySetValue(dict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        return dict
    }

    private fun readKeychain(): String? = memScoped {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)!!
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(SERVICE))
            CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(ACCOUNT))
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess) return null
            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        } finally {
            CFRelease(query as CFTypeRef?)
        }
    }

    companion object {
        const val SERVICE = "org.transline.geoworker.secure"
        const val ACCOUNT = "secure_config"
    }
}
