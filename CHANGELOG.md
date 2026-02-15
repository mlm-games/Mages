## v2.6.4




## v2.6.3

- Android: Try forwarding avatar to call screen
- Add start menu icons for Windows and MacOS
- Add power levels, and perms management to room info screen (initial)
- Add an option to set room alias when creating a room
- fix replies taking full width


## v2.6.2

- Update release notes for v2.6.1
- Add start menu icons for Windows and MacOS
- Add power levels, and perms management to room info screen (initial)
- Add an option to set room alias when creating a room
- move actually to 1.10.0
- fix replies taking full width


## v2.6.1

- Add start menu icons for Windows and MacOS
- Add power levels, and perms management to room info screen (initial)
- Add an option to set room alias when creating a room
- move actually to 1.10.0
- fix replies taking full width


## v2.5.2

- (Android) pass a diff. color to markdown text for better monochrome contrast
- Fix no results string
- Fix no rooms match string


## v2.5.1

- fix thread replies again
- Let message bubbles take 80% of available width
- Use the Material 3 Expressive theme
- Initial translation handling (es)


## v2.4.6

- rem legacy packaging, and update ndk ver (test fdroid build)
- bump from rc-02 to rel. for M. Compose


## v2.4.5

- Improve room info screen
- Make media gallery auto paginate for all 4 tabs (remove load more)


## v2.4.4

- hide option to change title and topic if power levels are low
- Resolve room names in spaces (lazy)


## v2.4.3

- avatar handling for spaces
- try fixing flathub screen share (and cam)
- Add View all option for pinned messages


## v2.4.2

- Add "Report content" and Pins support
- Shift flathub builds locally
- update media and files screen to seperate by month, show "load more" in empty rooms, add avatar for space detail screen, and other misc changes
- fix vendored tarball
- update vendor tarball yml
- bump back to 21 (for fdroid deb. migration)
- Update create-vendored-tarball workflow


## v2.3.7

- Clarify Android and Linux build availability in README
- add vendoring yml
- remove duplicate "Add space" button, when no spaces exist


## v2.3.6

- reduce the pagination load to 20 (from 50) messages at a time
- fix duplicate notifications on Desktop


## v2.3.5

- version info embedding for desktop AppImages
- Reduce monochrome icon size slightly
- Add an option to disable media preview thumbnails (can be used for saving mobile data)


## v2.3.4

- Update readme license from GNU GPL v3 to GNU AGPL v3
- Revise README for clarity and feature updates


## v2.3.3

- Add nav bars padding (#31)
- try fix windows build lib not being copied
- Update README.md
- Update README.md
- upgrade to agp 9.0.0


## v2.3.2

- Add back mono icon
- Add version trackers


## v2.3.1

- Update build.gradle.kts
- Desktop: Indicator for calls on first download
- Fix the input / resize issue with jcef windows (jcef call issue fixed)


## v2.2.1

- fix clearing of events after ending a call
- Fix back pagination to only consider visible events


## v2.1.4

- Update release notes for v2.1.3
- Back minimizes call instead of navigating back
- Fix webview not being destroyed on android


## v2.1.3

- Back minimizes call instead of navigating back
- Fix webview not being destroyed on android


## v2.1.2

- Do not show call notification by default
- Add resize handler for minimized call overlay
- Fix call notification deep link
- fix intent scheme logic for links
- Notifications consistenancy improvements
- Decrease icon's roundedness, and improve timeline caching (account-specific)
- Add dynamic colors option (Android 12+ only)
- Do not show the room name in notifications for dm rooms (revert)
- Working element calls, on android (theming and minimize action needs to be wired)


## v2.1.1

- Fix go back logic for adding an account, change security icon to settigns icon, and remove unused logic from security screen)
- Hide start in tray on Android (fix #26)
- Hide start in tray on Android (fix #26)
- Update release notes for v2.0.4
- Make verification flows global, and fix them for android
- Resolve homeservers (#22)
- Remove depinfo (migr miss)


## v2.0.4

- Make verification flows global, and fix them for android
- Resolve homeservers (#22)
- Remove depinfo (migr miss)


## v2.0.3

- Fix markdown colors for `` text, and also make the surface dark for loading bg
- Switch logout button to show logout icon, show options when added


## v2.0.2

- Android Multi account related fixes, remove AppCtx and MatrixProvider classes (might not be necessary now) for android
- Update release notes for v2.0.1
- Breaking change, bump major ver (may need to relogin)
- Add multi login support
- Dark theme by default
- fix the body_path for release notes, and remove extra step
- Login screen adjusts for keyboard padding (fix #18)
- Use DayNight instead of Light for AppCompat (#16)
- Update io.github.mlm_games.mages.metainfo.xml


## v2.0.1

- Breaking change, bump major ver (may need to relogin)
- Add multi login support
- Dark theme by default
- fix the body_path for release notes, and remove extra step
- Login screen adjusts for keyboard padding (fix #18)
- Use DayNight instead of Light for AppCompat (#16)
- Update io.github.mlm_games.mages.metainfo.xml


# Changelog

## v1.3.2

- Update release-android.yml
- Update release-android.yml
- Allow direct # paths in discovery (#21)
- try calling all tasks (in yml
- Revert "Trial fix for desktop"

