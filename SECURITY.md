# Security

Please report security problems **privately**: use [GitHub's private vulnerability reporting](https://github.com/3EYE3Y3/finsync/security/advisories/new) for this repository. Don't open a public issue for them.

Useful to include: the FinSync version and build (Play or GitHub APK), what an attacker could do, and steps to reproduce. You'll get an answer as soon as possible; fixes are released for the latest version.

FinSync stores your Jellyfin access token in the app's private storage and sends it only to the server you enter. Besides that server, the GitHub APK build checks GitHub once a day for a newer release (no data about you or your library is sent); the Play build only talks to Google Play for updates. Plain `http://` is allowed because home servers often don't have HTTPS; from 0.1.2 the app warns when an `http://` address isn't on the home network or a VPN. On untrusted networks, use HTTPS or a VPN. Signing out also ends the session on the server.

## Checking a download

Every APK on the [releases page](https://github.com/3EYE3Y3/finsync/releases) is signed with the same certificate:

```
SHA-256 95:88:C0:9F:E2:B4:4C:DC:FF:CB:6E:94:D9:9A:13:3B:0A:13:A8:7E:D1:F4:4F:71:A9:FF:2D:78:CC:5D:A2:74
```

An APK signed with anything else isn't from this project. Android also refuses to update an installed FinSync with an APK signed by a different key. The Google Play version is signed by Google Play.
