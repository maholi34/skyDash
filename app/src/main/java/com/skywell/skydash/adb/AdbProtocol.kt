package com.skywell.skydash.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    const val CONNECT_VERSION = 0x01000000
    const val MAX_PAYLOAD = 4096

    class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray? = null
    ) {
        val dataLength: Int = data?.size ?: 0
        val dataChecksum: Int = calculateChecksum(data)
        val magic: Int = command xor -0x1

        companion object {
            private fun calculateChecksum(data: ByteArray?): Int {
                if (data == null) return 0
                var sum = 0
                for (b in data) {
                    sum += (b.toInt() and 0xFF)
                }
                return sum
            }
        }
    }

    fun parseHeader(headerBytes: ByteArray): AdbMessage {
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val dataChecksum = buffer.int
        val magic = buffer.int

        if (command xor -0x1 != magic) {
            throw IllegalArgumentException("Invalid ADB message magic check!")
        }

        // We return message with empty data first, payload will be read separately
        return AdbMessage(command, arg0, arg1, null)
    }

    fun serializeMessage(msg: AdbMessage): ByteArray {
        val headerSize = 24
        val payloadSize = msg.dataLength
        val buffer = ByteBuffer.allocate(headerSize + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putInt(msg.command)
        buffer.putInt(msg.arg0)
        buffer.putInt(msg.arg1)
        buffer.putInt(msg.dataLength)
        buffer.putInt(msg.dataChecksum)
        buffer.putInt(msg.magic)
        
        if (msg.data != null) {
            buffer.put(msg.data)
        }
        
        return buffer.array()
    }
}
