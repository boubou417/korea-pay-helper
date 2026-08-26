# Auto-capture service source parts

`PayAccessibilityService.kt` is generated at Android build time by concatenating `part00.ktpart` through `part09.ktpart` in lexical order.

This split keeps the large, already-tested accessibility state machine unchanged while allowing the Capacitor integration branch to carry it without duplicating a second Android app module.

Do not reorder or edit only one part without regenerating/testing the complete concatenated Kotlin source.
