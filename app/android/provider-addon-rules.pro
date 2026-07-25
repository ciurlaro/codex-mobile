# Provider splits are released independently from the base APK, so their names must not depend on
# the base build's R8 mapping. Shrinking and optimization remain enabled.
-dontobfuscate
