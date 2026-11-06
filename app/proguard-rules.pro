# Shizuku starts the grant service in its own process by class name, through a no-arg constructor.
-keep class app.warden.GrantUserService { public <init>(); }
