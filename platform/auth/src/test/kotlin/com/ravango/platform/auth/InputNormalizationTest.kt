package com.ravango.platform.auth

import com.google.common.truth.Truth.assertThat
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.auth.supabase.parseSupabaseError
import org.junit.Test

class InputNormalizationTest {

    @Test
    fun `iranian mobile numbers in all common forms normalize to E164`() {
        val expected = "+989121234567"
        listOf(
            "09121234567",
            "9121234567",
            "989121234567",
            "00989121234567",
            "+989121234567",
            "+98 912 123 4567",
            "+98 0912 123 4567",
            "0912-123-4567",
            "(0912) 123.4567",
            "۰۹۱۲۱۲۳۴۵۶۷",
            "٠٩١٢١٢٣٤٥٦٧",
            "  ۰۹۱۲ ۱۲۳ ۴۵۶۷  ",
        ).forEach { input -> assertThat(PhoneNumbers.normalize(input)).isEqualTo(expected) }
    }

    @Test
    fun `invalid iranian numbers are rejected`() {
        listOf(
            "",
            "0912123456", // too short
            "091212345678", // too long
            "02112345678", // landline cannot receive SMS
            "+98 21 1234 5678",
            "0912abc4567",
            "+",
        ).forEach { input -> assertThat(PhoneNumbers.normalize(input)).isNull() }
    }

    @Test
    fun `international numbers require a country code`() {
        assertThat(PhoneNumbers.normalize("+44 7700 900123")).isEqualTo("+447700900123")
        assertThat(PhoneNumbers.normalize("0044 7700 900123")).isEqualTo("+447700900123")
        assertThat(PhoneNumbers.normalize("+1 (415) 555-2671")).isEqualTo("+14155552671")
        assertThat(PhoneNumbers.normalize("+0123456789")).isNull()
        assertThat(PhoneNumbers.normalize("+1234")).isNull()
        assertThat(PhoneNumbers.isIranian("+989121234567")).isTrue()
        assertThat(PhoneNumbers.isIranian("+14155552671")).isFalse()
    }

    @Test
    fun `emails are trimmed lowercased and validated`() {
        assertThat(EmailAddresses.normalize("  Sara.Ahmadi@Example.COM ")).isEqualTo("sara.ahmadi@example.com")
        assertThat(EmailAddresses.normalize("no-at-sign.com")).isNull()
        assertThat(EmailAddresses.normalize("a@b")).isNull()
        assertThat(EmailAddresses.normalize("a..b@example.com")).isNull()
    }

    @Test
    fun `otp codes accept persian digits and ignore separators`() {
        assertThat(OtpCodes.normalize("۱۲۳ ۴۵۶")).isEqualTo("123456")
        assertThat(OtpCodes.isComplete("12-34-56")).isTrue()
        assertThat(OtpCodes.isComplete("12345")).isFalse()
    }

    @Test
    fun `password policy requires length letters and digits`() {
        assertThat(Passwords.isAcceptable("abc12345")).isTrue()
        assertThat(Passwords.isAcceptable("abcdefgh")).isFalse()
        assertThat(Passwords.isAcceptable("a1")).isFalse()
    }

    @Test
    fun `gotrue errors map to typed auth errors`() {
        assertThat(parseSupabaseError(429, """{"code":429,"error_code":"over_email_send_rate_limit","msg":"email rate limit exceeded"}""").toAuthError())
            .isEqualTo(AuthError.RATE_LIMITED)
        assertThat(parseSupabaseError(403, """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}""").toAuthError())
            .isEqualTo(AuthError.INVALID_OTP)
        assertThat(parseSupabaseError(400, """{"error":"invalid_grant","error_description":"Invalid login credentials"}""").toAuthError())
            .isEqualTo(AuthError.INVALID_CREDENTIALS)
        assertThat(parseSupabaseError(400, """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""").toAuthError())
            .isEqualTo(AuthError.SESSION_EXPIRED)
        assertThat(parseSupabaseError(422, """{"code":422,"error_code":"user_already_exists","msg":"User already registered"}""").toAuthError())
            .isEqualTo(AuthError.USER_EXISTS)
        assertThat(parseSupabaseError(400, """{"code":400,"error_code":"phone_provider_disabled","msg":"Unsupported phone provider"}""").toAuthError())
            .isEqualTo(AuthError.PROVIDER_DISABLED)
        assertThat(SupabaseHttpException(503, null, null).toAuthError()).isEqualTo(AuthError.NETWORK)
        assertThat(java.io.IOException("offline").toAuthError()).isEqualTo(AuthError.NETWORK)
    }
}
