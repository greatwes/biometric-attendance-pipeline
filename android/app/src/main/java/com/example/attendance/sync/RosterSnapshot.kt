package com.example.attendance.sync

import android.util.Base64
import org.json.JSONObject

/**
 * One page of roster changes, as returned by `GET /webhook/zk/roster`.
 *
 * Parsed with org.json rather than a serialisation library because this is the
 * only JSON in the app - the rest of the wire format is ADMS key-value text -
 * and a dependency plus its Proguard rules is a poor trade for one object.
 */
data class RosterSnapshot(
    /**
     * The server's watermark, to be sent back as the next `since`.
     *
     * Deliberately taken from the response rather than computed from the rows:
     * it is the high-water mark of the whole table, so a device that receives an
     * empty page still moves forward instead of re-scanning the same range on
     * every poll.
     */
    val version: Long,
    val employees: List<RemoteEmployee>,
    val departments: List<RemoteDepartment>
) {
    val isEmpty: Boolean get() = employees.isEmpty() && departments.isEmpty()

    companion object {
        fun parse(json: String): RosterSnapshot {
            val root = JSONObject(json)

            val employees = mutableListOf<RemoteEmployee>()
            val employeeArray = root.optJSONArray("employees")
            if (employeeArray != null) {
                for (i in 0 until employeeArray.length()) {
                    val o = employeeArray.optJSONObject(i) ?: continue
                    val pin = o.optString("pin").trim()
                    if (pin.isEmpty()) continue

                    val fingers = mutableListOf<RemoteFinger>()
                    val fingerArray = o.optJSONArray("fingers")
                    if (fingerArray != null) {
                        for (f in 0 until fingerArray.length()) {
                            val fo = fingerArray.optJSONObject(f) ?: continue
                            val encoded = fo.optString("template")
                            if (encoded.isEmpty()) continue
                            // Decoded here so a corrupt template is rejected at
                            // the edge, before it can reach the database or the
                            // matcher. A row that fails to decode is dropped and
                            // the rest of the person still applies.
                            val bytes = runCatching {
                                Base64.decode(encoded, Base64.DEFAULT)
                            }.getOrNull() ?: continue
                            if (bytes.isEmpty()) continue
                            fingers += RemoteFinger(fo.optInt("fingerIndex", 0), bytes)
                        }
                    }

                    employees += RemoteEmployee(
                        pin = pin,
                        firstName = o.optString("firstName"),
                        lastName = o.optString("lastName"),
                        fullName = o.optString("fullName"),
                        department = o.optString("department"),
                        gender = o.optString("gender"),
                        deleted = o.optBoolean("deleted", false),
                        version = o.optLong("version", 0L),
                        fingers = fingers
                    )
                }
            }

            val departments = mutableListOf<RemoteDepartment>()
            val departmentArray = root.optJSONArray("departments")
            if (departmentArray != null) {
                for (i in 0 until departmentArray.length()) {
                    val o = departmentArray.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty()) continue
                    departments += RemoteDepartment(
                        name = name,
                        deleted = o.optBoolean("deleted", false),
                        version = o.optLong("version", 0L)
                    )
                }
            }

            return RosterSnapshot(
                version = root.optLong("version", 0L),
                employees = employees,
                departments = departments
            )
        }
    }
}

data class RemoteEmployee(
    val pin: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val department: String,
    val gender: String,
    val deleted: Boolean,
    val version: Long,
    val fingers: List<RemoteFinger>
) {
    /**
     * Names, falling back to splitting whatever single name we were given.
     *
     * A stock ZKTeco terminal only ever sends `Name=`, so rows that originated
     * on one arrive with `fullName` populated and the split parts empty. The
     * same first-space rule as the device migration and the parser - keep the
     * three in step.
     */
    fun resolvedNames(): Pair<String, String> {
        if (firstName.isNotBlank() || lastName.isNotBlank()) return firstName to lastName
        val whole = fullName.trim()
        if (whole.isEmpty()) return pin to ""
        val gap = whole.indexOf(' ')
        return if (gap > 0) whole.substring(0, gap) to whole.substring(gap + 1).trim()
        else whole to ""
    }
}

data class RemoteFinger(val fingerIndex: Int, val template: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteFinger) return false
        return fingerIndex == other.fingerIndex && template.contentEquals(other.template)
    }

    override fun hashCode(): Int = 31 * fingerIndex + template.contentHashCode()
}

data class RemoteDepartment(val name: String, val deleted: Boolean, val version: Long)
