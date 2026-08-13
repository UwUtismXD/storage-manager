plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.8"

stonecutter parameters {
    // The whole 26.1.2 port is three renames, all mechanical, so they're string replacements
    // rather than conditional blocks - one copy of every source file, no duplication.
    replacements {
        // Renamed in 1.21.11, so this is not a 26.x-only change. Same statics and getters.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        // Verified against 26.1.2; 1.21.8 still has the old names. The exact release that
        // introduced these wasn't tested, so the bound is set at the version we build.
        string(current.parsed >= "26.1") {
            replace("ClickType", "ContainerInput")
            replace("handleInventoryMouseClick", "handleContainerInput")
            // ResourceKey.location() -> identifier(). Scoped to the receiver so it can't
            // touch an unrelated location() call elsewhere.
            replace("key.location()", "key.identifier()")
        }
    }
}
