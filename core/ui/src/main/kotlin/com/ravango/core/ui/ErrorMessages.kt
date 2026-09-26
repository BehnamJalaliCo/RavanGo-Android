package com.ravango.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ravango.core.common.result.ErrorKind

@StringRes
fun ErrorKind.messageRes(): Int = when (this) {
    ErrorKind.NETWORK -> R.string.error_network
    ErrorKind.AUTH -> R.string.error_auth
    ErrorKind.PERMISSION -> R.string.error_permission
    ErrorKind.STORAGE_FULL -> R.string.error_storage_full
    ErrorKind.NOT_SUPPORTED -> R.string.error_not_supported
    ErrorKind.QUOTA -> R.string.error_quota
    ErrorKind.INVALID_INPUT -> R.string.error_invalid_input
    ErrorKind.NOT_CONFIGURED -> R.string.error_not_configured
    ErrorKind.CANCELLED -> R.string.error_cancelled
    ErrorKind.UNKNOWN -> R.string.error_unknown
}

@Composable
fun ErrorKind.message(): String = stringResource(messageRes())
