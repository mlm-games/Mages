## v4.6.2

- Update release notes for v4.6.1
- Add long press menu to mark rooms as read, or set room flags (#94)
- live loc fix
- resolve space avatars (#92)
- bump target sdk to 37, and other lib vers
- bump agp and element-call vers to 9.2.0 and 0.19.2 respectively
- strip out <body> and <p> tags when sending markdown messages (#81)


## v4.6.1

- Add long press menu to mark rooms as read, or set room flags (#94)
- live loc fix
- resolve space avatars (#92)
- bump target sdk to 37, and other lib vers
- bump agp and element-call vers to 9.2.0 and 0.19.2 respectively
- strip out <body> and <p> tags when sending markdown messages (#81)


## v4.5.7

- reduce the padding around floating date chip
- Increase message bubble's max horiz. size
- fix message grouping for username
- fix audio crash


## v4.5.6

- bump vers


## v4.5.5

- Handle newline like most other chatting apps (#84)


## v4.5.4

- Fix android voice message play logic
- Update release notes for v4.5.3


## v4.5.3




## v4.5.2

- fix formatted body not being sent
- Update release notes for v4.5.1
- Turn off bubble animations by default, and use immediate mode rendering in the markdown library (#78)
- Update release notes for v4.4.7
- Do not let the leave be clicked again, while the room is being left (#83)
- cancel the notification after it has been clicked
- experimental: audio handling
- Add raw json display of a message to Message Info sheet


## v4.5.1

- Turn off bubble animations by default, and use immediate mode rendering in the markdown library (#78)
- Update release notes for v4.4.7
- Do not let the leave be clicked again, while the room is being left (#83)
- cancel the notification after it has been clicked
- experimental: audio handling
- Add raw json display of a message to Message Info sheet


## v4.4.7

- Do not let the leave be clicked again, while the room is being left (#83)
- cancel the notification after it has been clicked
- experimental: audio handling
- Add raw json display of a message to Message Info sheet


## v4.4.6

- Remove the padding for bubbles, when displaying avatars
- Use system theme, as the default theme
- Android: fix Bubbles getting cancelled on opening a notification (fix #76, #73)
- Android: fix Oauth (#56)
- actually fix "timeline" issue


## v4.4.5

- android: Remove the room name from grp notifications
- Android: Always capitalise first letter, and fix "Timeline" being shown as "go5" on release builds
- Add home screen shortcuts support


## v4.4.4

- Update release notes for v4.4.3
- use m3 timepicker component for quiet time setting


## v4.4.3

- use m3 timepicker component for quiet time setting


## v4.4.2

- fix reaction notifications not being marked as read on android
- basic stickers display and "send sticker from image" option (no image packs support) (#67)
- Notification push rules UI


## v4.4.1

- Set retain state to true for markdown text bubble anim is turned off
- Revert "chore: set retain state to true for markdown text bubble anim is turned off"
- Improve attachments by showing its name (and type) in attachment ui, and hide filenames for images and videos
- voice call audio wiring fix
- improve media gallery pagination
- Fix pinned message banner (attach to the bottom of the topbar)
- add more types to timeline messages hide options (including deleted messages)
- Refactor message and room actions to only show if they are actionable (except for delete)


## v4.3.6

- Switching accounts clears previous cached recovery UI state (#68)
- Hide left public rooms in UI (#69)
- Add settings to hide few types of system timeline events
- add email and phone number options for password login
- Expose dynamic theme options to bubbles


## v4.3.5

- Fix client id not being properly saved (might need to relogin)
- add the deleted toolchain resolver
- make the sso and login wait for e2ee tasks before logging in
- show a silent notification if the notif is not noisy instead of discarding
- hide dup. circular indicator for unread messages
- Change other Linear and Circular indicators to m3e wavy components


## v4.3.4

- hide dup. circular indicator for unread messages
- Change other Linear and Circular indicators to m3e wavy components


## v4.3.3

- Add more details to message info sheet
- add multi-room share functionality
- Add ability to select multiple rooms when forwarding messages
- improve Seen by chip alignment for groups


## v4.3.2

- Add floating timeline date display
- Android: Select the correct audio device based on call method (#60)
- Add a toggle for bubble appearance animations
- fix android crash when trying to call


## v4.2.3

- show calls in previews
- Improve attachment progress anim
- Add language setting (to switch without switching the default lang.)
- change secondary color to be slighly less greyish
- fix: settings "calls" section shown as a "privacy" section
- fix \' strings translation


## v4.2.2

- add location permission request logic


## v4.2.1

- Do not consider system messages for read events (#55)
- Render mentions as bold links
- @Mentions support
- Show avatars toggle (#54)
- Live location impl. (android only)
- test: override OAuth for android
- Bump few dependencies' versions


## v4.1.2

- Unread FAB, and scroll to last unread on opening a room (#50)
- Add Unread count to filter chip in Rooms
- fallback to global avatars if local names aren't present


## v4.1.1

- try forcing the redirects be ipv4 addrs (#41)
- Show a return action, after clicking a reply (#50)
- Only ask for call perms, when making a call


## v4.0.2

- Move "This Device" down in DevicesTab, and try fixing UnifiedPush again
- UI: make "This device" text not autowrap


## v4.0.1

- Show the actual error message for few fails
- Add copy action to snackbar (for errors)
- Filter server or state messages to display them in a different way
- Option to change recovery key, and turn off key storage
- make filter chips row scrollable
- [BREAKING] Bump matrix-rust-sdk to latest main, and add dm voice calling support
- Improve status indicators, and make "seen by" clickable
- Revert tap to open color change (cause of bad text contrast)
- Hide predecessor room banner
- bump rust toolchain version, and element-call version


## v3.2.2

- Hide predecessor room banner
- bump rust toolchain version, and element-call version


## v3.2.1

- Login screen refactor
- add thread fallback (for non replies, replies was already present)
- Initial recovery key generation impl (experimental) (#47)
- Add manage account button for homeservers that support oidc (#47)
- Move invite screen to a filter in room screen
- implement direct user lookup in discover, while removing suggested people
- Invite event formatting (#46)


## v3.1.3

- Android: prevent the distributor popup from appearing when there are no options to choose from


## v3.1.2

- Add native OAuth login support
- Fix typo in license section of README.md


## v3.1.1

- multiple attachments, native file pickers on jvm, and an camera option
- ctrl + V can now paste multiple files instead of just one
- make message actions sheet scrollable


## v3.0.2

- make textlinks and md headings also react to the font size setting


## v3.0.1

- add the foss embedded distributor for notifications without a fcm client
- Improve search screen UI
- Fix font size not changing for messages (#44)
- add sound once per room option
- improve reply, and edit banner
- Reactions are now handled in message events
- desktop: show avatar/fp if present
- Add filtering based on groups and DMs
- Add a message info action to check seen by entries
- condense message bubble spacers, and have reactions closer to the message
- shrink images to look like other chat clients
- Move members listing dropdown button to room info screen
- Another room info screen overhaul
- Room info screen avatar, spaces avatar, member listing, and leave button
- Add swipe to reply, and the ability to click a reply to go to the message that was replied to.
- Add emojis sheet to have curated emojis (like other clients), and app icon badges
- Store draft messages, and other misc stuff
- Have an option to change the default unifiedPush provider
- Add enter handling (shift + enter now sends message by default, togglable in settings)
- do not hide the status bar (#40)


## v2.7.2

- Add ctrl + V support for desktop
- Add clipboard handling (attachment sheet has an extra button for pastables)
- better url handling for desktop notifications, and media gallery links
- Fix room info dropdowns not being aligned to the location, and use better room join rule display names


## v2.7.1

- Add ctrl + V support for desktop
- Add clipboard handling (attachment sheet has an extra button for pastables)
- better url handling for desktop notifications, and media gallery links
- Fix room info dropdowns not being aligned to the location, and use better room join rule display names


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

