# Tillbell for Android

A [Hotwire Native](https://native.hotwired.dev) bridge component that runs the Google Play Billing purchase flow for subscriptions sold from a Rails paywall.

This is the Android half of Tillbell. The server half is [tillbell-rails](https://github.com/pmakerhq/tillbell-rails); the iOS half is [tillbell-ios](https://github.com/pmakerhq/tillbell-ios).

> **Status: v0.1.** The component is unit tested and verified with real purchases. Expect changes before 1.0.

## What it does

Your paywall is a normal server-rendered Rails page, with the `tillbell` Stimulus controller installed by tillbell-rails. Inside your Hotwire Native app, the page asks this component to:

1. look up the localized price of each plan shown on the paywall (`products`);
2. open the Google Play purchase sheet for the selected product and base plan, tagged with the identifier created by Rails (`obfuscatedAccountId`), including plan changes (`purchase`);
3. list the subscriptions Google Play knows for the device's account (`restore`);
4. open the Play Store page where the customer manages the subscription (`manage`).

The page then posts the purchase token to your Rails app, which verifies it with Google, acknowledges it and unlocks the subscription. All business logic (who is subscribed, until when, in which state) stays in Rails.

The messages are specified by the [bridge contract](https://github.com/pmakerhq/tillbell-rails/blob/main/docs/bridge-contract.md) of tillbell-rails. Its JSON Schemas are copied into [`tillbell/src/test/resources/contract`](tillbell/src/test/resources/contract) and every reply is validated against them in the unit tests.

## Requirements

| | Version | Why |
|---|---|---|
| Hotwire Native Android | 1.4.1 | Latest stable release. The component extends its `BridgeComponent`. |
| Google Play Billing Library | 9.1.0 | Latest stable release. |
| Minimum SDK | 28 | Set by Hotwire Native Android (`dev.hotwire:core` declares `minSdk 28`); the Billing Library only needs 23. |
| Compile SDK | 37 | Latest stable Android platform. |

The library itself is built with Gradle 9.9.1, the Android Gradle Plugin 9.5.1 (with its built-in Kotlin support) and Kotlin 2.4.21, the latest stable releases. A host app does not need the same versions.

## Installation

Tillbell is published to [GitHub Packages](https://github.com/pmakerhq/tillbell-android/packages) as `com.pmaker:tillbell`. GitHub Packages asks for a token even to read, so add the repository with credentials in your app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/pmakerhq/tillbell-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("com.pmaker") }
        }
    }
}
```

Locally, put `gpr.user` (your GitHub login) and `gpr.key` (a personal access token with the `read:packages` scope) in `~/.gradle/gradle.properties`. In GitHub Actions, the `GITHUB_TOKEN` of another repository cannot read this package: the Maven registry only has repository-scoped permissions. Store a classic personal access token with `read:packages` as a secret and pass it to Gradle as `GITHUB_TOKEN`.

Then, in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.pmaker:tillbell:0.1.2")
}
```

### Releasing

Push a `vX.Y.Z` tag: the [release workflow](.github/workflows/release.yml) runs the checks and publishes version `X.Y.Z`. A published version cannot be overwritten, so a fix ships as a new tag.

## Registration

Register the component with Hotwire next to your other bridge components, usually in your `Application`:

```kotlin
import com.pmaker.tillbell.TillbellComponent

Hotwire.registerBridgeComponents(
    BridgeComponentFactory("external-offer", ::ExternalOfferComponent), // your own components
    BridgeComponentFactory(TillbellComponent.NAME, ::TillbellComponent),
)
```

That is all the native setup there is. Registering the component is what enables the paywall: Hotwire Native lists `tillbell` in the app's user agent, and the page only connects its controller when it finds it. Without it, the paywall shows its fallback and never sends a message.

The component coexists with any other bridge component, and with other Play Billing clients in the same app: it creates its own `BillingClient`.

## How it behaves

- **The server decides.** The component reports what Google Play says and nothing else. It never acknowledges a purchase (the server does, after verifying it) and never derives an identifier on the device: `obfuscatedAccountId` is passed to Play unchanged, and no obfuscated profile id is set.
- **Prices come from Google Play**: the `formattedPrice` of the base plan's regular pricing phase in `ProductDetails`. Plans Play does not know are left out of the reply, and the page disables them.
- **Base plans only.** v0.1 sells and prices base plans; other offers (free trials, promotions) are not used.
- **Plan changes** pass the old purchase token and the replacement mode chosen by the server to `SubscriptionUpdateParams`. The component never picks a replacement mode (Google recommends `CHARGE_PRORATED_PRICE` for an upgrade and `DEFERRED` for a downgrade; see the plan changes section of [The paywall](https://github.com/pmakerhq/tillbell-rails#the-paywall) in tillbell-rails). A plan change can succeed without its new purchase in the result of the purchase sheet. Google's documentation says a replacement gets a new purchase token, a deferred one included, and that the pending change is reported on it. The component then asks Google Play for the purchases of the account and replies with the new one, never with the purchase being replaced: the purchase of the requested product, or of the product being replaced (a deferred replacement stays associated with it until the renewal), and only the other purchase if the replaced one is no longer listed. If Google Play lists no such purchase, or several that cannot be told apart, the reply is an `error`, so the page never shows a plan change that did not happen. What Google Play delivers for a deferred change is not documented in detail: try it with a license tester. Without a plan change, a result with no purchase is an `error`.
- **Pending purchases** are enabled, as Google Play requires.
- **Every request gets one reply**, including errors and malformed requests, so the page never waits forever. A dropped connection to Play is retried once, then reported.
- **Activity recreation.** The Play Billing client and the purchase in flight live for the whole process, not for a screen, so the purchase result is never lost. Hotwire Native reloads the page in a new WebView when the Activity is recreated, though: if that happens while the purchase sheet is open, the page that asked is gone and the bridge drops the reply. The purchase is still recorded, because Google notifies the server, and `restore` (sent by the page when the customer taps "Restore" or buys again) returns it.

## Development

```sh
./gradlew build test
```

`build` runs Android lint and ktlint (both must report nothing), `test` runs the unit tests. The billing wrapper is tested against a fake Play Billing client, so the tests need neither a device nor Google Play. CI runs the same command on every pull request.

To update the bridge contract, copy the `contract/` directory of a newer tillbell-rails commit into `tillbell/src/test/resources/contract` and record the commit in its README.

## License

Tillbell is released under the [MIT license](LICENSE).
