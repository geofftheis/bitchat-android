package com.bitchat.android.protocol

import java.security.SecureRandom

/**
 * Privacy-preserving padding utilities - exact same as iOS version
 * Provides traffic analysis resistance by normalizing message sizes
 */
object MessagePadding {
    // Standard block sizes for padding - exact same as iOS
    private val blockSizes = listOf(256, 512, 1024, 2048)
    
    /**
     * Find optimal block size for data - exact same logic as iOS
     */
    fun optimalBlockSize(dataSize: Int): Int {
        // Account for encryption overhead (~16 bytes for AES-GCM tag)
        val totalSize = dataSize + 16
        
        // Find smallest block that fits
        for (blockSize in blockSizes) {
            if (totalSize <= blockSize) {
                return blockSize
            }
        }
        
        // For very large messages, just use the original size
        // (will be fragmented anyway)
        return dataSize
    }
    
    /**
     * Add PKCS#7-style padding to reach target size - FIXED: proper PKCS#7 (iOS compatible)
     */
    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        // Half-Wit Patch 7: Disable message padding for all packet types.
        // Padding obscures message length for traffic analysis resistance,
        // which is unnecessary for a local 8-player game.
        return data
    }
    
    /**
     * Remove padding from data - FIXED: strict PKCS#7 validation (iOS compatible)
     */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        
        val last = data[data.size - 1]
        val paddingLength = last.toInt() and 0xFF
        
        // Must have at least 1 pad byte and not exceed data length
        if (paddingLength <= 0 || paddingLength > data.size) return data
        
        // Verify PKCS#7: all last N bytes equal to pad length (iOS fix)
        val start = data.size - paddingLength
        for (i in start until data.size) {
            if (data[i] != last) {
                return data // Invalid padding, return original
            }
        }
        
        return data.copyOfRange(0, start)
    }
}
