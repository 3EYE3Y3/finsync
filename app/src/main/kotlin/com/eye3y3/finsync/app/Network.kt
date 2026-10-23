package com.eye3y3.finsync.app

import com.eye3y3.finsync.jellyfin.isPlainHttp

internal fun needsHttpConfirmation(address: String, confirmed: Boolean): Boolean = isPlainHttp(address) && !confirmed
