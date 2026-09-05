package com.skywell.skydash

import android.content.Context
import android.util.Log
import com.skywell.skydash.adb.AdbKeyPair
import com.skywell.skydash.adb.AdbProtocol
import com.skywell.skydash.adb.AdbProtocol.A_CNXN
import com.skywell.skydash.adb.AdbProtocol.A_AUTH
import com.skywell.skydash.adb.AdbProtocol.A_OPEN
import com.skywell.skydash.adb.AdbProtocol.A_OKAY
import com.skywell.skydash.adb.AdbProtocol.A_WRTE
import com.skywell.skydash.adb.AdbProtocol.A_CLSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class LocalAdbClient(private val context: Context) {
    private val TAG = "LocalAdbClient"
    private val HOST = "127.0.0.1"
    private val PORT = 5555

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var lastRemoteId = 0
    internal var isConnected = false

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Don't reconnect if already connected
        if (isConnected && socket?.isConnected == true) {
            return@withContext true
        }

        // Clean up any stale connection
        disconnect()

        try {
            socket = Socket(HOST, PORT)
            socket?.soTimeout = 30000 // 30s timeout — user needs time to approve RSA dialog
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            // 1. Send CNXN
            val cnxnData = "host::\u0000".toByteArray(Charsets.UTF_8)
            val cnxnMsg = AdbProtocol.AdbMessage(A_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.MAX_PAYLOAD, cnxnData)
            writeMessage(cnxnMsg)

            // 2. Read response (CNXN or AUTH)
            var header = readExactly(24)
            var msg = AdbProtocol.parseHeader(header)
            
            // Read payload if present
            var payload: ByteArray? = null
            if (msg.dataLength > 0) {
                payload = readExactly(msg.dataLength)
            }

            val keyPair = AdbKeyPair.getOrGenerate(context)

            if (msg.command == A_AUTH) {
                // A_AUTH arg0=1 means Token — payload contains the 20-byte token to sign
                val token = payload ?: throw IllegalStateException("AUTH token payload is null")

                // Try RSA Signature authentication (arg0 = 2)
                val sig = keyPair.signToken(token)
                val authSigMsg = AdbProtocol.AdbMessage(A_AUTH, 2, 0, sig)
                writeMessage(authSigMsg)

                // Read response to signature
                header = readExactly(24)
                msg = AdbProtocol.parseHeader(header)
                if (msg.dataLength > 0) {
                    readExactly(msg.dataLength) // Discard payload
                }

                if (msg.command == A_AUTH) {
                    // Signature was rejected or new device, send public key (arg0 = 3)
                    val pubKeyStr = keyPair.getPublicKeyBase64().toByteArray(Charsets.UTF_8)
                    val authKeyMsg = AdbProtocol.AdbMessage(A_AUTH, 3, 0, pubKeyStr)
                    writeMessage(authKeyMsg)

                    // Read response to public key
                    header = readExactly(24)
                    msg = AdbProtocol.parseHeader(header)
                    if (msg.dataLength > 0) {
                        readExactly(msg.dataLength)
                    }
                }
            }

            // Bağlantı onaylandıktan sonra soket üzerinde unutulmuş
            // (banner gibi) baytlar varsa hepsini temizle.
            inputStream?.let { stream ->
                while (stream.available() > 0) {
                    stream.read()
                }
            }

            if (msg.command == A_CNXN) {
                Log.i(TAG, "ADB handshake successful!")
                isConnected = true
                true
            } else {
                Log.e(TAG, "ADB handshake failed: unexpected response command: ${msg.command}")
                disconnect()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            disconnect()
            false
        }
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        if (!isConnected || socket?.isConnected != true) {
            Log.w(TAG, "Not connected. Reconnecting...")
            if (!connect()) {
                return@withContext "HATA: ADB Baglantisi kurulamadi"
            }
        }

        val localId = 1
        val remoteCmd = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        val openMsg = AdbProtocol.AdbMessage(A_OPEN, localId, 0, remoteCmd)

        try {
            writeMessage(openMsg)

            // Wait for OKAY response
            var header = readExactly(24)
            var msg = AdbProtocol.parseHeader(header)
            if (msg.dataLength > 0) {
                readExactly(msg.dataLength)
            }

            if (msg.command != A_OKAY) {
                return@withContext "HATA: Kanal acilamadi (expected OKAY, got ${msg.command})"
            }

            lastRemoteId = msg.arg0 // Save remote channel ID
            val output = StringBuilder()

            // Read output loop
            while (true) {
                header = readExactly(24)
                msg = AdbProtocol.parseHeader(header)
                
                var payload: ByteArray? = null
                if (msg.dataLength > 0) {
                    payload = readExactly(msg.dataLength)
                }

                if (msg.command == A_WRTE) {
                    if (payload != null) {
                        output.append(String(payload, Charsets.UTF_8))
                    }
                    // Acknowledge WRTE with OKAY
                    val okayMsg = AdbProtocol.AdbMessage(A_OKAY, localId, lastRemoteId, null)
                    writeMessage(okayMsg)
                } else if (msg.command == A_CLSE) {
                    // Close the channel
                    val clseMsg = AdbProtocol.AdbMessage(A_CLSE, localId, lastRemoteId, null)
                    writeMessage(clseMsg)
                    break
                } else {
                    Log.w(TAG, "Unexpected message while reading command output: ${msg.command}")
                }
            }

            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error: ${e.message}", e)
            isConnected = false // Reset connection state on socket error
            "HATA: ${e.message}"
        }
    }

    private fun writeMessage(msg: AdbProtocol.AdbMessage) {
        val raw = AdbProtocol.serializeMessage(msg)
        outputStream?.write(raw)
        outputStream?.flush()
    }

    private fun readExactly(size: Int): ByteArray {
        val stream = inputStream ?: throw IllegalStateException("Inputstream is null")
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(buffer, offset, size - offset)
            if (read == -1) {
                throw EOFException("ADB connection closed prematurely")
            }
            offset += read
        }
        return buffer
    }

    fun disconnect() {
        try {
            isConnected = false
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ADB client: ${e.message}")
        }
    }
}
