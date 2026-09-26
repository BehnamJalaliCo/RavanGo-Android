package com.ravango.platform.auth

import com.ravango.core.common.format.normalizeDigits

/**
 * Normalizes phone numbers to E.164 for SMS OTP.
 *
 * Iranian numbers are accepted in every common form — `09121234567`, `9121234567`, `989121234567`,
 * `00989121234567`, `+98 912 123 4567`, `+98 0912…` — with Persian (۰-۹) or Arabic-Indic (٠-٩) digits, spaces,
 * dashes, dots and parentheses, and become `+989121234567`. Other countries must be entered with a leading `+`
 * (or `00`). Returns null when the input cannot be a valid mobile number.
 */
object PhoneNumbers {
    private const val IRAN_CODE = "98"

    fun normalize(input: String): String? {
        val raw = input.normalizeDigits().trim()
        if (raw.isEmpty()) return null
        val hasPlus = raw.startsWith("+")
        // Only digits and common separators are allowed.
        if (raw.drop(if (hasPlus) 1 else 0).any { !it.isDigit() && it !in SEPARATORS }) return null
        var digits = raw.filter { it.isDigit() }
        var international = hasPlus
        if (!international && digits.startsWith("00")) {
            digits = digits.drop(2)
            international = true
        }
        return when {
            international -> normalizeInternational(digits)
            digits.length == 11 && digits.startsWith("09") -> iranMobile(digits.drop(1))
            digits.length == 10 && digits.startsWith("9") -> iranMobile(digits)
            digits.length == 12 && digits.startsWith("989") -> iranMobile(digits.drop(2))
            else -> null
        }
    }

    /** True when [e164] is an Iranian mobile number (used to show the local format hint). */
    fun isIranian(e164: String): Boolean = e164.startsWith("+$IRAN_CODE")

    private fun normalizeInternational(digits: String): String? {
        if (digits.startsWith(IRAN_CODE)) {
            var national = digits.drop(IRAN_CODE.length)
            if (national.startsWith("0")) national = national.drop(1) // "+98 0912…"
            return iranMobile(national)
        }
        // E.164: country code + subscriber number, 8..15 digits, no leading zero.
        if (digits.length !in 8..15 || digits.startsWith("0")) return null
        return "+$digits"
    }

    /** [national] is the 10-digit national number without trunk prefix, e.g. 9121234567. */
    private fun iranMobile(national: String): String? {
        if (national.length != 10 || !national.startsWith("9") || !national.all { it.isDigit() }) return null
        return "+$IRAN_CODE$national"
    }

    private val SEPARATORS = setOf(' ', '-', '.', '(', ')', '‌', ' ')
}

object EmailAddresses {
    private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** Trims and lower-cases; returns null when the address is not plausibly valid. */
    fun normalize(input: String): String? {
        val trimmed = input.normalizeDigits().trim().lowercase()
        return trimmed.takeIf { EMAIL.matches(it) && !it.contains("..") }
    }
}

object OtpCodes {
    /** Keeps only digits (Persian digits converted); Supabase codes are 6 digits by default (configurable up to 10). */
    fun normalize(input: String): String = input.normalizeDigits().filter { it.isDigit() }

    fun isComplete(code: String, length: Int = 6): Boolean = normalize(code).length == length
}

object Passwords {
    const val MIN_LENGTH = 8

    /** Minimal client-side policy; the server enforces the project's policy and reports WEAK_PASSWORD. */
    fun isAcceptable(password: String): Boolean = password.length >= MIN_LENGTH && password.any { it.isLetter() } && password.any { it.isDigit() }
}
