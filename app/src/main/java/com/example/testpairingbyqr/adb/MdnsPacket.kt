package com.example.testpairingbyqr.adb

import java.io.ByteArrayOutputStream

/** Tipos de registro DNS que hacen falta para resolver un servicio. */
const val TYPE_A = 1
const val TYPE_PTR = 12
const val TYPE_TXT = 16
const val TYPE_SRV = 33

/** Un registro SRV ya interpretado: a qué host y puerto apunta una instancia. */
data class SrvRecord(val instance: String, val target: String, val port: Int, val ttlSeconds: Long)

/**
 * Codificador/decodificador mínimo de mensajes DNS, lo justo para preguntar por un tipo de
 * servicio y leer PTR/SRV/A de las respuestas (incluida la compresión de nombres por punteros).
 */
object MdnsPacket {

    const val MULTICAST_ADDRESS = "224.0.0.251"
    const val PORT = 5353

    /**
     * Consulta PTR por un tipo de servicio.
     *
     * @param unicastResponse activa el bit QU, para que respondan a nuestro puerto efímero en
     *   vez de al 5353 multicast. Necesario cuando no se ha podido tomar el puerto 5353.
     */
    fun query(name: String, unicastResponse: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        u16(0)          // id: en mDNS se ignora
        u16(0)          // flags: consulta estándar
        u16(1)          // qdcount
        u16(0)          // ancount
        u16(0)          // nscount
        u16(0)          // arcount
        writeName(out, name)
        u16(TYPE_PTR)
        u16(if (unicastResponse) 0x8001 else 0x0001) // QU|IN o IN
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
    }

    /** Lo que nos interesa de una respuesta, ya agrupado. */
    data class Response(
        /** Nombres de instancia anunciados por PTR, con su TTL (0 = adiós). */
        val pointers: Map<String, Long>,
        val services: Map<String, SrvRecord>,
        /** host -> direcciones IPv4. */
        val addresses: Map<String, List<String>>,
    )

    /** @return null si el paquete no es una respuesta DNS legible. */
    fun parse(data: ByteArray, length: Int): Response? {
        val r = Reader(data, length)
        return try {
            r.u16() // id
            val flags = r.u16()
            if (flags and 0x8000 == 0) return null // no es respuesta
            val questions = r.u16()
            val records = r.u16() + r.u16() + r.u16() // answer + authority + additional
            repeat(questions) {
                r.name()
                r.u16()
                r.u16()
            }

            val pointers = HashMap<String, Long>()
            val services = HashMap<String, SrvRecord>()
            val addresses = HashMap<String, MutableList<String>>()

            repeat(records) {
                val name = r.name()
                val type = r.u16()
                r.u16() // clase (+ bit cache-flush)
                val ttl = r.u32()
                val rdLength = r.u16()
                val end = r.position + rdLength
                when (type) {
                    TYPE_PTR -> pointers[r.name()] = ttl
                    TYPE_SRV -> {
                        r.u16() // prioridad
                        r.u16() // peso
                        val port = r.u16()
                        val target = r.name()
                        services[name] = SrvRecord(name, target, port, ttl)
                    }
                    TYPE_A -> if (rdLength == 4) {
                        val ip = "${r.u8()}.${r.u8()}.${r.u8()}.${r.u8()}"
                        addresses.getOrPut(name) { mutableListOf() }.add(ip)
                    }
                }
                // Saltar al siguiente registro aunque no lo hayamos interpretado entero.
                r.position = end
            }
            Response(pointers, services, addresses)
        } catch (e: IndexOutOfBoundsException) {
            null // Paquete truncado o malformado: se ignora.
        }
    }

    /** Lector con soporte de punteros de compresión (RFC 1035 §4.1.4). */
    private class Reader(private val buf: ByteArray, private val length: Int) {
        var position = 0

        fun u8(): Int {
            if (position >= length) throw IndexOutOfBoundsException()
            return buf[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun name(): String {
            val sb = StringBuilder()
            var p = position
            var jumped = false
            var hops = 0
            while (true) {
                if (p >= length || hops++ > MAX_HOPS) throw IndexOutOfBoundsException()
                val len = buf[p].toInt() and 0xFF
                if (len == 0) {
                    if (!jumped) position = p + 1
                    break
                }
                if (len and 0xC0 == 0xC0) {
                    val pointer = ((len and 0x3F) shl 8) or (buf[p + 1].toInt() and 0xFF)
                    if (!jumped) {
                        position = p + 2
                        jumped = true
                    }
                    p = pointer
                    continue
                }
                p++
                if (p + len > length) throw IndexOutOfBoundsException()
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(buf, p, len, Charsets.UTF_8))
                p += len
            }
            return sb.toString()
        }
    }

    private const val MAX_HOPS = 128
}
