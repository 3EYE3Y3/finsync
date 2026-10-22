# Security

Please report security problems **privately**: use [GitHub's private vulnerability reporting](https://github.com/3EYE3Y3/finsync/security/advisories/new) for this repository. Don't open a public issue for them.

Useful to include: the FinSync version and build (Play or GitHub APK), what an attacker could do, and steps to reproduce. You'll get an answer as soon as possible; fixes are released for the latest version.

FinSync stores your Jellyfin access token in the app's private storage and talks only to the server you enter. Plain `http://` is allowed because home servers often don't have HTTPS; from 0.1.2 the app warns when an `http://` address isn't on the home network or a VPN. On untrusted networks, use HTTPS or a VPN. Signing out also ends the session on the server.
