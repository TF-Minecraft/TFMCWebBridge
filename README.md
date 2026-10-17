# TFMCWebBridge

> Connecting TF-Minecraft gameplay, website features, and Discord identity.

TFMCWebBridge is the repository for the `TFMCWeb` Minecraft plugin, the bridge to the TF-Minecraft website services. It helps players connect their Discord account, obtain access codes for website features, and receive account updates in-game.

The same bridge connects selected moderation and roleplay events to the wider community services, keeping account identity and supported player benefits aligned across them.

## Features

- **Discord account linking** — players can request a linking code or unlink their Minecraft account.
- **Website feature codes** — issues scoped codes for skins, drinks, and player profiles, subject to access rules and cooldowns.
- **Account status updates** — processes website notices about linking and eligibility, including RPCharacters survival access checks.
- **Moderation connections** — sends player warnings, and bans, unbans and expired timed bans from the server ban list, to the linked services so the Discord Banned role follows the in-game ban.
- **Supporter benefits** — links Patreon accounts and synchronizes supported LuckPerms tiers through TFMCWeb.
- **Player metadata** — shares resolved player permissions and entitlements with the website.
- **Bird-mail notifications** — provides the connection for BirdMessenger arrival notifications to linked Discord accounts.
- **Staff-panel LuckPerms bridge** — publishes LuckPerms groups, tracks and player permissions to the website's staff panel, and applies the rank and permission changes staff queue there through LuckPerms.

These features rely on the corresponding TF-Minecraft services and gameplay integrations being available.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/TFMCWebBridge/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

Run `mvn clean verify` with Java 21 after preparing the dependencies in the
[project guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/TFMCWebBridge/README.md#build-and-dependencies).
JUnit 5, Mockito and MockBukkit exercise plugin logic with mocked server APIs.
JaCoCo enforces 100% production line coverage with no class or package exclusions;
branch and instruction coverage are reported separately.

Surefire writes test results to `target/surefire-reports/`. Coverage reports are
`target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`; CI uploads
both test and coverage reports. These tests do not run a live Paper server,
LuckPerms storage, Discord, Patreon, or the deployed website.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
