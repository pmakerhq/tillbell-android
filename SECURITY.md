# Security policy

## Supported versions

Only the latest release of Tillbell for Android receives security fixes.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through [GitHub's private vulnerability reporting](https://github.com/pmakerhq/tillbell-android/security/advisories/new). Include the affected version, what you observed and, if you can, how to reproduce it.

You will get an acknowledgement as soon as possible. A confirmed vulnerability is fixed in a new release and disclosed in a published security advisory.

## Scope

Tillbell runs the Google Play Billing purchase flow on the device. It never decides who has access and never acknowledges a purchase: the server does both. Reports about the bridge messages, the handling of purchase tokens or the published artifact are in scope. Issues in Google Play Billing, Hotwire Native or your own server should be reported to their maintainers.
