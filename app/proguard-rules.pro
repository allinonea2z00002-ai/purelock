# Room / WorkManager / Play Services ship their own consumer rules.
# Keep services and receivers that the system instantiates by name.
-keep class com.purelock.app.vpn.PureLockVpnService { *; }
-keep class com.purelock.app.service.PureLockAccessibilityService { *; }
-keep class com.purelock.app.admin.PureLockAdminReceiver { *; }
-keep class com.purelock.app.ui.widget.** { *; }
