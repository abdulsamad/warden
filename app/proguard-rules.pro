# Shizuku starts the grant service in its own process by class name, through a no-arg constructor.
-keep class app.warden.GrantUserService { public <init>(); }

# The source is public, so renaming hides nothing. Keep real class and method names, files and
# line numbers (R8 still shrinks and optimizes) so a stack trace from a user report reads as-is,
# without a mapping file.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
