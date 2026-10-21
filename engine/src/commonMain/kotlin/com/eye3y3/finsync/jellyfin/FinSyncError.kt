package com.eye3y3.finsync.jellyfin

/** Everything that can go wrong, with a message fit to show to a person. [retryable] errors may be tried again. */
sealed class FinSyncError(message: String, val retryable: Boolean = false) : Exception(message) {
    class ServerUnreachable(url: String) : FinSyncError("Can't reach the server at $url. Check the address and that you are on the same network.", true)
    class ServerProblem(code: Int) : FinSyncError("The server had a problem (error $code). Try again in a moment.", true)
    class SignedOut : FinSyncError("You are signed out. Sign in again.")
    class DownloadNotAllowed : FinSyncError("This account is not allowed to download files. Ask the server owner to turn on downloading for it.")
    class NotFound(what: String) : FinSyncError("The server doesn't have $what.")
    class SignInFailed : FinSyncError("The server did not accept that sign-in.")
    class QuickConnectOff : FinSyncError("Quick Connect is turned off on this server. Turn it on in the Jellyfin dashboard under General.")
    class QuickConnectTimeout : FinSyncError("Nobody approved the Quick Connect code in time. Start again to get a new code.")
    class BadDownload(what: String) : FinSyncError("The download of $what arrived damaged. It was thrown away.", true)
    class NotEnoughSpace(needed: Long, free: Long) : FinSyncError("Not enough free space: this needs about ${needed / 1_000_000} MB but only ${free / 1_000_000} MB is free.")
    class DeletionBrake(reason: String) : FinSyncError("Stopped without changing anything: $reason. Run again with the large-delete option if that is really what you want.")
    class Busy : FinSyncError("Another sync is running on this folder. If it was stopped, try again in a few minutes: a sync that stopped without finishing is taken over after 5 minutes.")
    class WrongTarget(what: String) : FinSyncError("This folder was last synced from a different $what. Use an empty folder or the original server.")
    class Problem(message: String) : FinSyncError(message)
}
