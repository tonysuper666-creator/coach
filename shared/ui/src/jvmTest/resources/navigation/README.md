These are the original 27 synthetic legacy codec inputs prepared by Scout-W from
Expert v2 for TON-1449, preserved byte for byte. The original report describes the
state before the product migration existed; its `product_migration_implemented`
and expected implementation markers are historical evidence, not current results.

- Multica attachment: `01a1cdfc-dfac-7258-a3cb-30e2464c78c5`
- Expert v2 decision attachment: `01a1cdde-1a6a-74e6-b3d7-3c8e6cdcaea1`
- Original codec head: `ccdeabfe59381b126181630de774048218d44151`
- SHA-256: `c01e7b4c265793aecd5f2044cd4d84cddb0cfd014a353c65696914cea9499e76`

AppNavigationMigrationTest feeds every `old_input` into the product migration,
persists/reopens the result and checks every return frame. AppNavigationStateTest
restores the actual Compose SaveableStateHolder using literal pre-upgrade keys.
ThreeDestinationNavigationTest also boots CoachApp from the original
`built-records-mock` input and exercises the three destinations and exam routes.

These tests verify the shared JVM UI. They do not verify installation or the
Windows default host, which currently starts the separate textbook workspace.
