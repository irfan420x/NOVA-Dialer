package com.nova.dialer.data

/**
 * Pure phone-number helpers (no Android imports) — T9 search and display
 * formatting. Kept dependency-free so the logic can be unit-tested on the
 * JVM without a device.
 */
object PhoneUtils {

    private val t9Letters = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )
    private val letterToDigit: Map<Char, Char> = buildMap {
        for ((digit, letters) in t9Letters) for (c in letters) put(c, digit)
    }

    /** Digits only, keeping a leading '+' if present. */
    fun digitsOnly(s: String): String {
        val d = s.filter { it.isDigit() }
        return if (s.trimStart().startsWith("+")) "+$d" else d
    }

    /** T9 code for a name: "Rahim" -> "72446". Digits pass through. */
    fun t9Code(name: String): String = buildString {
        for (c in name.lowercase()) {
            when {
                c.isDigit() -> append(c)
                letterToDigit.containsKey(c) -> append(letterToDigit[c])
                else -> append(' ')
            }
        }
    }.replace(" ", "")

    /**
     * True when the typed digits match the contact: any word of the name
     * starts with the digit sequence in T9, or the number contains it.
     *
     * v1.6: the number comparison normalizes the Bangladeshi forms first —
     * a contact stored as +8801712… now matches typed 01712… (the local
     * form the user actually dials), and vice versa.
     *
     * v1.7: the prefix is stripped UNCONDITIONALLY. The v1.6 version only
     * stripped it for strings longer than 10 chars, so while the user was
     * still typing ("01712") the typed side kept its 0 and the stored side
     * had lost it — suggestions stayed empty at every step (a regression
     * proven by unit tests). A normalized typed string shorter than 2 digits
     * matches almost every number, so it matches nothing.
     */
    fun t9Matches(name: String, number: String, digits: String): Boolean {
        if (digits.isEmpty()) return false
        val stored = normalizeForMatch(digitsOnly(number))
        val typed = normalizeForMatch(digits)
        if (typed.isEmpty() || typed.length < 2) return false
        if (stored.contains(typed) || typed.contains(stored)) return true
        val words = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { t9Code(it).startsWith(digits) }
    }

    /**
     * Normalize a dialed/stored number for comparison: +8801712… and
     * 01712… and 8801712… all become "1712…". (Bangladesh country code 880.)
     * Stripping is unconditional — a length guard broke progressive
     * matching while the user was still typing (v1.7).
     */
    fun normalizeForMatch(digits: String): String {
        val d = digits.removePrefix("+")
        return when {
            d.startsWith("880") -> d.removePrefix("880")
            d.startsWith("0") -> d.removePrefix("0")
            else -> d
        }
    }

    /** Conservative display format: only plain 10/11-digit numbers are grouped. */
    fun prettyNumber(number: String): String {
        val d = digitsOnly(number)
        val hasPlus = d.startsWith("+")
        val core = d.removePrefix("+")
        return when {
            !hasPlus && core.length == 10 ->
                "${core.substring(0, 3)} ${core.substring(3, 6)} ${core.substring(6)}"
            hasPlus && core.length == 11 && core.startsWith("1") ->
                "+1 ${core.substring(1, 4)} ${core.substring(4, 7)} ${core.substring(7)}"
            else -> number
        }
    }

    /** "2m ago" / "Yesterday" style relative time for the call log. */
    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - epochMillis).coerceAtLeast(0)
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> {
                val date = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(epochMillis))
                date
            }
        }
    }

    /** Call duration as "1:05" / "12:34". */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
